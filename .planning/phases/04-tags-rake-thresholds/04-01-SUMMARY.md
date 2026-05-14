---
phase: "04-tags-rake-thresholds"
plan: "01"
subsystem: "metadata-portability-test-scaffolding"
tags: ["metadata", "jpa-converter", "flyway", "cucumber", "phase4"]
dependency_graph:
  requires:
    - "03-04-PLAN.md (Phase 3 streaming complete — Cucumber runner and test infrastructure)"
  provides:
    - "MetadataConverter: portable JPA AttributeConverter for metadata columns"
    - "V10 migration: TEXT columns for metadata on all three transaction tables"
    - "4 Phase 4 Cucumber feature files with @Pending scenarios"
    - "TagSteps.java: step definition skeleton for tags and end-by-tag"
    - "StreamingSteps.java: extended with tags and rake step sentences"
  affects:
    - "04-02-PLAN.md (TagCommittedTotalsRepository injection point in TagSteps)"
    - "04-03-PLAN.md (streaming-rake.feature scenarios to go GREEN)"
    - "04-04-PLAN.md (end-by-tag.feature scenarios to go GREEN)"
tech_stack:
  added:
    - "AssertJ testImplementation in engine-core"
    - "jackson-databind testImplementation + compileOnly in engine-core"
    - "jakarta.persistence-api testImplementation in engine-core"
  patterns:
    - "JPA AttributeConverter<Map<String,Object>, String> with static final ObjectMapper"
    - "Cucumber @Pending tag for Wave 0 scaffolding"
    - "Spring Boot 3.5 @TransactionalEventListener(NOT_SUPPORTED) for class-level @Transactional compatibility"
key_files:
  created:
    - "engine-core/src/main/java/com/certacota/engine/core/domain/MetadataConverter.java"
    - "engine-core/src/test/java/com/certacota/engine/core/domain/MetadataConverterTest.java"
    - "engine-service/src/main/resources/db/migration/V10__add_metadata_portability.sql"
    - "engine-service/src/test/resources/features/streaming-tags.feature"
    - "engine-service/src/test/resources/features/streaming-rake.feature"
    - "engine-service/src/test/resources/features/discrete-tags.feature"
    - "engine-service/src/test/resources/features/end-by-tag.feature"
    - "engine-service/src/test/java/com/certacota/engine/service/steps/TagSteps.java"
  modified:
    - "engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java (removed @JdbcTypeCode, added @Convert)"
    - "engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java (added metadata field)"
    - "engine-core/src/main/java/com/certacota/engine/core/domain/Account.java (removed @JdbcTypeCode, added @Convert)"
    - "engine-core/build.gradle (added jackson-databind, assertj, jakarta.persistence-api to test deps)"
    - "engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java (NOT_SUPPORTED on event listener)"
    - "engine-service/src/test/java/com/certacota/engine/service/steps/StreamingSteps.java (added tag/rake step sentences)"
decisions:
  - "jackson-databind added as compileOnly to engine-core (not implementation) — keeps engine-core framework-light"
  - "Account.metadata also retrofitted (D-14 extension) — V10 grep check covers all domain *.java files"
  - "Spring Boot 3.5 @TransactionalEventListener requires NOT_SUPPORTED propagation when class is @Transactional"
metrics:
  duration: "~35 minutes"
  completed: "2026-05-14"
  tasks_completed: 3
  files_created: 8
  files_modified: 6
---

# Phase 4 Plan 01: Wave 0 Foundation — Metadata Portability and Test Scaffolding Summary

Portable metadata storage on all transaction entities (TEXT + JPA AttributeConverter replaces Postgres-specific @JdbcTypeCode JSONB), Flyway V10 migration, and four Phase 4 Cucumber feature files with pending step skeletons ready for plans 02-04 to wire.

## Tasks Completed

| Task | Commit | Description |
|------|--------|-------------|
| 1 — MetadataConverter + V10 migration | 4602e71 | TDD RED→GREEN: 7 unit tests pass; V10 migration with 3 ALTER statements |
| 2 — Entity retrofit + Spring Boot 3.5 fix | 579d1ab | DiscreteTransaction, StreamingTransaction, Account retrofitted; existing Cucumber suite green |
| 3 — Feature files + step skeletons | 680e03e | 4 feature files (15 scenarios), TagSteps.java, extended StreamingSteps.java |

## Verification Results

- `./gradlew :engine-core:test` — 7 MetadataConverter unit tests PASSED
- `./gradlew :engine-service:test --tests "com.certacota.engine.service.CucumberTestRunner"` — BUILD SUCCESSFUL; all existing scenarios green; 15 new @Pending scenarios skipped
- `./gradlew :engine-service:compileTestJava` — BUILD SUCCESSFUL
- grep check: `grep -c "JdbcTypeCode|SqlTypes" engine-core/src/main/java/com/certacota/engine/core/domain/*.java` returns 0 for all 10 files

## Feature File Summary

| File | Covers | Scenarios | Status |
|------|--------|-----------|--------|
| streaming-tags.feature | TAG-01, TAG-03, TAG-04 | 4 | @Pending |
| streaming-rake.feature | RAKE-02, RAKE-03, RAKE-04 | 4 | @Pending |
| discrete-tags.feature | TAG-06 | 3 | @Pending |
| end-by-tag.feature | TAG-02 | 4 | @Pending |
| **Total** | | **15** | **All pending** |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical Functionality] Retrofitted Account.metadata to portable TEXT column**
- **Found during:** Task 2 — acceptance criteria grep check covers `*.java` domain files including Account.java
- **Issue:** Account.java also used `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`, which would fail the grep-returns-0 acceptance criterion and would break on non-Postgres databases per D-01
- **Fix:** Added `ALTER TABLE accounts ALTER COLUMN metadata TYPE TEXT USING metadata::text` to V10; retrofitted Account.java with `@Convert(converter = MetadataConverter.class)`
- **Files modified:** Account.java, V10__add_metadata_portability.sql
- **Commit:** 579d1ab

**2. [Rule 1 - Bug] Fixed Spring Boot 3.5 @TransactionalEventListener constraint violation**
- **Found during:** Task 2 — CucumberTestRunner failing to start application context
- **Issue:** Spring Boot 3.5 (Spring Framework 6.2.x) introduced a breaking change: `@TransactionalEventListener` methods must not be annotated with `@Transactional` unless declared as `REQUIRES_NEW` or `NOT_SUPPORTED`. `StreamingServiceImpl` has class-level `@Transactional` which applied to `onStreamSettled`, causing `BeanInitializationException` on startup.
- **Fix:** Added `@Transactional(propagation = Propagation.NOT_SUPPORTED)` to `onStreamSettled` to explicitly satisfy the Spring constraint
- **Files modified:** StreamingServiceImpl.java
- **Commit:** 579d1ab

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes at trust boundaries introduced by this plan. V10 migration changes column types for existing columns — no new trust surface.

MetadataConverter follows the threat model mitigations:
- T-4-METADATA-01 (Tampering, V10 ALTER): USING metadata::text preserves JSONB data losslessly — verified by existing discrete-metadata.feature passing after migration
- T-4-METADATA-02 (Tampering, convertToEntityAttribute): IllegalArgumentException thrown on parse failure — unit test 7 verifies this

## Known Stubs

- All 15 Phase 4 Cucumber scenarios are `@Pending` — this is intentional Wave 0 scaffolding per plan objective. They will turn GREEN as plans 02-04 wire implementation.
- `TagSteps.tagHasCommittedTotalDebitedGreaterThanOrEqualTo` and `tagHasCommittedTotalCreditedRecipientGreaterThanOrEqualTo` throw `PendingException` — FORWARD REF for plan 02 when `TagCommittedTotalsRepository` is available.

## Downstream Interfaces

- **MetadataConverter** — plans 02/03/04 can reference `@Convert(converter = MetadataConverter.class)` on any new metadata column
- **streaming-tags.feature** — plan 02 will wire step implementations; scenarios go GREEN when `TagController.aggregate()` + `TagCommittedTotals` are implemented
- **streaming-rake.feature** — plan 03 will wire settlement rake split; scenarios go GREEN when `StreamingServiceImpl` three-way split is implemented
- **discrete-tags.feature** — plan 02 will wire `TransactionServiceImpl` tag writes
- **end-by-tag.feature** — plan 04 will wire `TagServiceImpl.endByTag()`

## Self-Check: PASSED

- MetadataConverter.java: FOUND
- MetadataConverterTest.java: FOUND
- V10__add_metadata_portability.sql: FOUND
- streaming-tags.feature: FOUND
- streaming-rake.feature: FOUND
- discrete-tags.feature: FOUND
- end-by-tag.feature: FOUND
- TagSteps.java: FOUND
- Commits 4602e71, 579d1ab, 680e03e: FOUND (git log verified)
