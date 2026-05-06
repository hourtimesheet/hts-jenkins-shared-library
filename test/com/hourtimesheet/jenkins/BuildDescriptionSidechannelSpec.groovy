package com.hourtimesheet.jenkins

import spock.lang.Specification

/**
 * Pins issue #14: {@code currentBuild.description} is informational only and
 * MUST NOT be used as side-channel state.
 *
 * The audit-log JSONL artifact (and the {@code auditEventLog} row written by
 * the {@code .mongosh.js}) are the canonical sources of truth for status.
 * Multiple writes to {@code currentBuild.description} mid-pipeline duplicated
 * that state and could drift from canonical (UI edits, plugin upgrades,
 * partial pipeline runs leaving a stale "applied:" string).
 *
 * Contract assertions (static, source-grep style — same approach as
 * {@link ShellHardeningSpec}):
 *
 * <ul>
 *   <li>At most ONE {@code currentBuild.description = ...} write site exists,
 *       and it lives inside {@code post.always} so a partial pipeline cannot
 *       leave a stale string.</li>
 *   <li>The previous {@code "noop: …"} and {@code "applied: …"} mid-stage
 *       writes are gone.</li>
 *   <li>NO code path READS {@code currentBuild.description} (no {@code when{}}
 *       expression, no helper, no audit-log status derivation).</li>
 *   <li>The audit-log helper derives status from {@code _ctxStore} +
 *       {@code currentBuild.currentResult}, NOT from the description.</li>
 *   <li>The "Status of truth" doc block is present in the file-level Javadoc
 *       so future readers don't reinstate the side-channel.</li>
 * </ul>
 */
class BuildDescriptionSidechannelSpec extends Specification {

  private String varsSource() {
    def f = new File('vars/htsSupportJob.groovy')
    assert f.exists() : "expected vars/htsSupportJob.groovy at ${f.absolutePath}"
    return f.text
  }

  def "vars/htsSupportJob.groovy writes currentBuild.description AT MOST once (issue #14)"() {
    given:
    def src = varsSource()

    when: 'count assignment sites — `currentBuild.description = ...`'
    // Match assignments only: `currentBuild.description =` (single =, with surrounding
    // whitespace), not the surrounding doc/comments. The token always appears at
    // start-of-statement on its own line in this codebase.
    def writeSites = (src =~ /(?m)^\s*currentBuild\.description\s*=\s*[^=]/).findAll()

    then:
    writeSites.size() <= 1
  }

  def "vars/htsSupportJob.groovy does not contain the deleted intermediate description writes (issue #14)"() {
    given:
    def src = varsSource()

    expect: 'the two pre-fix string-template writes are removed'
    !src.contains('currentBuild.description = "noop: ${cfg.ticketId}')
    !src.contains('currentBuild.description = "applied: ${cfg.ticketId}')
  }

  def "the single description write is inside post.always (issue #14)"() {
    given:
    def src = varsSource()

    when: 'find the post block start and the description write'
    def postIdx        = src.indexOf('post {')
    def alwaysIdx      = src.indexOf('always {', postIdx)
    def descWriteIdx   = src.indexOf('currentBuild.description = ')
    def successIdx     = src.indexOf('success {', alwaysIdx)

    then: 'description write happens after `post { always {` and before the next post-condition'
    postIdx > 0
    alwaysIdx > postIdx
    descWriteIdx > alwaysIdx
    // If there's no success {} block, successIdx == -1; in that case the
    // description write just needs to be inside post {} (after alwaysIdx).
    successIdx == -1 || descWriteIdx < successIdx
  }

  def "no code path READS currentBuild.description (issue #14)"() {
    given:
    def src = varsSource()

    when: 'enumerate every line that mentions currentBuild.description'
    // Walk lines; classify each as one of:
    //   - comment (// or javadoc *)
    //   - assignment: `currentBuild.description = ...`
    //   - inside-a-string log/warn message (lone token surrounded by quotes)
    //   - otherwise: a read
    def reads = []
    src.eachLine { line ->
      def t = line.trim()
      if (!t.contains('currentBuild.description')) return
      // Comment lines (block-comment continuation `*` or line-comment `//`)
      if (t.startsWith('*') || t.startsWith('//') || t.startsWith('/*')) return
      // Bare-word line entirely inside a string literal (the warn message
      // case). We require that EVERY currentBuild.description occurrence on
      // this line is enclosed in quotes — i.e. there's no bare token outside
      // a string. A simple sufficient check: the line has matched quotes
      // around the token.
      // Allow exactly the assignment form on the left:
      //   currentBuild.description = <something>     (single `=`, not `==`)
      if (t ==~ /currentBuild\.description\s*=\s*[^=].*/) return
      // Allow if every occurrence of the token in this line is inside a
      // double-quoted span. Mask all "..." substrings, then check.
      def masked = t.replaceAll(/"(?:\\.|[^"\\])*"/, '""')
      if (!masked.contains('currentBuild.description')) return
      reads << t
    }

    then: 'no read sites remain'
    reads.isEmpty()
  }

  def "audit-log status derivation does NOT consult currentBuild.description (issue #14)"() {
    given:
    def src = varsSource()

    when: 'extract just the _writeAuditLog method body via brace matching'
    def methodIdx = src.indexOf('private void _writeAuditLog')
    // Find the opening `{` after the signature, then walk to the matching `}`.
    def openIdx = src.indexOf('{', methodIdx)
    int depth   = 0
    int closeIdx = -1
    for (int i = openIdx; i < src.length(); i++) {
      char c = src.charAt(i)
      if (c == '{' as char) depth++
      else if (c == '}' as char) {
        depth--
        if (depth == 0) { closeIdx = i; break }
      }
    }
    def body = src.substring(methodIdx, closeIdx + 1)

    then: 'status pulls from _ctxStore + currentBuild.currentResult, never from .description'
    body.contains('_ctxStore.wasApplied')
    body.contains('_ctxStore.isNoop')
    body.contains('currentBuild.currentResult')
    !body.contains('currentBuild.description')
  }

  def "file-level Javadoc carries the Status-of-truth contract (issue #14)"() {
    given:
    def src = varsSource()

    expect: 'a future reader sees the contract before reinstating the side-channel'
    src.contains('Status of truth')
    src.contains('informational')
    src.contains('audit-log')
  }
}
