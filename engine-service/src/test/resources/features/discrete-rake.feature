Feature: Rake-enabled debit transactions

  Scenario: Rake-enabled debit executes three-way split
    Given no account with id "rake-from" exists
    And no account with id "rake-to" exists
    And no account with id "rake-platform" exists
    And an account "rake-from" exists with balance 1000.00
    And an account "rake-to" exists with balance 0.00
    And an account "rake-platform" exists with balance 0.00
    And rake is configured with rate 0.20 for transaction type "private_show" using metadata key "transaction_type" and platform account "rake-platform"
    When I post a DEBIT of 100.00 from account "rake-from" to account "rake-to" with metadata '{"transaction_type":"private_show"}' and idempotency key "rake-key-001"
    Then the response status is 201
    And account "rake-from" has committed balance 900.00
    And account "rake-to" has committed balance 80.00
    And account "rake-platform" has committed balance 20.00

  Scenario: Zero rake rate skips rake credits
    Given no account with id "norake-from" exists
    And no account with id "norake-to" exists
    And an account "norake-from" exists with balance 500.00
    And an account "norake-to" exists with balance 0.00
    When I post a DEBIT of 100.00 from account "norake-from" to account "norake-to" with metadata '{"transaction_type":"unknown_type"}' and idempotency key "norake-key-001"
    Then the response status is 201
    And account "norake-from" has committed balance 400.00
