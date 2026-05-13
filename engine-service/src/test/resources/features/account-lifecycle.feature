Feature: Account lifecycle

  Scenario: Create, retrieve, and close an account
    Given no account with id "acct-001" exists
    When I create an account with id "acct-001" and initial balance 100.00
    Then the account "acct-001" exists with committed balance 100.00
    When I close account "acct-001"
    Then account "acct-001" has status CLOSED

  @Pending
  Scenario: Close account with active streaming transactions is rejected
    Given account "acct-003" exists with an active streaming transaction
    When I close account "acct-003"
    Then the response status is 409
    And the error message mentions active streaming transactions
