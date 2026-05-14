Feature: Rake-enabled debit transactions

  Scenario: Rake-enabled debit executes three-way split
    Given no account with id "rake-from" exists
    And no account with id "rake-to" exists
    And no account with id "rake-platform" exists
    And an account "rake-from" exists with balance 1000.00
    And an account "rake-to" exists with balance 0.00
    And an account "rake-platform" exists with balance 0.00
    When I post a DEBIT of 200.00 from account "rake-from" to account "rake-to" with rake rate 0.10 platform account "rake-platform" and idempotency key "rake-key-001"
    Then the response status is 201
    And account "rake-from" has committed balance 800.00
    And account "rake-to" has committed balance 180.00
    And account "rake-platform" has committed balance 20.00

  Scenario: Zero rake rate transfers full amount with no platform cut
    Given no account with id "norake-from" exists
    And no account with id "norake-to" exists
    And an account "norake-from" exists with balance 500.00
    And an account "norake-to" exists with balance 0.00
    When I post a DEBIT of 100.00 from account "norake-from" to account "norake-to" with rake rate 0.00 platform account "norake-platform" and idempotency key "norake-key-001"
    Then the response status is 201
    And account "norake-from" has committed balance 400.00
    And account "norake-to" has committed balance 100.00

  Scenario: Plain transfer with no rake fields credits full amount to destination
    Given no account with id "transfer-from" exists
    And no account with id "transfer-to" exists
    And an account "transfer-from" exists with balance 200.00
    And an account "transfer-to" exists with balance 50.00
    When I post a DEBIT of 75.00 from account "transfer-from" to account "transfer-to" with idempotency key "transfer-key-001"
    Then the response status is 201
    And account "transfer-from" has committed balance 125.00
    And account "transfer-to" has committed balance 125.00
