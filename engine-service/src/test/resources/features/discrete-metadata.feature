Feature: Transaction metadata flow-through

  Scenario: Transaction metadata is returned unchanged in response
    Given no account with id "meta-001" exists
    And an account "meta-001" exists with balance 200.00
    When I post a DEBIT of 10.00 from account "meta-001" with metadata '{"session":"abc","rate":"0.15"}' and idempotency key "meta-key-001"
    Then the response status is 201
    And the transaction response metadata contains key "session" with value "abc"
    And the transaction response metadata contains key "rate" with value "0.15"

  Scenario: Transaction metadata flows through to audit log
    Given no account with id "meta-002" exists
    And an account "meta-002" exists with balance 200.00
    When I post a DEBIT of 10.00 from account "meta-002" with metadata '{"source":"api-test"}' and idempotency key "meta-key-002"
    Then the response status is 201
    And the audit log for account "meta-002" has an entry with transaction metadata key "source" value "api-test"
