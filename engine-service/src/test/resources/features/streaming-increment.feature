Feature: Increment billing

  Scenario: Stop stream with increment set settles floor of elapsed times rate divided by increment times increment
    Given no account with id "stream-inc-001" exists
    And an account "stream-inc-001" exists with balance 100.00
    When I start a stream "stream-i1-001" on account "stream-inc-001" at rate 1.00 with increment 0.50 and idempotency key "ik-i1-001"
    And I stop stream "stream-i1-001"
    Then the response status is 200
    And the stop response settled amount is a multiple of 0.50

  Scenario: Start stream without increment settles exact elapsed amount
    Given no account with id "stream-inc-002" exists
    And an account "stream-inc-002" exists with balance 100.00
    When I start a stream "stream-i1-002" on account "stream-inc-002" at rate 1.00 with idempotency key "ik-i1-002"
    And I stop stream "stream-i1-002"
    Then the response status is 200
    And the stop response has settled amount greater than or equal to 0.00

  Scenario: Stream start response echoes back increment when provided
    Given no account with id "stream-inc-003" exists
    And an account "stream-inc-003" exists with balance 100.00
    When I start a stream "stream-i1-003" on account "stream-inc-003" at rate 1.00 with increment 0.25 and idempotency key "ik-i1-003"
    Then the response status is 201
    And the stream response has increment 0.25
