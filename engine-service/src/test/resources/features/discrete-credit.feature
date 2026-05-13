Feature: Discrete credit transactions

  Scenario: Credit increases account balance
    Given no account with id "credit-001" exists
    And an account "credit-001" exists with balance 100.00
    When I post a CREDIT of 50.00 to account "credit-001" with idempotency key "credit-key-001"
    Then the response status is 201
    And the transaction response has balanceAfter 150.00
    And account "credit-001" has committed balance 150.00

  Scenario: Credit with metadata returns metadata in response
    Given no account with id "credit-002" exists
    And an account "credit-002" exists with balance 100.00
    When I post a CREDIT of 25.00 to account "credit-002" with metadata '{"transaction_type":"tip"}' and idempotency key "credit-key-002"
    Then the response status is 201
    And the transaction response metadata contains key "transaction_type" with value "tip"

  Scenario: Same idempotency key for credit returns same response twice
    Given no account with id "credit-003" exists
    And an account "credit-003" exists with balance 100.00
    When I post a CREDIT of 10.00 to account "credit-003" with idempotency key "credit-idem-001"
    And I post a CREDIT of 10.00 to account "credit-003" with idempotency key "credit-idem-001"
    Then the response status is 201
    And account "credit-003" has committed balance 110.00
