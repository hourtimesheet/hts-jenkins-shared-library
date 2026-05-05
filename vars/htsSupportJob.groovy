import static groovy.lang.Closure.DELEGATE_ONLY

import com.hourtimesheet.jenkins.SupportJobConfig

/**
 * Generic operational-support pipeline for Hour Timesheet's Mongo-backed
 * one-shot Jenkins jobs (HK-2204..HK-2209 et al).
 *
 * The consumer Jenkinsfile passes a config closure; this step runs the canonical
 * five-stage flow (validate / dry-run / confirm / apply / audit) so each
 * ticket's Jenkinsfile stays at ~15 lines and ticket-specific logic lives only
 * in the sibling .mongosh.js file.
 *
 * Contract with the .mongosh.js:
 *   - Read these env vars: COMPANY_NAME, APPLY ('true'|'false'), REASON,
 *     OPERATOR, BUILD_URL, plus anything from {@code extraEnv}.
 *   - Print a final line {@code RESULT_JSON={"status":"...",...}} where status
 *     is one of: noop, dry-run, applied, failed.
 *   - Write to MongoDB.auditEventLog yourself (the script knows the eventType).
 *   - On fatal pre-checks: print a line containing "FATAL" and exit non-zero.
 *
 * The pipeline:
 *   - Never logs the Mongo URI. {@code withCredentials} masks it; we additionally
 *     pipe mongosh output through {@code tee} so Jenkins console + artifact get the
 *     same content and a leak in either is the same leak.
 *   - Aborts loudly on missing credential, missing script file, missing mongosh,
 *     and on any non-zero exit from the script (set -eo pipefail in every sh).
 *   - Is idempotent at the .mongosh.js layer: re-runs against a tenant that's
 *     already in the desired state must produce {@code status:"noop"}.
 *
 * @param closure DSL closure populating {@link SupportJobConfig}.
 */
void call(Closure closure) {
  def context = closure.owner as Script

  def cfg = new SupportJobConfig()
  closure.resolveStrategy = DELEGATE_ONLY
  closure.delegate = cfg
  closure()

  // The consumer's closure has now read params.* and populated cfg. The first
  // build of any new job has empty params (before the parameters block is
  // installed); validation will fail with a clear message and the *next* build
  // will have the parameter inputs available in the Jenkins UI.

  def cfgError = cfg.validate()
  if (cfgError) {
    context.error(cfgError)
    return
  }

  // Run optional consumer validation AFTER built-in validation so they can
  // assume the basic shape is sound (companyName regex passed, etc.).
  if (cfg.extraValidation != null) {
    def extra = cfg.extraValidation.rehydrate(cfg, this, this)
    extra.resolveStrategy = Closure.DELEGATE_ONLY
    try {
      extra(cfg)
    } catch (Throwable t) {
      context.error("htsSupportJob extraValidation failed for ${cfg.ticketId}/${cfg.companyName}: ${t.message}")
      return
    }
  }

  // Note: declarative `pipeline { ... }` is available inside a vars/ step's call()
  // because the pipeline-model-definition plugin transforms it via CPS — the
  // step itself is the entire Jenkinsfile from Jenkins's perspective.
  pipeline {
    agent any

    options {
      ansiColor('xterm')
      timestamps()
      timeout(time: 10, unit: 'MINUTES')
      // Two operators applying the same ticket to the same tenant at the same
      // time is the recipe for a double-write race. Serialize per (ticket,tenant).
      disableConcurrentBuilds()
    }

    stages {
      stage('Validate input') {
        steps {
          script {
            // Pre-flight: workspace + script file + mongosh on PATH.
            // Failing these here gives a clearer message than letting bash trip.
            if (!env.WORKSPACE) {
              error('WORKSPACE env var is unset — htsSupportJob requires a node{} context')
            }
            def scriptPath = "${env.WORKSPACE}/${cfg.mongoScriptFile}"
            if (!fileExists(scriptPath)) {
              error(
                "mongoScriptFile not found at '${scriptPath}'. " +
                "Ensure the consumer pipeline is configured as 'Pipeline script from SCM' " +
                "so the sibling ${cfg.mongoScriptFile} is checked out alongside the Jenkinsfile."
              )
            }

            // mongosh check: the sh -c probe returns 127 if missing; we want a clean error.
            def mongoshCheck = sh(
              script: 'set +e; command -v mongosh >/dev/null 2>&1 && echo present || echo missing',
              returnStdout: true
            ).trim()
            if (mongoshCheck != 'present') {
              error(
                "mongosh is not on PATH on the Jenkins agent. Install mongosh >= 1.10 " +
                "(see https://www.mongodb.com/docs/mongodb-shell/install/)."
              )
            }

            log.info("Ticket:    ${cfg.ticketId}")
            log.info("Operator:  ${env.BUILD_USER ?: 'unknown'}")
            log.info("Company:   ${cfg.companyNameTrimmed}")
            log.info("Reason:    ${cfg.reasonTrimmed}")
            log.info("Apply:     ${cfg.APPLY}")
            log.info("Script:    ${cfg.mongoScriptFile}")
          }
        }
      }

      stage('Dry-run preview') {
        steps {
          script {
            // Logs live in the workspace so archiveArtifacts (which uses
            // workspace-relative Ant globs) can pick them up. dir() handles
            // any unusual characters in $WORKSPACE without shell-quoting risk.
            dir('.htsSupportJob') {
              writeFile(file: '.gitkeep', text: '')
            }
            def previewLog = ".htsSupportJob/${cfg.ticketId}-preview.log"
            _runMongosh(cfg, previewLog, /* applyMode */ false, env.BUILD_USER ?: 'unknown')

            def text = readFile(previewLog)
            if (text.contains(cfg.fatalMarker)) {
              error("Pre-checks failed in dry-run for ${cfg.ticketId}/${cfg.companyNameTrimmed}; see preview log.")
            }
            if (text.contains(cfg.noopMarker)) {
              currentBuild.description = "noop: ${cfg.ticketId} for ${cfg.companyNameTrimmed}"
              log.info("No-op detected — already in desired state. Apply stage will be skipped.")
            } else if (!text.contains(cfg.dryRunMarker)) {
              // Defensive: a script that exited 0 but never printed dryRunMarker
              // could mean a marker mismatch, an unexpected branch, or a buggy
              // .mongosh.js. Make it loud rather than silently proceed.
              error(
                "Dry-run did not emit ${cfg.dryRunMarker}. " +
                "Either the .mongosh.js exited a path that doesn't print one of the four expected markers, " +
                "or the configured markers don't match the script's output. See preview log."
              )
            }
          }
        }
      }

      stage('Confirm') {
        when {
          expression { return cfg.APPLY && !(currentBuild.description?.startsWith('noop')) }
        }
        steps {
          script {
            def confirmation = input(
              message: "Apply ${cfg.ticketId} for company '${cfg.companyNameTrimmed}'?",
              ok: 'Apply',
              submitterParameter: 'submitter',
              parameters: [
                booleanParam(
                  name: 'I_HAVE_REVIEWED_THE_PREVIEW',
                  defaultValue: false,
                  description: 'Tick to confirm you reviewed the dry-run output above.'
                )
              ]
            )
            if (!confirmation.I_HAVE_REVIEWED_THE_PREVIEW) {
              error('Aborted: confirmation checkbox not ticked.')
            }
            env.HTS_SUPPORT_SUBMITTER = confirmation.submitter ?: env.BUILD_USER ?: 'unknown'
          }
        }
      }

      stage('Apply') {
        when {
          expression { return cfg.APPLY && !(currentBuild.description?.startsWith('noop')) }
        }
        steps {
          script {
            def applyLog = ".htsSupportJob/${cfg.ticketId}-apply.log"
            _runMongosh(cfg, applyLog, /* applyMode */ true, env.HTS_SUPPORT_SUBMITTER)

            def text = readFile(applyLog)
            if (!text.contains(cfg.appliedMarker)) {
              error(
                "Apply for ${cfg.ticketId}/${cfg.companyNameTrimmed} did not emit ${cfg.appliedMarker}. " +
                "Investigate before re-running — the .mongosh.js may have written partial state."
              )
            }
            currentBuild.description = "applied: ${cfg.ticketId} for ${cfg.companyNameTrimmed}"
          }
        }
      }

      stage('Audit') {
        steps {
          script {
            def auditLog = ".htsSupportJob/${cfg.ticketId}-audit.log"
            def stamp    = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('UTC'))
            def status   = currentBuild.description?.startsWith('applied') ? 'applied'
                         : currentBuild.description?.startsWith('noop')    ? 'noop'
                         : (cfg.APPLY ? 'aborted' : 'dry-run')
            def operator = env.HTS_SUPPORT_SUBMITTER ?: env.BUILD_USER ?: 'unknown'
            def line = "${cfg.ticketId} ${status} for ${cfg.companyNameTrimmed} at ${stamp} by ${operator} (build ${env.BUILD_URL ?: 'n/a'}) reason='${cfg.reasonTrimmed}'"
            writeFile(file: auditLog, text: line + "\n")
            log.info("Audit: ${line}")
          }
        }
      }
    }

    post {
      always {
        // Glob covers preview/apply/audit; allowEmptyArchive in case we
        // aborted before any was written. archiveArtifacts uses workspace-
        // relative Ant globs.
        archiveArtifacts artifacts: ".htsSupportJob/${cfg.ticketId}-*.log", allowEmptyArchive: true
      }
    }
  }
}

/**
 * Internal helper that runs the .mongosh.js in either dry-run or apply mode.
 *
 * Why a helper: identical except for the APPLY env var and the OPERATOR source,
 * and we DO NOT want one diverging from the other (e.g. someone adds an env var
 * to dry-run and forgets it on apply, producing different evaluation paths).
 */
private void _runMongosh(SupportJobConfig cfg, String outLog, boolean applyMode, String operator) {
  withCredentials([string(credentialsId: cfg.credentialId, variable: 'MONGO_URI')]) {
    def envList = [
      "COMPANY_NAME=${cfg.companyNameTrimmed}",
      "APPLY=${applyMode}",
      "REASON=${cfg.reasonTrimmed}",
      "OPERATOR=${operator ?: 'unknown'}",
      "HTS_SUPPORT_SCRIPT=${cfg.mongoScriptFile}",
      "HTS_SUPPORT_OUT_LOG=${outLog}",
    ] + (cfg.extraEnv ?: [:]).collect { k, v -> "${k}=${v}" }

    withEnv(envList) {
      // The mongosh command takes the URI as positional arg #1; we pass it via
      // "$MONGO_URI" (double-quoted) so any shell-special characters in the URI
      // (semicolons, ampersands, dollar signs) cannot break the command.
      // tee is in pipefail-safe position so a tee failure surfaces.
      sh '''
        set -eo pipefail
        umask 077
        : > "$WORKSPACE/$HTS_SUPPORT_OUT_LOG"
        mongosh "$MONGO_URI" --quiet --file "$WORKSPACE/$HTS_SUPPORT_SCRIPT" 2>&1 | tee "$WORKSPACE/$HTS_SUPPORT_OUT_LOG"
      '''
    }
  }
}
