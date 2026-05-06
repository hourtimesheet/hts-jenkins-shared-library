package com.hourtimesheet.jenkins

import spock.lang.Specification
import spock.lang.Unroll

import java.util.regex.Pattern

/**
 * Pins the mongosh version-parser hardening in {@code vars/htsSupportJob.groovy}
 * (pre-flight stage). Issue #13.
 *
 * Old form: {@code (mongoshVersion =~ /(\d+)\.(\d+)(?:\.(\d+))?/).find()}
 * matched anywhere in any line of stdout, producing false-positives like
 * {@code library v1.2.3 built on FOO 9.8} → '1.2' or
 * {@code Connection refused at 127.0.0.1} → '127.0'.
 *
 * New form:
 * <ul>
 *   <li>Iterates lines, skips blanks/comments.</li>
 *   <li>Strips an optional leading {@code mongosh } prefix.</li>
 *   <li>Matches a strict full-line semver triple with optional pre-release
 *       tag: {@code ^v?(\d+)\.(\d+)\.(\d+)(?:-(rc|beta|alpha)\S*)?$}.</li>
 *   <li>Rejects pre-release tags unless {@code cfg.allowMongoshPrerelease=true}.</li>
 *   <li>Logs the raw matched line for forensic clarity.</li>
 * </ul>
 *
 * Two angles, mirroring {@link CorrelationIdSpec}:
 *   1. <b>Static</b>: assert the source contains the new helper + behaviour.
 *   2. <b>Dynamic</b>: replay the parser regex/logic against representative
 *      mongosh output samples to assert the contract.
 */
class MongoshVersionParserSpec extends Specification {

  /**
   * Mirrors the production pattern exactly. If this drifts, the static guard
   * below catches it; if the production source drifts, this spec catches it.
   */
  private static final Pattern VERSION_LINE =
      ~/^v?(\d+)\.(\d+)\.(\d+)(?:-(rc|beta|alpha)(?:\S*)?)?$/

  private String supportJobSource() {
    def f = new File('vars/htsSupportJob.groovy')
    assert f.exists() : "expected vars/htsSupportJob.groovy at ${f.absolutePath}"
    return f.text
  }

  /**
   * Local replay of the production parser. Kept in lockstep with
   * {@code _parseMongoshVersionLines} in vars/htsSupportJob.groovy. The static
   * guards below pin the production source to this same shape.
   */
  private static Map parse(String raw) {
    if (raw == null) return null
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
      def m = candidate =~ VERSION_LINE
      if (m.matches()) {
        return [
          rawLine:    trimmed,
          major:      m.group(1) as int,
          minor:      m.group(2) as int,
          patch:      m.group(3) as int,
          prerelease: m.group(4),
        ]
      }
    }
    return null
  }

  // --------------------------------------------------------------------
  // Static guards — catch source drift / accidental revert without Jenkins.
  // --------------------------------------------------------------------

  def "vars/htsSupportJob.groovy retains the new parser helper (issue #13)"() {
    given:
    def src = supportJobSource()

    expect: 'the helper is defined'
    src.contains('private Map _parseMongoshVersionLines(String raw)')
    and: 'the strict full-line semver pattern is the one in use'
    src.contains('^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(rc|beta|alpha)(?:\\S*)?)?$')
  }

  def "the old permissive regex is gone (issue #13)"() {
    given:
    def src = supportJobSource()

    expect: 'the unanchored two-group form is no longer used at the version site'
    !src.contains('mongoshVersion =~ /(\\d+)\\.(\\d+)(?:\\.(\\d+))?/')
  }

  def "the parser emits the raw line for forensic clarity (issue #13)"() {
    given:
    def src = supportJobSource()

    expect: 'the raw line is logged before any version-policy decision'
    src.contains('log.info("mongosh raw:')
  }

  def "pre-release rejection consults cfg.allowMongoshPrerelease (issue #13)"() {
    given:
    def src = supportJobSource()

    expect:
    src.contains('cfg.allowMongoshPrerelease')
    src.contains('is a pre-release build')
  }

  // --------------------------------------------------------------------
  // Dynamic guards — replay the parser logic against real-shaped samples.
  // --------------------------------------------------------------------

  def "GA version 'mongosh 2.5.0' parses to major=2 minor=5 patch=0, no prerelease"() {
    when:
    def out = parse('mongosh 2.5.0')

    then:
    out != null
    out.major == 2
    out.minor == 5
    out.patch == 0
    out.prerelease == null
    out.rawLine == 'mongosh 2.5.0'
  }

  def "GA version '2.5.0' (bare, no mongosh prefix) still parses"() {
    when:
    def out = parse('2.5.0')

    then:
    out != null
    out.major == 2
    out.minor == 5
    out.patch == 0
    out.prerelease == null
  }

  def "GA version with v-prefix 'v1.10.6' parses"() {
    when:
    def out = parse('v1.10.6')

    then:
    out != null
    out.major == 1
    out.minor == 10
    out.patch == 6
    out.prerelease == null
  }

  @Unroll
  def "pre-release tag '#raw' is parsed and FLAGGED with prerelease='#expected'"(String raw, String expected) {
    when:
    def out = parse(raw)

    then:
    out != null
    out.prerelease == expected

    where:
    raw                     | expected
    'mongosh 2.5.0-rc.1'    | 'rc'
    'mongosh 2.5.0-rc1'     | 'rc'
    'mongosh 2.5.0-beta'    | 'beta'
    'mongosh 2.5.0-beta.3'  | 'beta'
    'mongosh 2.5.0-alpha.2' | 'alpha'
    '2.5.0-rc.1'            | 'rc'
  }

  def "garbage line 'library v1.2.3 built on FOO 9.8' does NOT match (issue #13 false-positive)"() {
    when:
    def out = parse('library v1.2.3 built on FOO 9.8')

    then: 'no false-positive match — old regex would have extracted 1.2'
    out == null
  }

  def "garbage line 'Connection refused at 127.0.0.1' does NOT match (issue #13 false-positive)"() {
    when:
    def out = parse('Connection refused at 127.0.0.1')

    then: 'no false-positive — old regex would have extracted 127.0'
    out == null
  }

  def "empty output returns null (caller raises clear error)"() {
    expect:
    parse('') == null
    parse('   ') == null
    parse('\n\n\n') == null
  }

  def "null input returns null"() {
    expect:
    parse(null) == null
  }

  def "blank/comment lines are skipped, real version on a later line wins"() {
    when:
    def out = parse('\n# build banner\n\nmongosh 2.3.4\n')

    then:
    out != null
    out.major == 2
    out.minor == 3
    out.patch == 4
    out.rawLine == 'mongosh 2.3.4'
  }

  def "multi-line output picks the FIRST matching line; trailing junk ignored"() {
    given:
    def raw = [
      'mongosh 1.10.6',
      'Using MongoDB:          7.0.5',
      'Using Mongosh:          1.10.6',
    ].join('\n')

    when:
    def out = parse(raw)

    then:
    out.major == 1
    out.minor == 10
  }

  def "lines with embedded version digits but extra trailing tokens do NOT match (anti-permissive)"() {
    expect: 'the strict full-line anchor blocks these'
    parse('mongosh 2.5.0 (using driver 5.6.0)') == null
    parse('downloaded mongosh 2.5.0') == null
  }

  def "alternate 'Using Mongosh:' line shape parses (forward compatibility)"() {
    when: 'mongosh has been observed to emit this form when invoked --nodb'
    def out = parse('Using Mongosh: 2.0.5')

    then:
    out != null
    out.major == 2
    out.minor == 0
    out.patch == 5
    out.prerelease == null
    out.rawLine == 'Using Mongosh: 2.0.5'
  }

  def "real-world multi-line where ONLY the 'Using Mongosh:' line carries the version still parses"() {
    given: 'a hypothetical future build where the bare `mongosh X.Y.Z` line is gone'
    def raw = [
      'Using MongoDB:          7.0.5',
      'Using Mongosh: 2.1.0',
      '',
    ].join('\n')

    when:
    def out = parse(raw)

    then:
    out != null
    out.major == 2
    out.minor == 1
    out.patch == 0
  }

  // --------------------------------------------------------------------
  // SupportJobConfig field — the new flag is wired up.
  // --------------------------------------------------------------------

  def "SupportJobConfig.allowMongoshPrerelease defaults to false (issue #13)"() {
    expect:
    new SupportJobConfig().allowMongoshPrerelease == false
  }

  def "SupportJobConfig.allowMongoshPrerelease is settable to true"() {
    given:
    def cfg = new SupportJobConfig(allowMongoshPrerelease: true)

    expect:
    cfg.allowMongoshPrerelease == true
  }
}
