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

  /** Tenant-slug regex: lowercase, digits, hyphen; 2-63 chars; must start alphanumeric. */
  static final java.util.regex.Pattern COMPANY_SLUG = ~/[a-z0-9][a-z0-9-]{1,62}/

  /** Minimum reason length. Short reasons defeat the audit log. */
  static final int MIN_REASON_LENGTH = 8

  // --- Required ---
  String ticketId
  String companyName
  String reason
  String mongoScriptFile

  // --- Optional with defaults ---
  boolean APPLY                = false
  String  credentialId         = 'hts-mongo-prod-uri'
  Map<String, String> extraEnv = [:]
  Closure extraValidation      = null

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
    } else if (!(ticketId ==~ /[A-Z0-9][A-Z0-9-]{2,31}/)) {
      errors << "ticketId '${ticketId}' must match [A-Z0-9][A-Z0-9-]{2,31} (e.g. 'HK-2204')"
    }

    if (!companyName?.trim()) {
      errors << "companyName is required"
    } else if (!(companyName ==~ COMPANY_SLUG)) {
      errors << "companyName '${companyName}' is not a valid tenant slug (must match ${COMPANY_SLUG.pattern()})"
    }

    if (!reason?.trim()) {
      errors << "reason is required"
    } else if (reason.trim().size() < MIN_REASON_LENGTH) {
      errors << "reason must be at least ${MIN_REASON_LENGTH} characters (audit log)"
    }

    if (!mongoScriptFile?.trim()) {
      errors << "mongoScriptFile is required (path relative to \$WORKSPACE)"
    } else if (mongoScriptFile.contains('..') || mongoScriptFile.startsWith('/')) {
      // path traversal would let a consumer escape $WORKSPACE; keep the contract
      // tight so the .mongosh.js always lives alongside the Jenkinsfile.
      errors << "mongoScriptFile '${mongoScriptFile}' must be a relative path inside \$WORKSPACE"
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
        // Block names we set ourselves so consumer overrides can't silently mask them.
        if (k in ['COMPANY_NAME', 'APPLY', 'REASON', 'OPERATOR', 'BUILD_URL', 'MONGO_URI']) {
          errors << "extraEnv key '${k}' is reserved by htsSupportJob and cannot be overridden"
        }
      }
    }

    if (!noopMarker)    errors << "noopMarker must not be empty"
    if (!dryRunMarker)  errors << "dryRunMarker must not be empty"
    if (!appliedMarker) errors << "appliedMarker must not be empty"
    if (!fatalMarker)   errors << "fatalMarker must not be empty"

    return errors.isEmpty() ? null : "htsSupportJob configuration invalid:\n  - " + errors.join("\n  - ")
  }

  /** Trimmed company name for use after {@link #validate()} has passed. */
  String getCompanyNameTrimmed() { companyName?.trim() }

  /** Trimmed reason for use after {@link #validate()} has passed. */
  String getReasonTrimmed() { reason?.trim() }
}
