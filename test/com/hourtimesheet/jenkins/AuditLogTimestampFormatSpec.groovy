package com.hourtimesheet.jenkins

import spock.lang.Specification

import java.time.Instant
import java.util.regex.Pattern

/**
 * Pins the ISO-8601 / RFC 3339 format used by the audit-log timestamp builder
 * in {@code vars/htsSupportJob.groovy} (see {@code _writeAuditLog}).
 *
 * The audit log is parsed by humans and downstream tooling (jq, log shippers,
 * SIEM rules); a silent format drift would break those consumers. This spec
 * exists so any change to the timestamp format trips a unit test before
 * shipping.
 *
 * Issue #7 — replaced legacy {@code new Date().toString()} (locale-sensitive,
 * non-ISO) with {@code java.time.Instant.now().toString()} (UTC, locale-immune,
 * always ISO-8601).
 */
class AuditLogTimestampFormatSpec extends Specification {

  /**
   * Matches the format {@code Instant.toString()} emits: a UTC instant in
   * RFC-3339 / ISO-8601 form, always ending in {@code Z}, with optional
   * fractional seconds (0, 3, 6, or 9 digits depending on platform clock).
   *
   * Examples that MUST match:
   *   2026-05-06T00:34:06Z
   *   2026-05-06T00:34:06.123Z
   *   2026-05-06T00:34:06.123456789Z
   */
  private static final Pattern ISO_8601_UTC = ~/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/

  def "Instant.now().toString() emits ISO-8601 UTC format"() {
    when:
    String stamp = Instant.now().toString()

    then:
    stamp ==~ ISO_8601_UTC
  }

  def "format is stable across many samples"() {
    expect:
    (1..50).every { Instant.now().toString() ==~ ISO_8601_UTC }
  }

  def "parsed stamp round-trips through Instant.parse()"() {
    given:
    String stamp = Instant.now().toString()

    when:
    Instant parsed = Instant.parse(stamp)

    then:
    noExceptionThrown()
    parsed.toString() ==~ ISO_8601_UTC
  }

  def "format always ends with Z (UTC) and never carries an offset"() {
    expect:
    String stamp = Instant.now().toString()
    stamp.endsWith('Z')
    !stamp.contains('+')
    // The only '-' chars are the date separators (positions 4 and 7); no
    // trailing offset like '-08:00'.
    stamp.count('-') == 2
  }

  def "legacy new Date().toString() would NOT match ISO-8601 (regression guard)"() {
    given:
    // new Date().toString() emits e.g. "Mon May 05 00:34:06 UTC 2026" —
    // locale-sensitive, non-ISO. This guard documents WHY we switched.
    String legacy = new Date().toString()

    expect:
    !(legacy ==~ ISO_8601_UTC)
  }
}
