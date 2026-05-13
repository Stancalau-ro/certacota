---
phase: 01-foundation
plan: 01
subsystem: infra
tags: [gradle, spring-boot, flyway, postgres, testcontainers, cucumber, junit5]

# Dependency graph
requires: []
provides:
  - "Three-module Gradle project (engine-core, engine-spring, engine-service) compiling with Java 21"
  - "Flyway V1/V2/V3 DDL migrations for accounts, idempotency_keys, balance_audit_log"
  - "EngineServiceApplication @SpringBootApplication main class"
  - "Spring Actuator + Prometheus endpoint configuration"
  - "Testcontainers + Cucumber JUnit 5 test infrastructure wired with @ServiceConnection"
affects: [01-02, 01-03, all-subsequent-phases]

# Tech tracking
tech-stack:
  added:
    - "Gradle 8.14 wrapper (Groovy DSL)"
    - "Spring Boot 3.5.3 (BOM for all Spring/Hibernate/Micrometer versions)"
    - "Flyway 10.x (flyway-core + flyway-database-postgresql)"
    - "Testcontainers 1.21.x (postgresql, spring-boot-testcontainers)"
    - "Cucumber 7.22.1 (cucumber-java, cucumber-spring, cucumber-junit-platform-engine)"
    - "JUnit Platform Suite (junit-platform-suite)"
    - "Micrometer Prometheus Registry"
    - "Lombok"
    - "PostgreSQL JDBC driver"
  patterns:
    - "Multi-module Gradle: Boot plugin on engine-service only; dependency-management on engine-core + engine-spring"
    - "@ServiceConnection pattern for Testcontainers — no hardcoded datasource URLs in any properties file"
    - "Cucumber JUnit 5 runner using @Suite + @IncludeEngines(cucumber) — no deprecated @RunWith"
    - "Exactly one @CucumberContextConfiguration class in the glue path (CucumberSpringConfiguration)"
    - "Flyway dual-artifact requirement: flyway-core + flyway-database-postgresql both declared"
    - "NUMERIC(38,18) for all token amount columns"

key-files:
  created:
    - "settings.gradle — multi-module root"
    - "build.gradle — root with Java 21 toolchain subprojects block"
    - "engine-core/build.gradle — dependency-management only, no Boot plugin"
    - "engine-spring/build.gradle — dependency-management only, no Boot plugin"
    - "engine-service/build.gradle — Spring Boot 3.5.3 plugin, Flyway, Cucumber BOM"
    - "engine-service/src/main/java/io/certacota/engine/service/EngineServiceApplication.java"
    - "engine-service/src/main/resources/application.yml"
    - "engine-service/src/main/resources/application-test.yml"
    - "engine-service/src/main/resources/db/migration/V1__create_accounts.sql"
    - "engine-service/src/main/resources/db/migration/V2__create_idempotency_keys.sql"
    - "engine-service/src/main/resources/db/migration/V3__create_balance_audit_log.sql"
    - "engine-service/src/test/java/io/certacota/engine/service/TestcontainersConfiguration.java"
    - "engine-service/src/test/java/io/certacota/engine/service/CucumberTestRunner.java"
    - "engine-service/src/test/java/io/certacota/engine/service/CucumberSpringConfiguration.java"
    - "engine-service/src/test/resources/junit-platform.properties"
  modified:
    - ".gitignore — added gradle-wrapper.jar exception and build output exclusions"

key-decisions:
  - "Spring Boot 3.5.3 used instead of D-02's 3.4.x — Spring Boot 3.4 OSS support ended 2025-12-31; 3.5.3 is drop-in compatible"
  - "flyway-database-postgresql declared as separate implementation dependency alongside flyway-core — Flyway 10+ split Postgres support"
  - "@ServiceConnection handles datasource injection — no spring.datasource.url in any properties file"
  - "CucumberTestRunner uses @Suite + @IncludeEngines(cucumber) — deprecated @RunWith(Cucumber.class) avoided"
  - "Gradle wrapper jar committed via .gitignore exception (!gradle/wrapper/gradle-wrapper.jar)"

patterns-established:
  - "Pattern 1: Multi-module Gradle with Boot plugin only on engine-service — prevents fat JAR on library modules"
  - "Pattern 2: Testcontainers @ServiceConnection — datasource injection without properties file"
  - "Pattern 3: Cucumber @Suite runner — JUnit 5 native test suite, no JUnit 4 runner"
  - "Pattern 4: Single @CucumberContextConfiguration — exactly one class per project"
  - "Pattern 5: Flyway dual artifacts — flyway-core + flyway-database-postgresql both required"

requirements-completed: [FUND-03]

# Metrics
duration: 6min
completed: 2026-05-13
---

# Phase 1 Plan 01: Walking Skeleton Summary

**Three-module Gradle 8.14 / Spring Boot 3.5.3 project scaffold with Flyway DDL migrations and Testcontainers + Cucumber test infrastructure, all compiling clean with Java 21**

## Performance

- **Duration:** 6 min
- **Started:** 2026-05-13T10:22:00Z
- **Completed:** 2026-05-13T10:27:40Z
- **Tasks:** 2
- **Files modified:** 16 created + 1 modified

## Accomplishments
- Three Gradle modules (engine-core, engine-spring, engine-service) compile with Java 21 toolchain; engine-core and engine-spring produce plain JARs (no fat JAR)
- Flyway DDL migrations V1/V2/V3 created with NUMERIC(38,18) amounts, UNIQUE(idempotency_key, operation) constraint, and FK from balance_audit_log to accounts
- Spring Actuator + Micrometer/Prometheus endpoint exposure configured in application.yml; BigDecimal deserialization enforced
- Testcontainers + Cucumber JUnit 5 infrastructure wired: @ServiceConnection PostgreSQLContainer, @Suite runner, single @CucumberContextConfiguration

## Task Commits

Each task was committed atomically:

1. **Task 1: Gradle multi-module scaffold, main class, migrations, and app config** - `c031068` (feat)
2. **Task 2: Testcontainers configuration and Cucumber test infrastructure** - `99b7a3b` (feat)

**Plan metadata:** (written after this summary)

## Files Created/Modified
- `settings.gradle` — declares engine-core, engine-spring, engine-service modules
- `build.gradle` — root subprojects block with Java 21 toolchain
- `engine-core/build.gradle` — dependency-management plugin only; NO Spring Boot plugin
- `engine-spring/build.gradle` — dependency-management plugin only; NO Spring Boot plugin
- `engine-service/build.gradle` — Spring Boot 3.5.3 plugin; both flyway-core and flyway-database-postgresql; Cucumber BOM
- `engine-service/src/main/java/io/certacota/engine/service/EngineServiceApplication.java` — @SpringBootApplication main class
- `engine-service/src/main/resources/application.yml` — Actuator endpoints, Flyway, BigDecimal config, balance-floor property
- `engine-service/src/main/resources/application-test.yml` — test profile; no datasource URL
- `engine-service/src/main/resources/db/migration/V1__create_accounts.sql` — accounts table with NUMERIC(38,18), JSONB metadata
- `engine-service/src/main/resources/db/migration/V2__create_idempotency_keys.sql` — idempotency_keys with UNIQUE(idempotency_key, operation)
- `engine-service/src/main/resources/db/migration/V3__create_balance_audit_log.sql` — balance_audit_log with FK to accounts
- `engine-service/src/test/java/io/certacota/engine/service/TestcontainersConfiguration.java` — @TestConfiguration with @ServiceConnection PostgreSQLContainer
- `engine-service/src/test/java/io/certacota/engine/service/CucumberTestRunner.java` — @Suite + @IncludeEngines("cucumber") runner
- `engine-service/src/test/java/io/certacota/engine/service/CucumberSpringConfiguration.java` — single @CucumberContextConfiguration with @SpringBootTest RANDOM_PORT
- `engine-service/src/test/resources/junit-platform.properties` — naming-strategy=long
- `.gitignore` — added gradle-wrapper.jar exception; excluded build/ and .gradle/ output directories

## Decisions Made
- **Spring Boot 3.5.3 instead of 3.4.x:** D-02 specified 3.4.x but Spring Boot 3.4 OSS support ended 2025-12-31. Used 3.5.3 (current OSS release, drop-in compatible) as called out in SKELETON.md and RESEARCH.md.
- **Gradle wrapper jar committed:** Added `!gradle/wrapper/gradle-wrapper.jar` negation rule to .gitignore to allow the wrapper binary (standard practice for reproducible builds).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated .gitignore to allow gradle-wrapper.jar commit**
- **Found during:** Task 1 (Gradle wrapper generation)
- **Issue:** Pre-existing `.gitignore` had `*.jar` rule that blocked `gradle/wrapper/gradle-wrapper.jar` from being staged; the wrapper JAR is required for reproducible Gradle builds
- **Fix:** Added `!gradle/wrapper/gradle-wrapper.jar` negation exception and added `build/` + `.gradle/` exclusions
- **Files modified:** `.gitignore`
- **Verification:** `git add gradle/wrapper/gradle-wrapper.jar` succeeded; staged in Task 1 commit
- **Committed in:** `c031068` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** The .gitignore fix was required for the wrapper JAR to be committed. No scope creep.

## Issues Encountered
- `gradle wrapper` command failed initially because `settings.gradle` must exist before Gradle can recognize the project directory. Created `settings.gradle` first, then re-ran wrapper command successfully.

## User Setup Required
None - no external service configuration required. Docker must be running for integration tests in later plans (Testcontainers pulls postgres:17-alpine).

## Known Stubs
- `engine-service/src/test/java/io/certacota/engine/service/steps/.gitkeep` — steps package is empty; step definition classes are added in Plan 03.

## Next Phase Readiness
- All three modules compile; ready for Plan 02 to add engine-core domain entities, repositories, and service interface
- engine-service/build.gradle declares all required test dependencies for Cucumber step implementations in Plan 03
- Testcontainers infrastructure will spin up postgres:17-alpine when tests are first run in Plan 03
- No blockers; Plan 02 (domain layer) can proceed immediately

---
*Phase: 01-foundation*
*Completed: 2026-05-13*
