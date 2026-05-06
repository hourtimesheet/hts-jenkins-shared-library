package com.hourtimesheet.jenkins

import spock.lang.Specification
import spock.lang.Unroll

class SupportJobConfigSpec extends Specification {

  private SupportJobConfig validConfig() {
    new SupportJobConfig(
      ticketId:        'HK-2204',
      companyName:     'acme',
      reason:          'Sales SUPP-1234 customer purchased GEP',
      mongoScriptFile: 'hk-2204-add-gep.mongosh.js',
    )
  }

  def "valid minimum config returns null from validate()"() {
    expect:
    validConfig().validate() == null
  }

  def "default credentialId is hts-mongo-prod-uri"() {
    expect:
    new SupportJobConfig().credentialId == 'hts-mongo-prod-uri'
  }

  def "default APPLY is false (dry-run-by-default)"() {
    expect:
    new SupportJobConfig().APPLY == false
  }

  def "default markers match the HK-2204 contract"() {
    given:
    def cfg = new SupportJobConfig()
    expect:
    cfg.noopMarker    == '"status":"noop"'
    cfg.dryRunMarker  == '"status":"dry-run"'
    cfg.appliedMarker == '"status":"applied"'
    cfg.fatalMarker   == 'FATAL'
  }

  def "default approverGroup is null (no submitter restriction)"() {
    expect:
    new SupportJobConfig().approverGroup == null
  }

  def "default requireDualApproval is false"() {
    expect:
    new SupportJobConfig().requireDualApproval == false
  }

  def "default confirmTenantName is true (operator must retype slug)"() {
    expect:
    new SupportJobConfig().confirmTenantName == true
  }

  def "default onApply / onFailure hooks are null"() {
    expect:
    new SupportJobConfig().onApply == null
    new SupportJobConfig().onFailure == null
  }

  @Unroll
  def "validate() rejects missing #field"() {
    given:
    def cfg = validConfig()
    cfg."${field}" = null

    when:
    def err = cfg.validate()

    then:
    err != null
    err.contains(field)

    where:
    field << ['ticketId', 'companyName', 'reason', 'mongoScriptFile']
  }

  @Unroll
  def "validate() rejects invalid companyName '#name'"() {
    given:
    def cfg = validConfig()
    cfg.companyName = name

    expect:
    cfg.validate()?.contains('companyName')

    where:
    name << [
      'A',                // uppercase
      '-acme',            // can't start with hyphen
      'acme-',            // can't end with hyphen (audit finding H10)
      'acme!',            // special char
      'acme corp',        // space
      'AcmeCorp',         // uppercase
      'a' * 64,           // too long (max 63)
      'a--b',             // consecutive hyphens (audit finding H10)
      'acme_x',           // underscore (DNS labels reject; audit finding H10)
    ]
  }

  @Unroll
  def "validate() accepts valid companyName '#name'"() {
    given:
    def cfg = validConfig()
    cfg.companyName = name

    expect:
    cfg.validate() == null

    where:
    name << ['a', 'ab', 'acme', 'acme-corp', 'acme123', '0acme', 'a' * 63, 'a-b-c', '0-1', 'x9-y2-z3']
  }

  def "validate() rejects reason shorter than 8 chars"() {
    given:
    def cfg = validConfig()
    cfg.reason = 'short'

    expect:
    cfg.validate()?.contains('reason')
  }

  def "validate() rejects reason longer than 500 chars (audit finding M6)"() {
    given:
    def cfg = validConfig()
    cfg.reason = 'A' * 501

    expect:
    cfg.validate()?.contains('reason')
  }

  def "validate() accepts reason at boundary (500 chars)"() {
    given:
    def cfg = validConfig()
    cfg.reason = 'A' * 500

    expect:
    cfg.validate() == null
  }

  @Unroll
  def "validate() rejects reason containing control char '#desc' (audit finding M6)"() {
    given:
    def cfg = validConfig()
    cfg.reason = 'Sales SUPP-1234 ' + ch + ' customer purchased GEP'

    expect:
    cfg.validate()?.contains('reason')

    where:
    desc        | ch
    'newline'   | '\n'
    'CR'        | '\r'
    'tab'       | '\t'
    'NUL'       | '\u0000'
    'DEL'       | '\u007f'
    'bell'      | '\u0007'
  }

  @Unroll
  def "validate() rejects ticketId '#tid' (issue #4 — tightened regex)"() {
    given:
    def cfg = validConfig()
    cfg.ticketId = tid

    expect:
    cfg.validate()?.contains('ticketId')

    where:
    tid << [
      // Pre-existing rejection cases
      'hk-2204',         // lowercase
      'HK',              // no hyphen / digits
      'HK 2204',         // embedded space
      '',                // empty
      null,              // null
      // Issue #4 additions: shapes that the OLD `[A-Z0-9][A-Z0-9-]{2,31}` regex
      // accepted but that aren't real Jira keys.
      'H-2204',          // 1 letter (need 2-6)
      'HKKKKKKK-2204',   // 8 letters (>6)
      'HK-',             // no digits
      'HK-1',            // 1 digit (need 2-6)
      'HK-1234567',      // 7 digits (>6)
      'HK2204',          // missing hyphen
      'HK-2204X',        // trailing junk
      'HK-2204-X',       // trailing -X
      'A1-B2-C3',        // alphanumeric letter-block (old regex accepted this)
      '12-345-678',      // digits-only with extra hyphens
      'H-K-2-2-0-4',     // dotted form (old regex accepted this)
      ' HK-2204',        // leading space
      'HK-2204 ',        // trailing space
    ]
  }

  @Unroll
  def "validate() accepts ticketId '#tid' (issue #4 — canonical Jira shape)"() {
    given:
    def cfg = validConfig()
    cfg.ticketId = tid

    expect:
    cfg.validate() == null

    where:
    tid << [
      'HK-2204',         // canonical example from the codebase
      'HK-220456',       // 6 digits (upper bound)
      'AB-12',           // 2 letters + 2 digits (lower bound)
      'ABCDEF-123456',   // 6 letters + 6 digits (upper bound)
      'SUPP-123',        // typical support-team prefix
    ]
  }

  def "validate() rejects mongoScriptFile with path traversal"() {
    given:
    def cfg = validConfig()
    cfg.mongoScriptFile = '../../../etc/passwd'

    expect:
    cfg.validate()?.contains('mongoScriptFile')
  }

  def "validate() rejects absolute mongoScriptFile path"() {
    given:
    def cfg = validConfig()
    cfg.mongoScriptFile = '/etc/passwd'

    expect:
    cfg.validate()?.contains('mongoScriptFile')
  }

  def "validate() rejects backslash in mongoScriptFile (audit finding H2)"() {
    given:
    def cfg = validConfig()
    cfg.mongoScriptFile = 'subdir\\..\\..\\..\\etc\\passwd'

    expect:
    cfg.validate()?.contains('mongoScriptFile')
  }

  def "validate() rejects mongoScriptFile with embedded space (audit finding H2)"() {
    given:
    def cfg = validConfig()
    cfg.mongoScriptFile = 'sub dir/script.js'

    expect:
    cfg.validate()?.contains('mongoScriptFile')
  }

  @Unroll
  def "validate() rejects extraEnv with reserved key '#k' (audit finding C2)"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = [(k): 'whatever']

    expect:
    cfg.validate()?.contains('reserved')

    where:
    k << [
      // Library-set
      'COMPANY_NAME', 'APPLY', 'REASON', 'OPERATOR', 'MONGO_URI',
      'HTS_SUPPORT_SCRIPT', 'HTS_SUPPORT_OUT_LOG', 'HTS_CORRELATION_ID',
      // Jenkins-set
      'BUILD_URL', 'BUILD_USER', 'WORKSPACE',
      // Shell / loader keys
      'PATH', 'HOME', 'LD_LIBRARY_PATH', 'LD_PRELOAD',
    ]
  }

  @Unroll
  def "validate() rejects extraEnv key '#k' that violates POSIX env-var convention"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = [(k): 'value']

    expect:
    cfg.validate()?.contains('POSIX')

    where:
    k << ['lowercase', '1starts-with-digit', 'has space', 'has-hyphen']
  }

  @Unroll
  def "validate() rejects extraEnv value containing control char '#desc' (audit finding M6)"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = [EMPLOYEE_NOTE: 'normal' + ch + 'tail']

    expect:
    cfg.validate()?.contains('EMPLOYEE_NOTE')

    where:
    desc        | ch
    'newline'   | '\n'
    'CR'        | '\r'
    'tab'       | '\t'
    'NUL'       | '\u0000'
  }

  def "validate() rejects null extraEnv value (audit finding M6)"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = [EMPLOYEE_EMAIL: null]

    expect:
    cfg.validate()?.contains('EMPLOYEE_EMAIL')
  }

  def "validate() accepts well-formed extraEnv"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = [EMPLOYEE_EMAIL: 'op@example.com', EXTRA_FLAG: 'true']

    expect:
    cfg.validate() == null
  }

  def "validate() rejects null extraEnv map"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = null

    expect:
    cfg.validate()?.contains('extraEnv')
  }

  def "validate() rejects blank credentialId override"() {
    given:
    def cfg = validConfig()
    cfg.credentialId = '   '

    expect:
    cfg.validate()?.contains('credentialId')
  }

  @Unroll
  def "validate() rejects empty marker '#which'"() {
    given:
    def cfg = validConfig()
    cfg."${which}" = ''

    expect:
    cfg.validate()?.contains(which)

    where:
    which << ['noopMarker', 'dryRunMarker', 'appliedMarker', 'fatalMarker']
  }

  def "validate() accumulates multiple errors into one message"() {
    given:
    def cfg = new SupportJobConfig()  // everything missing

    when:
    def err = cfg.validate()

    then:
    err.contains('ticketId')
    err.contains('companyName')
    err.contains('reason')
    err.contains('mongoScriptFile')
    err.split('\n').size() >= 4
  }

  def "trimmed accessors strip whitespace once validation has passed"() {
    given:
    def cfg = validConfig()
    cfg.companyName = '  acme  '
    cfg.reason      = '  long enough reason  '

    expect:
    cfg.companyNameTrimmed == 'acme'
    cfg.reasonTrimmed      == 'long enough reason'
  }

  def "config is Serializable so it can survive Jenkins CPS persistence"() {
    expect:
    Serializable.isAssignableFrom(SupportJobConfig)
  }

  def "RESERVED_EXTRA_ENV_KEYS is non-empty and contains the load-bearing names"() {
    expect:
    SupportJobConfig.RESERVED_EXTRA_ENV_KEYS.size() >= 13
    SupportJobConfig.RESERVED_EXTRA_ENV_KEYS.contains('PATH')
    SupportJobConfig.RESERVED_EXTRA_ENV_KEYS.contains('LD_PRELOAD')
    SupportJobConfig.RESERVED_EXTRA_ENV_KEYS.contains('HTS_CORRELATION_ID')
    SupportJobConfig.RESERVED_EXTRA_ENV_KEYS.contains('HTS_SUPPORT_OUT_LOG')
  }

  def "RESERVED_EXTRA_ENV_KEYS is immutable (defence-in-depth against runtime mutation)"() {
    when:
    SupportJobConfig.RESERVED_EXTRA_ENV_KEYS.add('NEW_KEY')

    then:
    thrown(UnsupportedOperationException)
  }

  // ------------------------------------------------------------------
  // Audit-finding C3: extraValidation removed entirely. Pin its absence.
  // ------------------------------------------------------------------
  def "extraValidation field has been removed (audit finding C3)"() {
    when:
    new SupportJobConfig().getProperty('extraValidation')

    then:
    thrown(MissingPropertyException)
  }
}
