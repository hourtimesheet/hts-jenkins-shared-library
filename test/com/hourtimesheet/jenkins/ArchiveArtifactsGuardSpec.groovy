package com.hourtimesheet.jenkins

import spock.lang.Specification

/**
 * Pins issue #12 (N-H1): {@code archiveArtifacts} in {@code post.always} MUST
 * be wrapped in {@code try/catch (Throwable)} so {@code cleanWs} always runs
 * even when the artifact-archive step throws (full disk, broken master-agent
 * connection mid-archive, S3 hiccup on a JCasC-configured artifact backend).
 *
 * Pre-fix, an archive failure left {@code .htsSupportJob/${BUILD_NUMBER}/} on
 * the agent — orphaned PII / audit data that the next build inherited.
 *
 * Contract assertions (static, source-grep style — same approach as
 * {@link BuildDescriptionSidechannelSpec} and {@link ShellHardeningSpec}):
 *
 * <ul>
 *   <li>{@code archiveArtifacts(...)} appears inside a {@code try {} catch}
 *       block, not bare.</li>
 *   <li>The catch clause uses {@code Throwable} (not {@code Exception}) so
 *       {@code InterruptedException} / sandbox {@code SecurityException} can
 *       NOT escape post.always.</li>
 *   <li>The catch clause sets {@code currentBuild.result = 'UNSTABLE'} so
 *       archive failure is operator-visible without flipping a successful
 *       apply to FAILED.</li>
 *   <li>The catch clause does NOT rethrow — control must flow into
 *       {@code cleanWs} below.</li>
 *   <li>{@code archiveArtifacts} appears textually BEFORE the {@code cleanWs}
 *       block within {@code post.always}, i.e. the ordering is preserved.</li>
 *   <li>{@code allowEmptyArchive: true} is preserved (a no-op build legitimately
 *       has no logs).</li>
 *   <li>The catch clause logs via {@code log.errorLine} (per the
 *       {@code log.error} -> {@code log.errorLine} rename in PR #26).</li>
 * </ul>
 */
class ArchiveArtifactsGuardSpec extends Specification {

  private String varsSource() {
    def f = new File('vars/htsSupportJob.groovy')
    assert f.exists() : "expected vars/htsSupportJob.groovy at ${f.absolutePath}"
    return f.text
  }

  /**
   * Returns the substring of {@code src} that is the body of the closest
   * enclosing brace block whose opening {@code {} is the last one before
   * {@code anchorIdx}. Used to locate "the post.always block" or
   * "the try block immediately wrapping archiveArtifacts" without writing
   * a full Groovy parser.
   */
  private String enclosingBlock(String src, int anchorIdx) {
    // Walk backward from anchorIdx, tracking unmatched `{`. The first
    // unmatched `{` is the opener of our enclosing block.
    int depth = 0
    int openIdx = -1
    for (int i = anchorIdx; i >= 0; i--) {
      char c = src.charAt(i)
      if (c == '}' as char) depth++
      else if (c == '{' as char) {
        if (depth == 0) { openIdx = i; break }
        depth--
      }
    }
    assert openIdx >= 0 : "no enclosing { found before index ${anchorIdx}"
    // Walk forward to its matching `}`.
    depth = 0
    int closeIdx = -1
    for (int i = openIdx; i < src.length(); i++) {
      char c = src.charAt(i)
      if (c == '{' as char) depth++
      else if (c == '}' as char) {
        depth--
        if (depth == 0) { closeIdx = i; break }
      }
    }
    assert closeIdx > openIdx : "no matching } found for { at ${openIdx}"
    return src.substring(openIdx, closeIdx + 1)
  }

  def "archiveArtifacts is invoked exactly once in vars/htsSupportJob.groovy"() {
    given:
    def src = varsSource()

    when: 'count call sites — must be the start of a statement (no whitespace in `archiveArtifacts(`), and the line is not a // comment'
    // The DSL form is `archiveArtifacts(` with no space; the comment at line 372
    // says `archiveArtifacts (which uses ...` — note the space. Anchor on
    // start-of-line + indent + no-space form, and reject lines starting with `//`.
    def callSites = src.readLines().findAll { line ->
      def t = line.trim()
      t.startsWith('archiveArtifacts(') && !t.startsWith('//')
    }

    then: 'one call site only — duplicate would mean a stray pre-fix copy survived'
    callSites.size() == 1
  }

  def "archiveArtifacts is wrapped in try/catch (issue #12)"() {
    given:
    def src = varsSource()

    when:
    def callIdx   = src.indexOf('archiveArtifacts(')
    def enclosing = enclosingBlock(src, callIdx)

    then: 'the immediate enclosing block is a try { ... } — i.e. the line directly above is `try {`'
    callIdx > 0
    // The block immediately wrapping the call must be opened by `try {`.
    // We check the slice of source BEFORE the block opener for the `try`
    // keyword on the same logical line.
    def beforeBlock = src.substring(0, src.indexOf(enclosing))
    beforeBlock.trim().endsWith('try')
  }

  def "the catch clause uses Throwable, not Exception (issue #12)"() {
    given:
    def src = varsSource()

    when: 'find the archiveArtifacts call, then the catch clause that follows the try block'
    def callIdx     = src.indexOf('archiveArtifacts(')
    // Find the `}` that closes the enclosing try block.
    def tryBlock    = enclosingBlock(src, callIdx)
    def tryEndIdx   = src.indexOf(tryBlock) + tryBlock.length()
    // The `catch (...)` clause must begin within ~80 chars of the closing `}`.
    def afterTry    = src.substring(tryEndIdx, Math.min(tryEndIdx + 200, src.length()))

    then: 'catch (Throwable t) — InterruptedException / SecurityException must not escape'
    afterTry =~ /(?s)\s*catch\s*\(\s*Throwable\s+\w+\s*\)/
    !(afterTry =~ /(?s)\s*catch\s*\(\s*Exception\s+\w+\s*\)/)
  }

  def "the catch clause marks build UNSTABLE (issue #12)"() {
    given:
    def src = varsSource()

    when: 'extract the catch block following the archiveArtifacts try'
    def callIdx     = src.indexOf('archiveArtifacts(')
    def tryBlock    = enclosingBlock(src, callIdx)
    def tryEndIdx   = src.indexOf(tryBlock) + tryBlock.length()
    // Find the catch block's `{` after the try's closing `}`.
    def catchOpenIdx = src.indexOf('{', tryEndIdx)
    def catchBlock   = enclosingBlock(src, catchOpenIdx + 1)

    then: 'UNSTABLE so the failure is operator-visible without flipping success to FAILED'
    catchBlock.contains("currentBuild.result = 'UNSTABLE'")
  }

  def "the catch clause does NOT rethrow — control must flow to cleanWs (issue #12)"() {
    given:
    def src = varsSource()

    when:
    def callIdx     = src.indexOf('archiveArtifacts(')
    def tryBlock    = enclosingBlock(src, callIdx)
    def tryEndIdx   = src.indexOf(tryBlock) + tryBlock.length()
    def catchOpenIdx = src.indexOf('{', tryEndIdx)
    def catchBlock   = enclosingBlock(src, catchOpenIdx + 1)

    then: 'no `throw` statement — rethrow would skip cleanWs, defeating the fix'
    !(catchBlock =~ /(?m)^\s*throw\s+/)
  }

  def "the catch clause logs via log.errorLine (post-#26 rename)"() {
    given:
    def src = varsSource()

    when:
    def callIdx     = src.indexOf('archiveArtifacts(')
    def tryBlock    = enclosingBlock(src, callIdx)
    def tryEndIdx   = src.indexOf(tryBlock) + tryBlock.length()
    def catchOpenIdx = src.indexOf('{', tryEndIdx)
    def catchBlock   = enclosingBlock(src, catchOpenIdx + 1)

    then: 'log.errorLine — log.error was renamed to errorLine to avoid shadowing Jenkins error()'
    catchBlock.contains('log.errorLine(')
    !catchBlock.contains('log.error(')
  }

  def "archiveArtifacts precedes cleanWs within post.always (ordering preserved)"() {
    given:
    def src = varsSource()

    when:
    def archiveIdx = src.indexOf('archiveArtifacts(')
    def cleanWsIdx = src.indexOf('cleanWs(')

    then:
    archiveIdx > 0
    cleanWsIdx > archiveIdx
  }

  def "allowEmptyArchive: true is preserved (a noop build legitimately has no logs)"() {
    given:
    def src = varsSource()

    when:
    def callIdx = src.indexOf('archiveArtifacts(')
    // Slice forward to the matching `)` for the call.
    int depth = 0
    int closeIdx = -1
    for (int i = callIdx; i < src.length(); i++) {
      char c = src.charAt(i)
      if (c == '(' as char) depth++
      else if (c == ')' as char) {
        depth--
        if (depth == 0) { closeIdx = i; break }
      }
    }
    def callArgs = src.substring(callIdx, closeIdx + 1)

    then:
    callArgs.contains('allowEmptyArchive: true')
  }

  def "archive try/catch lives inside post.always (so cleanWs in same block runs after)"() {
    given:
    def src = varsSource()

    when:
    def postIdx        = src.indexOf('post {')
    def alwaysIdx      = src.indexOf('always {', postIdx)
    def archiveIdx     = src.indexOf('archiveArtifacts(', alwaysIdx)
    def successIdx     = src.indexOf('success {', alwaysIdx)

    then:
    postIdx > 0
    alwaysIdx > postIdx
    archiveIdx > alwaysIdx
    // archive must be BEFORE the next post-condition (success/failure/aborted)
    successIdx == -1 || archiveIdx < successIdx
  }

  def "issue #12 reference is present in source so future readers find the rationale"() {
    given:
    def src = varsSource()

    expect: 'a code comment near the archive block names the issue'
    src.contains('issue #12') || src.contains('Issue #12')
  }
}
