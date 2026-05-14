---
phase: 04
plan: 02
subsystem: tags
tags: [tags, discrete-transactions, streaming-transactions, redis, jpa, cucumber, autoconfigure]
dependency_graph:
  requires: [04-01]
  provides: [04-03, 04-04]
  affects: [engine-core, engine-spring, engine-service]
tech_stack:
  added:
    - TagCommittedTotals JPA entity with PESSIMISTIC_WRITE lock
    - RedisStreamRegistry SADD/SREM for tag-streams:{tag} Sets
    - TagServiceImpl: Postgres committed + Redis in-flight projection aggregation
    - TagAutoConfiguration registered in AutoConfiguration.imports
    - V11 Flyway migration: stream_tags, discrete_transaction_tags, tag_committed_totals
    - V12 Flyway migration: to_account_id, rake_rate, platform_account_id on streaming_transactions
  patterns:
    - @ElementCollection for normalized tag join tables (stream_tags, discrete_transaction_tags)
    - PESSIMISTIC_WRITE lock with alphabetical tag ordering for deadlock prevention (D-08)
    - CommaSeparated tags stored as Redis hash field; tag-streams:{tag} as Redis Set members
    - TagAggregateResponse: committed (Postgres) + inFlight (Redis projection) + estimatedAt
key_files:
  created:
    - engine-core/src/main/java/com/certacota/engine/core/domain/TagCommittedTotals.java
    - engine-core/src/main/java/com/certacota/engine/core/repository/TagCommittedTotalsRepository.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/TagAggregateResponse.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/EndByTagResponse.java
    - engine-core/src/main/java/com/certacota/engine/core/service/TagService.java
    - engine-core/src/test/java/com/certacota/engine/core/domain/TagCommittedTotalsTest.java
    - engine-spring/src/main/java/com/certacota/engine/spring/service/TagServiceImpl.java
    - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TagAutoConfiguration.java
    - engine-spring/src/test/java/com/certacota/engine/spring/redis/RedisStreamRegistryTagIT.java
    - engine-service/src/main/java/com/certacota/engine/service/controller/TagController.java
    - engine-service/src/test/java/com/certacota/engine/service/TagAutoConfigurationIT.java
    - engine-service/src/main/resources/db/migration/V11__create_tag_tables.sql
    - engine-service/src/main/resources/db/migration/V12__add_streaming_rake_fields.sql
  modified:
    - engine-core/src/main/java/com/certacota/engine/core/dto/StartStreamRequest.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/CreditRequest.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/DebitRequest.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/PostTransferRequest.java
    - engine-core/src/main/java/com/certacota/engine/core/dto/StartStreamResponse.java
    - engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java
    - engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java
    - engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java
    - engine-core/src/main/java/com/certacota/engine/core/service/StreamRegistry.java
    - engine-spring/src/main/java/com/certacota/engine/spring/redis/RedisStreamRegistry.java
    - engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java
    - engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java
    - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java
    - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java
    - engine-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    - engine-spring/src/test/java/com/certacota/engine/spring/ArithmeticTest.java
    - engine-spring/build.gradle
    - engine-service/src/test/java/com/certacota/engine/service/steps/TagSteps.java
    - engine-service/src/test/resources/features/streaming-tags.feature
    - engine-service/src/test/resources/features/discrete-tags.feature
    - engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java
decisions:
  - "V12 migration created in plan 04-02 (not 04-03) because StreamingTransaction entity mappings (toAccountId, rakeRate, platformAccountId) were added in Task 1 to maintain a stable record shape; schema and entity must match on first context load"
  - "endByTag is stubbed (UnsupportedOperationException) in TagServiceImpl — full implementation deferred to plan 04-04 which owns bulk end-by-tag and TagTtlCleanupJob"
  - "TagAutoConfiguration conditionally exposes TagService bean with @ConditionalOnMissingBean allowing host app override"
  - "TransactionController /credit, /debit, /transfer sub-endpoints accept accountId in body (not path) to carry tags alongside amount, matching test step expectations"
metrics:
  duration: "~45 min (multi-session)"
  completed: "2026-05-14T14:10:00Z"
  tasks: 3
  files_created: 13
  files_modified: 21
---

# Phase 4 Plan 2: Tags Vertical Slice Summary

Tag accounting integrated into all transaction paths — discrete and streaming — with Postgres committed totals, Redis in-flight projection, and an auto-configured TagService + TagController REST endpoint.

## What Was Built

**Task 1 — V11 schema, TagCommittedTotals, DTO extensions, StreamState expansion (commit 27075af)**

Flyway V11 creates three tables: `stream_tags` (FK to streaming_transactions.stream_id), `discrete_transaction_tags` (FK to discrete_transactions.id), and `tag_committed_totals` (PK = tag string, non-negative CHECK constraints). TagCommittedTotals JPA entity includes zero() factory, addDebit() and addCreditedRecipient() mutators, and a PESSIMISTIC_WRITE-locked findWithLock() repository method.

All four transaction DTOs (StartStreamRequest, CreditRequest, DebitRequest, PostTransferRequest) received a `List<String> tags` field with @Size(max=50) and per-element @NotBlank/@Size(max=255) validation. StreamState record extended from 8 to 12 fields (tags, toAccountId, rakeRate, platformAccountId) with updated fromRedis() and fromDbRow() parsers. StreamingTransaction entity added three nullable JPA columns (to_account_id, rake_rate, platform_account_id) and an @ElementCollection for stream_tags. DiscreteTransaction got an @ElementCollection for discrete_transaction_tags.

**Task 2 — RedisStreamRegistry tag-set extension + service wiring (commit 077e157)**

RedisStreamRegistry.register() now calls SADD tag-streams:{tag} for every tag in the stream, stores tags as a comma-separated field in the Redis hash, and RedisStreamRegistry.remove() calls SREM from all tag sets after HDEL. getStreamsByTag() uses SMEMBERS + HGETALL round-trip to reconstruct full StreamState objects.

StreamingServiceImpl.startStream() persists tags on StreamingTransaction builder; stopStream() updates tag_committed_totals with addDebit()/addCreditedRecipient() under alphabetical PESSIMISTIC_WRITE lock ordering. TransactionServiceImpl.credit()/debit()/transfer() persist tags on DiscreteTransaction builder and update tag_committed_totals inside the same @Transactional boundary.

**Task 3 — TagServiceImpl, TagController, TagAutoConfiguration, Cucumber activation (commit 1737693)**

TagServiceImpl.aggregate() queries Postgres for committed totals then iterates Redis active streams for tag, computing per-stream in-flight debit projections via StreamSettlementCalculator. TagAutoConfiguration registers TagService as a @ConditionalOnMissingBean and is registered in AutoConfiguration.imports.

TagController exposes GET /api/v1/tags/{tag}/aggregate and POST /api/v1/tags/{tag}/end (end throws UnsupportedOperationException pending plan 04-04). TransactionController gains /credit, /debit, /transfer sub-endpoints accepting tags in the request body. StartStreamResponse echoes back the tags field.

Cucumber scenarios in streaming-tags.feature (4 scenarios) and discrete-tags.feature (3 scenarios) were activated by removing @Pending; TagSteps wired real TagCommittedTotalsRepository assertions.

V12 Flyway migration adds to_account_id, rake_rate, platform_account_id columns to streaming_transactions — required because the entity mappings were introduced in Task 1 before the corresponding migration existed.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] V12 migration created early to match entity schema**
- **Found during:** Task 3 — Hibernate schema validation failed: `missing column [platform_account_id] in table [streaming_transactions]`
- **Issue:** StreamingTransaction entity gained toAccountId, rakeRate, platformAccountId JPA columns in Task 1, but V11 only added tag tables. Hibernate validate mode rejected the mismatch on test startup.
- **Fix:** Created V12__add_streaming_rake_fields.sql adding the three nullable columns; this migration was anticipated for plan 04-03 but is needed in 04-02 because the entity shape must match the DB on first context load.
- **Files modified:** `engine-service/src/main/resources/db/migration/V12__add_streaming_rake_fields.sql` (created)
- **Commit:** 1737693

**2. [Rule 3 - Blocking] Testcontainers dependencies added to engine-spring**
- **Found during:** Task 2 — RedisStreamRegistryTagIT uses @Testcontainers/@Container but engine-spring build.gradle lacked testcontainers dependencies
- **Fix:** Added `org.testcontainers:testcontainers` and `org.testcontainers:junit-jupiter` to engine-spring testImplementation
- **Files modified:** `engine-spring/build.gradle`
- **Commit:** 077e157

**3. [Rule 1 - Bug] ArithmeticTest.buildState() updated for 12-field StreamState**
- **Found during:** Task 1 — ArithmeticTest directly constructs StreamState with 8 positional args; extending the record to 12 broke compilation
- **Fix:** Updated buildState() to pass null/emptyList for the four new fields (tags, toAccountId, rakeRate, platformAccountId)
- **Files modified:** `engine-spring/src/test/java/com/certacota/engine/spring/ArithmeticTest.java`
- **Commit:** 27075af

**4. [Rule 3 - Blocking] StreamingAutoConfiguration and TokenEngineAutoConfiguration updated for new constructor parameters**
- **Found during:** Task 2 — After injecting TagCommittedTotalsRepository into StreamingServiceImpl and TransactionServiceImpl constructors, the autoconfigure bean factories needed the new parameter
- **Fix:** Added TagCommittedTotalsRepository parameter to both streamingService() and transactionService() @Bean methods
- **Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java`, `TokenEngineAutoConfiguration.java`
- **Commit:** 077e157

## Known Stubs

| Stub | File | Line | Reason |
|------|------|------|--------|
| `TagServiceImpl.endByTag` throws `UnsupportedOperationException` | `engine-spring/src/main/java/com/certacota/engine/spring/service/TagServiceImpl.java` | 68 | Bulk end-by-tag is plan 04-04's deliverable; stub ensures the interface compiles and the bean loads without exposing broken behavior |

The stub does not prevent this plan's goal — aggregate query, tag wiring on all write paths, and Cucumber scenarios are fully functional.

## Self-Check: PASSED

- [x] V11__create_tag_tables.sql exists
- [x] V12__add_streaming_rake_fields.sql exists
- [x] TagCommittedTotals.java exists
- [x] TagCommittedTotalsRepository.java exists
- [x] TagAggregateResponse.java exists
- [x] TagService.java exists
- [x] TagServiceImpl.java exists
- [x] TagAutoConfiguration.java exists
- [x] TagController.java exists
- [x] TagAutoConfigurationIT.java exists
- [x] Commits 27075af, 077e157, 1737693 all present in git log
- [x] All tests pass: TagAutoConfigurationIT (2 tests), CucumberTestRunner (includes streaming-tags + discrete-tags scenarios)
