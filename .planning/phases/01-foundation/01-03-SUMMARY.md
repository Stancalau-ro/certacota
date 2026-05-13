---
phase: 01-foundation
plan: 03
subsystem: api
tags: [spring-boot, cucumber, testcontainers, rest, actuator, prometheus, micrometer, idempotency, balance-floor]

# Dependency graph
requires:
  - phase: 01-foundation plan-01
    provides: Gradle multi-module scaffold, Flyway migrations, Testcontainers + Cucumber test infrastructure
  - phase: 01-foundation plan-02
    provides: Domain entities (Account, IdempotencyKey, BalanceAuditLog), repositories, AccountService interface, AccountServiceImpl, TokenEngineAutoConfiguration

provides:
  - AccountController (POST /api/v1/accounts, GET /{id}, DELETE /{id})
  - GlobalExceptionHandler (@RestControllerAdvice: 404/409/422/400)
  - Five Cucumber feature files (account-lifecycle, idempotency, audit-log, balance-floor, observability)
  - Five step definition classes wired to Spring context via @LocalServerPort + RestTemplate
  - Idempotency check-then-insert pattern (no transaction poison on duplicate key)
  - Per-account balance floor enforcement at account creation time
  - Green Cucumber test suite against live Testcontainers Postgres

affects:
  - plan-04 (discrete transactions layer — AccountController extended)
  - plan-05 (streaming layer — same Cucumber test infrastructure)
  - plan-06 (embedder test — same module structure)

# Tech tracking
tech-stack:
  added:
    - Cucumber 7.22.1 (cucumber-java, cucumber-spring, cucumber-junit-platform-engine)
    - Testcontainers PostgreSQL (postgres:17-alpine via @ServiceConnection)
    - Micrometer Prometheus registry (micrometer-registry-prometheus:1.15.1)
    - JUnit Platform Suite
  patterns:
    - Thin controller delegation (no @Transactional, no business logic)
    - @RestControllerAdvice global exception handler with domain exception → HTTP status mapping
    - CucumberSpringConfiguration as sole @CucumberContextConfiguration + @SpringBootTest owner
    - RestTemplate with no-throw DefaultResponseErrorHandler for 4xx/5xx in step definitions
    - Check-then-insert idempotency: findByIdempotencyKeyAndOperation before creating account
    - Jackson UTC serialization (write-dates-as-timestamps=false, time-zone=UTC) for deterministic JSON

key-files:
  created:
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
  modified:
    - engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java
    - engine-service/src/main/java/io/certacota/engine/service/EngineServiceApplication.java
    - engine-service/src/main/resources/application.yml
    - engine-service/src/test/java/io/certacota/engine/service/CucumberSpringConfiguration.java
    - engine-service/src/test/java/io/certacota/engine/service/CucumberTestRunner.java
    - engine-service/src/test/java/io/certacota/engine/service/steps/ActuatorSteps.java
    - engine-service/src/test/resources/features/observability.feature
    - engine-service/src/test/resources/junit-platform.properties
    - engine-service/build.gradle

key-decisions:
  - "Check-then-insert idempotency: findByIdempotencyKeyAndOperation before doCreateAccount avoids DataIntegrityViolationException poisoning the outer transaction"
  - "Jackson UTC normalization: write-dates-as-timestamps=false + time-zone=UTC required for deterministic JSON comparison in idempotency test"
  - "Per-account balance floor enforced at creation time in doCreateAccount, not via enforceBalanceFloor(account, balance) helper which required a saved entity"
  - "DOCKER_HOST=tcp://localhost:2375 + api.version=1.44 required for Docker Desktop 4.60+ which drops API versions below 1.44"
  - "@Pending tag filter via cucumber.filter.tags=not @Pending in junit-platform.properties skips streaming-dependent scenarios cleanly"
  - "Prometheus endpoint requires explicit management.endpoint.prometheus.enabled=true + management.prometheus.metrics.export.enabled=true in Spring Boot 3.5.x"
  - "@EntityScan and @EnableJpaRepositories added to EngineServiceApplication to discover engine-core domain classes from a different base package"

patterns-established:
  - "Pattern: Thin REST controller — @RestController, @RequiredArgsConstructor, zero business logic, zero @Transactional, all methods single-line delegations to service"
  - "Pattern: @RestControllerAdvice exception handler — one @ExceptionHandler per domain exception class, log.warn, return Map.of(error, ex.getMessage())"
  - "Pattern: Cucumber step class — @Slf4j, @Autowired fields for repos, @LocalServerPort int port, no-throw RestTemplate, SharedContext for response passing"
  - "Pattern: CucumberSpringConfiguration is the only class with @CucumberContextConfiguration + @SpringBootTest; all step classes are plain beans"

requirements-completed:
  - ACCT-01
  - ACCT-02
  - ACCT-03
  - FUND-01
  - FUND-02
  - FUND-03
  - BAL-01
  - BAL-03

# Metrics
duration: ~90min (across 2 sessions)
completed: 2026-05-13
---

# Phase 1 Plan 03: Cucumber Acceptance Tests + REST Controller Summary

**Five Cucumber feature files green against Testcontainers Postgres: AccountController, GlobalExceptionHandler, idempotency fix, balance floor, Prometheus, and Docker connectivity resolved**

## Performance

- **Duration:** ~90 min (split across 2 sessions due to context limit)
- **Started:** 2026-05-13
- **Completed:** 2026-05-13T11:41:12Z
- **Tasks:** 2 (Task 1: feature files + step defs; Task 2: controller implementation)
- **Files modified:** 19

## Accomplishments

- All 5 Cucumber feature files written and passing (7 active tests pass, 1 @Pending skipped)
- `POST /api/v1/accounts` → 201, `GET /{id}` → 200, `DELETE /{id}` → 200 with correct status transitions
- Idempotent account creation: second call with same key returns cached response, single audit log entry
- Per-account balance floor enforcement at creation time (40.00 initialBalance with 50.00 floor → 422)
- `/actuator/health` → 200 UP against live Postgres, `/actuator/prometheus` → 200 text/plain
- Docker Desktop 4.60+ connectivity fixed (api.version=1.44 workaround for Testcontainers)

## Task Commits

Each task was committed atomically:

1. **Task 1: Cucumber feature files and step definitions** - `565a2c1` (feat)
2. **Task 2: AccountController, GlobalExceptionHandler, and all Cucumber tests green** - `5905a49` (feat)
3. **Fix: CucumberTestRunner GLUE package scope** - `2024299` (fix)

**Plan metadata:** (included in state update commit)

## Files Created/Modified

- `engine-service/src/main/java/.../controller/AccountController.java` - Thin REST controller, no @Transactional
- `engine-service/src/main/java/.../controller/GlobalExceptionHandler.java` - 404/409/422/400 exception mappings
- `engine-spring/src/.../service/AccountServiceImpl.java` - Fixed idempotency (check-then-insert) + per-account floor enforcement
- `engine-service/src/main/java/.../EngineServiceApplication.java` - Added @EntityScan + @EnableJpaRepositories
- `engine-service/src/main/resources/application.yml` - Jackson UTC, Prometheus endpoint enabled
- `engine-service/src/test/.../CucumberSpringConfiguration.java` - Added @ActiveProfiles("test")
- `engine-service/src/test/.../CucumberTestRunner.java` - Extended GLUE to include root package
- `engine-service/src/test/resources/junit-platform.properties` - Added cucumber.filter.tags=not @Pending
- `engine-service/src/test/resources/testcontainers.properties` - DockerDesktopClientProviderStrategy
- `engine-service/build.gradle` - DOCKER_HOST=tcp://localhost:2375, api.version=1.44
- Five feature files (account-lifecycle, idempotency, audit-log, balance-floor, observability)
- Five step definition classes (AccountSteps, IdempotencySteps, AuditLogSteps, BalanceFloorSteps, ActuatorSteps)

## Decisions Made

- **Check-then-insert idempotency:** `findByIdempotencyKeyAndOperation` before `doCreateAccount` prevents `DataIntegrityViolationException` from poisoning the outer `@Transactional` transaction. The catch-based approach failed because the rollback-marked transaction prevented reading the stored empty response body.
- **Jackson UTC normalization:** `write-dates-as-timestamps=false` + `time-zone=UTC` required because `OffsetDateTime` serialization differs between direct entity-to-response path (system timezone `+03:00`) and deserialize-from-stored-JSON path (UTC `Z`) producing non-matching bodies in idempotency test.
- **`@Pending` filter via `cucumber.filter.tags=not @Pending`:** Cleaner than `PendingException` stubs since Cucumber 7.x JUnit Platform engine marks `PendingException` scenarios as FAILED by default; tag filter produces a true "skipped" result.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Idempotency transaction poison on DataIntegrityViolationException**
- **Found during:** Task 2
- **Issue:** Original implementation used try/saveAndFlush-on-insert/catch(DataIntegrityViolationException) pattern. The exception marks the outer `@Transactional` method for rollback, causing the catch block's `findByIdempotencyKeyAndOperation` to run in a rolled-back context. The stored response body was `""` (set on initial save before account creation), so deserialization returned an empty/null response → 500.
- **Fix:** Changed to check-then-insert: `findByIdempotencyKeyAndOperation` at the start of `createAccount`; if found return cached response immediately; if not found proceed with `doCreateAccount` which builds the full response and saves the idempotency record atomically.
- **Files modified:** `engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java`
- **Verification:** Cucumber idempotency.feature passes; second call returns 201 with identical body; audit log has exactly 1 entry
- **Committed in:** `5905a49`

**2. [Rule 1 - Bug] Per-account balance floor not enforced at creation time**
- **Found during:** Task 2
- **Issue:** `enforceBalanceFloor(null, initialBalance)` always passed `null` as the account parameter, so only the global floor (0) was checked. A request with `balanceFloor=50.00` and `initialBalance=40.00` returned 201 instead of 422.
- **Fix:** In `doCreateAccount`, compute `effectiveFloor = request.balanceFloor() != null ? request.balanceFloor() : properties.getBalanceFloor()` before saving the account, then check `initialBalance < effectiveFloor`.
- **Files modified:** `engine-spring/src/.../AccountServiceImpl.java`
- **Verification:** Cucumber balance-floor.feature Per-account floor scenario passes: 422 returned
- **Committed in:** `5905a49`

**3. [Rule 1 - Bug] Jackson OffsetDateTime timezone inconsistency breaking idempotency body comparison**
- **Found during:** Task 2
- **Issue:** First HTTP response serialized `OffsetDateTime` with system timezone `+03:00`; second response deserialized the stored JSON (which had `+03:00`) and Jackson re-serialized it as UTC `Z` — non-matching strings.
- **Fix:** Added `spring.jackson.serialization.write-dates-as-timestamps: false` and `spring.jackson.time-zone: UTC` to `application.yml`.
- **Files modified:** `engine-service/src/main/resources/application.yml`
- **Verification:** Idempotency test passes — both responses now serialize timestamps as UTC ISO-8601
- **Committed in:** `5905a49`

**4. [Rule 1 - Bug] Prometheus endpoint returning 404**
- **Found during:** Task 2
- **Issue:** `/actuator/prometheus` returned 404 despite `micrometer-registry-prometheus` being on classpath. Spring Boot 3.5.x requires explicit `management.endpoint.prometheus.enabled=true` and `management.prometheus.metrics.export.enabled=true`.
- **Fix:** Added both properties to `application.yml`.
- **Files modified:** `engine-service/src/main/resources/application.yml`
- **Verification:** Cucumber observability.feature Prometheus scenario passes: 200 returned
- **Committed in:** `5905a49`

**5. [Rule 3 - Blocking] Docker Desktop 4.60+ API version incompatibility**
- **Found during:** Task 1/2 integration (test execution)
- **Issue:** Testcontainers 1.21.2 shaded docker-java falls back to API version `1.32` when not configured. Docker Desktop 4.60 requires minimum API `1.44`; versions below 1.44 return HTTP 400 with empty ServerInfo JSON, causing `NullPointerException` during server info parsing.
- **Fix:** Set `environment 'DOCKER_HOST', 'tcp://localhost:2375'` and `systemProperty 'api.version', '1.44'` in `build.gradle` test task; created `testcontainers.properties` with `DockerDesktopClientProviderStrategy`; created `~/.docker-java.properties` with `api.version=1.44`.
- **Files modified:** `engine-service/build.gradle`, `engine-service/src/test/resources/testcontainers.properties`
- **Verification:** Testcontainers connects successfully; postgres:17-alpine container starts; all tests run
- **Committed in:** `5905a49`

**6. [Rule 1 - Bug] CucumberExpressionException for /actuator/* step patterns**
- **Found during:** Task 2
- **Issue:** In Cucumber expressions, `/` is the alternation operator. `@When("I request GET /actuator/health")` was parsed as alternation causing "Alternative may not be empty" error, preventing all step definitions from loading.
- **Fix:** Changed to regex patterns: `@When("^I request GET /actuator/health$")`.
- **Files modified:** `engine-service/src/test/.../steps/ActuatorSteps.java`
- **Verification:** Actuator steps load and execute without CucumberExpressionException
- **Committed in:** `5905a49`

**7. [Rule 1 - Bug] `{string}` parameter can't match `"status":"UP"` (two embedded quotes)**
- **Found during:** Task 2
- **Issue:** Cucumber's `{string}` matches a single quoted or double-quoted string. `"status":"UP"` contains two separate quoted tokens, so the step `the response body contains "status":"UP"` failed to match any step definition.
- **Fix:** Changed feature file step to use single-quote wrapper: `the response body contains '"status":"UP"'`.
- **Files modified:** `engine-service/src/test/resources/features/observability.feature`
- **Verification:** ActuatorSteps `responseBodyContains` step matches and passes
- **Committed in:** `5905a49`

---

**Total deviations:** 7 auto-fixed (5 Rule 1 bugs, 1 Rule 3 blocking, 1 combined Rule 1/Rule 3)
**Impact on plan:** All auto-fixes were necessary for correctness and connectivity. No scope creep. The Docker API version issue and idempotency transaction pattern were novel infrastructure/design problems not anticipated in the plan.

## Issues Encountered

- **Docker Desktop API version floor**: The single most time-consuming issue. Required bytecode analysis of Testcontainers' docker-java shading to identify that `VERSION_1_32` was the fallback version, and raw socket testing to confirm Docker Desktop 4.60 rejects anything below `1.44`. Solution required both the `api.version` JVM system property and `DOCKER_HOST=tcp://localhost:2375`.
- **Idempotency transaction rollback**: `@Transactional` class-level annotation means catching `DataIntegrityViolationException` and then querying inside the catch block operates in a rolled-back transaction. The check-then-insert pattern is the correct approach for idempotency in Spring.
- **Missing `@EntityScan`/`@EnableJpaRepositories`**: Spring Boot's component scan doesn't auto-discover JPA infrastructure from a different base package. Required explicit `@EntityScan` and `@EnableJpaRepositories` on the application class.

## Known Stubs

None. All scenarios are fully wired except the `@Pending` streaming scenario which requires Phase 3 (StreamRegistry).

## Threat Flags

No new security surface introduced beyond what was planned in the threat model.

## Next Phase Readiness

- Phase 1 complete: all 8 requirement IDs (ACCT-01, ACCT-02, ACCT-03, FUND-01, FUND-02, FUND-03, BAL-01, BAL-03) verified by green Cucumber tests
- Engine-core and engine-spring produce non-fat JARs; engine-service produces a runnable fat JAR via bootJar
- Three Gradle modules all build successfully
- Phase 2 can extend AccountController with discrete transaction endpoints (Phase 2 scope)
- The `@Pending` streaming scenario in account-lifecycle.feature will become active in Phase 3

---
*Phase: 01-foundation*
*Completed: 2026-05-13*
