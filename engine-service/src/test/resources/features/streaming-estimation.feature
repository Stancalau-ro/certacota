Feature: Forward balance estimation

  Scenario: GET estimated balance with one active stream returns estimated balance less than committed
    Given no account with id "stream-est-001" exists
    And an account "stream-est-001" exists with balance 100.00
    And I start a stream "stream-e1-001" on account "stream-est-001" at rate 1.00 with idempotency key "ik-e1-001"
    When I get estimated balance for account "stream-est-001"
    Then the response status is 200
    And the estimation response has committed balance 100.00
    And the estimation response has estimated balance less than committed balance
    And the estimation response has estimatedAt populated
    And the estimation response has estimatedDrainAt populated

  Scenario: GET estimated balance with no active streams returns estimated balance equal to committed
    Given no account with id "stream-est-002" exists
    And an account "stream-est-002" exists with balance 100.00
    When I get estimated balance for account "stream-est-002"
    Then the response status is 200
    And the estimation response has committed balance 100.00
    And the estimation response has estimated balance equal to committed balance
    And the estimation response has estimatedDrainAt null

  Scenario: Estimation response includes committed balance equal to Postgres committed balance
    Given no account with id "stream-est-003" exists
    And an account "stream-est-003" exists with balance 75.00
    When I get estimated balance for account "stream-est-003"
    Then the response status is 200
    And the estimation response has committed balance 75.00
