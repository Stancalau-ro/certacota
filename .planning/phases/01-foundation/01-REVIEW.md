---
phase: 01-foundation
reviewed: 2026-05-13T00:00:00Z
depth: standard
files_reviewed: 47
files_reviewed_list:
  - settings.gradle
  - build.gradle
  - engine-core/build.gradle
  - engine-spring/build.gradle
  - engine-service/build.gradle
  - engine-service/src/main/java/io/certacota/engine/service/EngineServiceApplication.java
  - engine-service/src/main/resources/application.yml
  - engine-service/src/main/resources/application-test.yml
  - engine-service/src/main/resources/db/migration/V1__create_accounts.sql
  - engine-service/src/main/resources/db/migration/V2__create_idempotency_keys.sql
  - engine-service/src/main/resources/db/migration/V3__create_balance_audit_log.sql
  - engine-service/src/test/java/io/certacota/engine/service/TestcontainersConfiguration.java
  - engine-service/src/test/java/io/certacota/engine/service/CucumberTestRunner.java
  - engine-service/src/test/java/io/certacota/engine/service/CucumberSpringConfiguration.java
  - engine-service/src/test/resources/junit-platform.properties
  - engine-core/src/main/java/io/certacota/engine/core/domain/Account.java
  - engine-core/src/main/java/io/certacota/engine/core/domain/AccountStatus.java
  - engine-core/src/main/java/io/certacota/engine/core/domain/BalanceAuditLog.java
  - engine-core/src/main/java/io/certacota/engine/core/domain/IdempotencyKey.java
  - engine-core/src/main/java/io/certacota/engine/core/repository/AccountRepository.java
  - engine-core/src/main/java/io/certacota/engine/core/repository/BalanceAuditLogRepository.java
  - engine-core/src/main/java/io/certacota/engine/core/repository/IdempotencyKeyRepository.java
  - engine-core/src/main/java/io/certacota/engine/core/service/AccountService.java
  - engine-core/src/main/java/io/certacota/engine/core/dto/CreateAccountRequest.java
  - engine-core/src/main/java/io/certacota/engine/core/dto/AccountResponse.java
  - engine-core/src/main/java/io/certacota/engine/core/exception/AccountNotFoundException.java
  - engine-core/src/main/java/io/certacota/engine/core/exception/BalanceFloorViolationException.java
  - engine-core/src/main/java/io/certacota/engine/core/exception/DuplicateIdempotencyKeyException.java
  - engine-core/src/main/java/io/certacota/engine/core/exception/AccountClosedException.java
  - engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java
  - engine-spring/src/main/java/io/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java
  - engine-spring/src/main/java/io/certacota/engine/spring/config/TokenEngineProperties.java
  - engine-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  - engine-service/src/main/java/io/certacota/engine/service/controller/AccountController.java
  - engine-service/src/main/java/io/certacota/engine/service/controller/GlobalExceptionHandler.java
  - engine-service/src/test/resources/features/account-lifecycle.feature
  - engine-service/src/test/resources/features/idempotency.feature
  - engine-service/src/test/resources/features/audit-log.feature
  - engine-service/src/test/resources/features/balance-floor.feature
  - engine-service/src/test/resources/features/observability.feature
  - engine-service/src/test/java/io/certacota/engine/service/steps/AccountSteps.java
  - engine-service/src/test/java/io/certacota/engine/service/steps/IdempotencySteps.java
  - engine-service/src/test/java/io/certacota/engine/service/steps/AuditLogSteps.java
  - engine-service/src/test/java/io/certacota/engine/service/steps/BalanceFloorSteps.java
  - engine-service/src/test/java/io/certacota/engine/service/steps/ActuatorSteps.java
  - engine-service/src/test/resources/testcontainers.properties
  - engine-service/src/test/resources/junit-platform.properties
findings:
  critical: 5
  warning: 8
  info: 4
  total: 17
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-05-13T00:00:00Z
**Depth:** standard
**Files Reviewed:** 47
**Status:** issues_found

## Summary

This review covers the Phase 1 Foundation of the token economy engine: domain model, repositories, service implementation, REST controller, autoconfiguration, Flyway migrations, and BDD test scaffolding.

The overall structure is sound. BigDecimal comparisons use `compareTo` correctly, the audit log entity has `updatable = false` on all columns, and the autoconfigure registration file is present and correct. Several serious defects were found, however.

The most critical issue is the idempotency implementation: the check-then-insert logic in `AccountServiceImpl` is not protected against a race condition because `@Transactional` on the class is a write transaction, not a serializable one, and the unique constraint is the only real guard — but when the constraint fires it rolls back the entire transaction, including the already-persisted account and audit log, rather than surfacing a clean idempotent replay. A second critical issue is that `CreateAccountRequest` has no input validation (`@NotBlank`, `@NotNull`), meaning the service is called with a `null` idempotency key, which causes a `NullPointerException` inside the idempotency lookup query. The hardcoded `DOCKER_HOST=tcp://localhost:2375` in `build.gradle` will silently break CI on any machine that does not have a TCP-exposed daemon. There are also missing HTTP status codes and an unhandled exception type.

---

## Critical Issues

### CR-01: Null idempotency key causes NullPointerException in production path

**File:** `engine-core/src/main/java/io/certacota/engine/core/dto/CreateAccountRequest.java:6-13`

**Issue:** `CreateAccountRequest` is a plain record with no Bean Validation annotations. `idempotencyKey` is not annotated `@NotBlank` and the controller does not annotate `@RequestBody` with `@Valid`. A request omitting `idempotencyKey` will reach `AccountServiceImpl.createAccount`, which calls `idempotencyKeyRepository.findByIdempotencyKeyAndOperation(null, "ACCOUNT_CREATE")`. Spring Data will pass a null bind parameter into the query. Depending on the JPA provider, this either throws `IllegalArgumentException` (Hibernate) or silently passes, but the subsequent `idempotencyKeyRepository.save` will then attempt to insert a row with `idempotency_key = NULL`, which the `NOT NULL` column constraint rejects with a `DataIntegrityViolationException` that is not mapped by `GlobalExceptionHandler` — resulting in a 500 to the caller with a raw Spring exception body that exposes schema information. The `audit_log` row and the `accounts` row are both rolled back.

**Fix:**

In `CreateAccountRequest`:
```java
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(
    @NotBlank String id,
    BigDecimal initialBalance,
    BigDecimal balanceFloor,
    Map<String, Object> metadata,
    @NotBlank String idempotencyKey
) {}
```

In `AccountController`:
```java
public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
```

---

### CR-02: Idempotency check-then-insert rolls back account on duplicate key — idempotency silently broken under concurrency

**File:** `engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java:51-98`

**Issue:** `doCreateAccount` does the following in order: (1) saves the `Account`, (2) saves the `BalanceAuditLog`, (3) attempts to save the `IdempotencyKey`. The entire method runs inside a single `@Transactional` (READ_COMMITTED isolation by default). Under concurrent requests with the same idempotency key, both threads may pass the initial `findByIdempotencyKeyAndOperation` check (neither has committed yet), both proceed to save accounts, and both attempt to save the idempotency key. The second one hits the unique constraint; Hibernate translates that into a `DataIntegrityViolationException`. Because this is thrown inside the active transaction, Spring marks the transaction for rollback. The outer `try/catch` at line 93 catches the exception and rethrows as `IllegalStateException`, but the transaction is already poisoned and will roll back — taking the newly created account and the audit log with it. The caller receives a 500 rather than a clean idempotent 201. Additionally, the `DuplicateIdempotencyKeyException` that already exists in the codebase is never thrown here.

**Fix:** Restructure so the idempotency key is saved first (in a separate nested `REQUIRES_NEW` transaction), and the account save only proceeds if that succeeds. Alternatively, use `INSERT ... ON CONFLICT DO NOTHING` via a native query, or make the idempotency save a mandatory first step before any other writes:

```java
// Step 1: claim the idempotency slot in its own transaction (REQUIRES_NEW)
// If the slot is already taken, read back and return the cached response immediately.
// Step 2: only if slot was newly claimed, proceed to create account + audit log.
```

A minimal safe approach:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
IdempotencyKey claimSlot(String key, String operation) {
    // saveAndFlush — will throw DataIntegrityViolationException if duplicate
    // caller catches that, fetches existing row, and returns cached response
}
```

---

### CR-03: `DuplicateIdempotencyKeyException` is declared but never thrown; concurrent duplicate keys return 500 instead of 409

**File:** `engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java:93-95` and `engine-service/src/main/java/io/certacota/engine/service/controller/GlobalExceptionHandler.java`

**Issue:** `DuplicateIdempotencyKeyException` exists in the core module and `GlobalExceptionHandler` has no handler for it. When a concurrent duplicate write occurs (per CR-02 above), the actual exception that escapes to the controller is either `IllegalStateException` (from line 94) or `DataIntegrityViolationException`. Neither is handled by `GlobalExceptionHandler`, so Spring's default handler returns a 500 with a full stack trace and Spring internals in the response body. This leaks implementation details (table name `idempotency_keys`, constraint name `uq_idempotency_key`, JDBC driver version).

**Fix:** Add a handler for `DataIntegrityViolationException` (and throw `DuplicateIdempotencyKeyException` explicitly from the idempotency save path where appropriate):

```java
@ExceptionHandler(DataIntegrityViolationException.class)
@ResponseStatus(HttpStatus.CONFLICT)
public Map<String, String> handleDataIntegrity(DataIntegrityViolationException ex) {
    log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
    return Map.of("error", "Conflict: resource already exists");
}
```

---

### CR-04: Hardcoded `DOCKER_HOST=tcp://localhost:2375` overrides environment in CI

**File:** `engine-service/build.gradle:40`

**Issue:** The `test` task sets `environment 'DOCKER_HOST', 'tcp://localhost:2375'` unconditionally. On any CI agent that uses Docker Desktop with a Unix socket, uses a remote Docker daemon (e.g., DinD via `unix:///var/run/docker.sock`), or uses Testcontainers Cloud, this environment variable overrides the actual daemon location and every Testcontainers-based test fails with a connection error. Combined with `testcontainers.properties` pinning `DockerDesktopClientProviderStrategy`, tests that pass on a developer's Windows machine will consistently fail in CI or on Linux/macOS without a workaround.

**Fix:** Remove the hardcoded `DOCKER_HOST` from `build.gradle`. Configure it externally in CI if needed, or use the `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` and `DOCKER_HOST` environment variables at the CI job level:

```groovy
// Remove this line from build.gradle:
// environment 'DOCKER_HOST', 'tcp://localhost:2375'
```

Also update `testcontainers.properties` to not pin a Windows-only strategy:

```properties
# Remove or guard platform-specific strategy:
# docker.client.strategy=org.testcontainers.dockerclient.DockerDesktopClientProviderStrategy
```

---

### CR-05: `closeAccount` returns 200 instead of 200-with-204 convention — but more critically, no HTTP 404 differentiation when account does not exist for DELETE

**File:** `engine-service/src/main/java/io/certacota/engine/service/controller/AccountController.java:37-40`

**Issue:** `closeAccount` is mapped to `@DeleteMapping` with no `@ResponseStatus`, so it returns 200 with a body. That is a debatable REST convention but the real defect is subtler: the method has no `@ResponseStatus` annotation, so Spring will return 200 on success. However, if the account is not found, `AccountNotFoundException` is thrown and the handler correctly returns 404. If the account is already closed, `AccountClosedException` is thrown and returns 409. These are handled. But `closeAccount` itself never checks whether a DELETE on an already-closing resource under concurrency might produce an inconsistency: two concurrent DELETE calls will both read the account as ACTIVE (line 115 check passes for both), both call `account.close()`, and both call `accountRepository.save(account)`. The second save will overwrite the first with `updatedAt` from a slightly later timestamp but the same status — this is harmless here, but it demonstrates that the optimistic locking / version field that this domain critically needs is absent.

**Fix:** Add `@Version` to `Account` to get optimistic locking:

```java
@Version
@Column(name = "version")
private Long version;
```

And add the corresponding column to the migration:

```sql
ALTER TABLE accounts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

---

## Warnings

### WR-01: `Account.debit` does not enforce balance floor — floor check only at creation

**File:** `engine-core/src/main/java/io/certacota/engine/core/domain/Account.java:62-65`

**Issue:** The `debit` method on the `Account` domain entity performs no floor check. The balance floor enforcement in `AccountServiceImpl` only occurs during `doCreateAccount`. When debit operations are added in Phase 2, any caller of `account.debit(amount)` that does not independently replicate the floor check logic will allow the balance to drop below the floor. The domain entity itself should be the authoritative enforcer of its own invariants.

**Fix:**

```java
public void debit(BigDecimal amount) {
    BigDecimal newBalance = this.balance.subtract(amount);
    if (this.balanceFloor != null && newBalance.compareTo(this.balanceFloor) < 0) {
        throw new BalanceFloorViolationException(
            "Debit of " + amount + " would breach floor " + this.balanceFloor);
    }
    this.balance = newBalance;
    this.updatedAt = OffsetDateTime.now();
}
```

---

### WR-02: `AccountServiceImpl` is annotated `@Transactional` at class level but `getAccount` overrides with `readOnly = true` — `closeAccount` creates an audit-log-less close

**File:** `engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java:109-124`

**Issue:** `closeAccount` saves the updated account but writes no audit log entry for the closure event. Every other balance-affecting operation (currently only `doCreateAccount`) writes an audit log row. A closed account will have only the creation entry, making it impossible to determine from the audit log alone when or why an account was closed. This is inconsistent with the stated design of the `balance_audit_log` table.

**Fix:**

```java
auditLogRepository.save(BalanceAuditLog.builder()
    .accountId(account.getId())
    .operation("ACCOUNT_CLOSED")
    .amount(BigDecimal.ZERO)
    .balanceBefore(account.getBalance())
    .balanceAfter(account.getBalance())
    .recordedAt(OffsetDateTime.now())
    .build());
```

Add this before or after `account.close()` in `closeAccount`.

---

### WR-03: `IdempotencyKey.updateResponseBody` mutates `response_body` which is marked mutable — breaks idempotency contract

**File:** `engine-core/src/main/java/io/certacota/engine/core/domain/IdempotencyKey.java:47-49`

**Issue:** `responseBody` has no `updatable = false` on its `@Column`, and the entity exposes a public `updateResponseBody(String body)` mutation method. An idempotency record's stored response is definitionally immutable — it represents the canonical result that must be replayed identically. Providing a public setter invites accidental or future mutation of the cached response, corrupting the idempotency guarantee.

**Fix:** Remove the `updateResponseBody` method (it is unused in the current codebase) and add `updatable = false` to the `responseBody` column:

```java
@Column(name = "response_body", nullable = false, updatable = false)
private String responseBody;
```

---

### WR-04: `balance_audit_log` has no index on `account_id` — `findByAccountId` is a full table scan

**File:** `engine-service/src/main/resources/db/migration/V3__create_balance_audit_log.sql`

**Issue:** The migration creates a foreign key `fk_audit_account` on `account_id` but does not create an index. In PostgreSQL, a foreign key constraint does not automatically create an index on the referencing column. `BalanceAuditLogRepository.findByAccountId` will perform a sequential scan on the `balance_audit_log` table for every call, which degrades proportionally with audit volume — a correctness concern in the sense that under high audit load the query will lock rows longer and may interfere with the concurrent writes that are core to this engine's design.

**Fix:**

```sql
CREATE INDEX idx_audit_log_account_id ON balance_audit_log (account_id);
```

Add this to V3 or a new V4 migration.

---

### WR-05: `TokenEngineAutoConfiguration` instantiates `AccountServiceImpl` directly, bypassing Spring's proxy — `@Transactional` on the bean will not apply

**File:** `engine-spring/src/main/java/io/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java:25-33`

**Issue:** The `@Bean` method calls `new AccountServiceImpl(...)` directly. Spring's `@Transactional` support works via a JDK dynamic proxy or CGLIB subclass generated around the bean. When an autoconfiguration `@Bean` method calls `new` to construct the object, Spring does wrap the returned object in a proxy **if** it detects `@Transactional` annotations — but only when the proxy infrastructure (`TransactionInterceptor`) is available in the application context. In the standalone library use case (where a consumer does not pull in `spring-boot-starter-data-jpa` but only `spring-boot-autoconfigure`), the transactional proxy may not be applied because the `@AutoConfiguration` does not declare `@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)` or `TransactionAutoConfiguration.class`. If `AccountServiceImpl` is instantiated before `TransactionAutoConfiguration` has registered the `PlatformTransactionManager`, the CGLIB proxy will be a no-op transaction proxy.

**Fix:** Add explicit ordering:

```java
@AutoConfiguration(after = {
    org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class
})
```

---

### WR-06: `application.yml` exposes full health details unconditionally — health endpoint leaks internal state

**File:** `engine-service/src/main/resources/application.yml:8`

**Issue:** `management.endpoint.health.show-details: always` causes the `/actuator/health` endpoint to return database connectivity details, Flyway migration state, disk space, and connection pool metrics to any unauthenticated caller. There is no Spring Security in scope for Phase 1, meaning this endpoint is fully public. In production, this exposes that the service uses PostgreSQL, the Flyway schema version, and connection pool saturation — all useful information for an attacker.

**Fix:** Change to `when-authorized` for production, or at minimum `when-authorized`:

```yaml
management:
  endpoint:
    health:
      show-details: when-authorized
```

For development convenience, override in `application-test.yml` with `always` if needed.

---

### WR-07: `AuditLogSteps` calls `sharedContext.getLastResponse()` but the `audit-log.feature` scenario uses a `When` step that does not set `sharedContext` — `extractAccountIdFromLastResponse` will NPE

**File:** `engine-service/src/test/java/io/certacota/engine/service/steps/AuditLogSteps.java:42-48`

**Issue:** The `audit-log.feature` scenario starts with `When I create an account with id "audit-001" and initial balance 200.00`, which is handled by `AccountSteps.createAccount` and correctly sets `sharedContext.setLastResponse(response)`. The then-step `the entry has operation "ACCOUNT_CREATED"` calls `extractAccountIdFromLastResponse`, which reads from `sharedContext.getLastResponse()`. This works in isolation, but the `sharedContext` bean is `@ScenarioScope`. If any prior step in the scenario fails before setting the response, `getLastResponse()` returns `null`, and `mapper.readTree(null)` throws `IllegalArgumentException` wrapping in a `RuntimeException` with the message `"Failed to extract account id from response: null"`. The test will silently pass the error as a test infrastructure failure rather than a clear assertion failure.

**Fix:** Add a null guard:

```java
private String extractAccountIdFromLastResponse() {
    try {
        ResponseEntity<String> response = sharedContext.getLastResponse();
        assertThat(response).as("No response stored in shared context").isNotNull();
        return new ObjectMapper().readTree(response.getBody()).get("id").asText();
    } catch (AssertionError e) {
        throw e;
    } catch (Exception e) {
        throw new RuntimeException("Failed to extract account id from response: "
            + (sharedContext.getLastResponse() != null ? sharedContext.getLastResponse().getBody() : "null"), e);
    }
}
```

---

### WR-08: `noAccountExists` in `AccountSteps` deletes the account but not its audit log rows — FK constraint will cause deletion to fail if audit rows exist

**File:** `engine-service/src/test/java/io/certacota/engine/service/steps/AccountSteps.java:57-59`

**Issue:** `accountRepository.deleteById(accountId)` attempts to delete the account row from the `accounts` table. The `balance_audit_log` table has `CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts(id)` with no `ON DELETE CASCADE`. If any previous test run left an audit log entry for `acct-001`, this delete will fail with a `DataIntegrityViolationException` (FK violation), causing the `Given` step — which is supposed to set up a clean state — to throw an uncaught exception and fail the test unpredictably.

**Fix:** Delete audit log rows first, or use `ON DELETE CASCADE` on the FK:

```java
@Given("no account with id {string} exists")
public void noAccountExists(String accountId) {
    auditLogRepository.deleteAll(auditLogRepository.findByAccountId(accountId));
    accountRepository.deleteById(accountId);
}
```

---

## Info

### IN-01: `system-property 'api.version', '1.44'` in `build.gradle` is unused

**File:** `engine-service/build.gradle:39`

**Issue:** `systemProperty 'api.version', '1.44'` is set on the `test` task but there is no test or application code that reads `System.getProperty("api.version")`. This is dead configuration that will cause confusion when the actual API is versioned.

**Fix:** Remove the line or wire it to a version constant that is actually read.

---

### IN-02: `DuplicateIdempotencyKeyException` is defined but never referenced anywhere in production code

**File:** `engine-core/src/main/java/io/certacota/engine/core/exception/DuplicateIdempotencyKeyException.java`

**Issue:** This exception class exists and is well-formed, but no production code throws it. See CR-02 and CR-03 for the correct fix. Leaving dead code creates confusion about whether the idempotency duplicate path is handled.

**Fix:** Either throw it from `AccountServiceImpl` when a duplicate idempotency save is detected, or remove it until needed.

---

### IN-03: `IdempotencySteps.iHaveIdempotencyKey` derives `idempotencyAccountId` with a fragile substring operation

**File:** `engine-service/src/test/java/io/certacota/engine/service/steps/IdempotencySteps.java:54`

**Issue:** The expression `key.replaceAll("[^a-zA-Z0-9]", "").substring(0, Math.min(8, ...))` is applied to compute the test account ID. If `key.replaceAll(...)` produces an empty string (e.g., a key composed entirely of special characters), `substring(0, 0)` produces an empty string as the account ID suffix, and the resulting account ID is `"idem-acct-"` — a valid but ambiguous ID that could collide across test keys.

**Fix:** Use a deterministic hash or UUID derived from the key, or hardcode test account IDs in the feature file itself.

---

### IN-04: `account.yml` configures `show-sql: false` but there is no `format_sql` or `use_sql_comments` setting — debugging will be difficult with raw JPQL

**File:** `engine-service/src/main/resources/application.yml:23`

**Issue:** Minor: `show-sql: false` is correct for production. Consider adding a `logging.level.org.hibernate.SQL: DEBUG` override in a dedicated `application-dev.yml` profile so developers can enable SQL logging without changing the committed config.

**Fix:** Add `application-dev.yml` with:

```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```

---

_Reviewed: 2026-05-13T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
