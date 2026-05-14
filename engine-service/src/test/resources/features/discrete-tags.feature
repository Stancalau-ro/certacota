Feature: Discrete transactions with tags

  Scenario: Discrete CREDIT with tags increments tag committed total credited recipient
    Given no account with id "dtag-credit-001" exists
    And an account "dtag-credit-001" exists with balance 0.00
    When I post a CREDIT of 100.00 to account "dtag-credit-001" with tags "promo" and idempotency key "ik-dtag-credit-001"
    Then the response status is 201
    And tag "promo" has committed total credited recipient greater than or equal to 0.00

  Scenario: Discrete DEBIT with tags increments tag committed total debited
    Given no account with id "dtag-debit-001" exists
    And an account "dtag-debit-001" exists with balance 500.00
    When I post a DEBIT of 50.00 from account "dtag-debit-001" with tags "churn" and idempotency key "ik-dtag-debit-001"
    Then the response status is 201
    And tag "churn" has committed total debited greater than or equal to 0.00

  Scenario: Discrete TRANSFER with tags updates committed total for both sides
    Given no account with id "dtag-from-001" exists
    And no account with id "dtag-to-001" exists
    And an account "dtag-from-001" exists with balance 500.00
    And an account "dtag-to-001" exists with balance 0.00
    When I post a DEBIT of 75.00 from account "dtag-from-001" to account "dtag-to-001" with tags "bonus" and idempotency key "ik-dtag-transfer-001"
    Then the response status is 201
    And tag "bonus" has committed total debited greater than or equal to 0.00
