package com.hourtimesheet.jenkins

/**
 * Typed configuration for {@code htsSupportJob}.
 *
 * Populated by binding the consumer's DSL closure with
 * {@code resolveStrategy = DELEGATE_ONLY} and {@code delegate = new SupportJobConfig()}.
 * Validation happens once via {@link #validate()} so the failure message is single-shot
 * and lists every problem rather than dribbling errors out one at a time.
 *
 * Field names are intentionally identical to the DSL keys; do not rename without
 * coordinating with consumer pipelines.
 */
class SupportJobConfig implements Serializable {

  private static final long serialVersionUID = 1L

  /**
   * Issue #8: schema version for the JSON-line audit-log artifact written under
   * {@code .htsSupportJob/${BUILD_NUMBER}/${ticketId}-audit.log}.
   *
   * Every JSONL row carries this value as {@code schema_version} so downstream
   * consumers (log shippers, SIEM rules, future ingestion jobs, forensic
   * tooling) can detect schema changes and adapt — or refuse to ingest unknown
   * major versions.
   *
   * <h3>Migration policy</h3>
   * Bump this constant when, AND ONLY when, you make a backwards-incompatible
   * change to the audit-row shape:
   * <ul>
   *   <li>a required field is renamed or removed,</li>
   *   <li>a required field's type changes (string -> int, scalar -> object),</li>
   *   <li>the meaning of an existing field changes in a way consumers must
   *       handle (e.g. enum value semantics, units).</li>
   * </ul>
   * Adding a new <i>optional</i> field is a non-breaking change — keep the
   * version, document the field as optional in README. Adding a new <i>required</i>
   * field IS a breaking change for consumers that filter on field presence;
   * bump the version.
   *
   * Consumers SHOULD reject unknown major versions and SHOULD log a warning on
   * any unknown minor; we keep this an integer (not a semver triple) because
   * the audit log has only ever had a "major" contract.
   */
  static final int AUDIT_SCHEMA_VERSION = 1

  /**
   * Tenant-slug regex.
   *
   * Source-of-truth: tenant slugs are stored in {@code masterConfiguration._id}
   * and are used as both the Mongo lookup key and the URL subdomain. The shape
   * we accept must match what's in Mongo today AND what new onboardings produce.
   *
   * Rules:
   *   - lowercase letters, digits, single hyphens
   *   - 1-63 chars total (DNS-label cap; subdomains cannot exceed this)
   *   - must START with [a-z0-9] (no leading hyphen)
   *   - must END with [a-z0-9] (no trailing hyphen) — for slugs >= 2 chars
   *   - one-character slugs are allowed (a single [a-z0-9])
   *
   * Explicitly REJECTED (audit finding H10):
   *   - 'acme-'  (trailing hyphen — not a valid DNS label)
   *   - 'a--b'   (consecutive hyphens — defensive; we have not seen any in prod)
   *   - 'acme_x' (underscore — not valid in DNS labels)
   *
   * If a real-world tenant ever needs a shape this rejects, update this regex
   * AND the Spock tests pinning its behaviour in lockstep. We have not been
   * able to verify against an enumerated tenant list at the time this was
   * tightened (2026-05-05); pre-existing behaviour was strictly looser, so the
   * tighter form is conservative-vs-old-self.
   */
  static final java.util.regex.Pattern COMPANY_SLUG =
      ~/[a-z0-9](?:-?[a-z0-9]){0,62}/

  /** Minimum reason length. Short reasons defeat the audit log. */
  static final int MIN_REASON_LENGTH = 8

  /** Maximum reason length. Caps unbounded growth in logs / audit rows. */
  static final int MAX_REASON_LENGTH = 500

  /**
   * Env-var keys reserved by {@code htsSupportJob}: a consumer-supplied
   * {@code extraEnv} entry with one of these keys is rejected by
   * {@link #validate()}. The set covers (a) names the library sets itself,
   * (b) names whose meaning the surrounding shell relies on, and
   * (c) names the agent's loader honours (LD_*) — preventing a misconfigured
   * consumer from shadowing PATH to subvert mongosh resolution or shadowing
   * HTS_SUPPORT_OUT_LOG to write outside the workspace.
   */
  static final Set<String> RESERVED_EXTRA_ENV_KEYS = [
      // Set by the library itself
      'COMPANY_NAME', 'APPLY', 'REASON', 'OPERATOR', 'MONGO_URI',
      'HTS_SUPPORT_SCRIPT', 'HTS_SUPPORT_OUT_LOG', 'HTS_CORRELATION_ID',
      // Set by Jenkins (consumer overriding these would break the audit trail)
      'BUILD_URL', 'BUILD_USER', 'WORKSPACE',
      // Shell / loader keys whose hijack would subvert mongosh resolution or library ops
      'PATH', 'HOME', 'LD_LIBRARY_PATH', 'LD_PRELOAD',
  ].toSet().asImmutable()

  // --- Required ---
  String ticketId
  String companyName
  String reason
  String mongoScriptFile

  // --- Optional with defaults ---
  boolean APPLY                = false
  String  credentialId         = 'hts-mongo-prod-uri'
  Map<String, String> extraEnv = [:]

  // --- Operator-confirmation hardening (audit findings H3, M1) ---
  /**
   * If non-null, restrict the apply-stage {@code input} step's submitter to a
   * Jenkins group/user (Jenkins's {@code submitter:} field accepts a comma-
   * separated list of users/groups). Set to e.g. {@code 'hts-oncall'} to require
   * the approver be a member of that authorization group, distinct from the
   * person who started the build.
   */
  String approverGroup = null

  /**
   * If true, require two distinct submitters: a first {@code input} step
   * captures submitter A, then a second {@code input} step captures submitter
   * B and rejects when {@code A == B}. Off by default — opt in for high-risk
   * tickets (mass updates, deletes, financial-impact writes).
   */
  boolean requireDualApproval = false

  /**
   * If true, the apply confirmation prompts the operator to retype the tenant
   * slug; the apply stage aborts unless the typed value matches
   * {@link #companyNameTrimmed}. Defaults to {@code true} — the additional
   * friction is the cheapest defence against "applied to the wrong tenant".
   */
  boolean confirmTenantName = true

  /**
   * If true, the pre-flight stage will accept pre-release {@code mongosh}
   * builds (e.g. {@code 2.5.0-rc.1}, {@code 2.5.0-beta}, {@code 2.5.0-alpha.2}).
   * Defaults to {@code false} — operational support jobs should run against
   * GA shells only; release-candidate driver semantics may differ from GA in
   * ways that surprise an .mongosh.js (issue #13).
   *
   * Opt in only when knowingly testing a candidate build on a non-prod tenant.
   */
  boolean allowMongoshPrerelease = false

  // --- Decommission / retirement (issue #10) ---
  /**
   * Issue #10: deprecation banner.
   *
   * When {@code true}, {@code htsSupportJob} emits a WARN log line at pipeline
   * start (after Bootstrap) so every operator sees the upcoming retirement
   * before paging through the dry-run output. Defaults to {@code false} —
   * deprecation is opt-in per consumer Jenkinsfile.
   *
   * Pair with {@link #deprecatedMessage} for a per-ticket migration pointer
   * (e.g. "HK-22xx jobs are migrating to the platform-tools UI on 2026-08-01").
   *
   * The flag is informational ONLY — it does NOT block runs. Use
   * {@link #freezeAfter} to actually refuse runs after the freeze date.
   */
  boolean deprecated = false

  /**
   * Issue #10: human-readable migration pointer printed alongside the
   * deprecation banner. When null and {@link #deprecated} is true, a generic
   * fallback ("This pipeline is deprecated.") is emitted. Set this in every
   * deprecated consumer Jenkinsfile so operators have a one-line summary of
   * the migration target / runbook link / retirement date.
   *
   * Validation rejects control characters (defence against log-injection;
   * mirrors the {@link #reason} contract).
   */
  String deprecatedMessage = null

  /**
   * Issue #10: hard freeze date in {@code YYYY-MM-DD} (ISO 8601) form.
   *
   * When set, the {@code Apply} stage refuses to run on or after this date —
   * the {@code input} step still appears (so the operator gets a clear error
   * message rather than a confusing missing-stage), but submitting it throws
   * with a freeze message. The dry-run / noop path remains available unless
   * {@link #freezeBlocksNoop} is also set.
   *
   * Comparison uses {@code LocalDate.now(ZoneOffset.UTC)} to avoid Jenkins-
   * agent-timezone surprises (the same UTC date is observed by every agent
   * regardless of its TZ).
   *
   * Defaults to {@code null} (no freeze).
   */
  String freezeAfter = null

  /**
   * Issue #10: when {@code true} and {@link #freezeAfter} has elapsed, ALSO
   * block the dry-run / noop path so a retired pipeline can be reduced to
   * a hard error before the consumer Jenkinsfile is removed entirely.
   *
   * Defaults to {@code false} — the noop path stays available after freeze
   * for last-look forensics (e.g. "did this tenant ever have the toggle?").
   */
  boolean freezeBlocksNoop = false

  /**
   * Issue #17: post-apply audit-row probe.
   *
   * After the {@code .mongosh.js} returns {@code RESULT_JSON status=applied},
   * the Apply stage runs a tiny mongosh probe that asserts EXACTLY ONE
   * {@code auditEventLog} row exists with the run's {@code correlationId}.
   * !=1 fails the build.
   *
   * Defaults to {@code false} — the probe ALWAYS runs by default, enforcing
   * the audit-row contract that was previously documentation-only.
   *
   * Set to {@code true} ONLY as an emergency override during a Mongo outage
   * where the apply itself committed but the read-side probe cannot complete
   * (e.g. primary unreachable for reads after a write-and-failover). The
   * canonical audit trail still lives in Mongo's {@code auditEventLog}; this
   * flag only suppresses the post-apply verification, not the consumer
   * mongosh.js's audit-row write.
   */
  boolean skipPostApplyProbe = false

  // --- Notification hooks (audit finding M9) ---
  /**
   * Optional Jenkins-pipeline closure invoked from {@code post.success} after
   * an apply that wrote to Mongo. Receives a single {@code Map} argument with
   * keys {@code ticketId}, {@code companyName}, {@code submitter}, {@code reason},
   * {@code correlationId}, {@code buildUrl}. The library does NOT ship a default
   * Slack/PD integration; consumers wire their own webhook here.
   */
  Closure onApply = null

  /**
   * Optional Jenkins-pipeline closure invoked from {@code post.failure}.
   * Receives the same {@code Map} as {@link #onApply}, plus {@code status}
   * (one of {@code 'aborted'}, {@code 'failed'}).
   */
  Closure onFailure = null

  // --- Result-detection markers (override per ticket if your .mongosh.js uses different ones) ---
  String noopMarker    = '"status":"noop"'
  String dryRunMarker  = '"status":"dry-run"'
  String appliedMarker = '"status":"applied"'
  String fatalMarker   = 'FATAL'

  /**
   * @return null when configuration is valid; otherwise a human-readable, multi-line
   *         error message enumerating every problem found.
   */
  String validate() {
    def errors = []

    if (!ticketId?.trim()) {
      errors << "ticketId is required (e.g. 'HK-2204')"
    } else if (!(ticketId ==~ /^[A-Z]{2,6}-[0-9]{2,6}$/)) {
      // Tightened from `[A-Z0-9][A-Z0-9-]{2,31}` (audit finding L3, issue #4).
      // Old pattern accepted shapes like `A1-B2-C3`, `12-345-678`, `H-K-2-2-0-4`.
      // New pattern enforces the canonical Jira key shape used across our pipelines:
      //   - 2-6 uppercase letters, single hyphen, 2-6 digits; full-string match.
      //   - Examples accepted: 'HK-2204', 'SUPP-123456', 'AB-12'.
      //   - Examples rejected: 'hk-2204' (case), 'H-2204' (1 letter), 'HK2204' (no hyphen).
      errors << "ticketId '${ticketId}' must match [A-Z]{2,6}-[0-9]{2,6} (e.g. 'HK-2204')"
    }

    if (!companyName?.trim()) {
      errors << "companyName is required"
    } else if (!(companyName ==~ COMPANY_SLUG)) {
      errors << "companyName '${companyName}' is not a valid tenant slug (must match ${COMPANY_SLUG.pattern()})"
    } else if (companyName.length() > 63) {
      errors << "companyName '${companyName}' exceeds 63 chars (DNS label limit)"
    }

    if (!reason?.trim()) {
      errors << "reason is required"
    } else {
      def trimmedReason = reason.trim()
      if (trimmedReason.size() < MIN_REASON_LENGTH) {
        errors << "reason must be at least ${MIN_REASON_LENGTH} characters (audit log)"
      }
      if (trimmedReason.size() > MAX_REASON_LENGTH) {
        errors << "reason must be at most ${MAX_REASON_LENGTH} characters (audit log)"
      }
      if (_containsControlChars(trimmedReason)) {
        errors << "reason must not contain control characters or newlines"
      }
    }

    if (!mongoScriptFile?.trim()) {
      errors << "mongoScriptFile is required (path relative to \$WORKSPACE)"
    } else if (mongoScriptFile.contains('..') ||
               mongoScriptFile.startsWith('/') ||
               mongoScriptFile.contains('\\') ||
               mongoScriptFile.contains(' ') ||
               mongoScriptFile.contains('\u0000')) {
      // path traversal would let a consumer escape $WORKSPACE; keep the contract
      // tight so the .mongosh.js always lives alongside the Jenkinsfile. The
      // backslash check defends against Windows-style separators on agents that
      // accept them, and NUL chars defeat any length-based path parser.
      errors << "mongoScriptFile '${mongoScriptFile}' must be a relative path inside \$WORKSPACE (no '..', no leading '/', no backslashes, no NUL)"
    }

    if (!credentialId?.trim()) {
      errors << "credentialId is required (default 'hts-mongo-prod-uri' was overridden to blank)"
    }

    if (extraEnv == null) {
      errors << "extraEnv must not be null (use [:] for none)"
    } else {
      extraEnv.each { k, v ->
        if (!(k ==~ /[A-Z_][A-Z0-9_]*/)) {
          errors << "extraEnv key '${k}' must match [A-Z_][A-Z0-9_]* (POSIX env-var convention)"
        }
        if (RESERVED_EXTRA_ENV_KEYS.contains(k)) {
          errors << "extraEnv key '${k}' is reserved by htsSupportJob and cannot be overridden"
        }
        if (v == null) {
          errors << "extraEnv value for key '${k}' must not be null"
        } else if (_containsControlChars(v.toString())) {
          errors << "extraEnv value for key '${k}' must not contain newlines, NUL, or other control characters"
        }
      }
    }

    if (!noopMarker)    errors << "noopMarker must not be empty"
    if (!dryRunMarker)  errors << "dryRunMarker must not be empty"
    if (!appliedMarker) errors << "appliedMarker must not be empty"
    if (!fatalMarker)   errors << "fatalMarker must not be empty"

    // Issue #10: decommission story validation.
    //
    // freezeAfter is the load-bearing field — bad input here produces a
    // confusing pipeline error at run time when an operator triggers Apply.
    // Catch the bad date string at config-construction time so the misconfig
    // is surfaced on the very first build (validation runs before the lock).
    if (freezeAfter != null) {
      def trimmedFreeze = freezeAfter.toString().trim()
      if (!trimmedFreeze) {
        errors << "freezeAfter, when set, must be a non-blank ISO date 'YYYY-MM-DD' (got blank)"
      } else if (!(trimmedFreeze ==~ /\d{4}-\d{2}-\d{2}/)) {
        errors << "freezeAfter '${freezeAfter}' must be an ISO date 'YYYY-MM-DD' (e.g. '2026-08-01')"
      } else {
        // Strict parse — catches '2026-13-01', '2026-02-30', etc. that the
        // shape regex would otherwise accept.
        try {
          java.time.LocalDate.parse(trimmedFreeze)
        } catch (java.time.format.DateTimeParseException ex) {
          errors << "freezeAfter '${freezeAfter}' is not a valid calendar date: ${ex.message}"
        }
      }
    }
    // freezeBlocksNoop=true with no freezeAfter is a footgun — the flag silently
    // does nothing, masking a retirement misconfiguration. Reject explicitly.
    if (freezeBlocksNoop && freezeAfter == null) {
      errors << "freezeBlocksNoop=true requires freezeAfter to be set (issue #10: noop-block has no effect without a freeze date)"
    }
    // deprecatedMessage with control chars would log-inject (mirrors reason
    // contract); be just as strict here so the deprecation banner is safe to
    // print verbatim.
    if (deprecatedMessage != null && _containsControlChars(deprecatedMessage.toString())) {
      errors << "deprecatedMessage must not contain control characters or newlines"
    }

    // Issue #15: dual-approval defense in depth. submitterParameter may return
    // a display name rather than a userId; two accounts sharing a display name
    // would defeat the dual-approval string compare. The runtime fix in
    // htsSupportJob.groovy normalizes both submitters via the SecurityRealm,
    // but realm lookups can fall back to raw strings in degraded conditions.
    // approverGroup constrains the submitter to a known group at the input
    // step, narrowing realm semantics so the fallback path remains safe.
    // Reject the misconfiguration at config-construction time rather than
    // letting an operator deploy a job whose dual-approval is silently weaker
    // than intended.
    if (requireDualApproval && !approverGroup?.trim()) {
      errors << "approverGroup MUST be set when requireDualApproval=true (issue #15: prevents userId/displayName collisions on the dual-approval gate)"
    }

    return errors.isEmpty() ? null : "htsSupportJob configuration invalid:\n  - " + errors.join("\n  - ")
  }

  /** Trimmed company name for use after {@link #validate()} has passed. */
  String getCompanyNameTrimmed() { companyName?.trim() }

  /** Trimmed reason for use after {@link #validate()} has passed. */
  String getReasonTrimmed() { reason?.trim() }

  /**
   * @return true if {@code s} contains any character below 0x20 (control chars,
   *         including newlines, tabs, NUL) or 0x7F (DEL). Such characters are
   *         a vector for log-injection attacks (smuggling fake audit lines)
   *         and shell-quoting surprises.
   */
  private static boolean _containsControlChars(String s) {
    if (s == null) return false
    for (int i = 0; i < s.length(); i++) {
      int c = s.charAt(i) as int
      if (c < 0x20 || c == 0x7F) return true
    }
    return false
  }
}
