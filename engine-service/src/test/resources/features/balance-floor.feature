Feature: Balance floor enforcement

  Scenario: Creating account with initial balance below global floor is rejected
    Given the global balance floor is 0
    When I attempt to create an account with id "floor-001" and initialBalance -5.00
    Then the response status is 422

  Scenario: Per-account floor override takes precedence over global
    Given the global balance floor is 0
    When I attempt to create an account with id "floor-002" and initialBalance 40.00 and balanceFloor 50.00
    Then the response status is 422
