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
