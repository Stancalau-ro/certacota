---
phase: 01-foundation
plan: 02
subsystem: domain
tags: [jpa, domain-model, idempotency, floor-enforcement, autoconfigure, spring-data]

# Dependency graph
requires:
  - "01-01: Three-module Gradle scaffold, Flyway DDL, Testcontainers/Cucumber infrastructure"
provides:
  - "Account JPA entity with caller-supplied String PK, NUMERIC(38,18) balance/floor, JSONB metadata"
  - "AccountService interface (engine-core, zero Spring annotations)"
  - "AccountServiceImpl with idempotency via DB UNIQUE constraint catch and floor enforcement via compareTo"
  - "TokenEngineAutoConfiguration with @ConditionalOnMissingBean AccountService bean"
  - "AutoConfiguration.imports registration for Spring Boot 3.x autoconfigure"
affects: [01-03, all-subsequent-phases]

# Tech tracking
tech-stack:
  added:
    - "hibernate-core (compileOnly in engine-core for @JdbcTypeCode/@SqlTypes annotation resolution)"
    - "spring-data-jpa (compileOnly in engine-core for JpaRepository interface compilation)"
    - "jackson-databind (implementation in engine-spring for ObjectMapper injection in AccountServiceImpl)"
  patterns:
    - "DB UNIQUE constraint as idempotency gate: saveAndFlush triggers constraint before business write; DataIntegrityViolationException catch returns cached response"
    - "enforceBalanceFloor via compareTo — never BigDecimal.equals() — avoids scale mismatch false negatives"
    - "Class-level @Transactional on AccountServiceImpl; @Transactional(readOnly=true) on getAccount"
    - "@AutoConfiguration (not @Configuration) for Spring Boot 3.x autoconfigure registration"
    - "AutoConfiguration.imports replaces spring.factories (Boot 3.x)"

key-files:
  created:
    - "engine-core/src/main/java/io/certacota/engine/core/domain/Account.java — JPA entity: String PK, BigDecimal(38,18), JSONB metadata, close/credit/debit mutation methods"
    - "engine-core/src/main/java/io/certacota/engine/core/domain/AccountStatus.java — ACTIVE/CLOSED enum"
    - "engine-core/src/main/java/io/certacota/engine/core/domain/BalanceAuditLog.java — append-only entity, all columns updatable=false"
    - "engine-core/src/main/java/io/certacota/engine/core/domain/IdempotencyKey.java — UNIQUE(idempotency_key, operation) composite constraint"
    - "engine-core/src/main/java/io/certacota/engine/core/repository/AccountRepository.java"
    - "engine-core/src/main/java/io/certacota/engine/core/repository/BalanceAuditLogRepository.java"
    - "engine-core/src/main/java/io/certacota/engine/core/repository/IdempotencyKeyRepository.java"
    - "engine-core/src/main/java/io/certacota/engine/core/service/AccountService.java — interface, no Spring annotations"
    - "engine-core/src/main/java/io/certacota/engine/core/dto/CreateAccountRequest.java — Java record"
    - "engine-core/src/main/java/io/certacota/engine/core/dto/AccountResponse.java — Java record with static from(Account) factory"
    - "engine-core/src/main/java/io/certacota/engine/core/exception/AccountNotFoundException.java"
    - "engine-core/src/main/java/io/certacota/engine/core/exception/BalanceFloorViolationException.java"
    - "engine-core/src/main/java/io/certacota/engine/core/exception/DuplicateIdempotencyKeyException.java"
    - "engine-core/src/main/java/io/certacota/engine/core/exception/AccountClosedException.java"
    - "engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java"
    - "engine-spring/src/main/java/io/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java"
    - "engine-spring/src/main/java/io/certacota/engine/spring/config/TokenEngineProperties.java"
    - "engine-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
  modified:
    - "engine-core/build.gradle — added compileOnly hibernate-core and spring-data-jpa"
    - "engine-spring/build.gradle — added jackson-databind implementation"

key-decisions:
  - "Used Java records for DTOs (CreateAccountRequest, AccountResponse) — immutable, no Lombok needed, idiomatic modern Java"
  - "Added hibernate-core compileOnly to engine-core — required for @JdbcTypeCode/@SqlTypes annotation compilation; Hibernate runtime provided by engine-spring at runtime"
  - "Added spring-data-jpa compileOnly to engine-core — JpaRepository interface must be on compile classpath for repository interfaces"
  - "Added jackson-databind to engine-spring — ObjectMapper injection required for idempotency response serialization/deserialization"
  - "storeIdempotencyResponse is a private helper rather than inline code — cleaner separation of concerns"
  - "deserialize is a private helper wrapping objectMapper.readValue — consistent exception wrapping"

# Metrics
duration: 12min
completed: 2026-05-13
---

# Phase 1 Plan 02: Domain Layer Summary

**Full domain layer in engine-core (Account entity, repositories, AccountService interface, DTOs, domain exceptions) and Spring wiring layer in engine-spring (AccountServiceImpl with idempotency via DB constraint catch and floor enforcement via compareTo, TokenEngineAutoConfiguration, autoconfigure registration) — both modules compile with zero errors**

## Performance

- **Duration:** 12 min
- **Started:** 2026-05-13T10:30:00Z
- **Completed:** 2026-05-13T10:42:00Z
- **Tasks:** 2
- **Files modified:** 18 created + 2 modified

## Accomplishments

- Account JPA entity with no @GeneratedValue (caller-supplied String PK per D-07), BigDecimal(precision=38, scale=18) for all amount fields, JSONB metadata via @JdbcTypeCode(SqlTypes.JSON), AccountStatus enum, and mutation methods (close, credit, debit)
- BalanceAuditLog append-only entity: all columns marked updatable=false enforcing FUND-02 at the ORM layer
- IdempotencyKey entity with @UniqueConstraint on (idempotency_key, operation) — mirrors the DDL V2 UNIQUE constraint
- Three JpaRepository interfaces in engine-core; all Spring Data types compile via compileOnly dependency
- AccountService interface with no Spring annotations — clean boundary between engine-core and engine-spring
- CreateAccountRequest and AccountResponse as Java records; AccountResponse.from(Account) factory maps all fields
- Four domain exceptions extending RuntimeException with no Spring annotations
- AccountServiceImpl: saveAndFlush for idempotency key INSERT (forces UNIQUE constraint before business write); DataIntegrityViolationException catch returns cached response; enforceBalanceFloor uses compareTo never equals; auditLogRepository.save and accountRepository.save are inside the same @Transactional method
- TokenEngineAutoConfiguration uses @AutoConfiguration (Spring Boot 3.x) with @ConditionalOnMissingBean on AccountService bean; @ConditionalOnClass(DataSource.class) gates activation
- AutoConfiguration.imports file contains exactly: io.certacota.engine.spring.autoconfigure.TokenEngineAutoConfiguration

## Task Commits

1. **Task 1: engine-core domain layer** — `63078eb` (feat)
2. **Task 2: engine-spring layer** — `ba9ffe8` (feat)

## Files Created/Modified

**engine-core (14 new files + 1 modified):**
- `Account.java` — @Entity, @Table(accounts), String @Id (no @GeneratedValue), BigDecimal(38,18) balance/floor, @JdbcTypeCode(SqlTypes.JSON) metadata, AccountStatus enum field, close/credit/debit mutation methods
- `AccountStatus.java` — enum: ACTIVE, CLOSED
- `BalanceAuditLog.java` — @Entity, all @Column(updatable=false), BIGSERIAL PK via IDENTITY strategy
- `IdempotencyKey.java` — @UniqueConstraint(idempotency_key, operation), updateResponseBody() mutation method
- `AccountRepository.java`, `BalanceAuditLogRepository.java`, `IdempotencyKeyRepository.java` — JpaRepository interfaces
- `AccountService.java` — interface only, zero Spring annotations
- `CreateAccountRequest.java` — Java record: id, initialBalance, balanceFloor, metadata, idempotencyKey
- `AccountResponse.java` — Java record with static from(Account) factory
- `AccountNotFoundException.java`, `BalanceFloorViolationException.java`, `DuplicateIdempotencyKeyException.java`, `AccountClosedException.java` — domain exceptions, no @ResponseStatus
- `engine-core/build.gradle` — added compileOnly hibernate-core, spring-data-jpa

**engine-spring (4 new files + 1 modified):**
- `AccountServiceImpl.java` — @Service @Transactional @RequiredArgsConstructor @Slf4j; idempotency via saveAndFlush + DataIntegrityViolationException; floor via compareTo; audit log atomic with account save
- `TokenEngineAutoConfiguration.java` — @AutoConfiguration @ConditionalOnClass(DataSource) @ConditionalOnMissingBean
- `TokenEngineProperties.java` — @ConfigurationProperties(prefix=token-engine), balanceFloor=BigDecimal.ZERO
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — single line registration
- `engine-spring/build.gradle` — added jackson-databind

## Decisions Made

- **Java records for DTOs** — immutable, concise, idiomatic Java 21; no Lombok required on DTOs
- **compileOnly hibernate-core in engine-core** — @JdbcTypeCode and SqlTypes are Hibernate annotations; needed at compile time even though runtime provided by engine-spring
- **compileOnly spring-data-jpa in engine-core** — JpaRepository must be resolvable at engine-core compile time for repository interface declarations
- **jackson-databind in engine-spring** — ObjectMapper required for idempotency response serialization; engine-spring serializes/deserializes cached responses; keeps Jackson out of engine-core

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added compileOnly hibernate-core to engine-core/build.gradle**
- **Found during:** Task 1 verification (engine-core:compileJava)
- **Issue:** @JdbcTypeCode and SqlTypes are from Hibernate ORM, not from jakarta.persistence-api; engine-core/build.gradle only had jakarta.persistence-api as compileOnly; compilation failed with "cannot find symbol" on both annotations
- **Fix:** Added `compileOnly 'org.hibernate.orm:hibernate-core'` to engine-core/build.gradle; version managed by Spring Boot BOM
- **Files modified:** engine-core/build.gradle
- **Commit:** 63078eb

**2. [Rule 3 - Blocking] Added jackson-databind to engine-spring/build.gradle**
- **Found during:** Task 2 verification (engine-spring:compileJava)
- **Issue:** AccountServiceImpl and TokenEngineAutoConfiguration both use ObjectMapper from com.fasterxml.jackson.databind; jackson-databind was not on engine-spring's classpath (spring-boot-autoconfigure and spring-boot-starter-data-jpa do not pull Jackson as a compile dependency without spring-boot-starter-web)
- **Fix:** Added `implementation 'com.fasterxml.jackson.core:jackson-databind'` to engine-spring/build.gradle; version managed by Spring Boot BOM
- **Files modified:** engine-spring/build.gradle
- **Commit:** ba9ffe8

---

**Total deviations:** 2 auto-fixed (both Rule 3 — blocking compilation issues resolved inline)
**Impact on plan:** Both fixes were additive (new compileOnly/implementation entries); no scope change; constraints verified (no Boot plugin added to engine-core or engine-spring)

## Known Stubs

None — all plan-required functionality is implemented. Plan 03 will wire the REST controller layer.

## Threat Surface Scan

No new network endpoints, auth paths, or trust-boundary schema changes introduced in this plan. AccountServiceImpl contains all business logic but is not exposed to the network in this plan. The REST exposure comes in Plan 03.

## Self-Check: PASSED

Files verified:
- engine-core/src/main/java/io/certacota/engine/core/domain/Account.java — EXISTS
- engine-core/src/main/java/io/certacota/engine/core/service/AccountService.java — EXISTS
- engine-spring/src/main/java/io/certacota/engine/spring/service/AccountServiceImpl.java — EXISTS
- engine-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports — EXISTS, contains io.certacota.engine.spring.autoconfigure.TokenEngineAutoConfiguration

Commits verified:
- 63078eb — feat(01-02): engine-core domain layer
- ba9ffe8 — feat(01-02): engine-spring layer

Compilation: ./gradlew :engine-core:compileJava :engine-spring:compileJava — BUILD SUCCESSFUL

---
*Phase: 01-foundation*
*Completed: 2026-05-13*
