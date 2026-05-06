package com.hourtimesheet.jenkins

import spock.lang.Specification

import java.util.regex.Pattern

/**
 * Pins the correlationId construction in {@code vars/htsSupportJob.groovy}
 * (Bootstrap stage). Issue #16: Jenkins's "Replay" feature reuses the parent
 * build's {@code BUILD_TAG}, so the previous form
 * {@code env.BUILD_TAG ?: UUID.randomUUID()} produced identical correlationIds
 * across two distinct physical writes — breaking the 1:1 audit-row promise
 * with Mongo's {@code auditEventLog} (PK collisions on the
 * {@code correlationId} index).
 *
 * Fix: always append {@code -${BUILD_NUMBER}-${UUID(8)}} regardless of whether
 * BUILD_TAG is set. Defense-in-depth — no Replay-detection branching needed,
 * and any two calls (even with identical BUILD_TAG and BUILD_NUMBER, e.g. a
 * controller bug) still produce distinct ids thanks to the random suffix.
 *
 * Two angles, mirroring {@link ShellHardeningSpec}:
 *   1. <b>Static</b>: assert the source line contains BUILD_TAG, BUILD_NUMBER,
 *      and the {@code take(8)} random suffix, and that the regressed form is
 *      gone.
 *   2. <b>Dynamic</b>: reproduce the exact Groovy expression and assert the
 *      collision-resistance contract (two calls with the same env still
 *      differ).
 */
class CorrelationIdSpec extends Specification {

  /** Matches the new shape: any-prefix-${digits-or-0}-${8 hex chars}. */
  private static final Pattern CORRELATION_ID = ~/^.+-\d+-[0-9a-f]{8}$/

  private String supportJobSource() {
    def f = new File('vars/htsSupportJob.groovy')
    assert f.exists() : "expected vars/htsSupportJob.groovy at ${f.absolutePath}"
    return f.text
  }

  // --------------------------------------------------------------------
  // Static guards: catch an accidental revert without needing a Jenkins runtime.
  // --------------------------------------------------------------------

  def "correlationId construction includes BUILD_TAG with a 'jenkins' fallback (issue #16)"() {
    given:
    def src = supportJobSource()

    expect:
    src.contains("\${env.BUILD_TAG ?: 'jenkins'}")
  }

  def "correlationId construction includes BUILD_NUMBER with a '0' fallback (issue #16)"() {
    given:
    def src = supportJobSource()

    expect:
    src.contains("\${env.BUILD_NUMBER ?: '0'}")
  }

  def "correlationId construction appends an 8-char random UUID suffix (issue #16)"() {
    given:
    def src = supportJobSource()

    expect:
    src.contains('java.util.UUID.randomUUID().toString().take(8)')
  }

  def "regressed form 'env.BUILD_TAG ?: UUID.randomUUID()' is no longer present (issue #16)"() {
    given:
    def src = supportJobSource()

    expect: 'the bare ?: form that collided on Replay is gone'
    !(src =~ /env\.BUILD_TAG\s*\?:\s*java\.util\.UUID\.randomUUID\(\)\.toString\(\)\s*$/m)
    !(src =~ /env\.BUILD_TAG\s*\?:\s*UUID\.randomUUID\(\)\s*$/m)
  }

  // --------------------------------------------------------------------
  // Dynamic: reproduce the exact expression and verify the contract.
  // --------------------------------------------------------------------

  /**
   * Mirrors the production line in {@code vars/htsSupportJob.groovy}.
   * Kept in sync with the static guards above — if the production form
   * changes, both this helper and the static assertions must change.
   */
  private static String buildCorrelationId(Map env) {
    return "${env.BUILD_TAG ?: 'jenkins'}-${env.BUILD_NUMBER ?: '0'}-${java.util.UUID.randomUUID().toString().take(8)}"
  }

  def "correlationId always contains BUILD_TAG when set"() {
    when:
    def id = buildCorrelationId(BUILD_TAG: 'jenkins-hk-2204-7', BUILD_NUMBER: '7')

    then:
    id.startsWith('jenkins-hk-2204-7-')
    id ==~ CORRELATION_ID
  }

  def "correlationId falls back to 'jenkins' prefix when BUILD_TAG is unset"() {
    when:
    def id = buildCorrelationId(BUILD_TAG: null, BUILD_NUMBER: '42')

    then:
    id.startsWith('jenkins-42-')
    id ==~ CORRELATION_ID
  }

  def "correlationId falls back to '0' when BUILD_NUMBER is unset"() {
    when:
    def id = buildCorrelationId(BUILD_TAG: 'jenkins-hk-2204-1', BUILD_NUMBER: null)

    then:
    id.startsWith('jenkins-hk-2204-1-0-')
    id ==~ CORRELATION_ID
  }

  def "correlationId falls back to 'jenkins-0-' when both env vars are unset"() {
    when:
    def id = buildCorrelationId(BUILD_TAG: null, BUILD_NUMBER: null)

    then:
    id.startsWith('jenkins-0-')
    id ==~ CORRELATION_ID
  }

  def "correlationId always ends with an 8-char hex random suffix"() {
    given:
    def env = [BUILD_TAG: 'jenkins-hk-2204-7', BUILD_NUMBER: '7']

    expect:
    (1..50).every { buildCorrelationId(env) ==~ CORRELATION_ID }
  }

  /**
   * The collision regression test for issue #16. Before the fix, two Replay
   * runs (sharing BUILD_TAG and BUILD_NUMBER) produced identical
   * correlationIds. After the fix, the random 8-char suffix guarantees they
   * differ even with identical env.
   */
  def "two consecutive calls with the same env produce different correlationIds (issue #16 regression)"() {
    given: 'identical env, simulating a Jenkins Replay run with the same BUILD_TAG'
    def env = [BUILD_TAG: 'jenkins-hk-2204-7', BUILD_NUMBER: '7']

    when:
    def a = buildCorrelationId(env)
    def b = buildCorrelationId(env)

    then:
    a != b
    a ==~ CORRELATION_ID
    b ==~ CORRELATION_ID
  }

  def "many calls with the same env produce all-distinct correlationIds (collision-resistance, issue #16)"() {
    given:
    def env = [BUILD_TAG: 'jenkins-hk-2204-7', BUILD_NUMBER: '7']

    when:
    def ids = (1..100).collect { buildCorrelationId(env) } as Set

    then: '100 calls -> 100 distinct ids (8 hex chars = 16^8 = ~4.3B; collision is astronomically unlikely)'
    ids.size() == 100
  }
}
