package com.hourtimesheet.jenkins

import spock.lang.Specification
import spock.lang.Unroll

import java.time.LocalDate

/**
 * Issue #10: hard-freeze gate behaviour.
 *
 * Pure-logic tests on {@link FreezeGate}. The pipeline-shape integration
 * (apply path refuses, noop path optionally refuses, dry-run still produces
 * a preview log when {@code freezeBlocksNoop=false}) is verified via the
 * {@code Manual test plan} in README; this spec pins the date-comparison
 * semantics so a refactor cannot silently flip the inequality.
 */
class FreezeGateSpec extends Specification {

  // Reference date used as "today" for deterministic tests.
  private static final LocalDate TODAY = LocalDate.parse('2026-08-01')

  def "isFrozen() returns false when freezeAfter is null (no freeze configured)"() {
    expect:
    !FreezeGate.isFrozen(null, TODAY)
  }

  @Unroll
  def "isFrozen() returns false for blank freezeAfter '#input' (defensive)"() {
    expect:
    !FreezeGate.isFrozen(input, TODAY)

    where:
    input << ['', '   ', '\t']
  }

  def "isFrozen() returns true when today equals freezeAfter (tie counts as frozen)"() {
    // The semantics are 'frozen from this date onward' — today == freezeAfter
    // is the first frozen day, not the last unfrozen day.
    expect:
    FreezeGate.isFrozen('2026-08-01', TODAY)
  }

  def "isFrozen() returns true when today is after freezeAfter"() {
    expect:
    FreezeGate.isFrozen('2026-07-31', TODAY)
    FreezeGate.isFrozen('2000-01-01', TODAY)
  }

  def "isFrozen() returns false when today is before freezeAfter"() {
    expect:
    !FreezeGate.isFrozen('2026-08-02', TODAY)
    !FreezeGate.isFrozen('2999-12-31', TODAY)
  }

  def "isFrozen() trims surrounding whitespace on freezeAfter"() {
    expect:
    FreezeGate.isFrozen('  2026-07-31  ', TODAY)
    !FreezeGate.isFrozen('  2999-12-31  ', TODAY)
  }

  def "isFrozen() with malformed date throws DateTimeParseException (loud failure, not silent)"() {
    // Production callers gate on SupportJobConfig.validate() which rejects
    // malformed dates earlier; if the gate ever leaks a bad string through,
    // we want it loud, not silently treated as 'never frozen'.
    when:
    FreezeGate.isFrozen('2026-13-01', TODAY)

    then:
    thrown(java.time.format.DateTimeParseException)
  }

  def "single-arg isFrozen() compares against today (UTC) — sanity check"() {
    // We can't pin 'today' here; just confirm the convenience overload runs
    // and gives consistent results for a far-future date and a far-past date.
    expect:
    !FreezeGate.isFrozen('2999-12-31')
    FreezeGate.isFrozen('2000-01-01')
  }

  def "errorMessage() includes the job name, freeze date, and noop suffix when noop is allowed"() {
    given:
    def msg = FreezeGate.errorMessage('HK-2204', '2026-08-01', /* freezeBlocksNoop */ false)

    expect:
    msg.contains('HK-2204')
    msg.contains('2026-08-01')
    msg.contains('noop runs still allowed')
    !msg.contains('all runs blocked')
  }

  def "errorMessage() switches the suffix when freezeBlocksNoop is true"() {
    given:
    def msg = FreezeGate.errorMessage('HK-2204', '2026-08-01', /* freezeBlocksNoop */ true)

    expect:
    msg.contains('HK-2204')
    msg.contains('2026-08-01')
    msg.contains('all runs blocked')
    !msg.contains('noop runs still allowed')
  }

  def "errorMessage() contract: message starts with 'Apply blocked:' so log scrapes can match it"() {
    expect:
    FreezeGate.errorMessage('HK-2204', '2026-08-01', false).startsWith('Apply blocked:')
    FreezeGate.errorMessage('HK-2204', '2026-08-01', true).startsWith('Apply blocked:')
  }

  def "FreezeGate is Serializable — safe to reach from CPS-managed pipeline closures"() {
    expect:
    Serializable.isAssignableFrom(FreezeGate)
  }
}
