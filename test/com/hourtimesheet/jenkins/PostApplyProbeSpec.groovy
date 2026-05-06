package com.hourtimesheet.jenkins

import spock.lang.Specification

import java.util.regex.Pattern

/**
 * Issue #17: post-apply audit-row probe.
 *
 * Pre-#17, the contract that every {@code RESULT_JSON status=applied} produces
 * exactly one {@code auditEventLog} row with the matching {@code correlationId}
 * was documentation-only — a consumer {@code .mongosh.js} that wrote the data
 * but skipped the audit row would still report success. The Apply stage now
 * runs a probe that asserts {@code countDocuments == 1}; {@code != 1} fails
 * the build.
 *
 * Two angles, mirroring {@link ShellHardeningSpec} and {@link CorrelationIdSpec}:
 *
 * <ol>
 *   <li><b>Static</b>: read {@code vars/htsSupportJob.groovy} as a string and
 *       assert the probe shape, env-var indirection, setReadPref, xtrace
 *       suppression, and call-site placement.</li>
 *   <li><b>Dynamic</b>: parse-stdout-shape unit tests against the small
 *       integer-extraction logic mirrored locally; verify
 *       {@code SupportJobConfig.skipPostApplyProbe} default + flag wiring;
 *       verify the probe shell command shape literally.</li>
 * </ol>
 */
class PostApplyProbeSpec extends Specification {

  private String supportJobSource() {
    def f = new File('vars/htsSupportJob.groovy')
    assert f.exists() : "expected vars/htsSupportJob.groovy at ${f.absolutePath}"
    return f.text
  }

  // ----------------------------------------------------------------
  // Config wiring: skipPostApplyProbe defaults to false
  // ----------------------------------------------------------------

  def "SupportJobConfig.skipPostApplyProbe defaults to false (probe always runs unless explicit override)"() {
    given:
    def cfg = new SupportJobConfig()

    expect: 'default false: probe ALWAYS runs and enforces the audit-row contract (issue #17)'
    cfg.skipPostApplyProbe == false
  }

  def "SupportJobConfig.skipPostApplyProbe is settable to true for emergency override"() {
    given:
    def cfg = new SupportJobConfig()

    when:
    cfg.skipPostApplyProbe = true

    then:
    cfg.skipPostApplyProbe == true
  }

  // ----------------------------------------------------------------
  // Static guards on vars/htsSupportJob.groovy: probe is wired and shaped
  // ----------------------------------------------------------------

  def "_runPostApplyProbe helper is defined exactly once (issue #17)"() {
    given:
    def src = supportJobSource()

    when: 'count helper-method definitions matching the signature'
    def matches = (src =~ /private\s+void\s+_runPostApplyProbe\s*\(/).count

    then: 'exactly one definition'
    matches == 1
  }

  def "_runApply invokes _runPostApplyProbe AFTER appliedMarker check (issue #17)"() {
    given:
    def src = supportJobSource()

    when:
    def appliedMarkerCheckIdx = src.indexOf('!text.contains(cfg.appliedMarker)')
    def probeCallIdx          = src.indexOf('_runPostApplyProbe(cfg)')

    then: 'both exist'
    appliedMarkerCheckIdx > 0
    probeCallIdx > 0

    and: 'probe call is AFTER the appliedMarker check'
    probeCallIdx > appliedMarkerCheckIdx
  }

  def "_runApply invokes _runPostApplyProbe BEFORE setting wasApplied=true (issue #17)"() {
    given:
    def src = supportJobSource()

    when:
    def probeCallIdx  = src.indexOf('_runPostApplyProbe(cfg)')
    def wasAppliedIdx = src.indexOf('_ctxStore.wasApplied = true')

    then: 'a probe failure must throw before _ctxStore.wasApplied flips to true'
    probeCallIdx > 0
    wasAppliedIdx > 0
    probeCallIdx < wasAppliedIdx
  }

  def "probe is invoked exactly once in vars/htsSupportJob.groovy"() {
    given:
    def src = supportJobSource()

    when: 'count probe call sites at start of statement (no comment prefix)'
    def callSites = src.readLines().findAll { line ->
      def t = line.trim()
      t.startsWith('_runPostApplyProbe(cfg)') && !t.startsWith('//')
    }

    then: 'one call site only — duplicate would mean a stray pre-fix copy survived'
    callSites.size() == 1
  }

  def "probe respects cfg.skipPostApplyProbe (issue #17 emergency override)"() {
    given:
    def src = supportJobSource()

    expect: 'probe early-returns on cfg.skipPostApplyProbe'
    src.contains('if (cfg.skipPostApplyProbe)')

    and: 'skipping logs a WARN so the override is operator-visible'
    src =~ /(?s)cfg\.skipPostApplyProbe.*?log\.warn/
  }

  def "probe passes correlationId via env var, NOT shell interpolation (security)"() {
    given:
    def src = supportJobSource()

    expect: 'env-var indirection: MONGOSH_CORR_ID is the env var name'
    src.contains('MONGOSH_CORR_ID=${correlationId}')

    and: 'eval references process.env.MONGOSH_CORR_ID, not a shell-interpolated $HTS_CORRELATION_ID'
    src.contains('process.env.MONGOSH_CORR_ID')

    and: 'no naive shell-interpolation of correlationId into the eval string'
    !src.contains('correlationId: "$HTS_CORRELATION_ID"')
    !src.contains('correlationId: "\'$HTS_CORRELATION_ID\'"')
  }

  def "probe sets readPreference=primary via --eval BEFORE the count (mirrors issue #24 hardening)"() {
    given:
    def src = supportJobSource()

    when: 'locate the probe block bounded by the helper signature'
    def helperIdx = src.indexOf('_runPostApplyProbe')
    def probeSlice = src.substring(helperIdx)

    then: 'setReadPref("primary") appears'
    probeSlice.contains('--eval \'db.getMongo().setReadPref("primary")\'')

    and: 'countDocuments eval references process.env.MONGOSH_CORR_ID'
    probeSlice.contains('countDocuments({correlationId: process.env.MONGOSH_CORR_ID})')

    and: 'reads from the hourtimesheet database, auditEventLog collection'
    probeSlice.contains('getSiblingDB("hourtimesheet").auditEventLog')
  }

  def "probe suppresses xtrace around mongosh (mirrors audit finding C6)"() {
    given:
    def src = supportJobSource()
    def helperIdx = src.indexOf('_runPostApplyProbe')
    def probeSlice = src.substring(helperIdx)

    expect: 'set +x hardening present in probe just like _runMongosh'
    probeSlice.contains('{ set +x; } 2>/dev/null')
  }

  def "probe uses bash shebang and set -euo pipefail (mirrors audit finding C1)"() {
    given:
    def src = supportJobSource()
    def helperIdx = src.indexOf('_runPostApplyProbe')
    def probeSlice = src.substring(helperIdx)

    expect:
    probeSlice.contains("'''#!/usr/bin/env bash")
    probeSlice.contains('set -euo pipefail')
  }

  def "probe uses withCredentials so MONGO_URI is masked in console (mirrors apply hardening)"() {
    given:
    def src = supportJobSource()
    def helperIdx = src.indexOf('_runPostApplyProbe')
    def probeSlice = src.substring(helperIdx)

    expect: 'same credentialsId pattern as _runMongosh — URI is masked the same way'
    probeSlice.contains('withCredentials([string(credentialsId: cfg.credentialId, variable: \'MONGO_URI\')])')
  }

  def "probe error messages cite correlationId for forensics (issue #17)"() {
    given:
    def src = supportJobSource()
    def helperIdx = src.indexOf('_runPostApplyProbe')
    def probeSlice = src.substring(helperIdx)

    expect: 'count==0 error names the contract violation and includes correlationId'
    probeSlice.contains('Audit-row contract violation')
    probeSlice =~ /(?s)no auditEventLog row found.*correlationId=\$\{correlationId\}/

    and: 'count>1 error explains duplicates'
    probeSlice =~ /(?s)duplicate auditEventLog rows.*correlationId=\$\{correlationId\}/

    and: 'success path logs INFO with VERIFIED keyword for grepability'
    probeSlice.contains('Audit row VERIFIED for correlationId=')
  }

  def "probe execution failure (mongosh throw) is treated as a hard error, not soft-fail (P1)"() {
    given:
    def src = supportJobSource()
    def helperIdx = src.indexOf('_runPostApplyProbe')
    def probeSlice = src.substring(helperIdx)

    expect: 'execution failure path calls error(...) — soft-fail would defeat the P1 contract'
    probeSlice =~ /(?s)catch\s*\(\s*Throwable.*?\{\s*[^}]*?error\s*\(/
    probeSlice.contains('FAILED to execute')

    and: 'override hint is included in the error so operators know the escape hatch'
    probeSlice.contains('cfg.skipPostApplyProbe=true')
  }

  // ----------------------------------------------------------------
  // Dynamic: stdout-parsing logic mirror — pin contract-by-contract.
  // ----------------------------------------------------------------

  /**
   * Mirrors the parse logic in {@code _runPostApplyProbe}: take the LAST
   * all-digits line (mongosh banners may print preamble even with --quiet on
   * some driver versions). Returns the integer or null.
   */
  private static Integer parseProbeOutput(String raw) {
    if (raw == null) return null
    def countLine = null
    for (String line : raw.split('\\r?\\n')) {
      def trimmed = line?.trim()
      if (trimmed && trimmed ==~ /\d+/) {
        countLine = trimmed
      }
    }
    return countLine == null ? null : (countLine as int)
  }

  def "probe parses a single integer line as the count"() {
    expect:
    parseProbeOutput("1\n") == 1
    parseProbeOutput("0\n") == 0
    parseProbeOutput("2\n") == 2
    parseProbeOutput("42") == 42
  }

  def "probe parses the LAST integer line, ignoring banner preamble"() {
    given: 'a driver banner that prints connection info before the count'
    def raw = "Current Mongosh Log ID: abc123\nConnected to: mongodb-host\n1\n"

    expect:
    parseProbeOutput(raw) == 1
  }

  def "probe returns null on empty / non-numeric output (caller fails the build)"() {
    expect:
    parseProbeOutput("") == null
    parseProbeOutput("\n\n") == null
    parseProbeOutput("MongoServerError: connection refused") == null
    parseProbeOutput(null) == null
  }

  def "probe handles whitespace-padded integer lines"() {
    expect:
    parseProbeOutput("  1  \n") == 1
    parseProbeOutput("\t0\t") == 0
  }

  // ----------------------------------------------------------------
  // Dynamic: probe shell-command shape pin (the literal command on the wire).
  // ----------------------------------------------------------------

  def "the probe shell command shape is the documented one (issue #17)"() {
    given:
    def src = supportJobSource()

    expect: 'the exact mongosh invocation shape — guard against accidental drift'
    src.contains('mongosh "$MONGO_URI" --quiet')
    // Multi-line backslash continuation in the bash heredoc is fine; we just
    // assert each component appears.
    src.contains('--eval \'db.getMongo().setReadPref("primary")\'')
    src.contains('countDocuments({correlationId: process.env.MONGOSH_CORR_ID})')
    src.contains('print(db.getSiblingDB("hourtimesheet").auditEventLog')
  }

  def "probe label tag is operator-friendly and references issue #17"() {
    given:
    def src = supportJobSource()

    expect: 'sh label includes the issue number so console drill-down is easy'
    src.contains("label: 'post-apply audit-row probe (issue #17)'")
  }

  // ----------------------------------------------------------------
  // Negative-space: probe does NOT run on noop / dry-run.
  // ----------------------------------------------------------------

  def "_runApply still early-returns on isNoop, never reaching the probe (issue #17)"() {
    given:
    def src = supportJobSource()

    when: 'extract _runApply body'
    def applyIdx = src.indexOf('private void _runApply(SupportJobConfig cfg)')
    def applyEnd = src.indexOf('private void _runPostApplyProbe', applyIdx)
    def applySlice = src.substring(applyIdx, applyEnd)

    then: 'noop short-circuit precedes any probe consideration'
    applySlice =~ /(?s)if\s*\(\s*!cfg\.APPLY\s*\|\|\s*_ctxStore\.isNoop\s*\)\s*\{\s*return/

    and: 'the noop check appears BEFORE _runPostApplyProbe in the body'
    def noopIdx  = applySlice.indexOf('_ctxStore.isNoop')
    def probeIdx = applySlice.indexOf('_runPostApplyProbe(cfg)')
    noopIdx > 0
    probeIdx > 0
    noopIdx < probeIdx
  }

  // ----------------------------------------------------------------
  // Issue marker: future readers find the rationale.
  // ----------------------------------------------------------------

  def "issue #17 reference is present in source so future readers find the rationale"() {
    given:
    def src = supportJobSource()

    expect: 'source comment(s) name the issue'
    src.contains('issue #17') || src.contains('Issue #17')
  }
}
