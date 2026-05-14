---
phase: 03-streaming-transactions
plan: 01
subsystem: engine-core, engine-service, engine-spring
tags: [streaming, flyway, redis, shedlock, contracts, tdd-red, testcontainers]
dependency_graph:
  requires: []
  provides:
    - streaming_transactions DDL (V7)
    - shedlock DDL (V8)
    - audit_archive schema (V9)
    - StreamingTransaction JPA entity
    - StreamState value object
    - StreamingTransactionRepository
    - StreamingService interface
    - StreamRegistry interface
    - StartStreamRequest/StartStreamResponse/StopStreamRequest/StopStreamResponse/EstimatedBalanceResponse DTOs
    - StreamNotFoundException/RedisUnavailableException/StreamAlreadyActiveException exceptions
    - Redis Testcontainers setup in TestcontainersConfiguration
    - 6 streaming Cucumber feature files (RED)
    - StreamingSteps glue code with PendingException stubs
  affects:
    - engine-service/build.gradle (added Redis/Redisson/ShedLock deps)
    - engine-spring/build.gradle (added Redis/Redisson/ShedLock deps)
    - engine-service/src/test/java/.../TestcontainersConfiguration.java (added Redis container)
tech_stack:
  added:
    - spring-boot-starter-data-redis (BOM-managed)
    - redisson-spring-boot-starter:3.50.0
    - shedlock-spring:6.6.0
    - shedlock-provider-jdbc-template:6.6.0
    - testcontainers GenericContainer redis:7-alpine (test)
  patterns:
    - Flyway DDL migrations with CONSTRAINT naming convention (pk_, uq_, fk_, chk_, idx_)
    - Java record for value objects (StreamState) and DTOs
    - Pure Java port interface (StreamRegistry) — no Spring or Redis imports
    - Testcontainers GenericContainer + System.setProperty for Redisson autoconfiguration compatibility
    - Cucumber PendingException stubs for TDD RED phase
key_files:
  created:
    - engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql
    - engine-service/src/main/resources/db/migration/V8__create_shedlock.sql
    - engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql
    - engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java
    - engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java
    - engine-core/src/main/java/com/certacota/engine/core/repository/StreamingTransactionRepository.java
    - engine-core/src/main/java/com/certacota/engine/core/service/StreamingService.java
    - engine-core/src/main/java/com/certacota/engine/core/service/StreamRegistry.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/StartStreamRequest.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/StartStreamResponse.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/StopStreamRequest.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/StopStreamResponse.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/EstimatedBalanceResponse.java
    - engine-core/src/main/java/com/certacota/engine/core/exception/StreamNotFoundException.java
    - engine-core/src/main/java/com/certacota/engine/core/exception/RedisUnavailableException.java
    - engine-core/src/main/java/com/certacota/engine/core/exception/StreamAlreadyActiveException.java
    - engine-service/src/test/resources/features/streaming-start.feature
    - engine-service/src/test/resources/features/streaming-stop.feature
    - engine-service/src/test/resources/features/streaming-estimation.feature
    - engine-service/src/test/resources/features/streaming-minimum-amount.feature
    - engine-service/src/test/resources/features/streaming-increment.feature
    - engine-service/src/test/resources/features/streaming-auto-termination.feature
    - engine-service/src/test/java/com/certacota/engine/service/steps/StreamingSteps.java
  modified:
    - engine-service/build.gradle
    - engine-spring/build.gradle
    - engine-service/src/test/java/com/certacota/engine/service/TestcontainersConfiguration.java
decisions:
  - "Testcontainers Redis via System.setProperty in static initializer — @DynamicPropertySource in @TestConfiguration is not picked up by Redisson autoconfiguration before context start; System.setProperty ensures Redisson reads host/port before bean instantiation"
  - "TestcontainersConfiguration Redis container started eagerly in static block alongside @DynamicPropertySource for completeness (belt and suspenders)"
  - "StreamingTransaction.status stored as String not enum — avoids @Enumerated overhead and matches the plain VARCHAR(20) constraint in DDL; status constants as public static final String"
  - "StreamState.fromDbRow() computes startedAtNano from wall-clock delta at load time with startedAtNanoFromCurrentJvm=false — cross-pod nanoTime semantics documented in RESEARCH.md Pitfall 1"
  - "Flyway V9 uses plain CREATE TABLE in audit_archive schema (no LIKE) — explicit column list avoids implicit inheritance of constraints and sequences from public.balance_audit_log"
metrics:
  duration: ~45 minutes
  completed: 2026-05-14
  tasks_completed: 3
  files_created: 23
  files_modified: 3
---

# Phase 3 Plan 01: Wave 1 Foundation — Flyway DDL, engine-core Contracts, Test Scaffolding

Wave 1 foundation established: Flyway DDL migrations V7/V8/V9, Redis/Redisson/ShedLock dependencies, engine-core streaming contracts (13 files), and Cucumber test scaffolding (7 files) — 20 streaming scenarios in PENDING/RED state with zero Phase 1/2 regressions.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Flyway DDL V7/V8/V9 and dependency additions | f2da852 | V7/V8/V9 SQL, engine-service/build.gradle, engine-spring/build.gradle, TestcontainersConfiguration |
| 2 | engine-core contracts | 1b00585 | StreamingTransaction, StreamState, repository, service interfaces, 5 DTOs, 3 exceptions |
| 3 | Test scaffolding | 45db7e7 | 6 feature files, StreamingSteps.java |

## Verification Results

- `./gradlew :engine-core:build` — BUILD SUCCESSFUL
- `./gradlew :engine-service:test --tests '*.CucumberTestRunner'` — 39 tests: 18 GREEN (Phase 1/2), 20 PENDING (streaming), 1 skipped (pre-existing)
- Flyway logs confirm: "Successfully applied 9 migrations to schema public, now at version v9"
- V7 contains CONSTRAINT chk_str_rate CHECK (rate_per_second > 0) and CONSTRAINT uq_stream_id UNIQUE (stream_id)
- V8 contains exactly: name, lock_until, locked_at, locked_by and PRIMARY KEY (name)
- V9 contains CREATE SCHEMA IF NOT EXISTS audit_archive and audit_archive.balance_audit_log with recorded_at TIMESTAMPTZ
- engine-spring/build.gradle contains redisson-spring-boot-starter:3.50.0
- engine-service/build.gradle contains shedlock-spring:6.6.0
- StreamRegistry.java has no Spring or Redis imports (pure Java)
- StreamingService.java interface contains autoTerminate(String streamId)
- StopStreamResponse.java has no finalBalance field
- EstimatedBalanceResponse.java uses Long (not long) for estimatedDrainAt
- All 3 exception classes exist in com.certacota.engine.core.exception package

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Redisson autoconfiguration connects before @DynamicPropertySource can override host/port**

- **Found during:** Task 1 verification (Cucumber test run)
- **Issue:** `redisson-spring-boot-starter` autoconfigures `RedissonClient` bean at Spring context startup using default `spring.data.redis.host=localhost` and `port=6379`. `@DynamicPropertySource` in `@TestConfiguration` classes (imported via `@Import`) is NOT applied before Redisson's `RedissonAutoConfigurationV2` runs — it is only applied when declared in the main test class annotated with `@SpringBootTest`. The Redis Testcontainer had started but its mapped port was never reaching Redisson.
- **Fix:** Added `System.setProperty("spring.data.redis.host", ...)` and `System.setProperty("spring.data.redis.port", ...)` calls in the static initializer of `TestcontainersConfiguration` so Redisson reads the correct values before Spring context initialization. Retained `@DynamicPropertySource` as well for Spring Data Redis compatibility.
- **Files modified:** `engine-service/src/test/java/com/certacota/engine/service/TestcontainersConfiguration.java`
- **Commit:** f2da852

**2. [Rule 3 - Blocking] TestcontainersConfiguration Redis container added in Task 1 scope**

- **Found during:** Task 1 verification — Redisson needs Redis to autoconfigure; Task 3 adds the container, but Task 1 verify runs first
- **Issue:** The plan places `TestcontainersConfiguration.java` modification in Task 3, but Task 1's verification step runs `CucumberTestRunner` which requires the Spring context to start — which requires Redis since Redisson is now on the classpath
- **Fix:** Added the Redis Testcontainer to `TestcontainersConfiguration.java` during Task 1. Task 3 then only needed to add the remaining artifacts (feature files, StreamingSteps)
- **Files modified:** Covered by same commit (f2da852)

**3. [Rule 1 - Bug] Duplicate step definition removed from StreamingSteps**

- **Found during:** Task 3 verification — `DuplicateStepDefinitionException` caused all Phase 1/2 tests to fail
- **Issue:** Initial StreamingSteps had both `@Given("I start a stream...")` and `@When("I start a stream...")` with identical pattern text. Cucumber treats all step type annotations as equivalent for pattern matching — identical patterns across different annotation types = duplicate
- **Fix:** Removed `@Given` variants; retained only `@When` variants. Cucumber `And` steps in `Given` context match `@When` annotations
- **Files modified:** `engine-service/src/test/java/com/certacota/engine/service/steps/StreamingSteps.java`
- **Commit:** 45db7e7

## TDD Gate Compliance

This plan is marked `tdd="true"` on Task 1. The RED gate is established:
- Task 1 commit (f2da852) includes test infrastructure (TestcontainersConfiguration with Redis)
- Task 3 commit (45db7e7) is the `test(...)` commit that establishes the RED gate with 20 PENDING scenarios
- GREEN gate will be established in Plan 02 when StreamingServiceImpl and StreamController are implemented

Note: The conventional RED gate commit pattern (`test(...)` before `feat(...)`) is split across plans by design — Plan 01 establishes contracts and test scaffolding; Plan 02 establishes the implementation that turns tests GREEN.

## Self-Check: PASSED

Files verified:
- FOUND: engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql
- FOUND: engine-service/src/main/resources/db/migration/V8__create_shedlock.sql
- FOUND: engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql
- FOUND: engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java
- FOUND: engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java
- FOUND: engine-core/src/main/java/com/certacota/engine/core/repository/StreamingTransactionRepository.java
- FOUND: engine-core/src/main/java/com/certacota/engine/core/service/StreamingService.java
- FOUND: engine-core/src/main/java/com/certacota/engine/core/service/StreamRegistry.java
- FOUND: engine-service/src/test/resources/features/streaming-start.feature
- FOUND: engine-service/src/test/resources/features/streaming-stop.feature
- FOUND: engine-service/src/test/resources/features/streaming-estimation.feature
- FOUND: engine-service/src/test/resources/features/streaming-minimum-amount.feature
- FOUND: engine-service/src/test/resources/features/streaming-increment.feature
- FOUND: engine-service/src/test/resources/features/streaming-auto-termination.feature
- FOUND: engine-service/src/test/java/com/certacota/engine/service/steps/StreamingSteps.java

Commits verified:
- FOUND: f2da852 (feat(03-01): add Flyway DDL V7/V8/V9 and Redis/ShedLock deps)
- FOUND: 1b00585 (feat(03-01): add engine-core streaming contracts)
- FOUND: 45db7e7 (test(03-01): add streaming test scaffolding)
