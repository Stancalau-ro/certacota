Feature: Discrete debit transactions

  Scenario: Debit decreases account balance
    Given no account with id "debit-001" exists
    And an account "debit-001" exists with balance 100.00
    When I post a DEBIT of 30.00 from account "debit-001" with idempotency key "debit-key-001"
    Then the response status is 201
    And the transaction response has balanceAfter 70.00
    And account "debit-001" has committed balance 70.00

  Scenario: Debit below floor is rejected
    Given no account with id "debit-002" exists
    And an account "debit-002" exists with balance 20.00
    When I post a DEBIT of 50.00 from account "debit-002" with idempotency key "debit-key-002"
    Then the response status is 422
    And account "debit-002" has committed balance 20.00

  Scenario: Debit exact amount leaving zero balance is allowed
    Given no account with id "debit-003" exists
    And an account "debit-003" exists with balance 50.00
    When I post a DEBIT of 50.00 from account "debit-003" with idempotency key "debit-key-003"
    Then the response status is 201
    And account "debit-003" has committed balance 0.00
