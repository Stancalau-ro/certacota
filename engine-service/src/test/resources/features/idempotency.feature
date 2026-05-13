Feature: Idempotency enforcement

  Scenario: Same idempotency key twice returns same result, one audit entry
    Given I have idempotency key "idem-abc-123"
    When I create an account with idempotency key "idem-abc-123" and balance 50.00
    And I create an account again with idempotency key "idem-abc-123" and balance 50.00
    Then both responses are identical
    And there is exactly 1 audit log entry for account creation
