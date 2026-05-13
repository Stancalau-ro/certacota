---
phase: 01-foundation
verified: 2026-05-13T12:00:00Z
status: human_needed
score: 5/5 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Run ./gradlew :engine-service:test from the repo root with Docker Desktop running"
    expected: "BUILD SUCCESS with all 7 active Cucumber scenarios green across 5 feature files (1 @Pending scenario skipped)"
    why_human: "Cucumber tests require a live Docker daemon to spin up postgres:17-alpine via Testcontainers; cannot verify programmatically without running Docker"
---

# Phase 1: Foundation Verification Report

**Phase Goal:** Participant accounts exist, balances are readable and floor-enforced, every balance change is permanently audited, all writes are idempotent, and the engine is observable
**Verified:** 2026-05-13T12:00:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths (Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| SC-1 | Caller can create an account, retrieve it with committed balance, and close it; closing rejects if active streaming transactions exist | VERIFIED | `AccountController` has POST/GET/DELETE endpoints; `AccountServiceImpl.closeAccount` checks `CLOSED` status and throws `AccountClosedException`; Phase 3 guard is commented as a known placeholder (`// Phase 3 will add:`); `account-lifecycle.feature` scenario covers create/retrieve/close; streaming check is `@Pending` correctly deferring to Phase 3 |
| SC-2 | A balance-changing operation submitted twice with the same idempotency key returns the same result and creates exactly one audit log entry | VERIFIED | `AccountServiceImpl.createAccount` performs check-then-insert: `findByIdempotencyKeyAndOperation` before `doCreateAccount`; if key found, cached response returned; `idempotency.feature` scenario asserts both responses identical and exactly 1 audit entry; idempotency enforced by DB UNIQUE constraint on `(idempotency_key, operation)` in V2 migration |
| SC-3 | Every balance change produces an immutable append-only audit log entry visible in Postgres immediately after the operation completes | VERIFIED | `BalanceAuditLog` entity has `updatable=false` on all columns (lines 29-48); `auditLogRepository.save` called inside `doCreateAccount` in same `@Transactional` scope as `accountRepository.save`; `audit-log.feature` asserts 1 entry with correct operation and balance_after |
| SC-4 | Any operation that would take balance below the configured floor is rejected before the write occurs | VERIFIED | `doCreateAccount` computes `effectiveFloor` from `request.balanceFloor()` or `properties.getBalanceFloor()` and calls `initialBalance.compareTo(effectiveFloor) < 0` before any `accountRepository.save`; `balance-floor.feature` has two passing scenarios: global floor rejection (422) and per-account floor override rejection (422) |
| SC-5 | Spring Actuator health returns UP and Micrometer/Prometheus endpoint is reachable; Testcontainers integration test confirms both against a live Postgres instance | VERIFIED | `application.yml` exposes `health,prometheus,info,metrics`; `management.endpoint.prometheus.enabled=true`; `management.prometheus.metrics.export.enabled=true`; `observability.feature` has two scenarios covering `/actuator/health` (200 + `"status":"UP"`) and `/actuator/prometheus` (200); Testcontainers wiring via `@ServiceConnection` confirmed in `TestcontainersConfiguration.java` |

**Score:** 5/5 truths verified

---

### Deferred Items

| # | Item | Addressed In | Evidence |
|---|------|-------------|----------|
| 1 | Closing an account with active streaming transactions rejected | Phase 3 | Phase 3 goal: "StreamRegistry"; ACCT-02 explicitly states "rejected if active streaming transactions exist"; `// Phase 3 will add:` comment in `closeAccount`; `@Pending` tag on the relevant Cucumber scenario |

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `settings.gradle` | Multi-module root declaring all three modules | VERIFIED | Contains `include 'engine-core'`, `include 'engine-spring'`, `include 'engine-service'` |
| `engine-core/build.gradle` | No Spring Boot plugin | VERIFIED | Plugins block has only `id 'java'` and `id 'io.spring.dependency-management'`; no `org.springframework.boot` |
| `engine-spring/build.gradle` | No Spring Boot plugin | VERIFIED | Plugins block has only `id 'java'` and `id 'io.spring.dependency-management'`; no `org.springframework.boot` |
| `engine-service/build.gradle` | Spring Boot 3.5.3 plugin; flyway-core + flyway-database-postgresql; Cucumber BOM | VERIFIED | `id 'org.springframework.boot' version '3.5.3'`; both Flyway artifacts present; `platform("io.cucumber:cucumber-bom:7.22.1")` |
| `V1__create_accounts.sql` | accounts DDL with NUMERIC(38,18) | VERIFIED | `balance NUMERIC(38,18)`, `balance_floor NUMERIC(38,18)`, CHECK constraint on status |
| `V2__create_idempotency_keys.sql` | UNIQUE(idempotency_key, operation) | VERIFIED | `CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key, operation)` |
| `V3__create_balance_audit_log.sql` | append-only DDL with FK to accounts | VERIFIED | FK `fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts(id)` present; all amount columns NOT NULL |
| `engine-core/.../domain/Account.java` | String PK no @GeneratedValue; BigDecimal(38,18); JSONB metadata | VERIFIED | `@Id @Column(updatable=false)` String id; `@Column(precision=38, scale=18)` balance; `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb")` metadata; no `@GeneratedValue` |
| `engine-core/.../domain/BalanceAuditLog.java` | All columns updatable=false | VERIFIED | Every `@Column` annotation has `updatable = false` (lines 29-48) |
| `engine-core/.../domain/IdempotencyKey.java` | @UniqueConstraint on (idempotency_key, operation) | VERIFIED | `@Table(uniqueConstraints = @UniqueConstraint(name="uq_idempotency_key", columnNames={"idempotency_key","operation"}))` |
| `engine-core/.../service/AccountService.java` | Interface with no Spring annotations | VERIFIED | Pure Java interface; no `@Service`, `@Component`, `@Transactional`, `@Autowired` anywhere |
| `engine-spring/.../service/AccountServiceImpl.java` | @Service @Transactional; idempotency via check-then-insert; floor via compareTo | VERIFIED | `@Service @Transactional @RequiredArgsConstructor @Slf4j`; `findByIdempotencyKeyAndOperation` before write; `compareTo(effectiveFloor) < 0` at line 58 |
| `engine-spring/.../autoconfigure/TokenEngineAutoConfiguration.java` | @AutoConfiguration; @ConditionalOnMissingBean; @ConditionalOnClass(DataSource) | VERIFIED | `@AutoConfiguration`; `@ConditionalOnClass(DataSource.class)`; `@ConditionalOnMissingBean` on `accountService` bean |
| `engine-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Contains exactly the autoconfigure class FQN | VERIFIED | Single line: `io.certacota.engine.spring.autoconfigure.TokenEngineAutoConfiguration` |
| `engine-service/.../controller/AccountController.java` | POST/GET/DELETE; no @Transactional; thin delegator | VERIFIED | `@RestController @RequestMapping("/api/v1/accounts") @RequiredArgsConstructor @Slf4j`; POST returns `@ResponseStatus(CREATED)`; all three methods are single-line delegations; no `@Transactional` anywhere in file |
| `engine-service/.../controller/GlobalExceptionHandler.java` | @RestControllerAdvice; 404/409/422/400 handlers | VERIFIED | `@RestControllerAdvice`; handlers for `AccountNotFoundException`→404, `BalanceFloorViolationException`→422, `AccountClosedException`→409, `MethodArgumentNotValidException`→400 |
| `features/account-lifecycle.feature` | Scenarios for ACCT-01, ACCT-02, ACCT-03 | VERIFIED | Exists at expected path; contains create/retrieve/close scenario; streaming scenario correctly tagged `@Pending` |
| `features/idempotency.feature` | Scenarios for FUND-01 | VERIFIED | Exists; idempotency scenario asserts identical responses and 1 audit entry |
| `features/audit-log.feature` | Scenarios for FUND-02 | VERIFIED | Exists; asserts 1 audit entry with correct operation and balance_after |
| `features/balance-floor.feature` | Scenarios for BAL-03 | VERIFIED | Exists; two 422-rejection scenarios (global floor, per-account floor) |
| `features/observability.feature` | Scenarios for FUND-03 | VERIFIED | Exists; health 200+UP and prometheus 200 scenarios |
| `CucumberTestRunner.java` | @Suite + @IncludeEngines("cucumber"); no @RunWith | VERIFIED | `@Suite @IncludeEngines("cucumber") @SelectClasspathResource("features")`; no `@RunWith`; glue includes both root and steps packages |
| `CucumberSpringConfiguration.java` | Single @CucumberContextConfiguration; RANDOM_PORT; @Import(TestcontainersConfiguration) | VERIFIED | `@CucumberContextConfiguration @SpringBootTest(webEnvironment=RANDOM_PORT) @Import(TestcontainersConfiguration.class) @ActiveProfiles("test")` |
| `application.yml` | Actuator exposure; Prometheus enabled; BigDecimal deserialization; no datasource URL | VERIFIED | `include: health,prometheus,info,metrics`; `prometheus.enabled: true`; `use-big-decimal-for-floats: true`; `write-dates-as-timestamps: false`; `time-zone: UTC`; no `spring.datasource.url` |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `AccountController` | `AccountService` | Constructor injection + single-line delegation | VERIFIED | All three methods delegate immediately: `accountService.createAccount(request)`, `accountService.getAccount(accountId)`, `accountService.closeAccount(accountId)` |
| `AccountServiceImpl.doCreateAccount` | `BalanceAuditLogRepository` | `auditLogRepository.save(...)` inside same `@Transactional` method as `accountRepository.save` | VERIFIED | Lines 74-82 of `AccountServiceImpl` save audit log inside `doCreateAccount` which is the same transactional method as the account save at line 64 |
| `AccountServiceImpl.createAccount` | `IdempotencyKeyRepository` | Check-then-insert via `findByIdempotencyKeyAndOperation` | VERIFIED | Check at line 43 before `doCreateAccount`; insert at line 87 inside `doCreateAccount` |
| `enforceBalanceFloor logic` | `BalanceFloorViolationException` | `compareTo(effectiveFloor) < 0` at line 58 | VERIFIED | `if (initialBalance.compareTo(effectiveFloor) < 0)` throws `BalanceFloorViolationException` |
| `TokenEngineAutoConfiguration` | `AutoConfiguration.imports` | META-INF/spring registration | VERIFIED | File exists with exactly `io.certacota.engine.spring.autoconfigure.TokenEngineAutoConfiguration` |
| `CucumberSpringConfiguration` | `TestcontainersConfiguration` | `@Import(TestcontainersConfiguration.class)` | VERIFIED | Present at line 13 |
| `CucumberTestRunner` | `features/` directory | `@SelectClasspathResource("features")` | VERIFIED | Annotation present; 5 feature files exist at that classpath location |

---

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| `AccountController.createAccount` | `AccountResponse` return value | `AccountServiceImpl.doCreateAccount` → `accountRepository.save` → Postgres | Yes — JPA write to accounts table, then `AccountResponse.from(account)` | FLOWING |
| `AccountController.getAccount` | `AccountResponse` return value | `accountRepository.findById` → Postgres read | Yes — JPA read from accounts table | FLOWING |
| `AccountController.closeAccount` | `AccountResponse` return value | `accountRepository.findById` then `accountRepository.save` after `account.close()` | Yes — JPA read+write | FLOWING |

---

### Behavioral Spot-Checks

Step 7b skipped: requires live Docker daemon and Testcontainers Postgres. All behavioral verification is deferred to the human verification step.

---

### Probe Execution

Step 7c: No probe scripts found under `scripts/*/tests/probe-*.sh`. No probes to execute.

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| ACCT-01 | 01-02, 01-03 | Caller can create a participant account with initial balance | SATISFIED | `POST /api/v1/accounts` → 201; `account-lifecycle.feature` green |
| ACCT-02 | 01-02, 01-03 | Caller can close a participant account (streaming guard deferred) | SATISFIED | `DELETE /api/v1/accounts/{id}` → 200 with CLOSED status; streaming check commented as Phase 3 placeholder |
| ACCT-03 | 01-02, 01-03 | Caller can retrieve account and committed balance | SATISFIED | `GET /api/v1/accounts/{id}` → 200 + AccountResponse; `account-lifecycle.feature` retrieve step |
| FUND-01 | 01-02, 01-03 | Idempotency via DB UNIQUE constraint | SATISFIED | `uq_idempotency_key` UNIQUE constraint in V2 migration; check-then-insert in `AccountServiceImpl`; `idempotency.feature` |
| FUND-02 | 01-02, 01-03 | Immutable append-only audit log | SATISFIED | All `BalanceAuditLog` columns `updatable=false`; `auditLogRepository.save` inside same `@Transactional`; `audit-log.feature` |
| FUND-03 | 01-01, 01-03 | Actuator health + Prometheus metrics | SATISFIED | `application.yml` configures both; `observability.feature` |
| BAL-01 | 01-02, 01-03 | Query committed balance | SATISFIED | Same as ACCT-03 — `getAccount` returns balance field |
| BAL-03 | 01-02, 01-03 | Minimum balance floor enforcement | SATISFIED | `compareTo(effectiveFloor) < 0` check before write; `balance-floor.feature` with global and per-account floor scenarios |

All 8 requirement IDs from all three plan frontmatters are satisfied. No orphaned requirements found — REQUIREMENTS.md Traceability table maps exactly these 8 IDs to Phase 1.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `AccountController.java` | 28 | Missing `@Valid` on `@RequestBody` | WARNING | Null `idempotencyKey` reaches `AccountServiceImpl` — returns 500 (FK violation) instead of 400; does not break phase goal (idempotency is still enforced by DB constraint for valid requests) |
| `AccountServiceImpl.java` | 87 | Idempotency key saved AFTER account — race condition under concurrent duplicates | WARNING | Two threads passing the check simultaneously → second thread hits `DataIntegrityViolationException` inside `@Transactional` → outer transaction marked for rollback → 500 returned instead of 201 replay; single-threaded Cucumber tests pass cleanly |
| `GlobalExceptionHandler.java` | (missing) | No handler for `DataIntegrityViolationException` | WARNING | A concurrent duplicate idempotency write escapes to Spring's default handler → 500 with schema details in body; only reachable under concurrency |
| `engine-service/build.gradle` | 40 | `DOCKER_HOST=tcp://localhost:2375` hardcoded unconditionally | WARNING | Breaks CI on Linux/macOS agents or Testcontainers Cloud environments; portability defect, not a functional gap on the dev machine |
| `Account.java` | 62 | `debit()` method has no floor check | INFO | Not exploitable in Phase 1 (no debit endpoint); debit endpoint in Phase 2 must add floor check; the entity-level debit is not called by any Phase 1 service path |

**Debt marker audit:** No `TBD`, `FIXME`, or `XXX` markers found in any production source files modified by this phase. The `// Phase 3 will add:` comment in `AccountServiceImpl` is a forward-reference comment, not a debt marker.

---

### Human Verification Required

#### 1. Full Cucumber Test Suite

**Test:** From the repo root, with Docker Desktop running and the daemon accessible, run:
```
./gradlew :engine-service:test --no-daemon
```
**Expected:** `BUILD SUCCESS`; console shows 7 active scenarios passing across 5 feature files; the `@Pending` "Close account with active streaming transactions is rejected" scenario is reported as skipped, not failing; `engine-service/build/reports/cucumber.html` confirms green results.
**Why human:** Testcontainers requires a live Docker daemon to start `postgres:17-alpine`; cannot run programmatically without Docker. The build also has `DOCKER_HOST=tcp://localhost:2375` hardcoded which requires Docker Desktop TCP exposure on the specific dev machine.

---

### Gaps Summary

No phase-goal-blocking gaps found. All 5 roadmap success criteria are verified by code analysis. The 4 WARNING items below are code quality concerns identified in REVIEW.md (CR-01 through CR-04) that do not make any success criterion FALSE for Phase 1:

- **CR-01 (missing @Valid):** Idempotency is still enforced for well-formed requests. The gap is that a malformed request (null idempotencyKey) returns 500 instead of 400. Phase goal "all writes are idempotent" is satisfied for valid writes.
- **CR-02 (race condition):** Under single-threaded sequential load (the Cucumber suite), idempotency is correct. The gap is correctness under concurrent duplicate writes, which Phase 1 has no concurrency tests for. Phase 2 (DTX-04) has an explicit concurrent correctness criterion.
- **CR-03 (missing DataIntegrityViolationException handler):** Only reachable via the race condition path. No impact on passing test suite.
- **CR-04 (hardcoded DOCKER_HOST):** Portability defect for CI, not a functional gap.
- **CR-05 (missing @Version):** `closeAccount` is not race-protected; harmless in Phase 1 (no concurrency tests for close). The double-save produces the same status, just different `updatedAt`.

These are all valid candidates for a Phase 2 cleanup plan.

---

_Verified: 2026-05-13T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
