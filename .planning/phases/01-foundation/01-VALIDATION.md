---
phase: 1
slug: foundation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-13
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Cucumber 7.22.1 + JUnit 5 Platform + Testcontainers 1.21.3 |
| **Config file** | `engine-service/src/test/resources/junit-platform.properties` (Wave 0 creates) |
| **Quick run command** | `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i` |
| **Full suite command** | `./gradlew :engine-service:test` |
| **Estimated runtime** | ~60 seconds (Testcontainers container spin-up ~15s + test execution) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i`
- **After every plan wave:** Run `./gradlew :engine-service:test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 1-01-01 | 01 | 0 | ACCT-01 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 1-01-02 | 01 | 0 | ACCT-02 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 1-01-03 | 01 | 0 | ACCT-03, BAL-01 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 1-01-04 | 01 | 0 | FUND-01 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 1-01-05 | 01 | 0 | FUND-02 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 1-01-06 | 01 | 0 | FUND-03 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| 1-01-07 | 01 | 0 | BAL-03 | — | N/A | Cucumber integration | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `engine-service/src/test/resources/features/account-lifecycle.feature` — scenarios for ACCT-01, ACCT-02, ACCT-03, BAL-01
- [ ] `engine-service/src/test/resources/features/idempotency.feature` — scenarios for FUND-01
- [ ] `engine-service/src/test/resources/features/audit-log.feature` — scenarios for FUND-02
- [ ] `engine-service/src/test/resources/features/balance-floor.feature` — scenarios for BAL-03
- [ ] `engine-service/src/test/resources/features/observability.feature` — scenarios for FUND-03
- [ ] `engine-service/src/test/java/.../CucumberTestRunner.java` — `@Suite` + `@IncludeEngines("cucumber")` runner
- [ ] `engine-service/src/test/java/.../CucumberSpringConfiguration.java` — `@CucumberContextConfiguration` + `@SpringBootTest`
- [ ] `engine-service/src/test/java/.../TestcontainersConfiguration.java` — `@TestConfiguration` + `@Bean @ServiceConnection PostgreSQLContainer<?>`

---

## Cucumber Scenarios (Phase 1 Success Criteria)

### SC-1: Account Lifecycle (ACCT-01, ACCT-02, ACCT-03)
```gherkin
Feature: Account lifecycle
  Scenario: Create, retrieve, and close an account
    Given no account with id "acct-001" exists
    When I create an account with id "acct-001" and initial balance 100.00
    Then the account "acct-001" exists with committed balance 100.00
    When I close account "acct-001"
    Then account "acct-001" has status CLOSED

  Scenario: Close account with active streaming transactions is rejected
    Given account "acct-003" exists with an active streaming transaction
    When I close account "acct-003"
    Then the response status is 409
    And the error message mentions active streaming transactions
```

### SC-2: Idempotency (FUND-01)
```gherkin
Feature: Idempotency enforcement
  Scenario: Same idempotency key twice returns same result, one audit entry
    Given I have idempotency key "idem-abc-123"
    When I create an account with idempotency key "idem-abc-123" and balance 50.00
    And I create an account again with idempotency key "idem-abc-123" and balance 50.00
    Then both responses are identical
    And there is exactly 1 audit log entry for account creation
```

### SC-3: Audit Log (FUND-02)
```gherkin
Feature: Audit log immutability
  Scenario: Every balance change produces an immediate audit log entry
    When I create an account with id "audit-001" and initial balance 200.00
    Then the balance_audit_log table contains exactly 1 entry for account "audit-001"
    And the entry has operation "ACCOUNT_CREATED" and balance_after 200.00
```

### SC-4: Balance Floor (BAL-03)
```gherkin
Feature: Balance floor enforcement
  Scenario: Operation taking balance below global floor is rejected before write
    Given the global balance floor is 0
    And account "floor-001" has balance 10.00
    When I attempt to debit 15.00 from account "floor-001"
    Then the response status is 422
    And the balance of account "floor-001" remains 10.00
    And no audit log entry was created for that attempt

  Scenario: Per-account floor override takes precedence over global
    Given the global balance floor is 0
    And account "floor-002" has a balance floor of 50.00 and balance 60.00
    When I attempt to debit 15.00 from account "floor-002"
    Then the response status is 422
```

### SC-5: Observability (FUND-03)
```gherkin
Feature: Actuator and metrics endpoints
  Scenario: Health endpoint returns UP against live Postgres
    Given the application is started with a live Postgres instance via Testcontainers
    When I request GET /actuator/health
    Then the response status is 200
    And the response body contains "status": "UP"

  Scenario: Prometheus metrics endpoint is reachable
    Given the application is running
    When I request GET /actuator/prometheus
    Then the response status is 200
```

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Audit log row cannot be DELETEd via SQL | FUND-02 | DB-level GRANT/REVOKE is ops concern; not testable via app integration test | Connect to Postgres directly, attempt `DELETE FROM balance_audit_log WHERE ...`; verify permission denied |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
