package com.hourtimesheet.jenkins

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Issue #10: pure date-comparison logic for the {@code htsSupportJob} hard
 * freeze gate. Extracted from {@code vars/htsSupportJob.groovy} so it can be
 * unit-tested without a Jenkins runtime — pipeline-shape tests are deferred
 * (see README "Local development"), so any logic that CAN be unit-tested
 * SHOULD live in {@code src/} where the Spock harness reaches it.
 *
 * <h3>Why UTC</h3>
 * Jenkins agents may run in different timezones (controller in UTC, an agent
 * in PST, another agent in IST). Using {@link LocalDate#now()} would give a
 * different "today" on each — a freeze date of {@code 2026-08-01} would trip
 * up to ~24 hours apart depending on which agent picked up the build. We
 * always compare in UTC so every agent observes the same calendar date for
 * the same wall-clock instant.
 */
final class FreezeGate implements Serializable {
  private static final long serialVersionUID = 1L

  // Pure utility — no instances.
  private FreezeGate() {}

  /**
   * Returns {@code true} iff today (UTC) is on or after {@code freezeAfter}.
   *
   * @param freezeAfter ISO {@code YYYY-MM-DD} string, or null/blank for "never frozen".
   *                    Caller (via {@link SupportJobConfig#validate()}) is
   *                    expected to reject malformed dates earlier; this
   *                    helper trusts the format and lets {@link
   *                    java.time.format.DateTimeParseException} propagate
   *                    on bad input so the failure is loud, not silent.
   * @param today       the calendar date to compare against, in UTC. Caller
   *                    passes {@link LocalDate#now(ZoneOffset)} with
   *                    {@link ZoneOffset#UTC}; tests pass a fixed value.
   * @return false when {@code freezeAfter} is null/blank; otherwise
   *         {@code today >= freezeDate}.
   */
  static boolean isFrozen(String freezeAfter, LocalDate today) {
    if (freezeAfter == null) return false
    def trimmed = freezeAfter.trim()
    if (trimmed.isEmpty()) return false
    def freezeDate = LocalDate.parse(trimmed)
    // "freezeAfter" semantically means "frozen from this date onward" — i.e.
    // today >= freezeDate trips the gate. Implementation uses isBefore() and
    // negates so a tie (today == freezeDate) is a freeze hit.
    return !today.isBefore(freezeDate)
  }

  /**
   * Convenience overload that compares against {@link LocalDate#now(ZoneOffset)}
   * with UTC. Production callers use this; tests use the two-arg form so they
   * can pin "today" deterministically.
   */
  static boolean isFrozen(String freezeAfter) {
    return isFrozen(freezeAfter, LocalDate.now(ZoneOffset.UTC))
  }

  /**
   * Builds the freeze-error message that {@code htsSupportJob} throws when
   * the gate trips. Centralised so the apply-block and noop-block paths emit
   * identical wording (modulo the "all runs blocked" / "noop runs still
   * allowed" suffix).
   */
  static String errorMessage(String jobName, String freezeAfter, boolean freezeBlocksNoop) {
    def suffix = freezeBlocksNoop ? 'all runs blocked' : 'noop runs still allowed'
    return "Apply blocked: ${jobName} is frozen as of ${freezeAfter}; ${suffix}"
  }
}
