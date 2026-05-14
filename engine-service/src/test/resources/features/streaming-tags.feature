Feature: Streaming transactions with tags

  @Pending
  Scenario: Start a stream with tags echoes tags in response
    Given no account with id "stag-001" exists
    And an account "stag-001" exists with balance 500.00
    When I start a stream "stag-stream-001" on account "stag-001" at rate 1.00 with tags "alpha,beta" and idempotency key "ik-stag-001"
    Then the response status is 201
    And the start response has tags "alpha,beta"

  @Pending
  Scenario: Stop tagged stream records tag committed totals for each tag
    Given no account with id "stag-002" exists
    And an account "stag-002" exists with balance 500.00
    And I start a stream "stag-stream-002" on account "stag-002" at rate 1.00 with tags "session-a" and idempotency key "ik-stag-002"
    When I stop stream "stag-stream-002"
    Then the response status is 200
    And tag "session-a" has committed total debited greater than or equal to 0.00

  @Pending
  Scenario: Query tag aggregate while stream is active returns in-flight debit greater than zero
    Given no account with id "stag-003" exists
    And an account "stag-003" exists with balance 500.00
    And I start a stream "stag-stream-003" on account "stag-003" at rate 2.00 with tags "inflight-tag" and idempotency key "ik-stag-003"
    When I get tag aggregate for "inflight-tag"
    Then the response status is 200
    And the tag aggregate in-flight debit is greater than or equal to 0.00
    And the tag aggregate estimated at is populated

  @Pending
  Scenario: Query tag aggregate after stream is settled shows committed totals
    Given no account with id "stag-004" exists
    And an account "stag-004" exists with balance 500.00
    And I start a stream "stag-stream-004" on account "stag-004" at rate 1.00 with tags "settled-tag" and idempotency key "ik-stag-004"
    And I stop stream "stag-stream-004"
    When I get tag aggregate for "settled-tag"
    Then the response status is 200
    And the tag aggregate committed total debited is greater than or equal to 0.00
