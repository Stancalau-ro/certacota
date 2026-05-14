Feature: Stream start

  Scenario: Start stream with valid rate returns 201 with stream id echoed back
    Given no account with id "stream-start-001" exists
    And an account "stream-start-001" exists with balance 100.00
    When I start a stream "stream-s1-001" on account "stream-start-001" at rate 1.00 with idempotency key "ik-s1-001"
    Then the response status is 201
    And the stream response has stream id "stream-s1-001"
    And the stream response has account id "stream-start-001"

  Scenario: Start stream on non-existent account returns 404
    Given no account with id "stream-start-002" exists
    When I start a stream "stream-s1-002" on account "stream-start-002" at rate 1.00 with idempotency key "ik-s1-002"
    Then the response status is 404

  Scenario: Start stream on CLOSED account returns 409
    Given no account with id "stream-start-003" exists
    And an account "stream-start-003" exists with balance 100.00
    And account "stream-start-003" is closed
    When I start a stream "stream-s1-003" on account "stream-start-003" at rate 1.00 with idempotency key "ik-s1-003"
    Then the response status is 409

  Scenario: Start stream with idempotency key returns same response on duplicate request
    Given no account with id "stream-start-004" exists
    And an account "stream-start-004" exists with balance 100.00
    When I start a stream "stream-s1-004" on account "stream-start-004" at rate 1.00 with idempotency key "ik-s1-004"
    And I start a stream "stream-s1-004" on account "stream-start-004" at rate 1.00 with idempotency key "ik-s1-004"
    Then the response status is 201
    And the stream response has stream id "stream-s1-004"
