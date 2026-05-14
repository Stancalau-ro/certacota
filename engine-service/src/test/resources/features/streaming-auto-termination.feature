Feature: Auto-termination

  Scenario: Account balance fully drained by active stream auto-terminates with reason balance_exhaustion
    Given no account with id "stream-auto-001" exists
    And an account "stream-auto-001" exists with balance 0.01
    When I start a stream "stream-at-001" on account "stream-auto-001" at rate 10.00 with idempotency key "ik-at-001"
    And the auto-termination scheduler processes stream "stream-at-001"
    Then stream "stream-at-001" has status "SETTLED" in the database
    And stream "stream-at-001" has reason "balance_exhaustion" in the database

  Scenario: Audit log entry for auto-terminated stream has reason balance_exhaustion
    Given no account with id "stream-auto-002" exists
    And an account "stream-auto-002" exists with balance 0.01
    When I start a stream "stream-at-002" on account "stream-auto-002" at rate 10.00 with idempotency key "ik-at-002"
    And the auto-termination scheduler processes stream "stream-at-002"
    Then stream "stream-at-002" has reason "balance_exhaustion" in the database

  Scenario: Auto-terminated stream settles actual elapsed ignoring minimum amount
    Given no account with id "stream-auto-003" exists
    And an account "stream-auto-003" exists with balance 0.01
    When I start a stream "stream-at-003" on account "stream-auto-003" at rate 10.00 with minimum amount 5.00 and idempotency key "ik-at-003"
    And the auto-termination scheduler processes stream "stream-at-003"
    Then stream "stream-at-003" has status "SETTLED" in the database
    And stream "stream-at-003" has settled amount less than 5.00 in the database
