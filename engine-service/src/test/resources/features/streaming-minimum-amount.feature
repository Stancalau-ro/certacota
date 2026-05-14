Feature: Minimum amount enforcement

  Scenario: Stop stream before minimum amount is reached settles minimum amount
    Given no account with id "stream-min-001" exists
    And an account "stream-min-001" exists with balance 100.00
    When I start a stream "stream-m1-001" on account "stream-min-001" at rate 0.001 with minimum amount 10.00 and idempotency key "ik-m1-001"
    And I stop stream "stream-m1-001"
    Then the response status is 200
    And the stop response has settled amount 10.00

  Scenario: Stop with ignoreMinimum true settles actual elapsed when less than minimum
    Given no account with id "stream-min-002" exists
    And an account "stream-min-002" exists with balance 100.00
    When I start a stream "stream-m1-002" on account "stream-min-002" at rate 0.001 with minimum amount 10.00 and idempotency key "ik-m1-002"
    And I stop stream "stream-m1-002" with ignoreMinimum true
    Then the response status is 200
    And the stop response has settled amount less than 10.00

  Scenario: Start stream without minimumAmount parameter has no minimumAmount in response
    Given no account with id "stream-min-003" exists
    And an account "stream-min-003" exists with balance 100.00
    When I start a stream "stream-m1-003" on account "stream-min-003" at rate 1.00 with idempotency key "ik-m1-003"
    Then the response status is 201
    And the stream response has no minimum amount
