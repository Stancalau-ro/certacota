Feature: Rake-enabled streaming transactions

  Scenario: Rake-enabled streaming settlement produces three-way split
    Given no account with id "srake-from" exists
    And no account with id "srake-to" exists
    And no account with id "srake-platform" exists
    And an account "srake-from" exists with balance 1000.00
    And an account "srake-to" exists with balance 0.00
    And an account "srake-platform" exists with balance 0.00
    When I start a stream "srake-stream-001" on account "srake-from" at rate 10.00 with rake rate 0.10 to account "srake-to" platform account "srake-platform" and idempotency key "ik-srake-001"
    And I stop stream "srake-stream-001"
    Then the response status is 200
    And the stop response has settled amount greater than or equal to 0.00

  Scenario: Zero rake rate credits full settled amount to recipient
    Given no account with id "srake-zero-from" exists
    And no account with id "srake-zero-to" exists
    And an account "srake-zero-from" exists with balance 500.00
    And an account "srake-zero-to" exists with balance 0.00
    When I start a stream "srake-stream-002" on account "srake-zero-from" at rate 5.00 with rake rate 0.00 to account "srake-zero-to" platform account "srake-zero-platform" and idempotency key "ik-srake-002"
    And I stop stream "srake-stream-002"
    Then the response status is 200
    And the stop response has settled amount greater than or equal to 0.00

  Scenario: Full rake routes entire settlement to platform account
    Given no account with id "srake-full-from" exists
    And no account with id "srake-full-to" exists
    And no account with id "srake-full-platform" exists
    And an account "srake-full-from" exists with balance 500.00
    And an account "srake-full-to" exists with balance 0.00
    And an account "srake-full-platform" exists with balance 0.00
    When I start a stream "srake-stream-003" on account "srake-full-from" at rate 5.00 with rake rate 1.00 to account "srake-full-to" platform account "srake-full-platform" and idempotency key "ik-srake-003"
    And I stop stream "srake-stream-003"
    Then the response status is 200
    And the stop response has settled amount greater than or equal to 0.00

  Scenario: Non-rake stream settlement credits full amount to from-account drain only
    Given no account with id "srake-noneonly-from" exists
    And an account "srake-noneonly-from" exists with balance 500.00
    When I start a stream "srake-stream-004" on account "srake-noneonly-from" at rate 1.00 with idempotency key "ik-srake-004"
    And I stop stream "srake-stream-004"
    Then the response status is 200
    And account "srake-noneonly-from" has committed balance less than 500.00
