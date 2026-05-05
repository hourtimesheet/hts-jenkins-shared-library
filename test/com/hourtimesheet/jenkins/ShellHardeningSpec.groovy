package com.hourtimesheet.jenkins

import spock.lang.IgnoreIf
import spock.lang.Specification

/**
 * Verifies the shell-hardening claims of {@code _runMongosh} that {@link
 * SupportJobConfigSpec} cannot reach (because it tests the typed config, not
 * the rendered shell). Two angles:
 *
 *   1. <b>Static</b>: read {@code vars/htsSupportJob.groovy} as a string and
 *      assert the bash shebang, {@code set -euo pipefail}, the {@code set +x}
 *      hardening, and the {@code umask 077} are present in the helper. This
 *      catches an accidental revert without needing a Jenkins runtime.
 *   2. <b>Dynamic</b>: when {@code bash} is on PATH (any Linux/Mac dev box,
 *      and CI), execute a tiny {@code false | true} pipeline under bash with
 *      the same {@code set -euo pipefail} we'd ship and assert the shell
 *      exits non-zero. This is the contract C1 protects.
 *
 * Audit finding C1 (bash-vs-dash pipefail) and C6 (xtrace masking) are
 * pinned by these tests.
 */
class ShellHardeningSpec extends Specification {

  private String shellHelperSource() {
    def f = new File('vars/htsSupportJob.groovy')
    assert f.exists() : "expected vars/htsSupportJob.groovy at ${f.absolutePath}"
    return f.text
  }

  def "vars/htsSupportJob.groovy uses bash shebang in shell helpers (audit finding C1)"() {
    given:
    def src = shellHelperSource()

    expect: 'every sh heredoc declares bash explicitly so set -o pipefail is honoured'
    src.contains("sh '''#!/usr/bin/env bash")
    // No legacy POSIX-only sh heredoc snuck back in (we look for the old shape).
    !src.contains("sh '''\n        set -eo pipefail")
  }

  def "vars/htsSupportJob.groovy sets euo pipefail (not just eo) (audit finding C1)"() {
    given:
    def src = shellHelperSource()

    expect:
    src.contains('set -euo pipefail')
  }

  def "vars/htsSupportJob.groovy disables xtrace around mongosh (audit finding C6)"() {
    given:
    def src = shellHelperSource()

    expect: 'set +x hardening is present immediately around the mongosh call'
    src.contains('{ set +x; } 2>/dev/null')
    src.contains('mongosh "$MONGO_URI"')
  }

  def "vars/htsSupportJob.groovy preserves umask 077 on log files"() {
    given:
    def src = shellHelperSource()

    expect:
    src.contains('umask 077')
  }

  // ---------------------------------------------------------------------
  // Dynamic: actually run the pipefail contract through bash.
  // Skipped on platforms where bash is not at /bin/bash (rare); CI's Ubuntu
  // runner has bash.
  // ---------------------------------------------------------------------
  @IgnoreIf({ !new File('/bin/bash').canExecute() })
  def "bash with set -euo pipefail propagates a left-pipe failure (audit finding C1)"() {
    when:
    def proc = ['/bin/bash', '-c', 'set -euo pipefail; false | true'].execute()
    proc.waitFor()

    then: 'pipefail makes the left-side false propagate to the overall exit code'
    proc.exitValue() != 0
  }

  @IgnoreIf({ !new File('/bin/bash').canExecute() })
  def "bash without pipefail would NOT propagate left-pipe failure (control case)"() {
    when:
    def proc = ['/bin/bash', '-c', 'set -eu; false | true'].execute()
    proc.waitFor()

    then: 'without pipefail, the right-side success masks the left-side failure'
    proc.exitValue() == 0
  }

  @IgnoreIf({ !new File('/bin/bash').canExecute() })
  def "bash with set -x echoes secrets but { set +x; } silences the next line"() {
    // set -x writes to stderr, not stdout. Capture combined output via a
    // wrapping shell so we can assert on what the operator would see.
    when: 'two scripts: one with xtrace on for the secret, one with the suppress wrapper'
    def xtraceOut = runShellCombined(
        '/bin/bash -c \'set -x; SECRET=hunter2; echo "$SECRET"\' 2>&1'
    )
    def suppressOut = runShellCombined(
        '/bin/bash -c \'set -x; SECRET=hunter2; { set +x; } 2>/dev/null; echo "$SECRET"\' 2>&1'
    )

    then:
    xtraceOut.contains("+ echo hunter2") || xtraceOut.contains("+ echo 'hunter2'")
    !(suppressOut.contains("+ echo hunter2") || suppressOut.contains("+ echo 'hunter2'"))
  }

  /** Runs a shell snippet under bash and returns the combined stdout/stderr. */
  private static String runShellCombined(String script) {
    def proc = ['/bin/bash', '-c', script].execute()
    def sb = new StringBuilder()
    proc.waitForProcessOutput(sb, sb)
    return sb.toString()
  }
}
