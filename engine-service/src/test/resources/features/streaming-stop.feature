Feature: Stream stop

  Scenario: Start then stop returns 200 with settled amount and correct reason
    Given no account with id "stream-stop-001" exists
    And an account "stream-stop-001" exists with balance 100.00
    And I start a stream "stream-sp-001" on account "stream-stop-001" at rate 1.00 with idempotency key "ik-sp-001"
    When I stop stream "stream-sp-001"
    Then the response status is 200
    And the stop response has reason "stop endpoint call"
    And the stop response has settled amount greater than or equal to 0.00

  Scenario: Stop non-existent stream id returns 404
    Given no account with id "stream-stop-002" exists
    When I stop stream "stream-sp-nonexistent"
    Then the response status is 404

  Scenario: Settled stream shows status SETTLED in Postgres
    Given no account with id "stream-stop-003" exists
    And an account "stream-stop-003" exists with balance 100.00
    And I start a stream "stream-sp-003" on account "stream-stop-003" at rate 1.00 with idempotency key "ik-sp-003"
    When I stop stream "stream-sp-003"
    Then the response status is 200
    And stream "stream-sp-003" has status "SETTLED" in the database

  Scenario: Stop stream charges actual elapsed amount
    Given no account with id "stream-stop-004" exists
    And an account "stream-stop-004" exists with balance 100.00
    And I start a stream "stream-sp-004" on account "stream-stop-004" at rate 2.00 with idempotency key "ik-sp-004"
    When I stop stream "stream-sp-004"
    Then the response status is 200
    And account "stream-stop-004" has committed balance less than 100.00
