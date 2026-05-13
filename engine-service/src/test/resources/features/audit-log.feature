Feature: Audit log immutability

  Scenario: Every balance change produces an immediate audit log entry
    When I create an account with id "audit-001" and initial balance 200.00
    Then the balance_audit_log table contains exactly 1 entry for account "audit-001"
    And the entry has operation "ACCOUNT_CREATED" and balance_after 200.00
