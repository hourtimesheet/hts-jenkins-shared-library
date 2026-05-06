package com.hourtimesheet.jenkins

import spock.lang.Specification

import java.util.regex.Pattern

/**
 * Pins the {@code schema_version} field on every JSONL line written by
 * {@code _writeAuditLog} in {@code vars/htsSupportJob.groovy}.
 *
 * Issue #8 — every audit row must carry a {@code schema_version} integer so
 * downstream consumers (log shippers, SIEM rules, future ingestion jobs,
 * forensic tooling) can detect schema changes and adapt — or refuse to ingest
 * unknown major versions.
 *
 * Three angles, mirroring {@link CorrelationIdSpec} and
 * {@link AuditLogTimestampFormatSpec}:
 *   1. <b>Constant</b>: assert {@link SupportJobConfig#AUDIT_SCHEMA_VERSION} is
 *      the integer value the contract promises.
 *   2. <b>Static source</b>: assert the audit-row builder in
 *      {@code vars/htsSupportJob.groovy} (a) declares {@code schema_version} as
 *      a row field, (b) sources its value from the
 *      {@code SupportJobConfig.AUDIT_SCHEMA_VERSION} constant (no magic literal),
 *      and (c) lists it as the FIRST field in the LinkedHashMap so it appears
 *      first in the emitted JSON.
 *   3. <b>Dynamic encoder</b>: reproduce the manual JSON encoder used by
 *      {@code _writeAuditLog} and assert the emitted line literally starts
 *      with the bytes {@code "schema_version":1} (preceded by a single
 *      open-brace) for a representative field set, and that the value parses
 *      back as integer-1 via a strict regex.
 */
class AuditSchemaVersionSpec extends Specification {

  /**
   * Matches a JSONL row that starts with {@code schema_version} as the first
   * key, value an integer (no quotes), followed by a comma. Anchors the
   * "FIRST KEY" contract so a future refactor that reorders the LinkedHashMap
   * trips this guard before consumers see the regression.
   */
  private static final Pattern FIRST_KEY_SCHEMA_VERSION =
      ~/^\{"schema_version":\d+,.*\}$/

  private String supportJobSource() {
    def f = new File('vars/htsSupportJob.groovy')
    assert f.exists() : "expected vars/htsSupportJob.groovy at ${f.absolutePath}"
    return f.text
  }

  // --------------------------------------------------------------------
  // 1. Constant
  // --------------------------------------------------------------------

  def "AUDIT_SCHEMA_VERSION is 1 (issue #8 initial contract)"() {
    expect:
    SupportJobConfig.AUDIT_SCHEMA_VERSION == 1
  }

  def "AUDIT_SCHEMA_VERSION is an int (not a String, not a semver triple)"() {
    expect:
    // Integer wrapper for the autoboxed int from the static-final field.
    SupportJobConfig.AUDIT_SCHEMA_VERSION instanceof Integer
  }

  def "AUDIT_SCHEMA_VERSION is positive (versioning convention)"() {
    expect:
    SupportJobConfig.AUDIT_SCHEMA_VERSION > 0
  }

  // --------------------------------------------------------------------
  // 2. Static source guards: catch an accidental revert without a Jenkins runtime.
  // --------------------------------------------------------------------

  def "_writeAuditLog declares schema_version as a row field (issue #8)"() {
    given:
    def src = supportJobSource()

    expect:
    src.contains('schema_version:')
  }

  def "_writeAuditLog sources schema_version from SupportJobConfig.AUDIT_SCHEMA_VERSION (no magic literal)"() {
    given:
    def src = supportJobSource()

    expect:
    // The row builder must reference the constant by name — refactors that
    // inline a literal '1' silently desync from any future bump.
    src.contains('SupportJobConfig.AUDIT_SCHEMA_VERSION')
  }

  def "schema_version is the FIRST field in the audit-row LinkedHashMap (issue #8: grep-at-column-0)"() {
    given:
    def src = supportJobSource()

    when:
    // Locate the audit-row map literal by anchoring on the well-known opener
    // 'def fields = [' and inspecting its first non-blank, non-comment line.
    def marker = 'def fields = ['
    def idx = src.indexOf(marker)
    assert idx >= 0 : "could not locate 'def fields = [' in vars/htsSupportJob.groovy"
    def afterOpener = src.substring(idx + marker.length())
    def firstField = null
    for (String line : afterOpener.split('\\r?\\n')) {
      def trimmed = line?.trim()
      if (!trimmed) continue
      // Skip block-comment lines that immediately follow the opener.
      if (trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*')) continue
      firstField = trimmed
      break
    }

    then:
    firstField != null
    firstField.startsWith('schema_version:')
  }

  def "JSON-encoder switch path renders integer values without quotes (issue #8 contract: schema_version is an int)"() {
    given:
    def src = supportJobSource()

    expect:
    // The manual encoder branches on Number to emit unquoted toString(); this
    // is the load-bearing detail that keeps schema_version as JSON int (1) and
    // not JSON string ("1"). If a future refactor breaks this branch, every
    // audit row silently quotes the version and consumers reject it.
    src.contains('if (v instanceof Number) return v.toString()')
  }

  // --------------------------------------------------------------------
  // 3. Dynamic encoder: replicate _writeAuditLog's row construction and
  //    assert the emitted JSONL line carries schema_version as expected.
  // --------------------------------------------------------------------

  /**
   * Replica of the manual JSON encoder in {@code vars/htsSupportJob.groovy}'s
   * {@code _jsonEncode}. Kept in sync with the source via the static guard
   * above ("JSON-encoder switch path renders integer values without quotes").
   */
  private static String jsonEncode(Object v) {
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
   * Replica of the row construction in {@code _writeAuditLog}, sourcing
   * {@code schema_version} from {@link SupportJobConfig#AUDIT_SCHEMA_VERSION}
   * exactly as the live code does.
   */
  private static String buildAuditRow(Map<String, Object> overrides = [:]) {
    def base = [
        schema_version: SupportJobConfig.AUDIT_SCHEMA_VERSION,
        ticketId:       'HK-2204',
        companyName:    'acme',
        status:         'applied',
        buildResult:    'SUCCESS',
        operator:       'jdoe',
        secondary:      '',
        correlationId:  'jenkins-hk-2204-42-deadbeef',
        buildUrl:       'https://jenkins.example.com/job/HK-2204/42/',
        buildNumber:    '42',
        reason:         'Sales SUPP-1234 customer purchased GEP',
        apply:          true,
        timestamp:      '2026-05-05T12:34:56Z',
    ]
    def fields = new LinkedHashMap<String, Object>(base)
    overrides.each { k, v -> fields[k] = v }
    return '{' + fields.collect { k, v -> "\"${k}\":${jsonEncode(v)}" }.join(',') + '}'
  }

  def "every emitted JSONL line starts with schema_version as the first key (issue #8)"() {
    when:
    def line = buildAuditRow()

    then:
    line ==~ FIRST_KEY_SCHEMA_VERSION
  }

  def "schema_version is encoded as a JSON integer, not a quoted string"() {
    when:
    def line = buildAuditRow()

    then:
    // Integer encoding: "schema_version":1, NOT "schema_version":"1".
    line.contains('"schema_version":1,')
    !line.contains('"schema_version":"1"')
  }

  def "schema_version value matches the SupportJobConfig.AUDIT_SCHEMA_VERSION constant"() {
    given:
    def line = buildAuditRow()

    when:
    // Pull the schema_version int out of the JSONL line via a tiny regex.
    def m = (line =~ /"schema_version":(\d+)/)
    assert m.find()
    def parsed = Integer.parseInt(m.group(1))

    then:
    parsed == SupportJobConfig.AUDIT_SCHEMA_VERSION
  }

  def "every status variant (applied, noop, dry-run, aborted, failed) carries schema_version"() {
    expect:
    ['applied', 'noop', 'dry-run', 'aborted', 'failed'].each { status ->
      def line = buildAuditRow(status: status)
      assert line.contains("\"schema_version\":${SupportJobConfig.AUDIT_SCHEMA_VERSION},") :
          "status=${status} missing schema_version: ${line}"
      assert line.contains("\"status\":\"${status}\"") :
          "status=${status} not encoded: ${line}"
    }
  }

  def "regression guard: removing schema_version from the row builder fails this spec"() {
    when:
    // Simulate the regression: a future refactor drops schema_version from the
    // map. The dynamic encoder reproduces the row construction, so we can
    // assert the contract holds independently of source-grep guards.
    def fields = new LinkedHashMap<String, Object>([
        ticketId:    'HK-2204',
        companyName: 'acme',
        status:      'applied',
    ])
    def line = '{' + fields.collect { k, v -> "\"${k}\":${jsonEncode(v)}" }.join(',') + '}'

    then:
    // The regressed form does NOT match the FIRST_KEY anchor — proves the
    // regex actually fails when schema_version is absent. (Belt-and-braces:
    // the static source guard would also catch this in CI.)
    !(line ==~ FIRST_KEY_SCHEMA_VERSION)
  }

  def "no key is emitted before schema_version in the row (issue #8: column-0 grep contract)"() {
    when:
    def line = buildAuditRow()

    then:
    // The first character is '{', the next characters are '"schema_version"'.
    line.startsWith('{"schema_version":')
  }
}
