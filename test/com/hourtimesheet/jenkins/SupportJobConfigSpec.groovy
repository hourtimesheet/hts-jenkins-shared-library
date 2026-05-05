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
      'A',                // too short + uppercase
      '-acme',            // can't start with hyphen
      'acme!',            // special char
      'acme corp',        // space
      'AcmeCorp',         // uppercase
      'a' * 64,           // too long (max 63)
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
    name << ['ab', 'acme', 'acme-corp', 'acme123', '0acme', 'a' * 63]
  }

  def "validate() rejects reason shorter than 8 chars"() {
    given:
    def cfg = validConfig()
    cfg.reason = 'short'

    expect:
    cfg.validate()?.contains('reason')
  }

  @Unroll
  def "validate() rejects ticketId '#tid'"() {
    given:
    def cfg = validConfig()
    cfg.ticketId = tid

    expect:
    cfg.validate()?.contains('ticketId')

    where:
    tid << ['hk-2204', 'HK', 'HK 2204', '', null]
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

  @Unroll
  def "validate() rejects extraEnv with reserved key '#k'"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = [(k): 'whatever']

    expect:
    cfg.validate()?.contains('reserved')

    where:
    k << ['COMPANY_NAME', 'APPLY', 'REASON', 'OPERATOR', 'BUILD_URL', 'MONGO_URI']
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

  def "validate() accepts well-formed extraEnv"() {
    given:
    def cfg = validConfig()
    cfg.extraEnv = [EMPLOYEE_EMAIL: 'op@example.com', EXTRA_FLAG: 'true']

    expect:
    cfg.validate() == null
  }

  def "validate() rejects null extraEnv"() {
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
}
