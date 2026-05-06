# hts-jenkins-shared-library

Jenkins shared-library steps for Hour Timesheet's operational support jobs (the
HK-22xx ticket family and similar).

The library exposes one main step, **`htsSupportJob`**, which factors out the
common pipeline — *bootstrap / validate / dry-run preview / confirm / apply /
audit* — that every Mongo-backed support job needs. Each ticket's Jenkinsfile
shrinks from ~280 lines of boilerplate to ~15 lines, and ticket-specific logic
lives exclusively in a sibling `.mongosh.js`.

---

## One-time Jenkins registration

In Jenkins **Manage Jenkins → System → Global Pipeline Libraries**, add a new
library:

| Field                    | Value                                                  |
| ------------------------ | ------------------------------------------------------ |
| Name                     | `hts`                                                  |
| Default version          | `main` (only during pre-v1 phase — see below)          |
| Load implicitly          | unchecked                                              |
| Allow default version override | checked                                          |
| Retrieval method         | Modern SCM                                             |
| Source                   | GitHub: `hourtimesheet/hts-jenkins-shared-library`     |
| Credentials              | A read-only PAT or GitHub App with repo:read           |

### Library-version policy

Pinning a Jenkins shared library to a moving branch is a supply-chain risk: any
push to `main` rolls out to every running consumer pipeline immediately, with
no review. We therefore distinguish two phases:

1. **Pre-v1 (today)** — exactly the first **two** consumers (HK-2204 and
   HK-2205) load the library as `@Library('hts@main') _` so the library and
   the first two scripts can co-evolve quickly. Every change to the library
   still goes through the 8-agent ensemble audit; the `main` pin is a tighter
   feedback loop, not an excuse to skip review.
2. **Post-v1 (after HK-2205 ships green)** — the repo is tagged `v1.0.0`. From
   then on every consumer Jenkinsfile loads the library as
   `@Library('hts@v1') _` (floating major) and PRs may NOT add a third
   `@main` consumer.

CODEOWNERS + branch protection on `vars/` and `src/` (requiring two reviewers,
including a security-team member, on every change) are tracked as a follow-up
issue and SHOULD be in place before the v1.0.0 tag.

```groovy
// During pre-v1 (HK-2204, HK-2205 only):
@Library('hts@main') _

// Post-v1.0.0 (every other ticket):
@Library('hts@v1') _
```

### Agent prerequisites

Each Jenkins agent that runs a support job needs:

1. **`mongosh` ≥ 1.10** on `PATH` ([install guide](https://www.mongodb.com/docs/mongodb-shell/install/)).
   The library asserts the version at the start of every build and aborts on
   anything older than 1.10.
2. **`bash`** on `PATH`. Jenkins's default `sh` step on Debian/Ubuntu agents
   resolves to `/bin/sh` → `dash`, which does not support `set -o pipefail`.
   The library's shell helpers all begin with `#!/usr/bin/env bash` so the
   pipefail/-e/-u guarantees actually hold.
3. Network egress to the prod Mongo cluster.
4. The Mongo URI registered as a **string** credential (id `hts-mongo-prod-uri`
   by default — override per job via `credentialId`).

### Required Jenkins plugins

| Plugin                                                                     | Used for                                                              |
| -------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| [pipeline-model-definition](https://plugins.jenkins.io/pipeline-model-definition/) | Declarative `pipeline { ... }` syntax inside the `vars/` step        |
| [credentials](https://plugins.jenkins.io/credentials/) + [credentials-binding](https://plugins.jenkins.io/credentials-binding/) | `withCredentials([string(credentialsId:…)])` masking of the Mongo URI |
| [ansicolor](https://plugins.jenkins.io/ansicolor/)                          | Coloured stage banners in the console                                 |
| [timestamper](https://plugins.jenkins.io/timestamper/)                      | `timestamps { ... }` wrapper for replayable logs                      |
| [lockable-resources](https://plugins.jenkins.io/lockable-resources/)        | Per-tenant `lock("hts-support-…")` so two HK-22xx jobs cannot interleave on the same tenant |
| [ws-cleanup](https://plugins.jenkins.io/ws-cleanup/)                        | `cleanWs` after archiving — keeps the agent workspace from growing per build |
| [build-user-vars](https://plugins.jenkins.io/build-user-vars-plugin/) (optional) | Populates `BUILD_USER` so the dry-run audit line names a real human  |

---

## Consumer Jenkinsfile shape

Every consumer Jenkinsfile follows the same template:

```groovy
@Library('hts@main') _   // pre-v1 only; switch to 'hts@v1' after v1.0.0

properties([parameters([
  string(
    name: 'companyName',
    description: 'Tenant subdomain — must exist in masterConfiguration._id.',
    trim: true
  ),
  booleanParam(
    name: 'APPLY',
    defaultValue: false,
    description: 'Uncheck for dry-run (default). Check to actually write.'
  ),
  string(
    name: 'reason',
    description: 'Audit-log entry. Min 8 chars (e.g. "Sales SUPP-1234 — customer purchased GEP").',
    trim: true
  ),
])])

htsSupportJob {
  ticketId         = 'HK-2204'
  companyName      = params.companyName
  APPLY            = params.APPLY
  reason           = params.reason
  mongoScriptFile  = 'hk-2204-add-gep.mongosh.js'
  // credentialId       = 'hts-mongo-prod-uri'      // default — override only if needed
  // extraEnv           = [:]                       // extra env vars passed to mongosh
  // approverGroup      = 'hts-oncall'              // restrict the apply submitter
  // requireDualApproval = false                    // require two distinct approvers
  // confirmTenantName  = true                      // operator must retype the slug (default true)
  // onApply            = { payload -> /* webhook */ }   // notify on apply success
  // onFailure          = { payload -> /* webhook */ }   // notify on failure / abort
}
```

> **First run of a new job has empty `params`.** That's expected: the first
> build registers the parameter inputs in Jenkins and fails validation. The
> second build picks up the populated params and runs normally.

> Any consumer-side validation belongs in the Jenkinsfile **before** the call
> to `htsSupportJob {}`. The library deliberately does NOT accept an
> `extraValidation` closure — capturing a closure that runs hours later (after
> the input step) is brittle across Jenkins controller restarts.

---

## Example: HK-2204 (single-toggle write)

A complete consumer Jenkinsfile for the HK-2204 ticket "Add GEP to a specific
account" — replaces the manual "enable PAYROLL toggle" step Support runs by
hand.

```groovy
// Jenkinsfile in hts-company-onboarding-pipeline-script (alongside hk-2204-add-gep.mongosh.js)
@Library('hts@main') _

properties([parameters([
  string(
    name: 'companyName',
    description: 'Tenant subdomain — case-sensitive, must exist in masterConfiguration._id (e.g. acme, globex).',
    trim: true
  ),
  booleanParam(
    name: 'APPLY',
    defaultValue: false,
    description: 'Uncheck for dry-run (default). Check to actually write the toggle.'
  ),
  string(
    name: 'reason',
    description: 'Why GEP is being enabled (e.g. "Sales SUPP-1234 customer purchased GEP"). Logged to auditEventLog.',
    trim: true
  ),
])])

htsSupportJob {
  ticketId         = 'HK-2204'
  companyName      = params.companyName
  APPLY            = params.APPLY
  reason           = params.reason
  mongoScriptFile  = 'hk-2204-add-gep.mongosh.js'
}
```

The sibling `hk-2204-add-gep.mongosh.js` (~120 lines) contains the actual Mongo
logic; see the contract below.

---

## `.mongosh.js` contract

Each ticket's `.mongosh.js` is invoked once per pipeline stage that needs
Mongo: once for dry-run (`APPLY=false`) and, after operator confirmation, once
for apply (`APPLY=true`).

### Env vars the script can read

| Env var               | Set by                                | Notes                                     |
| --------------------- | ------------------------------------- | ----------------------------------------- |
| `COMPANY_NAME`        | `htsSupportJob`                       | Tenant slug, validated by the pipeline.   |
| `APPLY`               | `htsSupportJob`                       | Literal string `'true'` or `'false'`.     |
| `REASON`              | `htsSupportJob`                       | The audit reason from the operator.       |
| `OPERATOR`            | `htsSupportJob`                       | Jenkins `BUILD_USER` (dry-run) or the input-step submitter (apply). |
| `BUILD_URL`           | Jenkins                               | Provided by Jenkins for free.             |
| `HTS_CORRELATION_ID`  | `htsSupportJob`                       | UUID (or `BUILD_TAG`) tying the Jenkins-side audit log to the Mongo `auditEventLog` row the script writes. **MUST be persisted** to Mongo (see *Auditing*). |
| `HTS_SUPPORT_SCRIPT`  | `htsSupportJob`                       | Path of THIS script (informational).      |
| `HTS_SUPPORT_OUT_LOG` | `htsSupportJob`                       | Path of the per-stage tee log (informational). |
| (anything in `extraEnv`) | consumer Jenkinsfile               | E.g. `EMPLOYEE_EMAIL`. Names must match `[A-Z_][A-Z0-9_]*` and not collide with the reserved set above. |

#### Reserved env-var names (rejected by `extraEnv` validation)

`COMPANY_NAME`, `APPLY`, `REASON`, `OPERATOR`, `MONGO_URI`,
`HTS_SUPPORT_SCRIPT`, `HTS_SUPPORT_OUT_LOG`, `HTS_CORRELATION_ID`,
`BUILD_URL`, `BUILD_USER`, `WORKSPACE`,
`PATH`, `HOME`, `LD_LIBRARY_PATH`, `LD_PRELOAD`.

The shell-loader names (`PATH`, `LD_*`) are reserved so a misconfigured
consumer cannot redirect mongosh resolution or hijack the process loader.
`WORKSPACE` and `HTS_SUPPORT_OUT_LOG` are reserved so a consumer cannot trick
the helper into writing log files outside the workspace.

### Exit codes

| Code | Meaning                                                |
| ---- | ------------------------------------------------------ |
| `0`  | Success — the script ran one of its happy paths (noop, dry-run, applied). |
| `≠0` | Fatal error. The script SHOULD also print a line containing `FATAL`; the pipeline aborts in either case (`set -euo pipefail` under `bash`). |

The pipeline **never swallows non-zero exits**. If `mongosh` exits non-zero,
the build fails — investigate the artifact log in the Jenkins build page.

### `RESULT_JSON=` final-line contract

Every successful run MUST emit a final line shaped like:

```
RESULT_JSON={"status":"<status>", ...}
```

…where `<status>` is one of:

| Status     | Set when                                                      |
| ---------- | ------------------------------------------------------------- |
| `noop`     | Tenant is already in the desired state. Apply stage is skipped. |
| `dry-run`  | `APPLY=false` and a write would happen. Pipeline pauses for confirmation. |
| `applied`  | `APPLY=true` and the write succeeded. Recorded in the audit-log JSONL artifact (canonical). |
| `failed`   | `APPLY=true` but the post-write verify failed. Use exit code `≠0` to abort the pipeline. |

The pipeline scans the captured log for the configured markers (`noopMarker`,
`dryRunMarker`, `appliedMarker`, `fatalMarker`) and routes accordingly.

### Apply-time precondition re-check (mandatory)

The dry-run runs ahead of the operator confirmation. Between dry-run and apply
the operator can wait minutes or hours, and we cannot prove that no other
process mutated the same documents in the interim — Jenkins's lock is on the
Jenkins side, not the Mongo side.

**Every `.mongosh.js` MUST therefore re-assert its preconditions inside its
apply branch**, not just rely on the dry-run check. If the precondition fails
on apply, exit non-zero with `FATAL` so the pipeline aborts before writing.

```javascript
// Inside the .mongosh.js apply branch — sketch
if (APPLY === 'true') {
  // RE-CHECK preconditions; do not rely on the dry-run.
  const current = db.masterConfiguration.findOne({ _id: COMPANY_NAME });
  if (current.modules.PAYROLL === true) {
    print('RESULT_JSON={"status":"noop","correlationId":"' + HTS_CORRELATION_ID + '"}');
    exit(0);
  }
  if (current.locked === true) {
    print('FATAL: tenant ' + COMPANY_NAME + ' is locked; refusing to write.');
    exit(2);
  }
  // ... write inside the same withTransaction as the auditEventLog row ...
}
```

### Idempotency

Re-running the same pipeline against the same `companyName` MUST be safe:

* If the tenant is already in the desired state, the script prints
  `"status":"noop"` and exits 0. The pipeline records `noop` in the audit-log
  JSONL artifact (the canonical record) and skips the apply stage.
* The pipeline declares `disableConcurrentBuilds()` to serialise runs of the
  same job, so two operators clicking "Apply" within seconds of each other on
  the same Jenkins job cannot race a double-write.
* The pipeline ALSO acquires a Jenkins `lock("hts-support-${companyName}")`
  spanning dry-run + confirm + apply, so two DIFFERENT HK-22xx jobs targeting
  the same tenant cannot interleave between dry-run and apply.

### Auditing

The `.mongosh.js` writes to `hourtimesheet.auditEventLog` itself (it knows the
correct `eventType`). **The audit row MUST be written in the same Mongo
transaction as the data write** (or via `findAndModify` if a single-document
update is enough), so a partial commit cannot leave a write without an audit
row, or vice versa.

```javascript
// Skeleton — write the audit row in the SAME transaction as the data write.
const session = db.getMongo().startSession();
session.startTransaction();
try {
  session.getDatabase('hourtimesheet').masterConfiguration.updateOne(
    { _id: COMPANY_NAME }, { $set: { 'modules.PAYROLL': true } }
  );
  session.getDatabase('hourtimesheet').auditEventLog.insertOne({
    eventType:     'hk-2204-add-gep',
    companyName:   COMPANY_NAME,
    operator:      OPERATOR,
    reason:        REASON,
    correlationId: HTS_CORRELATION_ID,                          // <-- ties to Jenkins audit log
    buildUrl:      BUILD_URL,
    timestamp:     new Date(),
  });
  session.commitTransaction();
} catch (e) {
  session.abortTransaction();
  throw e;
} finally {
  session.endSession();
}
```

The pipeline ALSO writes a Jenkins-side audit artifact (a JSON line under
`.htsSupportJob/<buildNumber>/<ticketId>-audit.log`) carrying the same
`correlationId`. Every build's preview / apply / audit logs are archived as
build artefacts so you can reconstruct the full history from Jenkins alone if
the Mongo write fails.

#### Audit-log schema (issue #8)

The Jenkins-side audit artifact is a **versioned JSON-line (JSONL) file**:

| Property         | Value                                                                          |
| ---------------- | ------------------------------------------------------------------------------ |
| Path             | `.htsSupportJob/${BUILD_NUMBER}/${ticketId}-audit.log`                         |
| Format           | JSONL — one JSON object per line, UTF-8 encoded, LF-terminated (`\n`)          |
| Lines per build  | Exactly **one** (written from `post.always` after the lock releases)           |
| Encoding         | Manual JSON encoder; integer values unquoted, strings escape control chars     |

**Every line is guaranteed to start with `{"schema_version":N,…`** so consumers
can grep at column 0 and reject unknown major versions.

```json
{"schema_version":1,"ticketId":"HK-2204","companyName":"acme","status":"applied","buildResult":"SUCCESS","operator":"jdoe","secondary":"","correlationId":"jenkins-HK-2204-42-deadbeef","buildUrl":"https://jenkins.example.com/job/HK-2204/42/","buildNumber":"42","reason":"Sales SUPP-1234 customer purchased GEP","apply":true,"timestamp":"2026-05-05T12:34:56Z"}
```

##### Required fields (schema_version=1)

| Field            | Type    | Description                                                                              |
| ---------------- | ------- | ---------------------------------------------------------------------------------------- |
| `schema_version` | int     | Audit-log schema major version. Currently `1`. Always the FIRST key in the JSON object. |
| `ticketId`       | string  | Jira-style key, e.g. `HK-2204`. Validated against `[A-Z]{2,6}-[0-9]{2,6}`.               |
| `companyName`    | string  | Tenant slug (DNS label) — must match `masterConfiguration._id` in Mongo.                 |
| `status`         | string  | One of: `applied`, `noop`, `dry-run`, `aborted`, `failed`.                               |
| `buildResult`    | string  | Jenkins's `currentBuild.currentResult` (`SUCCESS`, `FAILURE`, `ABORTED`, `UNSTABLE`).    |
| `operator`       | string  | Confirm-step submitter (or `BUILD_USER` pre-confirm; `unknown` if neither).              |
| `secondary`      | string  | Second approver when `requireDualApproval=true`; empty string otherwise.                 |
| `correlationId`  | string  | Ties this row 1:1 to the Mongo `auditEventLog` row. Format: `BUILD_TAG-BUILD_NUMBER-UUID8`. |
| `buildUrl`       | string  | Jenkins build URL (empty string if Jenkins did not set `BUILD_URL`).                     |
| `buildNumber`    | string  | Jenkins's `BUILD_NUMBER` (string, not int — Jenkins sets it as an env-var string).       |
| `reason`         | string  | Operator-supplied reason from the `reason` parameter (8..500 chars, no control chars).   |
| `apply`          | boolean | The `APPLY` parameter as supplied to this build (true=apply attempt; false=dry-run).     |
| `timestamp`      | string  | ISO-8601 UTC instant from `java.time.Instant.now().toString()`. Always ends in `Z`.      |

##### Migration policy

The version is stored as the constant
`com.hourtimesheet.jenkins.SupportJobConfig.AUDIT_SCHEMA_VERSION`. **Bump it
when, AND ONLY when, you make a backwards-incompatible change to the row
shape:**

* a required field is **renamed** or **removed**, OR
* a required field's **type changes** (string → int, scalar → object), OR
* the **meaning** of an existing field changes in a way consumers must handle
  (e.g. enum values, units).

Adding a new **optional** field is a non-breaking change — keep the version,
document the field as optional in this table.

Adding a new **required** field IS a breaking change for consumers that filter
on field presence — bump the version.

##### Consumer guidance

Downstream tooling (log shippers, SIEM rules, future ingestion jobs, forensic
queries) SHOULD:

1. **Reject** unknown major versions — `schema_version > 1` means the row may
   omit fields you depend on.
2. **Tolerate** unknown OPTIONAL fields at the current major version — they are
   backwards-compatible additions.
3. **Pin** the schema version in any cached extraction logic so a future bump
   surfaces as a loud parser error rather than a silent data-shape drift.

A minimal `jq` reader for v1:

```bash
jq -c 'select(.schema_version == 1) | {ticketId, status, correlationId, timestamp}' \
   .htsSupportJob/*/HK-2204-audit.log
```

#### Post-apply probe — enforcing the audit-row contract (issue #17)

Pre-#17, the contract that every `RESULT_JSON status=applied` produces
**exactly one** `auditEventLog` row with the matching `correlationId` was
documentation-only. A consumer `.mongosh.js` that wrote the data but skipped
the audit row (bug, schema typo, network glitch mid-write) would still report
success — Mongo silently lacked the forensic record, and you'd only discover
it later from a missing forensics search.

The Apply stage now runs a tiny **post-apply probe** immediately after the
`appliedMarker` check passes:

```bash
mongosh "$MONGO_URI" --quiet \
  --eval 'db.getMongo().setReadPref("primary")' \
  --eval 'print(db.getSiblingDB("hourtimesheet").auditEventLog
                  .countDocuments({correlationId: process.env.MONGOSH_CORR_ID}))'
```

Expected output is the integer `1`. The build fails on:

| Count   | Diagnosis                                                              | Build outcome |
| ------- | ---------------------------------------------------------------------- | ------------- |
| `0`     | Consumer mongosh.js did NOT write the audit row.                       | FAIL with correlationId in error |
| `>1`    | Same correlationId written multiple times (broken consumer).           | FAIL with duplicate diagnostic   |
| `1`     | Contract upheld — audit row present.                                   | INFO `Audit row VERIFIED ...`    |

Implementation notes:

* The probe runs ONLY on `status=applied` — never on noop / dry-run / failed.
* The probe runs ONLY when the apply itself just reported `appliedMarker`.
* `correlationId` is passed via the `MONGOSH_CORR_ID` env var and read from
  inside the eval JS as `process.env.MONGOSH_CORR_ID`. It is **never**
  interpolated into the shell command or the `--eval` JS string. The id is
  built from `BUILD_TAG` (issue #16), which contains `JOB_NAME` — partially
  user-controlled — so any string-interpolation path would be a shell / JS
  injection surface. Env-var indirection is the safe pattern.
* `setReadPref("primary")` is set first via `--eval`, mirroring the apply-
  side `_runMongosh` hardening (issue #24): the probe never reads from a
  stale secondary while verifying a just-committed write.
* Probe execution failures (mongosh missing, primary unreachable, malformed
  URI) fail the build. Soft-fail would defeat the P1 contract enforcement —
  operators with a justified reason to bypass set `cfg.skipPostApplyProbe =
  true`.
* `cfg.skipPostApplyProbe` (default `false`) is an emergency override for
  Mongo-outage scenarios where the apply committed but the read-side primary
  is unreachable (post-write failover, etc.). When set, the probe is skipped
  with a WARN log; the operator must verify the audit row manually in Mongo
  before declaring the build complete. **Use sparingly and only with explicit
  on-call sign-off.**

**Backward compatibility:** the existing HK-2204..HK-2209 consumer
`.mongosh.js` scripts already write the audit row in the same transaction as
the data write (the canonical skeleton above). They pass the probe as-is — no
consumer-side change is required. New consumers MUST follow the same skeleton
or the build will fail loudly (which is the point of #17).

### Status of truth (issue #14)

The **canonical** sources of truth for what a build did are:

1. The audit-log JSONL artifact at
   `.htsSupportJob/<buildNumber>/<ticketId>-audit.log` (status, operator,
   secondary, correlationId, buildResult, timestamp).
2. The `auditEventLog` row written by the `.mongosh.js` itself, tied 1:1 to
   the JSONL line via `correlationId`.

`currentBuild.description` is written **once**, in `post.always`, as an
operator-glance summary string (e.g. `applied: HK-2204 for acme`). It is
**informational only** and **never read back** by any pipeline code, `when{}`
clause, or consumer Jenkinsfile. Reasons:

* The Jenkins UI lets users edit a build's description by hand, which would
  silently desync from canonical state.
* Plugin upgrades and the Jenkins master can lose the description; the audit-
  log artifact survives in build artefacts.
* A failed or interrupted intermediate write (the previous design wrote
  description twice, at noop-detect and at apply-success) could leave a stale
  string masking a later failure.

If you find yourself reaching for `currentBuild.description` to make a
decision, read the audit-log JSONL artifact instead.

---

## Operator-confirmation hardening

The `Confirm` stage's `input` step is the single human gate between a vetted
dry-run and a real write. Three knobs harden it:

| Field                  | Default | Effect                                                                                                                                                  |
| ---------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `confirmTenantName`    | `true`  | The input step adds a `CONFIRM_TENANT_NAME` text field; the apply aborts unless the operator retypes the tenant slug exactly.                            |
| `approverGroup`        | `null`  | If set, restricts the input step's `submitter:` to the named Jenkins user / group (e.g. `'hts-oncall'`). Anyone else who clicks "Apply" is rejected.     |
| `requireDualApproval`  | `false` | Adds a SECOND input step that requires a different submitter from the first. The apply aborts if both gates were approved by the same person.            |
| `allowMongoshPrerelease` | `false` | Pre-flight refuses pre-release `mongosh` builds (`-rc.*`, `-beta`, `-alpha.*`) by default — driver semantics may differ from GA. Opt in only on a non-prod tenant when knowingly testing a candidate build (issue #13). |
| `skipPostApplyProbe`   | `false` | Skip the post-apply audit-row probe (issue #17). The probe enforces the documented contract that every `status=applied` produces exactly one `auditEventLog` row matching `correlationId`. Set to `true` ONLY as an emergency override during a Mongo outage where the apply committed but the read-side primary is unreachable; the operator must then verify the row manually. |

Use `requireDualApproval = true` for high-risk tickets (mass updates, deletes,
financial-impact writes). Single-tenant single-toggle changes can stay on the
single-approver default.

---

## Notification hooks

`htsSupportJob` does not ship a default Slack/PagerDuty/email integration —
those are organisation-specific and best handled at the consumer level. Two
optional hooks fire on success-after-apply and on failure/abort respectively:

```groovy
htsSupportJob {
  ticketId        = 'HK-2204'
  /* ... */
  onApply = { payload ->
    // payload keys: ticketId, companyName, submitter, reason, correlationId, buildUrl, status
    httpRequest(
        url: env.SLACK_WEBHOOK,
        httpMode: 'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: groovy.json.JsonOutput.toJson([
            text: "${payload.ticketId} applied for ${payload.companyName} by ${payload.submitter}: ${payload.buildUrl}",
        ]),
    )
  }
  onFailure = { payload ->
    // payload.status is one of: 'failed', 'aborted'
    pagerdutyTrigger(severity: 'error', summary: "${payload.ticketId} ${payload.status}")
  }
}
```

A throwing hook is logged as a `WARN` and does NOT fail the build — failed
notifications must not roll back a successful write.

---

## Choosing markers

`htsSupportJob` ships with sensible defaults that match the HK-22xx ticket
family:

| Marker          | Default               |
| --------------- | --------------------- |
| `noopMarker`    | `"status":"noop"`     |
| `dryRunMarker`  | `"status":"dry-run"`  |
| `appliedMarker` | `"status":"applied"`  |
| `fatalMarker`   | `FATAL`               |

Override only if your `.mongosh.js`:

* Uses a different result envelope (e.g. logs `RESULT={status:noop}` instead of
  the JSON form). In that case set all four markers consistently.
* Has multiple legitimate "no-op" paths and you want to distinguish them in
  the build description (you can set `noopMarker` to a substring shared by
  both, and let the script's `RESULT_JSON` carry the detail).
* Needs to coexist with an unrelated `FATAL` token in normal output (rare —
  prefer fixing the script).

Don't set markers to overly generic substrings like `"status"` — false matches
are a silent failure mode.

`.mongosh.js` MUST NEVER print fragments of the connection string (URI
substrings, hostnames, base64 fragments). Jenkins's credential masking is
exact-match against the credential value; partial echoes can leak through.

---

## Step API

```groovy
htsSupportJob {
  // --- Required ---
  ticketId         = 'HK-2204'                     // [A-Z0-9][A-Z0-9-]{2,31}; used in log file names & build description
  companyName      = params.companyName            // tenant slug; [a-z0-9](-?[a-z0-9]){0,62} — no leading/trailing/double hyphens, no underscores
  reason           = params.reason                 // 8..500 chars, no control chars; recorded in audit log
  mongoScriptFile  = 'hk-2204-add-gep.mongosh.js'  // relative path inside $WORKSPACE; no '..', leading '/', backslashes, NUL, or spaces

  // --- Optional ---
  APPLY                = params.APPLY              // boolean, default false (dry-run)
  credentialId         = 'hts-mongo-prod-uri'      // Jenkins string credential holding the Mongo URI
  extraEnv             = [:]                       // additional env vars; keys must match [A-Z_][A-Z0-9_]*, not collide with the
                                                   //   reserved set, and values must not contain control characters
  approverGroup        = null                      // optional Jenkins user/group authorised to submit the apply input step
  requireDualApproval  = false                     // require two distinct approvers
  confirmTenantName    = true                      // operator must retype tenant slug at the input gate
  allowMongoshPrerelease = false                   // accept mongosh pre-release builds (-rc/-beta/-alpha)? default false
  skipPostApplyProbe   = false                     // emergency override: skip the issue #17 audit-row probe (Mongo outage only)
  // Decommission story (issue #10) — used when a support job approaches retirement
  deprecated           = false                     // emit a WARN banner at pipeline start; informational, does NOT block runs
  deprecatedMessage    = null                      // optional one-line migration pointer; falls back to a generic banner
  freezeAfter          = null                      // ISO date 'YYYY-MM-DD'; on/after this date (UTC) the Apply path refuses
  freezeBlocksNoop     = false                     // when true AND freezeAfter has elapsed, ALSO block dry-run/noop runs
  onApply              = null                      // closure invoked on apply success
  onFailure            = null                      // closure invoked on apply failure / abort

  // --- Result-detection markers (override only if your .mongosh.js uses different ones) ---
  noopMarker           = '"status":"noop"'
  dryRunMarker         = '"status":"dry-run"'
  appliedMarker        = '"status":"applied"'
  fatalMarker          = 'FATAL'
}
```

### Stages

1. **Bootstrap** — initialises in-build state (correlation ID, submitter,
   isNoop / wasApplied flags). Runs first so the rest of the pipeline can
   reference these without touching `env.*`. When `cfg.deprecated = true`
   (issue #10), emits a `⚠️ DEPRECATED:` WARN banner here so it's the first
   thing the operator sees.
2. **Validate input** — required-fields check, slug regex, reason length /
   control chars, plus pre-flight checks: `WORKSPACE` set, script file exists,
   canonical script path is inside `WORKSPACE` (rejects symlink escape),
   `mongosh ≥ 1.10` on `PATH`.
3. **Per-tenant lock** — wraps Dry-run / Confirm / Apply in a Jenkins
   `lock("hts-support-${companyName}")` so two HK-22xx jobs targeting the same
   tenant cannot interleave.
4. **Dry-run preview** — runs `.mongosh.js` with `APPLY=false`. Aborts on
   `fatalMarker`. Marks the run as a no-op on `noopMarker` (status recorded in
   the audit-log JSONL artifact — see "Status of truth"), in which case the
   apply stage is skipped. 5-minute timeout. When `cfg.freezeBlocksNoop=true`
   AND today (UTC) ≥ `cfg.freezeAfter`, the dry-run path also refuses with
   the freeze message (issue #10).
5. **Confirm** — only when `APPLY=true` and the dry-run was not a no-op.
   Pauses the pipeline for an interactive `input` step (60-minute timeout)
   with a mandatory `I_HAVE_REVIEWED_THE_PREVIEW` checkbox, an optional
   tenant-name retype, optional approver-group restriction, and optional
   dual-approval second gate. Captures the submitter for the audit trail.
6. **Apply** — only when `APPLY=true` and not a no-op (15-minute timeout).
   When `cfg.freezeAfter` is set AND today (UTC) ≥ that date, the stage
   throws with `Apply blocked: …` immediately AFTER the operator submits
   the Confirm input — the input still appears so the operator gets a
   clear error rather than a silently missing stage (issue #10).
   Otherwise, runs `.mongosh.js` again with `APPLY=true` and
   `OPERATOR=<submitter>`. Aborts if `appliedMarker` is not in the log.
   **After `appliedMarker` passes**, a post-apply probe queries Mongo for
   an `auditEventLog` row matching the run's `correlationId`; the build
   fails if exactly one row is not present (issue #17).
   `cfg.skipPostApplyProbe = true` is an emergency override for Mongo-
   outage scenarios.
7. **Audit (`post.always`)** — writes a JSON-line audit artefact to
   `.htsSupportJob/<buildNumber>/<ticketId>-audit.log` REGARDLESS of pipeline
   outcome (success, failure, abort). The line carries the same
   `correlationId` the `.mongosh.js` wrote into Mongo's `auditEventLog`.
8. **Archive (`post.always`)** — `archiveArtifacts` of
   `.htsSupportJob/<buildNumber>/<ticketId>-*.log` so preview / apply / audit
   logs are attached to every build, then `cleanWs` clears the workspace
   subtree so per-build logs don't accumulate on the agent.

---

## Logger API (`vars/log.groovy`)

Lightweight ANSI emitters: `log.info`, `log.debug`, `log.warn`, `log.errorLine`.
The red-line emitter is `errorLine` (not `error`) so it does not shadow
Jenkins's built-in `error(String)` step that aborts the build — use
`log.errorLine(msg)` to emit a red line, and the global `error(msg)` to fail
the pipeline.

---

## Operations

* **On-call**: support-platform on-call rota (TODO: link to Confluence).
* **Mongo URI rotation**: stored in Vault path `secret/jenkins/hts-mongo-prod-uri`
  (TODO: link). Update Vault and re-import the credential in Jenkins.
* **Audit trail**: every build writes both (a) a Jenkins-side JSON-line audit
  log to the build artefacts and (b) a Mongo `auditEventLog` row from the
  `.mongosh.js`. Both share the same `correlationId` for cross-referencing.

### Recovery — apply log shows mongosh exited 0 but no `RESULT_JSON={"status":"applied"...}`

This is a rare but load-bearing case: the pipeline's `appliedMarker` check
fails the build, but state in Mongo is unknown.

1. **Do NOT re-run with `APPLY=true`** — you'd risk a double-write.
2. Re-run with `APPLY=false` and the same `companyName`. Read the dry-run
   output to determine whether the tenant is in the desired state.
   * If `"status":"noop"` — the original apply succeeded; the missing marker
     was a script bug. File a follow-up to fix the marker emission.
   * If `"status":"dry-run"` — the original apply did NOT fully commit.
     Investigate the apply log + Mongo `auditEventLog` to determine whether
     the data write happened without the audit row, before re-applying.
3. Cross-reference the Jenkins-side audit log's `correlationId` against
   `auditEventLog.find({correlationId: …})`. A row exists ⇔ the
   `.mongosh.js` reached its commit point.

### Decommissioning a support job (issue #10)

Operational support jobs are intentionally short-lived: a ticket lands, the
toggle is applied across the affected tenants, and after some weeks of "just
in case" availability the consumer Jenkinsfile should retire. Retiring a job
hot — deleting the Jenkinsfile, breaking the next operator who tries to
re-run a known-good fix — is the failure mode this section exists to
prevent.

Drive every retirement through a four-stage playbook so operators always
get a clear signal about what they can and cannot do, and never see "stage
disappeared mysteriously" failures:

1. **T-90 days — flip on the deprecation banner.** In the consumer
   Jenkinsfile, set:

   ```groovy
   htsSupportJob {
     // … existing config …
     deprecated        = true
     deprecatedMessage = 'HK-2204 (add GEP) retires on 2026-08-01; use the platform-tools UI instead. See go/hts-retirement.'
   }
   ```

   Effect: a `⚠️ DEPRECATED:` log line appears at the top of every build.
   Runs still work normally. This is the cheapest signal — operators paging
   through the dry-run output now know retirement is coming.

2. **T-14 days — freeze the Apply path.** Set `freezeAfter` to the
   retirement date in `YYYY-MM-DD` form (UTC):

   ```groovy
   freezeAfter = '2026-08-01'
   ```

   Effect: on or after `2026-08-01` (UTC), the operator can still trigger
   the build and submit the Apply input, but submission throws with
   `Apply blocked: HK-2204 is frozen as of 2026-08-01; noop runs still
   allowed`. Dry-run / noop runs are still permitted so operators can do
   "did this tenant ever have the toggle?" forensics from the dry-run
   preview.

3. **T-0 — freeze noop too.** When the dry-run / noop path is no longer
   useful (e.g. the Mongo schema has already changed under it), set:

   ```groovy
   freezeBlocksNoop = true
   ```

   Effect: dry-run / noop runs ALSO fail with the freeze message. The
   pipeline is now a hard error on every invocation — useful as a
   tombstone before the file is removed.

4. **T+0 — archive artefacts and remove the job.** Once the freeze message
   has been visible long enough that operators have stopped triggering it:

   1. Pull the last 90 days of `.htsSupportJob/<n>/<ticketId>-audit.log`
      JSONL artefacts off the Jenkins agent / archive backend (S3 / NFS /
      whatever the JCasC artifact store is). Store them in the support
      team's evidence archive (e.g. a GCS bucket dump) so future forensics
      can correlate `correlationId` ↔ Mongo `auditEventLog` rows after the
      Jenkins job is gone. Mongo's `auditEventLog` row is the canonical
      record (issue #14) but the JSONL adds the Jenkins-side build URL
      and submitter pair.
   2. Delete the consumer pipeline's `Jenkinsfile` and `.mongosh.js`.
   3. Remove the `htsSupportJob { … }` invocation entirely if it's still
      referenced by another script (it usually isn't — one Jenkinsfile
      per ticket).
   4. Remove the Jenkins Pipeline job in the UI (or via JCasC).
   5. Document the retirement in the operations runbook: the ticket id,
      the retirement date, the migration target (UI / new pipeline /
      "no longer applicable"), and the location of the archived audit
      artefacts.

Why four stages instead of "delete it": every previous out-of-hours operator
fix has had at least one "we re-ran the old job" recovery path. Removing the
job without a window where it logs `⚠️ DEPRECATED:` and a window where it
logs `Apply blocked:` produces a "stage disappeared / Jenkinsfile not found"
failure mode that's strictly worse than a clear refusal.

All date comparisons run in UTC (`LocalDate.now(ZoneOffset.UTC)`) so a
freeze date means the same thing on every Jenkins agent regardless of its
local timezone — see `FreezeGate.isFrozen` in `src/`.

---

### Rollback

Most HK-22xx tickets toggle a single field on a single document. To roll back,
run the inverse ticket (e.g. HK-2204 added GEP; HK-220X removes it). Where no
inverse ticket exists, write a one-shot `.mongosh.js` against the live URI
through this same pipeline — never edit Mongo by hand.

---

## Security notes

* The Mongo URI is loaded via `withCredentials([string(...)])`, which masks it
  in the Jenkins console output. The library additionally suppresses xtrace
  (`{ set +x; } 2>/dev/null`) immediately around the `mongosh` invocation, so
  the URI is not echoed to the console even if the calling `sh` was invoked
  with `-x`.
* Inside the `sh` block the URI is passed to `mongosh` as a double-quoted
  argument — shell-special characters in the URI (semicolons, ampersands,
  dollar signs, spaces) cannot break the command.
* `set -euo pipefail` and `umask 077` are set on every `sh` block via a
  `#!/usr/bin/env bash` shebang (Jenkins's default `sh` is dash on
  Debian/Ubuntu, which does not support `pipefail`). The preview/apply log
  files are world-unreadable on the agent.
* `mongoScriptFile` is constrained to a relative path inside `$WORKSPACE`
  (no `..`, no absolute paths, no backslashes, no spaces, no NUL). After the
  declarative validation, the pipeline ALSO canonicalises the path
  (`readlink -f`) and rejects scripts whose canonical form falls outside
  `WORKSPACE` — defending against symlink escape.
* `extraEnv` rejects keys that would shadow library-set or shell-loader
  variables (`PATH`, `LD_PRELOAD`, `HTS_SUPPORT_OUT_LOG`, etc.) and rejects
  values containing control characters.

### Code review

Sensitive paths in this repo (`vars/`, `src/`, `.github/workflows/`,
`.github/dependabot.yml`) are tagged in [`.github/CODEOWNERS`](.github/CODEOWNERS).
A change to `vars/` or `src/` is a change to every HK-22xx consumer pipeline
simultaneously, so every change is intended to require explicit code-owner
review.

**Out of scope for the CODEOWNERS commit**: enabling branch protection on
`main` (require PR reviews, require review from Code Owners, require status
checks to pass) is tracked as a follow-up. It needs org-admin coordination
because GitHub blocks self-approval and David is currently the sole admin —
either a second human reviewer with admin rights, a service-account reviewer,
or an explicit decision to rely on the admin-override workflow is required
before branch protection can be turned on. Until then, the
`ensemble-audit-pass` label and audit comment on each PR serve as the review
artefact.

---

## Manual test plan

(For when you're rolling the library out for the first time, before there's a
green `./gradlew test` run plus a sentinel-tenant smoke job to lean on.)

1. Register the library in Jenkins as above and install the required plugins.
2. Create a Jenkins Pipeline job pointing at a feature branch of
   `hts-company-onboarding-pipeline-script` containing the consumer
   `Jenkinsfile` + `hk-2204-add-gep.mongosh.js`.
3. **Run 1 — empty params**: build with default values. Expect: validation
   error, parameter inputs registered, build fails red.
4. **Run 2 — dry-run on a tenant that already has the toggle**: companyName
   set, APPLY unchecked, reason filled. Expect: preview log shows
   `"status":"noop"`, audit-log JSONL artefact records `"status":"noop"`
   (canonical), end-of-pipeline build description summary `noop: HK-2204 for
   <tenant>` (informational only), build green, no apply stage runs.
5. **Run 3 — dry-run on a tenant that does NOT have the toggle**: APPLY still
   unchecked. Expect: preview log shows `"status":"dry-run"`, build green, no
   apply stage runs.
6. **Run 4 — apply**: same params as Run 3 but APPLY checked. Expect: pipeline
   pauses on input, operator confirms (retypes tenant slug), apply log shows
   `"status":"applied"`, audit-log JSONL artefact records `"status":"applied"`
   (canonical), end-of-pipeline build description summary `applied: …`
   (informational only), audit-log artefact archived under
   `.htsSupportJob/<buildNumber>/`, Mongo `auditEventLog` collection has a new
   entry whose `correlationId` matches the artefact's.
7. **Run 5 — re-apply (idempotency)**: rerun Run 4 unchanged. Expect:
   `"status":"noop"` in the preview, apply stage skipped.
8. **Run 6 — wrong tenant name confirmation**: same params as Run 4 but
   the operator types a different slug at the `CONFIRM_TENANT_NAME` prompt.
   Expect: pipeline aborts before the apply stage; `post.always` audit log
   line records `status:"aborted"`.

---

## Local development

```bash
./gradlew test            # run the Spock suite
./gradlew check           # test + assemble
```

The unit suite uses [Spock](https://spockframework.org/) exclusively today and
exercises `SupportJobConfig` exhaustively (regex edges, reserved-key
collisions, multi-error accumulation, control-char rejection in reason /
extraEnv values). Pipeline-shape behaviour — the declarative
`pipeline { ... }`, `withCredentials`, `input`, `archiveArtifacts`, the
`lock`, the post hooks — is verified manually in Jenkins (see *Manual test
plan* above). Adding a pipeline-shape test framework (e.g.
[JenkinsPipelineUnit](https://github.com/jenkinsci/JenkinsPipelineUnit)) is
deferred until its declarative-pipeline support matures or we factor more
logic out of the declarative block into plain Groovy methods.

CI runs the same Spock suite under JDK 17 (matching the Jenkins 2.452+
controller baseline) on every push and PR, with the Gradle wrapper JAR
validated against its published checksum and the Gradle distribution pinned
by SHA-256.

---

## Adding a new ticket

For each subsequent HK-22xx ticket:

1. Add `<ticket>.mongosh.js` to `hts-company-onboarding-pipeline-script`
   following the contract above (single-transaction write+audit, idempotent,
   re-asserts preconditions in the apply branch, persists `HTS_CORRELATION_ID`
   to the `auditEventLog` row).
2. Add `<ticket>.groovy` (the Jenkinsfile) — copy the example above and change
   only `ticketId`, `mongoScriptFile`, the parameter help text, and any
   `extraEnv`. Inline any consumer-side validation in the Jenkinsfile BEFORE
   the call to `htsSupportJob {}`.
3. Register the Pipeline job in Jenkins UI pointing at the new file.
4. Run the *Manual test plan* steps 3–8.

That's it. No copy-paste of the validate / dry-run / confirm / apply / audit flow.
