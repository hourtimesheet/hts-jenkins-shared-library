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
 * Contract with the .mongosh.js (full detail in README):
 *   - Read these env vars: COMPANY_NAME, APPLY ('true'|'false'), REASON,
 *     OPERATOR, BUILD_URL, HTS_CORRELATION_ID, plus anything from {@code extraEnv}.
 *   - Print a final line {@code RESULT_JSON={"status":"...",...}} where status
 *     is one of: noop, dry-run, applied, failed.
 *   - Write to MongoDB.auditEventLog yourself, in the SAME transaction as the
 *     data write, including {@code correlationId: HTS_CORRELATION_ID}.
 *   - On fatal pre-checks: print a line containing "FATAL" and exit non-zero.
 *
 * The pipeline:
 *   - Never logs the Mongo URI. {@code withCredentials} masks it; we additionally
 *     suppress xtrace ({@code set +x}) around the mongosh invocation so the
 *     URI is not echoed to the console even before masking can intervene.
 *   - Aborts loudly on missing credential, missing script file, missing mongosh,
 *     and on any non-zero exit from the script (bash {@code set -euo pipefail}).
 *   - Is idempotent at the .mongosh.js layer: re-runs against a tenant that's
 *     already in the desired state must produce {@code status:"noop"}.
 *   - Serializes per-tenant via Jenkins {@code lock("hts-support-${companyName}")}
 *     so two HK-22xx jobs cannot interleave dry-run and apply on the same tenant.
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

  // Note: declarative `pipeline { ... }` is available inside a vars/ step's call()
  // because the pipeline-model-definition plugin transforms it via CPS — the
  // step itself is the entire Jenkinsfile from Jenkins's perspective.
  pipeline {
    agent any

    options {
      ansiColor('xterm')
      timestamps()
      // Two operators applying the same ticket to the same tenant at the same
      // time is the recipe for a double-write race. disableConcurrentBuilds()
      // serialises within a job; the lock("hts-support-${companyName}") wrapping
      // dry-run + confirm + apply (below) serialises ACROSS jobs that touch the
      // same tenant. Both are required.
      disableConcurrentBuilds()
    }

    stages {
      stage('Bootstrap') {
        steps {
          script {
            // Mutable state lives in a script-level Serializable LinkedHashMap.
            // Restart-survival comes from Jenkins's CPS engine snapshotting
            // program state at every step boundary (the `input` step in Confirm
            // can wait hours), NOT from any "hoisting" we do here — Jenkins
            // re-hydrates the snapshot on controller restart. Keeping the
            // backing type Serializable is the load-bearing detail; do not
            // change it to a non-Serializable container, and add an integration
            // test before refactoring this pattern.
            //
            // We intentionally avoid env.HTS_SUPPORT_SUBMITTER (cross-stage env
            // smuggling is brittle, visible to child processes, and not part
            // of Jenkins's CPS snapshot).
            _ctxStore.isNoop        = false
            _ctxStore.wasApplied    = false
            _ctxStore.submitter     = env.BUILD_USER ?: 'unknown'
            _ctxStore.secondSubmitter = ''
            _ctxStore.correlationId = env.BUILD_TAG ?: java.util.UUID.randomUUID().toString()
            _ctxStore.applyStatus   = null

            log.info("Correlation ID: ${_ctxStore.correlationId}")
          }
        }
      }

      stage('Validate input') {
        steps {
          script {
            // Pre-flight: workspace + script file + mongosh on PATH.
            // Failing these here gives a clearer message than letting bash trip.
            if (!env.WORKSPACE) {
              error('WORKSPACE env var is unset — htsSupportJob requires a node{} context')
            }

            // Path-traversal canonicalisation (audit finding H2): after the
            // declarative validate() check, also resolve symlinks to confirm
            // the script truly lives inside $WORKSPACE. readlink -f handles
            // intermediate symlinks; the prefix check rejects escape via
            // creative directory layouts.
            //
            // We pass the (already-validated) script path through an env var
            // rather than interpolating it into the shell, so Groovy-string
            // injection cannot reach the canonicalisation probe.
            def scriptRel = cfg.mongoScriptFile
            if (!fileExists("${env.WORKSPACE}/${scriptRel}")) {
              error(
                "mongoScriptFile not found at '${env.WORKSPACE}/${scriptRel}'. " +
                "Ensure the consumer pipeline is configured as 'Pipeline script from SCM' " +
                "so the sibling ${scriptRel} is checked out alongside the Jenkinsfile."
              )
            }
            def canonical
            def workspaceCanonical
            withEnv(["__HTS_PROBE_PATH=${scriptRel}"]) {
              canonical = sh(
                  script: '''#!/usr/bin/env bash
                    set -euo pipefail
                    readlink -f -- "$WORKSPACE/$__HTS_PROBE_PATH"
                  '''.stripIndent(),
                  returnStdout: true,
                  label: 'canonicalise mongoScriptFile path',
              ).trim()
              workspaceCanonical = sh(
                  script: '''#!/usr/bin/env bash
                    set -euo pipefail
                    readlink -f -- "$WORKSPACE"
                  '''.stripIndent(),
                  returnStdout: true,
                  label: 'canonicalise WORKSPACE',
              ).trim()
            }
            if (!canonical.startsWith(workspaceCanonical + '/') && canonical != workspaceCanonical) {
              error(
                "mongoScriptFile '${scriptRel}' canonicalises to '${canonical}', " +
                "which is outside WORKSPACE '${workspaceCanonical}'. Refusing to run a " +
                "script from outside the consumer pipeline's checkout."
              )
            }

            // mongosh check + version assertion (audit finding M2). The probe
            // returns 127 if mongosh is missing; we want a clean error rather
            // than letting bash trip later.
            def mongoshVersion = sh(
                script: 'set +e; command -v mongosh >/dev/null 2>&1 && mongosh --version 2>/dev/null || echo missing',
                returnStdout: true,
                label: 'mongosh version probe',
            ).trim()
            if (mongoshVersion == 'missing' || !mongoshVersion) {
              error(
                "mongosh is not on PATH on the Jenkins agent. Install mongosh >= 1.10 " +
                "(see https://www.mongodb.com/docs/mongodb-shell/install/)."
              )
            }
            def versionMatch = (mongoshVersion =~ /(\d+)\.(\d+)(?:\.(\d+))?/)
            if (!versionMatch.find()) {
              error("Could not parse mongosh version output: '${mongoshVersion}'.")
            }
            def major = versionMatch.group(1) as int
            def minor = versionMatch.group(2) as int
            if (major < 1 || (major == 1 && minor < 10)) {
              error(
                "mongosh ${major}.${minor} is too old for htsSupportJob. " +
                "Minimum required version is 1.10 (Mongo 7-era driver semantics)."
              )
            }

            log.info("Ticket:         ${cfg.ticketId}")
            log.info("Operator:       ${_ctxStore.submitter}")
            log.info("Company:        ${cfg.companyNameTrimmed}")
            log.info("Reason:         ${cfg.reasonTrimmed}")
            log.info("Apply:          ${cfg.APPLY}")
            log.info("Script:         ${cfg.mongoScriptFile}")
            log.info("Correlation ID: ${_ctxStore.correlationId}")
            log.info("mongosh:        ${mongoshVersion.split('\n')[0]}")
          }
        }
      }

      stage('Per-tenant lock') {
        steps {
          // Per-tenant Jenkins lock (audit finding C4 / H5). Requires the
          // Jenkins lockable-resources plugin. The lock spans dry-run + confirm
          // + apply so two HK-22xx jobs targeting the same tenant cannot
          // interleave between dry-run and apply.
          //
          // The .mongosh.js itself MUST also re-assert preconditions inside its
          // apply branch — the dry-run check is informational only because
          // (a) Jenkins lock != Mongo write lock, and (b) external actors can
          // mutate state outside Jenkins. README documents this contract.
          //
          // Using scripted nested stages here so the Jenkins UI still renders
          // distinct Dry-run / Confirm / Apply stages (declarative does not
          // allow lock() in options, and a single declarative stage would
          // collapse the three steps into one card).
          lock(resource: "hts-support-${cfg.companyNameTrimmed}") {
            script {
              stage('Dry-run preview') { _runDryRun(cfg) }
              stage('Confirm')         { _runConfirm(cfg) }
              stage('Apply')           { _runApply(cfg) }
            }
          }
        }
      }
    }

    post {
      always {
        script {
          // Audit-log write moved into post.always (audit finding C5) so a
          // failure in Apply does not skip auditing. Status reflects what
          // actually happened; correlationId ties it back to the .mongosh.js's
          // own auditEventLog write.
          _writeAuditLog(cfg)

          // Build-numbered subdir (audit finding H7) so archiveArtifacts does
          // not re-archive every previous build's logs on every run.
          archiveArtifacts(
              artifacts: ".htsSupportJob/${env.BUILD_NUMBER}/${cfg.ticketId}-*.log",
              allowEmptyArchive: true,
          )
          // cleanWs runs AFTER archiveArtifacts; its job is to keep the agent
          // workspace from accumulating across builds, not to pre-empt the archive.
          try {
            cleanWs(
                deleteDirs: true,
                patterns: [[pattern: '.htsSupportJob/**', type: 'INCLUDE']],
                notFailBuild: true,
            )
          } catch (Throwable t) {
            log.warn("cleanWs failed (non-fatal): ${t.message}")
          }
        }
      }
      success {
        script {
          // onApply only fires when we actually applied — not on dry-run-only
          // success. The notification hook is the consumer's responsibility;
          // we don't ship a default Slack/PD integration.
          if (_ctxStore.wasApplied && cfg.onApply != null) {
            _safeInvokeHook(cfg.onApply, _hookPayload(cfg, 'applied'))
          }
        }
      }
      failure {
        script {
          if (cfg.onFailure != null) {
            def status = _ctxStore.applyStatus ?: 'failed'
            _safeInvokeHook(cfg.onFailure, _hookPayload(cfg, status))
          }
        }
      }
      aborted {
        script {
          if (cfg.onFailure != null) {
            _safeInvokeHook(cfg.onFailure, _hookPayload(cfg, 'aborted'))
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Stage helpers (extracted so each has its own per-stage timeout, and so the
// declarative pipeline above stays readable).
// ---------------------------------------------------------------------------

/**
 * In-build context map. We avoid environment-variable smuggling between stages
 * (audit finding H1) — env vars are visible to child processes and cross-stage
 * env writes are brittle when a Jenkins controller restarts mid-build.
 *
 * Held on the {@link Script} instance, which is recreated per-build by Jenkins,
 * so cross-build contamination is impossible. {@code LinkedHashMap} is
 * {@link Serializable} and CPS-friendly. Stage helpers use it as a struct.
 *
 * Keys:
 *   - isNoop:          (Boolean) dry-run determined the tenant is already in desired state
 *   - wasApplied:      (Boolean) Apply stage actually wrote
 *   - submitter:       (String)  primary approver (BUILD_USER pre-confirm; input submitter post)
 *   - secondSubmitter: (String)  optional second approver when requireDualApproval=true
 *   - correlationId:   (String)  tying Jenkins audit log to .mongosh.js auditEventLog row
 *   - applyStatus:     (String)  null | 'applied' | 'failed' | 'aborted'
 */
@SuppressWarnings('GroovyUnusedDeclaration')
final Map<String, Object> _ctxStore = new LinkedHashMap<String, Object>()

private void _runDryRun(SupportJobConfig cfg) {
  timeout(time: 5, unit: 'MINUTES') {
    // Logs live in the workspace so archiveArtifacts (which uses workspace-
    // relative Ant globs) can pick them up. Build-numbered subdir prevents
    // cross-build log accumulation (H7).
    def logDir = ".htsSupportJob/${env.BUILD_NUMBER}"
    dir(logDir) {
      writeFile(file: '.gitkeep', text: '')
    }
    def previewLog = "${logDir}/${cfg.ticketId}-preview.log"
    _runMongosh(cfg, previewLog, /* applyMode */ false, _ctxStore.submitter)

    def text = readFile(previewLog)
    if (text.contains(cfg.fatalMarker)) {
      error("Pre-checks failed in dry-run for ${cfg.ticketId}/${cfg.companyNameTrimmed}; see preview log.")
    }
    if (text.contains(cfg.noopMarker)) {
      _ctxStore.isNoop = true
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

private void _runConfirm(SupportJobConfig cfg) {
  if (!cfg.APPLY || _ctxStore.isNoop) {
    return
  }
  timeout(time: 60, unit: 'MINUTES') {
    def inputArgs = [
        message: "Apply ${cfg.ticketId} for company '${cfg.companyNameTrimmed}'?",
        ok: 'Apply',
        submitterParameter: 'submitter',
        parameters: [
            booleanParam(
                name: 'I_HAVE_REVIEWED_THE_PREVIEW',
                defaultValue: false,
                description: 'Tick to confirm you reviewed the dry-run output above.'
            ),
        ],
    ]
    if (cfg.confirmTenantName) {
      inputArgs.parameters << string(
          name: 'CONFIRM_TENANT_NAME',
          defaultValue: '',
          description: "Retype the tenant slug exactly: '${cfg.companyNameTrimmed}'.",
          trim: true,
      )
    }
    if (cfg.approverGroup) {
      inputArgs.submitter = cfg.approverGroup
    }
    def confirmation = input(inputArgs)
    if (!confirmation.I_HAVE_REVIEWED_THE_PREVIEW) {
      error('Aborted: confirmation checkbox not ticked.')
    }
    if (cfg.confirmTenantName && confirmation.CONFIRM_TENANT_NAME != cfg.companyNameTrimmed) {
      error(
        "Aborted: confirmation tenant name '${confirmation.CONFIRM_TENANT_NAME}' " +
        "does not match expected '${cfg.companyNameTrimmed}'."
      )
    }
    def submitterA = confirmation.submitter ?: env.BUILD_USER ?: 'unknown'
    _ctxStore.submitter = submitterA

    if (cfg.requireDualApproval) {
      def secondArgs = [
          message: "Dual-approval gate: a SECOND approver must confirm '${cfg.ticketId}' for '${cfg.companyNameTrimmed}'.",
          ok: 'Confirm (second approver)',
          submitterParameter: 'submitter',
      ]
      if (cfg.approverGroup) {
        secondArgs.submitter = cfg.approverGroup
      }
      def secondConfirmation = input(secondArgs)
      def submitterB = secondConfirmation.submitter ?: 'unknown'
      if (submitterB == submitterA) {
        error(
          "Aborted: dual approval requires two distinct approvers, but '${submitterA}' " +
          "approved both gates. Have a different operator confirm."
        )
      }
      _ctxStore.secondSubmitter = submitterB
      log.info("Dual approval: primary=${submitterA}, secondary=${submitterB}")
    }
  }
}

private void _runApply(SupportJobConfig cfg) {
  if (!cfg.APPLY || _ctxStore.isNoop) {
    return
  }
  timeout(time: 15, unit: 'MINUTES') {
    def applyLog = ".htsSupportJob/${env.BUILD_NUMBER}/${cfg.ticketId}-apply.log"
    try {
      _runMongosh(cfg, applyLog, /* applyMode */ true, _ctxStore.submitter)

      def text = readFile(applyLog)
      if (!text.contains(cfg.appliedMarker)) {
        _ctxStore.applyStatus = 'failed'
        error(
          "Apply for ${cfg.ticketId}/${cfg.companyNameTrimmed} did not emit ${cfg.appliedMarker}. " +
          "Investigate before re-running — the .mongosh.js may have written partial state."
        )
      }
      _ctxStore.wasApplied = true
      _ctxStore.applyStatus = 'applied'
      currentBuild.description = "applied: ${cfg.ticketId} for ${cfg.companyNameTrimmed}"
    } catch (Throwable t) {
      if (_ctxStore.applyStatus == null) {
        _ctxStore.applyStatus = 'failed'
      }
      throw t
    }
  }
}

/**
 * Writes a JSON-line-format audit log to the workspace. Runs from
 * {@code post.always} so the line is written regardless of stage outcome
 * (audit finding C5). Includes the same {@code correlationId} the
 * {@code .mongosh.js} writes into Mongo's {@code auditEventLog} so the
 * Jenkins-side and Mongo-side records can be correlated 1:1.
 */
private void _writeAuditLog(SupportJobConfig cfg) {
  def auditLog = ".htsSupportJob/${env.BUILD_NUMBER}/${cfg.ticketId}-audit.log"
  def stamp    = java.time.Instant.now().toString()
  def status   = _ctxStore.wasApplied ? 'applied'
              : _ctxStore.isNoop      ? 'noop'
              : (currentBuild.currentResult == 'SUCCESS' && !cfg.APPLY) ? 'dry-run'
              : (_ctxStore.applyStatus ?: 'aborted')
  def operator = _ctxStore.submitter ?: env.BUILD_USER ?: 'unknown'
  def secondary = _ctxStore.secondSubmitter ?: ''
  // JSON-line format for grep/jq friendliness.
  def fields = [
      ticketId:       cfg.ticketId,
      companyName:    cfg.companyNameTrimmed,
      status:         status,
      buildResult:    currentBuild.currentResult,
      operator:       operator,
      secondary:      secondary,
      correlationId:  _ctxStore.correlationId,
      buildUrl:       env.BUILD_URL ?: '',
      buildNumber:    env.BUILD_NUMBER ?: '',
      reason:         cfg.reasonTrimmed,
      apply:          cfg.APPLY,
      timestamp:      stamp,
  ]
  // Tiny manual JSON encoder — no jenkins-pipeline-utility-steps dependency,
  // CPS-safe (no external libs), and the field set is whitelisted above.
  def line = '{' + fields.collect { k, v -> "\"${k}\":${_jsonEncode(v)}" }.join(',') + '}'
  // Defence-in-depth: ensure no embedded newline / control char escapes from
  // user input (already barred at validate()) silently truncates the line.
  dir(".htsSupportJob/${env.BUILD_NUMBER}") {
    writeFile(file: '.gitkeep', text: '')
  }
  writeFile(file: auditLog, text: line + "\n")
  log.info("Audit: ${line}")
}

private String _jsonEncode(Object v) {
  if (v == null) return 'null'
  if (v instanceof Boolean) return v.toString()
  if (v instanceof Number) return v.toString()
  def s = v.toString()
  def sb = new StringBuilder('"')
  for (int i = 0; i < s.length(); i++) {
    int c = s.charAt(i) as int
    switch (c) {
      case 0x22: sb.append('\\"'); break       // "
      case 0x5c: sb.append('\\\\'); break      // \
      case 0x0a: sb.append('\\n'); break
      case 0x0d: sb.append('\\r'); break
      case 0x09: sb.append('\\t'); break
      default:
        if (c < 0x20 || c == 0x7F) {
          sb.append(String.format('\\u%04x', c))
        } else {
          sb.append((char) c)
        }
    }
  }
  sb.append('"')
  return sb.toString()
}

private Map<String, Object> _hookPayload(SupportJobConfig cfg, String status) {
  return [
      ticketId:      cfg.ticketId,
      companyName:   cfg.companyNameTrimmed,
      submitter:     _ctxStore.submitter,
      reason:        cfg.reasonTrimmed,
      correlationId: _ctxStore.correlationId,
      buildUrl:      env.BUILD_URL ?: '',
      status:        status,
  ]
}

private void _safeInvokeHook(Closure hook, Map<String, Object> payload) {
  try {
    hook.call(payload)
  } catch (Throwable t) {
    log.warn("Notification hook failed (non-fatal): ${t.message}")
  }
}

/**
 * Internal helper that runs the .mongosh.js in either dry-run or apply mode.
 *
 * Why a helper: identical except for the APPLY env var and the OPERATOR source,
 * and we DO NOT want one diverging from the other (e.g. someone adds an env var
 * to dry-run and forgets it on apply, producing different evaluation paths).
 *
 * Shell hardening (audit findings C1, C6):
 *   - {@code #!/usr/bin/env bash} so {@code set -o pipefail} actually works
 *     (Jenkins's default {@code sh} is {@code /bin/sh}, which on Debian/Ubuntu
 *     agents is dash — and dash does NOT support pipefail).
 *   - {@code set -euo pipefail} catches both unset vars and tee/mongosh
 *     pipeline failures.
 *   - {@code { set +x; } 2>/dev/null} immediately around the mongosh
 *     invocation so the URI is not xtrace-echoed to the console even before
 *     Jenkins's withCredentials masking runs. (The masking is incomplete on
 *     derived values like base64 fragments; better to never emit it.)
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
      "HTS_CORRELATION_ID=${_ctxStore.correlationId}",
    ] + (cfg.extraEnv ?: [:]).collect { k, v -> "${k}=${v}" }

    withEnv(envList) {
      // The mongosh command takes the URI as positional arg #1; we pass it via
      // "$MONGO_URI" (double-quoted) so any shell-special characters in the URI
      // (semicolons, ampersands, dollar signs) cannot break the command.
      // See class-level docstring for the bash/dash/xtrace rationale.
      sh '''#!/usr/bin/env bash
        set -euo pipefail
        umask 077
        : > "$WORKSPACE/$HTS_SUPPORT_OUT_LOG"
        # Suppress xtrace specifically around the mongosh line so the URI is
        # not echoed even if Jenkins ran the script with -x.
        { set +x; } 2>/dev/null
        mongosh "$MONGO_URI" --quiet --file "$WORKSPACE/$HTS_SUPPORT_SCRIPT" 2>&1 | tee "$WORKSPACE/$HTS_SUPPORT_OUT_LOG"
      '''
    }
  }
}
