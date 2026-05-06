import static groovy.lang.Closure.DELEGATE_ONLY

import com.hourtimesheet.jenkins.FreezeGate
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
 * <h2>Status of truth (issue #14)</h2>
 * The audit-log JSONL artifact ({@code .htsSupportJob/$BUILD_NUMBER/$ticketId-audit.log})
 * plus Mongo's {@code auditEventLog} row are the <b>canonical</b> sources of
 * truth for pipeline status (applied / noop / dry-run / failed / aborted).
 * {@code currentBuild.description} is an <b>informational-only</b> operator-
 * glance string written exactly once at the end of the pipeline (post.always)
 * for the Jenkins build-page summary. Never read it back: it can be edited via
 * the Jenkins UI, can be lost on plugin upgrades, and writes that fail mid-
 * pipeline could otherwise leave a stale string masking real failures. If you
 * need to know what happened, read the audit-log artifact.
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
            // Jenkins 'Replay' shares BUILD_TAG with its parent build, which
            // would collide correlationIds across two distinct physical writes
            // (issue #16). Append BUILD_NUMBER + an 8-char random suffix
            // unconditionally so every run — original or Replay — gets a
            // unique id, while keeping BUILD_TAG as the human-readable prefix.
            _ctxStore.correlationId = "${env.BUILD_TAG ?: 'jenkins'}-${env.BUILD_NUMBER ?: '0'}-${java.util.UUID.randomUUID().toString().take(8)}"
            _ctxStore.applyStatus   = null

            log.info("Correlation ID: ${_ctxStore.correlationId}")

            // Issue #10: deprecation banner. Emitted at Bootstrap so every
            // operator sees it before paging through the dry-run output. The
            // banner is informational ONLY — it does NOT block runs. Use
            // cfg.freezeAfter for hard refusal.
            if (cfg.deprecated) {
              def msg = cfg.deprecatedMessage?.trim() ?: 'This pipeline is deprecated.'
              log.warn("⚠️ DEPRECATED: ${msg}")
            }
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
            // Issue #13: anchor the regex to a full-line semver. The previous
            // form `(\d+)\.(\d+)` matched ANYWHERE in ANY line of stdout —
            // false-positives like `library v1.2.3 built on FOO 9.8` or
            // `Connection refused at 127.0.0.1` (matching '127.0') would
            // satisfy the version gate. Strip an optional leading `mongosh `
            // prefix per the canonical CLI output, then match a strict
            // full-line semver triple with an optional pre-release tag. Skip
            // blanks/comments. Pre-releases are rejected unless the consumer
            // opts in via cfg.allowMongoshPrerelease=true.
            def parsed = _parseMongoshVersionLines(mongoshVersion)
            if (parsed == null) {
              error(
                "Could not parse mongosh version output. Raw output:\n" +
                "----\n${mongoshVersion}\n----"
              )
            }
            log.info("mongosh raw:    ${parsed.rawLine}")
            if (parsed.prerelease && !cfg.allowMongoshPrerelease) {
              error(
                "mongosh ${parsed.major}.${parsed.minor}.${parsed.patch}-${parsed.prerelease} " +
                "is a pre-release build; htsSupportJob refuses pre-releases by default " +
                "(driver semantics may diverge from GA). Raw line: '${parsed.rawLine}'. " +
                "Set cfg.allowMongoshPrerelease=true on a non-prod tenant to override."
              )
            }
            def major = parsed.major
            def minor = parsed.minor
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
            log.info("mongosh:        ${parsed.rawLine}")
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

          // INFORMATIONAL ONLY: a single operator-glance summary on the build
          // page. Issue #14 — never read this back; canonical status lives in
          // the audit-log JSONL artifact and Mongo's auditEventLog. Written
          // exactly once per build, here in post.always, so a partial pipeline
          // can't leave a stale "applied:" string masking a real failure.
          try {
            currentBuild.description = _summaryDescription(cfg)
          } catch (Throwable t) {
            log.warn("currentBuild.description summary failed (non-fatal, informational only): ${t.message}")
          }

          // Build-numbered subdir (audit finding H7) so archiveArtifacts does
          // not re-archive every previous build's logs on every run.
          //
          // Issue #12 (N-H1): wrap archiveArtifacts in try/catch so cleanWs
          // ALWAYS runs. Pre-fix, a transient archive-storage failure (full
          // disk on master, broken master-agent connection mid-archive, S3
          // hiccup on a JCasC-configured artifact backend) would throw out of
          // post.always before cleanWs and leave .htsSupportJob/${BUILD_NUMBER}/
          // orphaned on the agent — cross-build PII / audit data leakage.
          //
          // Catch Throwable (not Exception) so InterruptedException and
          // sandbox SecurityException variants don't escape. Mark the build
          // UNSTABLE so the failure is operator-visible without flipping a
          // successful apply to FAILED — the Mongo auditEventLog row plus the
          // JSONL audit-log artifact are the canonical record of what the
          // .mongosh.js did; an archive-side failure should not retroactively
          // claim the apply itself failed.
          try {
            archiveArtifacts(
                artifacts: ".htsSupportJob/${env.BUILD_NUMBER}/${cfg.ticketId}-*.log",
                allowEmptyArchive: true,
            )
          } catch (Throwable t) {
            log.errorLine("archiveArtifacts failed but cleanWs must still run (issue #12): ${t.message}")
            currentBuild.result = 'UNSTABLE'
          }
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
  // Issue #10: freeze gate for the noop / dry-run path. Only fires when
  // cfg.freezeBlocksNoop=true AND today (UTC) >= cfg.freezeAfter — otherwise
  // the dry-run path stays available for last-look forensics after retirement.
  // Date comparison logic in src/.../FreezeGate so it's unit-testable without
  // a Jenkins runtime.
  if (cfg.freezeBlocksNoop && FreezeGate.isFrozen(cfg.freezeAfter)) {
    error(FreezeGate.errorMessage(cfg.ticketId, cfg.freezeAfter, cfg.freezeBlocksNoop))
  }
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
      // NOTE: do NOT set currentBuild.description here. The audit-log JSONL
      // artifact + Mongo auditEventLog row are the canonical sources of truth
      // for status (issue #14). A single end-of-pipeline summary description
      // is set in post.always for operator-glance UX.
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
      // Issue #15: Jenkins's submitterParameter may return a display name
      // ("David Allison") rather than the canonical userId ("dallison"),
      // depending on the active SecurityRealm. Two distinct accounts that
      // share a display name would defeat dual-approval. Normalize both
      // values via the realm to the canonical userId before comparing.
      //
      // Defense in depth: SupportJobConfig.validate() now also requires
      // approverGroup to be set when requireDualApproval=true so the input
      // step's group constraint narrows realm semantics.
      def normalizedA = _resolveUserId(submitterA)
      def normalizedB = _resolveUserId(submitterB)
      if (normalizedB == normalizedA) {
        error(
          "Aborted: dual approval requires two distinct approvers, but '${submitterA}' " +
          "(userId '${normalizedA}') approved both gates. Have a different operator confirm."
        )
      }
      _ctxStore.secondSubmitter = submitterB
      log.info("Dual approval: primary=${submitterA} (userId=${normalizedA}), secondary=${submitterB} (userId=${normalizedB})")
    }
  }
}

private void _runApply(SupportJobConfig cfg) {
  if (!cfg.APPLY || _ctxStore.isNoop) {
    return
  }
  // Issue #10: hard freeze gate for the apply path. Runs AFTER the operator
  // submits the Confirm input step (per "the Apply button input still
  // appears, but on submit, the job throws") so the operator gets a clear
  // error rather than a silently missing stage. The dry-run / noop path
  // remains available unless cfg.freezeBlocksNoop=true (gated in _runDryRun).
  // Date comparison logic in src/.../FreezeGate so it's unit-testable
  // without a Jenkins runtime.
  if (FreezeGate.isFrozen(cfg.freezeAfter)) {
    error(FreezeGate.errorMessage(cfg.ticketId, cfg.freezeAfter, cfg.freezeBlocksNoop))
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
      // Issue #17: enforce the audit-row contract via a post-apply mongosh
      // probe. Runs ONLY on status=applied (never on noop / dry-run / failed),
      // and ONLY after the appliedMarker check above passes — so a partial-
      // write apply that never claimed success cannot be "verified" by this
      // probe. The probe asserts EXACTLY ONE auditEventLog row with the run's
      // correlationId; !=1 fails the build with a diagnostic citing the
      // correlationId. cfg.skipPostApplyProbe=true is an emergency override.
      _runPostApplyProbe(cfg)

      _ctxStore.wasApplied = true
      _ctxStore.applyStatus = 'applied'
      // NOTE: do NOT set currentBuild.description here. See the comment in
      // _runDryRun and the file-level "Status of truth" doc — canonical state
      // lives in the audit-log JSONL + Mongo auditEventLog (issue #14). A
      // single end-of-pipeline summary is written in post.always.
    } catch (Throwable t) {
      if (_ctxStore.applyStatus == null) {
        _ctxStore.applyStatus = 'failed'
      }
      throw t
    }
  }
}

/**
 * Issue #17: post-apply audit-row probe.
 *
 * Verifies the audit-row contract that every {@code RESULT_JSON status=applied}
 * produces EXACTLY ONE {@code auditEventLog} row carrying the run's
 * {@code correlationId}. Pre-#17 this contract was documentation-only — a
 * consumer {@code .mongosh.js} that wrote the data but skipped the audit row
 * (bug, schema typo, network glitch mid-write) reported success while Mongo
 * silently lacked the forensic record.
 *
 * <h3>Probe mechanics</h3>
 * <ul>
 *   <li>Runs ONLY when {@code cfg.skipPostApplyProbe == false}. The override
 *       exists for emergency Mongo-outage scenarios (apply committed, read-
 *       side primary unreachable) and logs a WARN when used.</li>
 *   <li>Runs ONLY on {@code status=applied} — never on noop / dry-run /
 *       failed; the caller invokes {@code _runPostApplyProbe} only after the
 *       {@code appliedMarker} check passes inside {@link #_runApply}.</li>
 *   <li>Uses the SAME {@code MONGO_URI} credential as the apply itself (via
 *       {@code withCredentials}), so the probe sees the same primary the
 *       apply wrote to.</li>
 *   <li>Sets {@code db.getMongo().setReadPref("primary")} via {@code --eval}
 *       BEFORE the count, mirroring the {@link #_runMongosh} hardening
 *       (issue #24): never read from a stale secondary while verifying a
 *       just-committed write.</li>
 *   <li>Passes {@code correlationId} via the {@code MONGOSH_CORR_ID} env var
 *       and references it inside the eval JS as
 *       {@code process.env.MONGOSH_CORR_ID} — NEVER interpolates the id into
 *       the eval string. correlationId is built from BUILD_TAG (issue #16),
 *       which contains JOB_NAME — partially user-controlled — so any
 *       string-interpolation path would be a shell / JS injection surface.
 *       Env-var indirection is the safe pattern.</li>
 *   <li>Suppresses xtrace via {@code { set +x; } 2>/dev/null} around the
 *       mongosh invocation (mirrors {@link #_runMongosh}).</li>
 *   <li>Captures the count via {@code returnStdout: true} and parses an
 *       integer; non-integer output (mongosh banner leakage, network error)
 *       trips the regex check and fails with the raw output for forensics.</li>
 * </ul>
 *
 * <h3>Failure modes</h3>
 * <ul>
 *   <li>{@code count == 0}: the consumer mongosh.js did NOT write the audit
 *       row. Build fails with a P1-explicit error citing the correlationId.</li>
 *   <li>{@code count > 1}: duplicate audit rows for the same correlationId
 *       (broken consumer). Build fails with a diagnostic.</li>
 *   <li>{@code count == 1}: contract upheld. Logs INFO with the correlationId
 *       and returns.</li>
 *   <li>Probe execution throws (mongosh missing, primary unreachable, malformed
 *       URI): build fails. Soft-fail would defeat the P1 contract enforcement.
 *       Operators with a justified reason to bypass set
 *       {@code cfg.skipPostApplyProbe = true}.</li>
 * </ul>
 */
private void _runPostApplyProbe(SupportJobConfig cfg) {
  if (cfg.skipPostApplyProbe) {
    log.warn(
      "Post-apply audit-row probe SKIPPED for ${cfg.ticketId}/${cfg.companyNameTrimmed} " +
      "(cfg.skipPostApplyProbe=true). Audit-row contract NOT verified for " +
      "correlationId=${_ctxStore.correlationId}. Operator must verify the row " +
      "in Mongo manually before declaring this build complete. (Issue #17.)"
    )
    return
  }
  def correlationId = _ctxStore.correlationId
  if (!correlationId) {
    error(
      "Post-apply probe: correlationId is unset. This is a library bug — Bootstrap " +
      "should always set it. Refusing to run the audit-row probe blind."
    )
  }
  String rawOutput
  try {
    withCredentials([string(credentialsId: cfg.credentialId, variable: 'MONGO_URI')]) {
      // correlationId flows in via env var only (NEVER interpolated into the
      // shell or the --eval JS). Inside the eval we read it via
      // process.env.MONGOSH_CORR_ID.
      withEnv(["MONGOSH_CORR_ID=${correlationId}"]) {
        rawOutput = sh(
            script: '''#!/usr/bin/env bash
              set -euo pipefail
              umask 077
              { set +x; } 2>/dev/null
              mongosh "$MONGO_URI" --quiet \
                --eval 'db.getMongo().setReadPref("primary")' \
                --eval 'print(db.getSiblingDB("hourtimesheet").auditEventLog.countDocuments({correlationId: process.env.MONGOSH_CORR_ID}))'
            '''.stripIndent(),
            returnStdout: true,
            label: 'post-apply audit-row probe (issue #17)',
        )
      }
    }
  } catch (Throwable t) {
    error(
      "Post-apply audit-row probe (issue #17) FAILED to execute for " +
      "correlationId=${correlationId} ticket=${cfg.ticketId} tenant=${cfg.companyNameTrimmed}: " +
      "${t.class.simpleName}: ${t.message}. " +
      "The apply itself reported status=applied; the audit-row contract is " +
      "now UNVERIFIED. Investigate Mongo connectivity and the auditEventLog row " +
      "manually before declaring this build complete. To bypass during a confirmed " +
      "Mongo outage set cfg.skipPostApplyProbe=true on the consumer Jenkinsfile."
    )
  }

  // Find the last all-digits line; mongosh banners / connection notices may
  // print preamble even with --quiet on some driver versions.
  def countLine = null
  for (String line : (rawOutput ?: '').split('\\r?\\n')) {
    def trimmed = line?.trim()
    if (trimmed && trimmed ==~ /\d+/) {
      countLine = trimmed
    }
  }
  if (countLine == null) {
    error(
      "Post-apply audit-row probe (issue #17) returned no integer count for " +
      "correlationId=${correlationId} ticket=${cfg.ticketId} tenant=${cfg.companyNameTrimmed}. " +
      "Raw mongosh output:\n----\n${rawOutput}\n----\n" +
      "The audit-row contract is UNVERIFIED. Investigate."
    )
  }
  def count = countLine as int
  if (count == 0) {
    error(
      "Audit-row contract violation (issue #17): no auditEventLog row found for " +
      "correlationId=${correlationId} after status=applied. " +
      "Ticket=${cfg.ticketId} tenant=${cfg.companyNameTrimmed}. " +
      "Consumer mongosh.js may have failed to write the audit row in the same " +
      "transaction as the data write. Investigate Mongo state and the consumer " +
      "script's audit-row write path before re-running."
    )
  }
  if (count > 1) {
    error(
      "Audit-row contract violation (issue #17): ${count} duplicate auditEventLog rows " +
      "found for correlationId=${correlationId} (expected exactly 1). " +
      "Ticket=${cfg.ticketId} tenant=${cfg.companyNameTrimmed}. " +
      "Consumer mongosh.js wrote the audit row more than once — investigate the " +
      "script's audit-row write path before re-running."
    )
  }
  log.info(
    "Audit row VERIFIED for correlationId=${correlationId} " +
    "(ticket=${cfg.ticketId}, tenant=${cfg.companyNameTrimmed}). " +
    "Audit-row contract upheld (issue #17)."
  )
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
  //
  // Issue #8: schema_version is FIRST so consumers can grep for it at column 0
  // and so visual scanners see the contract version immediately. JSON keys are
  // technically unordered, but Groovy's LinkedHashMap + the manual encoder
  // below preserve insertion order, which is the contract downstream tooling
  // relies on. The integer literal is sourced from
  // SupportJobConfig.AUDIT_SCHEMA_VERSION so bumping the version is a single-
  // edit, single-source-of-truth operation; see that constant's javadoc for
  // the migration policy (when to bump, what counts as breaking). The README
  // documents the column-0 grep contract for log-shipper / SIEM consumers.
  def fields = [
      schema_version: SupportJobConfig.AUDIT_SCHEMA_VERSION,
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

/**
 * Builds the single end-of-pipeline {@code currentBuild.description} string.
 *
 * <b>Informational only (issue #14).</b> The audit-log JSONL artifact and
 * Mongo's {@code auditEventLog} are the canonical sources of truth for
 * pipeline status. Nothing in this codebase (no {@code when{}} clause,
 * no helper, no consumer Jenkinsfile) reads {@code currentBuild.description}
 * back; if you find yourself wanting to, read the audit-log artifact instead.
 */
private String _summaryDescription(SupportJobConfig cfg) {
  def status = _ctxStore.wasApplied ? 'applied'
            : _ctxStore.isNoop      ? 'noop'
            : (currentBuild.currentResult == 'SUCCESS' && !cfg.APPLY) ? 'dry-run'
            : (_ctxStore.applyStatus ?: 'aborted')
  return "${status}: ${cfg.ticketId} for ${cfg.companyNameTrimmed}"
}

/**
 * Parses {@code mongosh --version} stdout into a structured version triple.
 *
 * Issue #13: the previous regex {@code (\d+)\.(\d+)} called via {@code .find()}
 * matched ANYWHERE in ANY line of stdout, producing false-positives on lines
 * like {@code library v1.2.3 built on FOO 9.8} or
 * {@code Connection refused at 127.0.0.1} (matching '127.0').
 *
 * This helper:
 * <ul>
 *   <li>Iterates over each output line, skipping blanks and {@code #}-comments.</li>
 *   <li>Strips an optional leading {@code mongosh } prefix (the canonical
 *       CLI prints {@code mongosh 2.5.0}).</li>
 *   <li>Matches a strict full-line semver triple with an optional pre-release
 *       tag: {@code ^v?(\d+)\.(\d+)\.(\d+)(?:-(rc|beta|alpha)\S*)?$}.</li>
 *   <li>Returns the FIRST matching line as a Map with keys
 *       {@code rawLine, major, minor, patch, prerelease}; pre-release is null
 *       for GA. Returns {@code null} when no line matches.</li>
 * </ul>
 *
 * Pre-release rejection (when a tag is found AND
 * {@code cfg.allowMongoshPrerelease == false}) is the caller's job — this
 * helper just classifies. Caller logs the {@code rawLine} for forensics
 * regardless of accept/reject.
 *
 * @param raw stdout from {@code mongosh --version}; may be multi-line.
 * @return Map with keys rawLine/major/minor/patch/prerelease, or null if no
 *         line is a recognisable semver.
 */
private Map _parseMongoshVersionLines(String raw) {
  if (raw == null) return null
  def pattern = ~/^v?(\d+)\.(\d+)\.(\d+)(?:-(rc|beta|alpha)(?:\S*)?)?$/
  // Recognised line-prefix shapes for known mongosh version output formats.
  // Anything not matching one of these (driver banners, error lines, build
  // metadata) falls through to the strict full-line anchor and is rejected.
  // Order matters only for clarity; each prefix is tried and the rest of the
  // line must STILL match the strict semver anchor.
  def knownPrefixes = ['mongosh ', 'Using Mongosh: ', 'Using Mongosh:']
  for (String line : raw.split('\\r?\\n')) {
    def trimmed = line?.trim()
    if (!trimmed) continue
    if (trimmed.startsWith('#')) continue
    def candidate = trimmed
    for (String prefix : knownPrefixes) {
      if (candidate.startsWith(prefix)) {
        candidate = candidate.substring(prefix.length()).trim()
        break
      }
    }
    def m = candidate =~ pattern
    if (m.matches()) {
      return [
        rawLine:    trimmed,
        major:      m.group(1) as int,
        minor:      m.group(2) as int,
        patch:      m.group(3) as int,
        prerelease: m.group(4),  // null if not a pre-release
      ]
    }
  }
  return null
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

/**
 * Normalizes a submitter string returned by Jenkins's {@code submitterParameter}
 * to the canonical {@code userId} via the active {@code SecurityRealm}.
 *
 * Issue #15: {@code submitterParameter} may yield either a userId or a display
 * name depending on the realm. Two distinct user accounts that share a display
 * name (e.g. two "David Allison"s) would compare as equal and silently defeat
 * dual-approval. Resolving via {@code loadUserByUsername(...)} returns the
 * canonical {@code User.id} regardless of which form Jenkins handed us.
 *
 * Failure modes (e.g. realm is a stub in unit tests, lookup throws because the
 * name does not exist, sandbox blocks the call) fall back to the raw input
 * string so legitimate runs are not blocked. The fallback is logged as a
 * warning — never silently swallowed — so a security-relevant misconfiguration
 * (realm offline, sandbox preventing lookup) surfaces in the build log and the
 * audit trail.
 *
 * The collision risk during fallback is the SAME as today's pre-fix behaviour
 * (string compare on whatever Jenkins returned), so we do not regress; we only
 * improve.
 */
private String _resolveUserId(String submitter) {
  if (submitter == null || submitter.trim().isEmpty() || submitter == 'unknown') {
    return submitter
  }
  try {
    // Fully-qualified to avoid sandbox confusion and to be obvious to readers.
    // Local var is named `jenkinsInstance` (not `jenkins`) so it does not shadow
    // the `jenkins.model` package on the RHS at parse time.
    def jenkinsInstance = jenkins.model.Jenkins.get()
    def realm = jenkinsInstance?.getSecurityRealm()
    if (realm == null) {
      log.warn("SecurityRealm unavailable; falling back to raw submitter '${submitter}' for userId comparison (issue #15)")
      return submitter
    }
    def userDetails = realm.loadUserByUsername(submitter)
    def resolved = userDetails?.getUsername()
    if (resolved == null || resolved.trim().isEmpty()) {
      log.warn("SecurityRealm returned empty userId for '${submitter}'; falling back to raw value (issue #15)")
      return submitter
    }
    return resolved
  } catch (Throwable t) {
    // Catch Throwable (not just Exception) because realm impls in some Jenkins
    // versions throw UsernameNotFoundException (a checked subclass of
    // RuntimeException via Spring), and the sandbox can throw RejectedAccessException
    // which is a SecurityException. Either way we log and fall back.
    log.warn("SecurityRealm.loadUserByUsername('${submitter}') failed (${t.class.simpleName}: ${t.message}); falling back to raw value for userId comparison (issue #15)")
    return submitter
  }
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
        # --eval runs BEFORE --file, so setReadPref applies to the script's
        # session. Belt-and-suspenders: even if the URI from the credential
        # drifts (or omits readPreference=primary), mongosh enforces primary
        # reads for the dry-run pre-count, apply-time recheck, destructive
        # writes, and post-write verify. Tracks
        # hourtimesheet/hts-company-onboarding-pipeline-script#24 (audit F-Low-1).
        mongosh "$MONGO_URI" --quiet --eval 'db.getMongo().setReadPref("primary")' --file "$WORKSPACE/$HTS_SUPPORT_SCRIPT" 2>&1 | tee "$WORKSPACE/$HTS_SUPPORT_OUT_LOG"
      '''
    }
  }
}
