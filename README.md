# hts-jenkins-shared-library

Jenkins shared-library steps for Hour Timesheet's operational support jobs (the
HK-22xx ticket family and similar).

The library exposes one main step, **`htsSupportJob`**, which factors out the
common five-stage flow — *validate / dry-run preview / confirm / apply / audit*
— that every Mongo-backed support job needs. Each ticket's Jenkinsfile shrinks
from ~280 lines of boilerplate to ~15 lines, and ticket-specific logic lives
exclusively in a sibling `.mongosh.js`.

---

## One-time Jenkins registration

In Jenkins **Manage Jenkins → System → Global Pipeline Libraries**, add a new
library:

| Field                    | Value                                                  |
| ------------------------ | ------------------------------------------------------ |
| Name                     | `hts`                                                  |
| Default version          | `main`                                                 |
| Load implicitly          | unchecked                                              |
| Allow default version override | checked                                          |
| Retrieval method         | Modern SCM                                             |
| Source                   | GitHub: `hourtimesheet/hts-jenkins-shared-library`     |
| Credentials              | A read-only PAT or GitHub App with repo:read           |

Consumer Jenkinsfiles then load the library on the first line:

```groovy
@Library('hts@main') _
```

Pin a specific tag (`@Library('hts@v1.2.0') _`) once the library is tagged for
release; `main` is appropriate during the initial roll-out.

### Agent prerequisites

Each Jenkins agent that runs a support job needs:

1. `mongosh` ≥ 1.10 on `PATH` ([install guide](https://www.mongodb.com/docs/mongodb-shell/install/)).
2. Network egress to the prod Mongo cluster.
3. The Mongo URI registered as a **string** credential (id `hts-mongo-prod-uri`
   by default — override per job via `credentialId`).

---

## Consumer Jenkinsfile shape

Every consumer Jenkinsfile follows the same template:

```groovy
@Library('hts@main') _

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
  // credentialId   = 'hts-mongo-prod-uri'   // default — override only if needed
  // extraEnv       = [:]                     // extra env vars passed to mongosh
  // extraValidation = { cfg -> ... }         // optional consumer-side validation
}
```

> **First run of a new job has empty `params`.** That's expected: the first
> build registers the parameter inputs in Jenkins and fails validation. The
> second build picks up the populated params and runs normally.

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

| Env var          | Set by                                | Notes                                     |
| ---------------- | ------------------------------------- | ----------------------------------------- |
| `COMPANY_NAME`   | `htsSupportJob`                       | Tenant slug, validated by the pipeline.   |
| `APPLY`          | `htsSupportJob`                       | Literal string `'true'` or `'false'`.     |
| `REASON`         | `htsSupportJob`                       | The audit reason from the operator.       |
| `OPERATOR`       | `htsSupportJob`                       | Jenkins `BUILD_USER` (dry-run) or the input-step submitter (apply). |
| `BUILD_URL`      | Jenkins                               | Provided by Jenkins for free.             |
| (anything in `extraEnv`) | consumer Jenkinsfile          | E.g. `EMPLOYEE_EMAIL`. Names must match `[A-Z_][A-Z0-9_]*` and not collide with the reserved set above. |

### Exit codes

| Code | Meaning                                                |
| ---- | ------------------------------------------------------ |
| `0`  | Success — the script ran one of its happy paths (noop, dry-run, applied). |
| `≠0` | Fatal error. The script SHOULD also print a line containing `FATAL`; the pipeline aborts in either case (`set -eo pipefail`). |

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
| `applied`  | `APPLY=true` and the write succeeded. Recorded as `applied:` in the build description. |
| `failed`   | `APPLY=true` but the post-write verify failed. Use exit code `≠0` to abort the pipeline. |

The pipeline scans the captured log for the configured markers (`noopMarker`,
`dryRunMarker`, `appliedMarker`, `fatalMarker`) and routes accordingly.

### Idempotency

Re-running the same pipeline against the same `companyName` MUST be safe:

* If the tenant is already in the desired state, the script prints
  `"status":"noop"` and exits 0. The pipeline records `noop: …` in the build
  description and skips the apply stage.
* The pipeline declares `disableConcurrentBuilds()` to serialize runs of the
  same job, so two operators clicking "Apply" within seconds of each other
  cannot race a double-write.

### Auditing

The `.mongosh.js` writes to `hourtimesheet.auditEventLog` itself (it knows the
correct `eventType`). The pipeline additionally archives a stamped audit-log
artifact (`/tmp/<ticketId>-audit.log`) recording the build URL, submitter, and
status — so you can reconstruct the full history from Jenkins alone if the
Mongo write fails.

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

---

## Step API

```groovy
htsSupportJob {
  // --- Required ---
  ticketId         = 'HK-2204'                     // [A-Z0-9][A-Z0-9-]{2,31}; used in log file names & build description
  companyName      = params.companyName            // tenant slug; [a-z0-9][a-z0-9-]{1,62}
  reason           = params.reason                 // ≥ 8 chars; recorded in audit log
  mongoScriptFile  = 'hk-2204-add-gep.mongosh.js'  // relative path inside $WORKSPACE; no '..' or absolute paths

  // --- Optional ---
  APPLY            = params.APPLY                  // boolean, default false (dry-run)
  credentialId     = 'hts-mongo-prod-uri'          // Jenkins string credential holding the Mongo URI
  extraEnv         = [:]                           // additional env vars; keys must match [A-Z_][A-Z0-9_]*
                                                   //   and not collide with reserved names
                                                   //   (COMPANY_NAME, APPLY, REASON, OPERATOR, BUILD_URL, MONGO_URI)
  extraValidation  = { cfg -> /* ... */ }          // optional closure; throw to fail validation

  // --- Result-detection markers (override only if your .mongosh.js uses different ones) ---
  noopMarker       = '"status":"noop"'
  dryRunMarker     = '"status":"dry-run"'
  appliedMarker    = '"status":"applied"'
  fatalMarker      = 'FATAL'
}
```

### Stages

1. **Validate input** — required-fields check, slug regex, reason length,
   `extraValidation`, plus pre-flight checks for the workspace, the script
   file, and `mongosh` on `PATH`.
2. **Dry-run preview** — runs `.mongosh.js` with `APPLY=false`. Aborts on
   `fatalMarker`. Sets `currentBuild.description = 'noop: …'` on `noopMarker`,
   in which case the apply stage is skipped.
3. **Confirm** — only when `APPLY=true` and the dry-run was not a no-op. Pauses
   the pipeline for an interactive `input` step with a mandatory
   `I_HAVE_REVIEWED_THE_PREVIEW` checkbox; captures the submitter for the
   audit trail.
4. **Apply** — only when `APPLY=true` and not a no-op. Runs `.mongosh.js` again
   with `APPLY=true` and `OPERATOR=<submitter>`. Aborts if `appliedMarker` is
   not in the log.
5. **Audit** — writes a stamped artifact line (build URL, submitter, status,
   reason) to `/tmp/<ticketId>-audit.log` regardless of outcome.

`post.always` archives `/tmp/<ticketId>-*.log` so preview / apply / audit logs
are attached to every build.

---

## Security notes

* The Mongo URI is loaded via `withCredentials([string(...)])`, which masks it
  in the Jenkins console output. The pipeline never echoes `$MONGO_URI`.
* Inside the `sh` block the URI is passed to `mongosh` as a double-quoted
  argument — shell-special characters in the URI (semicolons, ampersands,
  dollar signs, spaces) cannot break the command.
* `set -eo pipefail` and `umask 077` are set on every `sh` block; the
  preview/apply log files are world-unreadable on the agent.
* `mongoScriptFile` is constrained to a relative path inside `$WORKSPACE`
  (no `..`, no absolute paths) to prevent a misconfigured consumer from
  pointing the step at an arbitrary file on the agent.

---

## Manual test plan

(For when you're rolling the library out for the first time, before there's a
green `./gradlew test` run to lean on.)

1. Register the library in Jenkins as above.
2. Create a Jenkins Pipeline job pointing at a feature branch of
   `hts-company-onboarding-pipeline-script` containing the consumer
   `Jenkinsfile` + `hk-2204-add-gep.mongosh.js`.
3. **Run 1 — empty params**: build with default values. Expect: validation
   error, parameter inputs registered, build fails red.
4. **Run 2 — dry-run on a tenant that already has the toggle**: companyName
   set, APPLY unchecked, reason filled. Expect: preview log shows
   `"status":"noop"`, build description `noop: HK-2204 for <tenant>`, build
   green, no apply stage runs.
5. **Run 3 — dry-run on a tenant that does NOT have the toggle**: APPLY still
   unchecked. Expect: preview log shows `"status":"dry-run"`, build green, no
   apply stage runs.
6. **Run 4 — apply**: same params as Run 3 but APPLY checked. Expect: pipeline
   pauses on input, operator confirms, apply log shows `"status":"applied"`,
   build description `applied: …`, audit-log artifact archived, Mongo
   `auditEventLog` collection has a new entry.
7. **Run 5 — re-apply (idempotency)**: rerun Run 4 unchanged. Expect:
   `"status":"noop"` in the preview, apply stage skipped.

---

## Local development

```bash
./gradlew test            # run Spock + JenkinsPipelineUnit suites
./gradlew check           # test + lint + assemble
```

The unit suite uses [Spock](https://spockframework.org/) and exercises
`SupportJobConfig` exhaustively (regex edges, reserved-key collisions, multi-
error accumulation). Pipeline-shape behaviour — the declarative
`pipeline { ... }`, `withCredentials`, `input`, `archiveArtifacts` — is
verified manually in Jenkins (see *Manual test plan* above) because
[JenkinsPipelineUnit](https://github.com/jenkinsci/JenkinsPipelineUnit) cannot
fully evaluate a declarative pipeline today and a half-mocked test would lock
us into JPU's particular shape. We'll add JPU coverage when JPU's declarative
support matures or when we factor more logic out of the declarative block into
plain Groovy methods.

---

## Adding a new ticket

For each subsequent HK-22xx ticket:

1. Add `<ticket>.mongosh.js` to `hts-company-onboarding-pipeline-script`
   following the contract above.
2. Add `<ticket>.groovy` (the Jenkinsfile) — copy the example above and change
   only `ticketId`, `mongoScriptFile`, the parameter help text, and any
   `extraEnv` / `extraValidation`.
3. Register the Pipeline job in Jenkins UI pointing at the new file.
4. Run the *Manual test plan* steps 3–7.

That's it. No copy-paste of the five-stage flow.
