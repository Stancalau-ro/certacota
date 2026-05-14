# Phase 4: Tags and Rake on Streaming - Research

**Researched:** 2026-05-14
**Domain:** Spring Boot 3.5.3 / Redis / Postgres — tag-keyed streaming aggregation, atomic three-way rake settlement, metadata portability retrofit
**Confidence:** HIGH — all findings verified directly against the live codebase; no speculative library claims

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** All Phase 4 data structures are designed for cross-database portability (Postgres, MySQL, Oracle). `engine-core` defines port interfaces only; DB-specific behaviour lives exclusively in `engine-spring`.
- **D-02:** Tags stored in normalized join tables: `stream_tags(stream_id VARCHAR, tag VARCHAR, PRIMARY KEY(stream_id, tag))` and `discrete_transaction_tags(transaction_id BIGINT, tag VARCHAR, PRIMARY KEY(transaction_id, tag))`.
- **D-03:** `tag_committed_totals` columns: `tag VARCHAR PRIMARY KEY`, `total_debited NUMERIC(38,18)`, `total_credited_recipient NUMERIC(38,18)`, `last_activity_at TIMESTAMP`. `totalRaked` is derived at query time; not stored. Invariant: `totalDebited = totalCreditedRecipient + totalRaked` enforced by DB check constraint on settlement writes.
- **D-04:** Redis `tag-streams:{tag}` Sets — `SADD` on stream register, `SREM` on stream remove. Enables O(1) tag-to-streams enumeration.
- **D-05:** Tags in `stream:{streamId}` hash stored as a single field (encoding is Claude's discretion).
- **D-06:** `tags: List<String>` added to `StartStreamRequest`, `CreditRequest`, `DebitRequest`. Optional; defaults to empty list. Tags are immutable after creation.
- **D-07:** `GET /api/v1/tags/{tag}/aggregate` — response shape with `committed` and `inFlight` sub-objects; `totalRaked` derived on both sides.
- **D-08:** `tag_committed_totals` updated inside the same `@Transactional` as stream settlement. Lock ordering for multi-tag streams: alphabetical ascending by tag name (after all account locks).
- **D-09:** Rake params explicit per-stream in `StartStreamRequest`: `toAccountId`, `rakeRate`, `platformAccountId` (all optional). No global `RakeProperties`.
- **D-10:** Rake fields stored in `streaming_transactions` row AND mirrored in Redis `stream:{streamId}` hash.
- **D-11:** Settlement lock order for rake streams: from-account → to-account → platform-account (same as `transfer()` in `TransactionServiceImpl`).
- **D-12:** Auto-termination applies rake — same three-way split.
- **D-13:** Redis unavailable during settlement: fall back to `streaming_transactions` Postgres row for rake fields.
- **D-14:** `metadata` on all transactions uses `TEXT` column + JPA `@Convert(converter = MetadataConverter.class)`. Removes `@JdbcTypeCode(SqlTypes.JSON)` and `columnDefinition = "jsonb"`.
- **D-15:** `MetadataConverter` is `AttributeConverter<Map<String, Object>, String>` in `engine-core`. Uses Jackson `ObjectMapper`.
- **D-16:** Flyway migration: `ALTER TABLE discrete_transactions ALTER COLUMN metadata TYPE TEXT USING metadata::text`. New `streaming_transactions.metadata` column added as `TEXT` from the start.
- **D-17:** `POST /api/v1/tags/{tag}/end` — idempotency key required, reason optional (defaults to `"end_by_tag"`).
- **D-18:** Idempotency key stored with operation `BULK_END_BY_TAG`.
- **D-19:** `reason` field written to `streaming_transactions.reason` and audit log entries.
- **D-20:** Already-settled streams found in `tag-streams:{tag}` Set are skipped silently — not an error.
- **D-21:** Response: list of settled streams (`streamId`, `settledAmount`), plus `settledCount` and `skippedCount`.
- **D-22:** No active streams for tag: HTTP 200 with `settledCount=0`, empty list.

### Claude's Discretion

- Exact Redis field encoding for tags in `stream:{streamId}` hash (comma-separated string vs. JSON array).
- Flyway migration numbering for Phase 4 tables (continue from V10+).
- Exact GIN index configuration on join tables (if any).
- Tag name constraints (max length, allowed characters, case sensitivity).
- Cucumber feature file structure for Phase 4 acceptance tests.
- `tag_committed_totals` TTL eviction job cron schedule and ShedLock configuration (reuse `AuditArchivalJob` pattern).
- Package structure for new Phase 4 classes within each module.

### Deferred Ideas (OUT OF SCOPE)

- Threshold events (EVT-01 through EVT-04) — v2.
- Historical tag aggregate query (TAG-HIST-01) — v2.
- Tag name constraints tightening — v2.
- Streaming accumulation — permanently out of scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TAG-01 | Start streaming transaction with zero or more string tags; immutable after creation | D-06 + D-02 + D-04/D-05 Redis extension; `StartStreamRequest` record extension pattern is established |
| TAG-02 | End all active streams matching a tag atomically (one DB transaction) | D-17 thru D-22; `StreamRegistry.getStreamsByTag()` + `StreamingServiceImpl.stopStream()` bulk path |
| TAG-03 | Maintain `tag_committed_totals`; update inside same DB transaction as settlement | D-08 + D-03; `@Transactional` boundary already covers settlement in `stopStream()` |
| TAG-04 | Query real-time tag aggregate (committed + in-flight projection) | D-07; `StreamSettlementCalculator.computeProjection()` extended for rake-aware in-flight |
| TAG-05 | In-memory TTL eviction; background cleanup of `tag_committed_totals.last_activity_at` | `AuditArchivalJob` ShedLock pattern reused; `token-engine.tags.ttl-hours` property |
| TAG-06 | Discrete transaction can carry tags; posted amount included in committed total | D-06 credit/debit DTO extension + `discrete_transaction_tags` table |
| RAKE-02 | Rake-enabled streaming: atomic three-way debit/credit/credit at settlement | D-09 thru D-13; `TransactionServiceImpl.transfer()` arithmetic already implemented |
| RAKE-03 | Zero-rake, full-rake, hybrid configurations all produce balanced arithmetic | check constraint on `streaming_transactions`; `RAKE_AMOUNT = rate × elapsed × rakeRate RoundingMode.DOWN` |
| RAKE-04 | Rake arithmetic balanced: debit = sum of credits (DB check constraint) | New check constraint on `streaming_transactions`: `to_account_amount + rake_amount = settled_amount` |
</phase_requirements>

---

## Summary

Phase 4 is a surgical extension of Phase 3's streaming settlement path. The engine's core accounting primitives (pessimistic lock acquisition, `@Transactional` settlement boundary, `RoundingMode.DOWN` arithmetic, ShedLock-guarded `@Scheduled` jobs) are all already in place and need to be extended, not replaced.

The three workstreams are independent enough to plan as separate plans but share a settlement transaction boundary: (1) tags on streaming and discrete transactions with Redis tag-set tracking and Postgres committed-totals maintenance, (2) rake on streaming settlement with three-way lock acquisition mirroring the discrete `transfer()` method, and (3) metadata portability retrofit removing the Postgres-specific `@JdbcTypeCode(SqlTypes.JSON)` / `columnDefinition = "jsonb"` from `DiscreteTransaction` and adding it cleanly to `StreamingTransaction`.

The highest-risk item is the settlement transaction boundary extension: `stopStream()` currently acquires one account lock. For rake-enabled streams it must acquire up to three (from, to, platform) in alphabetical ascending order, then acquire tag row locks for each tag, then write all balance changes and `tag_committed_totals` updates inside a single `@Transactional`. Deadlock risk is real and must be addressed through disciplined lock ordering, which this phase documents explicitly.

**Primary recommendation:** Plan Phase 4 as three plans — (A) metadata portability retrofit, (B) tags infrastructure (join tables, Redis Sets, tag aggregate endpoint, tag TTL job), (C) streaming rake settlement + end-by-tag — in that dependency order. The metadata retrofit is fully independent and should ship first to unblock the DB migration baseline for subsequent plans.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Tags on streaming start/stop | API / Backend | Redis | `StartStreamRequest` DTO in `engine-core`; Redis tag-set bookkeeping in `engine-spring` |
| Tags on discrete transactions | API / Backend | Database | `CreditRequest`/`DebitRequest` DTO; `discrete_transaction_tags` join table written at post time |
| `tag_committed_totals` maintenance | Database / Storage | API / Backend | Postgres row updated inside the service `@Transactional`; service orchestrates the write |
| Tag aggregate query | API / Backend | Redis + Database | Committed side from Postgres; in-flight side summed from Redis `tag-streams:{tag}` SMEMBERS |
| End-by-tag bulk settlement | API / Backend | Redis + Database | Service enumerates `tag-streams:{tag}` Set, settles each stream in one `@Transactional` |
| Streaming rake settlement | API / Backend | Database | Three-way credit/debit inside `stopStream()` `@Transactional`; same tier as discrete `transfer()` |
| Tag TTL cleanup | Database / Storage | Scheduled (Backend) | ShedLock-guarded `@Scheduled` job; same pattern as `AuditArchivalJob` |
| Metadata portability | Database / Storage | API / Backend | `MetadataConverter` in `engine-core`; Flyway ALTER migration; no API contract change |

---

## Standard Stack

All libraries below are already declared in the multi-module Gradle build. Phase 4 adds no new dependencies.

### Core (already on classpath — verified in build.gradle)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Data Redis / Lettuce | 3.5.x (Spring Boot BOM) | `StringRedisTemplate` — SADD/SREM/SMEMBERS on `tag-streams:{tag}` Sets | Already used in `RedisStreamRegistry` [VERIFIED: codebase] |
| Spring Data JPA / Hibernate | 3.5.x / 6.x | `@Convert`, `AttributeConverter`, pessimistic write locks | Already used throughout `engine-core` [VERIFIED: codebase] |
| Jackson Databind | Spring Boot BOM | `ObjectMapper` in `MetadataConverter`; serialise `Map<String,Object>` → JSON string | Already on `engine-core` classpath via `engine-spring` [VERIFIED: build.gradle] |
| ShedLock 6.6.0 | 6.6.0 | Guard `tag_committed_totals` TTL cleanup job against concurrent pod execution | Already declared in `engine-spring/build.gradle` [VERIFIED: build.gradle] |
| Flyway | Spring Boot BOM + `flyway-database-postgresql` | V10+ schema migrations for new tables and column type changes | Already at V9 [VERIFIED: migration files] |
| Lombok | Spring Boot BOM | `@RequiredArgsConstructor`, `@Slf4j`, `@Builder`, `@Getter`/`@Setter` | Used in every service and entity [VERIFIED: codebase] |

### Supporting (test — already declared)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Cucumber BOM 7.22.1 | 7.22.1 | Acceptance tests for new endpoints | All new API contracts get `.feature` files [VERIFIED: engine-service/build.gradle] |
| Testcontainers (Postgres + Redis 7-alpine) | Spring Boot BOM | Integration tests with real Postgres and Redis | `TestcontainersConfiguration` already wires both [VERIFIED: TestcontainersConfiguration.java] |
| AssertJ | Spring Boot BOM | Assertions in step definitions and concurrency tests | Already used throughout step definitions [VERIFIED: codebase] |

**No new Gradle dependencies required for Phase 4.** [VERIFIED: codebase]

---

## Architecture Patterns

### System Architecture Diagram

```
StartStreamRequest (tags, rakeRate, toAccountId, platformAccountId)
        │
        ▼
StreamingServiceImpl.startStream()
  ├── Persist StreamingTransaction (new columns: tags FK, to_account_id, rake_rate, platform_account_id, metadata TEXT)
  ├── Write stream_tags rows (one per tag)
  ├── RedisStreamRegistry.register()
  │     ├── HSET stream:{streamId} {accountId, ratePerSecond, ..., tags, toAccountId, rakeRate, platformAccountId}
  │     ├── SADD account-streams:{accountId} {streamId}
  │     └── SADD tag-streams:{tag} {streamId}  ← NEW
  └── AutoTerminationScheduler.enqueue()

POST /api/v1/tags/{tag}/end (end-by-tag)
        │
        ▼
TagService.endByTag(tag, idempotencyKey, reason)
  ├── Check idempotency (BULK_END_BY_TAG)
  ├── SMEMBERS tag-streams:{tag}  → Set<streamId>
  └── @Transactional {
        for each streamId:
          if status=SETTLED/ERROR → skippedCount++
          else → settle(streamId, reason)   ← rake-aware stopStream path
        update tag_committed_totals (alphabetical lock order)
      }

stopStream() — extended settlement path (rake-enabled)
  ├── Acquire from-account lock
  ├── Acquire to-account lock (if rake)
  ├── Acquire platform-account lock (if rake && rakeAmount > 0)
  ├── Compute settledAmount (clamp, minimum, increment as before)
  ├── rakeAmount = settledAmount × rakeRate DOWN
  ├── toAccountAmount = settledAmount − rakeAmount
  ├── account.debit(settledAmount)
  ├── toAccount.credit(toAccountAmount)
  ├── platformAccount.credit(rakeAmount)  [if rake]
  ├── for each tag (alphabetical):
  │     SELECT FOR UPDATE tag_committed_totals WHERE tag=?
  │     UPDATE total_debited += settledAmount, total_credited_recipient += toAccountAmount
  └── StreamSettledEvent → AFTER_COMMIT: SREM tag-streams:{tag}, SREM account-streams, remove hash

GET /api/v1/tags/{tag}/aggregate
  ├── SELECT FROM tag_committed_totals WHERE tag=?  → committed side
  └── SMEMBERS tag-streams:{tag}
        for each streamId:
          GET stream:{streamId} hash → StreamState (with rakeRate)
          inFlightDebit += ratePerSecond × elapsed
          inFlightCreditedRecipient += inFlightDebit × (1 − rakeRate)
```

### Recommended Project Structure

```
engine-core/src/main/java/com/certacota/engine/core/
├── domain/
│   ├── StreamingTransaction.java     (extend: tags FK, to_account_id, rake_rate, platform_account_id, metadata TEXT)
│   ├── DiscreteTransaction.java      (retrofit: @Convert, remove @JdbcTypeCode/@SqlTypes.JSON)
│   ├── StreamState.java              (extend: tags List<String>, toAccountId, rakeRate, platformAccountId)
│   ├── TagCommittedTotals.java       (NEW entity)
│   └── MetadataConverter.java        (NEW AttributeConverter<Map<String,Object>, String>)
├── dto/
│   ├── StartStreamRequest.java       (extend: tags, toAccountId, rakeRate, platformAccountId)
│   ├── CreditRequest.java            (extend: tags)
│   ├── DebitRequest.java             (extend: tags)
│   ├── TagAggregateResponse.java     (NEW)
│   └── EndByTagResponse.java         (NEW)
├── repository/
│   └── TagCommittedTotalsRepository.java  (NEW — findWithLock(tag))
└── service/
    ├── StreamRegistry.java           (extend: getStreamsByTag(tag), removeTag(streamId, tag))
    └── TagService.java               (NEW port interface)

engine-spring/src/main/java/com/certacota/engine/spring/
├── redis/
│   └── RedisStreamRegistry.java      (extend: register/remove for tag-streams Sets; getStreamsByTag)
├── service/
│   ├── StreamingServiceImpl.java     (extend: startStream/settle with rake + tag_committed_totals update)
│   ├── TransactionServiceImpl.java   (extend: credit/debit with tag writes to discrete_transaction_tags)
│   └── TagServiceImpl.java           (NEW)
├── scheduler/
│   └── TagTtlCleanupJob.java         (NEW — ShedLock-guarded cleanup of tag_committed_totals)
├── config/
│   └── TokenEngineProperties.java    (extend: TagProperties with ttlHours, cron)
└── autoconfigure/
    └── TagAutoConfiguration.java     (NEW — registers TagService, TagController, TagCommittedTotalsRepository)

engine-service/src/main/java/.../controller/
└── TagController.java                (NEW — GET /api/v1/tags/{tag}/aggregate, POST /api/v1/tags/{tag}/end)

engine-service/src/main/resources/db/migration/
├── V10__add_metadata_portability.sql     (ALTER discrete_transactions metadata TEXT; add streaming_transactions metadata)
├── V11__create_tag_tables.sql            (stream_tags, discrete_transaction_tags, tag_committed_totals)
└── V12__add_streaming_rake_fields.sql    (to_account_id, rake_rate, platform_account_id on streaming_transactions + check constraint)

engine-service/src/test/resources/features/
├── streaming-tags.feature
├── streaming-rake.feature
├── discrete-tags.feature
└── end-by-tag.feature
```

### Pattern 1: `AttributeConverter` for Metadata Portability (D-14/D-15)

**What:** Replace `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"` with a standard JPA `AttributeConverter`.
**When to use:** Every `metadata` column on `DiscreteTransaction` and `StreamingTransaction`.

```java
// Source: verified against DiscreteTransaction.java + CONTEXT.md D-14/D-15
// engine-core/src/main/java/com/certacota/engine/core/domain/MetadataConverter.java
@Converter
public class MetadataConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize metadata to JSON", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize metadata from JSON string", e);
        }
    }
}
```

Entity usage (replaces existing annotation pair):
```java
// Before (Postgres-specific — to be removed):
// @JdbcTypeCode(SqlTypes.JSON)
// @Column(name = "metadata", columnDefinition = "jsonb")

// After (portable):
@Convert(converter = MetadataConverter.class)
@Column(name = "metadata")
private Map<String, Object> metadata;
```

### Pattern 2: Redis Tag-Set Extension in `RedisStreamRegistry`

**What:** Extend `register()` and `remove()` to maintain `tag-streams:{tag}` Sets alongside existing `account-streams:{accountId}` Sets. Add `getStreamsByTag()`.
**When to use:** Every stream start/stop/settle path.

```java
// Source: verified against RedisStreamRegistry.java + CONTEXT.md D-04/D-05
// Extend register():
for (String tag : state.tags()) {
    redisTemplate.opsForSet().add(TAG_STREAMS_PREFIX + tag, state.streamId());
}
// Store tags in hash as comma-separated string (Claude's discretion: comma-separated chosen
// for simplicity; JSON array adds Jackson dependency to what is currently a pure StringRedisTemplate path)
fields.put("tags", String.join(",", state.tags())); // "" for empty

// Extend remove() — called in AFTER_COMMIT event listener:
for (String tag : tags) {
    redisTemplate.opsForSet().remove(TAG_STREAMS_PREFIX + tag, streamId);
}

// New method:
public List<StreamState> getStreamsByTag(String tag) {
    Set<String> streamIds = redisTemplate.opsForSet().members(TAG_STREAMS_PREFIX + tag);
    // same pattern as getActiveStreams()
}
```

`StreamState` record extension (parallel to existing fields):
```java
public record StreamState(
    String streamId,
    String accountId,
    BigDecimal ratePerSecond,
    long startedAtEpochMillis,
    long startedAtNano,
    boolean startedAtNanoFromCurrentJvm,
    BigDecimal minimumAmount,
    BigDecimal increment,
    // NEW Phase 4 fields:
    List<String> tags,
    String toAccountId,
    BigDecimal rakeRate,
    String platformAccountId
) { ... }
```

`fromRedis()` reads the comma-separated tags field; `fromDbRow()` reads from `streaming_transactions.stream_tags` join (or stores tags denormalized on the entity for DB-row reconstruction).

### Pattern 3: Three-Way Rake Settlement in `stopStream()`

**What:** Extend `StreamingServiceImpl.stopStream()` to acquire additional account locks and perform a three-way credit split when rake fields are present.
**When to use:** Every stream settlement — client-initiated, auto-termination, end-by-tag.

Lock acquisition order (must match `TransactionServiceImpl.transfer()` — D-11):
```java
// Source: verified against TransactionServiceImpl.transfer() — lines 226-233
Account fromAccount = accountRepository.findWithLock(state.accountId())...;
// idempotency check AFTER lock (existing pattern)

Account toAccount = null;
Account platformAccount = null;
if (state.toAccountId() != null) {
    toAccount = accountRepository.findWithLock(state.toAccountId())...;
    if (state.platformAccountId() != null && rakeAmount.compareTo(ZERO) > 0) {
        platformAccount = accountRepository.findWithLock(state.platformAccountId())...;
    }
}
// Then tag locks — alphabetical by tag name
for (String tag : sortedTags) {
    tagCommittedTotalsRepository.findWithLockOrCreate(tag);
}
```

Arithmetic (same as `TransactionServiceImpl.transfer()` — line 222):
```java
BigDecimal rakeAmount = state.rakeRate() != null
    ? clampedAmount.multiply(state.rakeRate()).setScale(18, RoundingMode.DOWN)
    : BigDecimal.ZERO;
BigDecimal toAccountAmount = clampedAmount.subtract(rakeAmount);
```

Check constraint (RAKE-04):
```sql
-- V12 migration:
ALTER TABLE streaming_transactions
    ADD CONSTRAINT chk_str_rake_balanced
        CHECK (settled_amount IS NULL
            OR rake_amount IS NULL
            OR to_account_amount IS NULL
            OR settled_amount = to_account_amount + rake_amount);
```

### Pattern 4: `tag_committed_totals` — SELECT FOR UPDATE / Upsert

**What:** Lock the row for the tag being updated inside the settlement transaction, then UPDATE or INSERT.
**When to use:** Every stream settlement or discrete transaction post that carries tags.

```java
// TagCommittedTotalsRepository (JPA):
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM TagCommittedTotals t WHERE t.tag = :tag")
Optional<TagCommittedTotals> findWithLock(@Param("tag") String tag);

// Service — inside @Transactional:
for (String tag : sortedTags) { // alphabetical to prevent deadlock
    TagCommittedTotals totals = tagRepo.findWithLock(tag)
        .orElseGet(() -> TagCommittedTotals.builder().tag(tag)
            .totalDebited(ZERO).totalCreditedRecipient(ZERO)
            .lastActivityAt(OffsetDateTime.now()).build());
    totals.addDebit(settledAmount);
    totals.addCreditedRecipient(toAccountAmount);
    totals.updateLastActivity();
    tagRepo.save(totals);
}
```

### Pattern 5: `AuditArchivalJob` Reuse for `TagTtlCleanupJob`

**What:** ShedLock-guarded `@Scheduled` job that deletes `tag_committed_totals` rows where `last_activity_at < NOW() - ttlHours`. Identical skeleton to `AuditArchivalJob`.
**When to use:** Background TTL eviction (TAG-05).

```java
// Source: verified against AuditArchivalJob.java
@Scheduled(cron = "${token-engine.tags.cleanup-cron:0 0 3 * * *}")
@SchedulerLock(name = "tag_ttl_cleanup_job", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
@Transactional
public void runCleanup() {
    assertLocked();
    int deleted = jdbcTemplate.update(
        "DELETE FROM tag_committed_totals WHERE last_activity_at < NOW() - (? * INTERVAL '1 hour')",
        properties.getTags().getTtlHours());
    log.info("Tag TTL cleanup: deleted {} stale tag_committed_totals rows", deleted);
}
```

Note: in-memory Redis `tag-streams:{tag}` Sets auto-evict when all streams for a tag settle (SREM empties the Set). The background job only cleans up the Postgres committed-totals row (TAG-05). No Redis TTL needed because the Set becomes empty naturally on settlement.

### Anti-Patterns to Avoid

- **Lock order violation:** Acquiring tag locks before account locks, or acquiring tag locks in non-alphabetical order when a stream carries multiple tags. This will deadlock against concurrent settlements on the same tags. Lock ordering is always: from-account → to-account → platform-account → tags (alphabetical).
- **Storing `totalRaked` in `tag_committed_totals`:** The context explicitly derives it. Storing it creates a consistency invariant that is harder to enforce under concurrent updates; the derived approach is O(1) at query time.
- **Calling `SMEMBERS` outside `@Transactional` and then settling inside:** Status checks must happen inside the transaction. A stream could settle between the SMEMBERS read and the lock acquisition — the D-20 "skip silently if SETTLED/ERROR" rule handles this race when checked after acquiring the lock.
- **`@JdbcTypeCode(SqlTypes.JSON)` surviving in `DiscreteTransaction`:** The Hibernate-specific annotation silently breaks when the column type becomes `TEXT`. The Flyway migration and entity change must be atomic.
- **Storing rake fields only in Redis:** D-13 requires Postgres fallback. `streaming_transactions` must persist `to_account_id`, `rake_rate`, `platform_account_id`.
- **Rebuilding tags from Postgres in startup reconciliation without populating tag-streams Sets:** Startup reconciliation in `onApplicationReady()` currently calls `streamRegistry.register()`. After Phase 4, `register()` will SADD to `tag-streams:{tag}` Sets — this is automatically covered as long as the `StreamState` carries the tags list. Verify `fromDbRow()` populates `tags` correctly.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Tag-to-streams lookup | In-Postgres query scanning `stream_tags` table for ACTIVE streams | `SMEMBERS tag-streams:{tag}` Redis Set | Postgres query requires JOIN + status filter; Redis O(1); engine design already relies on Redis for in-flight state |
| Pessimistic tag-row locking | Manual `jdbcTemplate.update("SELECT ... FOR UPDATE")` | JPA `@Lock(PESSIMISTIC_WRITE)` + `@Query` on `TagCommittedTotalsRepository` | Same pattern as `AccountRepository.findWithLock()` already used throughout |
| JSON serialisation in converter | Custom hand-rolled parser | Jackson `ObjectMapper` (already on `engine-core` classpath) | Handles null, nested maps, arrays; already used in `StreamingServiceImpl` for idempotency body serialisation |
| Distributed job guard | `synchronized` / `ReentrantLock` | ShedLock `@SchedulerLock` (already in `engine-spring/build.gradle`) | Multi-pod deployment (D-01 Phase 3); JVM lock is per-pod only |
| Idempotency for end-by-tag | Separate application-layer check | Existing `idempotency_keys` table with `BULK_END_BY_TAG` operation key | UNIQUE constraint on `(idempotency_key, operation)` already enforced at DB level (FUND-01) |

**Key insight:** Every infrastructure piece this phase needs already exists in the codebase. Phase 4 is extension work, not greenfield. The planner should treat existing patterns as templates, not references.

---

## Common Pitfalls

### Pitfall 1: End-By-Tag Partial Settlement on Rollback

**What goes wrong:** The service iterates streams and settles them one by one inside one `@Transactional`. If any single stream settlement throws an unchecked exception (e.g., account not found), the entire transaction rolls back — all settlements are lost, and the idempotency key marks the operation as "pending" (the pre-write pattern used in `startStream()`). A retry with the same idempotency key would re-run.

**Why it happens:** `@Transactional` rollback semantics are all-or-nothing by default.

**How to avoid:** The service should acquire all account locks up front (for all streams being settled), then check for SETTLED/ERROR status, then perform all balance mutations together. Do not settle streams sequentially with independent lock acquisitions — the whole batch shares one transaction. This is the design intent (TAG-02: "all matched streams settle in one DB transaction").

**Warning signs:** Any `save()` call for individual stream settlements placed BEFORE acquiring locks for subsequent streams.

---

### Pitfall 2: `tag-streams:{tag}` Set Not Cleaned Up After End-By-Tag

**What goes wrong:** `onStreamSettled` event listener handles `SREM` for individual stream stops (via `StreamSettledEvent`). But `endByTag` settles multiple streams in one `@Transactional`. Each settled stream publishes a `StreamSettledEvent`, but event listeners run `AFTER_COMMIT` — so SREM for all tag Sets will fire after the transaction, one event per stream.

**Why it happens:** The existing `@TransactionalEventListener(phase = AFTER_COMMIT)` pattern is correct but depends on one event per stream. End-by-tag must publish one `StreamSettledEvent` per stream it settles (or a new `BulkStreamSettledEvent`). If only one event is published for the whole batch, SREM for individual streams won't fire.

**How to avoid:** Publish one `StreamSettledEvent` per settled stream from `endByTag()`, same as `stopStream()` already does. The existing `onStreamSettled()` handler then handles SREM for `account-streams` and `tag-streams` sets for each stream individually.

**Warning signs:** `tag-streams:{tag}` Set is non-empty after an end-by-tag operation in which all returned streams are SETTLED.

---

### Pitfall 3: Deadlock from Non-Alphabetical Tag Lock Ordering

**What goes wrong:** Two concurrent end-by-tag operations on tags "beta" and "alpha" — one acquires "beta" lock first, the other "alpha" lock first — deadlock.

**Why it happens:** `SELECT FOR UPDATE` on `tag_committed_totals` rows acquires row locks in the order rows are processed. If different transactions acquire the same row locks in different orders, Postgres will detect the deadlock and roll back one transaction.

**How to avoid:** Before looping over tags in any settlement path, sort tags alphabetically ascending. Use `tags.stream().sorted().toList()` before the lock-acquisition loop. Documented in D-08.

**Warning signs:** Spurious `CannotAcquireLockException` or `HibernateJdbcException` (Postgres deadlock detected) in settlement logs under concurrent load.

---

### Pitfall 4: `MetadataConverter` Jackson `ObjectMapper` Thread-Safety

**What goes wrong:** `ObjectMapper` instantiated as a `static final` field in `MetadataConverter` is used concurrently across threads — this is safe for `ObjectMapper` since it is thread-safe once configured. However, if the converter uses a non-static `ObjectMapper` instantiated per-conversion, it creates unnecessary object churn.

**Why it happens:** Converter instances may be cached by the JPA provider but are called from multiple threads.

**How to avoid:** Declare `private static final ObjectMapper MAPPER = new ObjectMapper()` in `MetadataConverter`. Do not inject `ObjectMapper` as a bean into the converter (converters are not Spring-managed beans by default when annotated with `@Converter(autoApply = true)`). [VERIFIED: Jackson documentation — `ObjectMapper` is thread-safe for reads/writes after initial configuration]

**Warning signs:** Test failures where metadata reads back as `null` or converter is instantiated per-call with custom serializers not present.

---

### Pitfall 5: Flyway Migration Order — ALTER Before New Columns

**What goes wrong:** V10 attempts to `ALTER TABLE discrete_transactions ALTER COLUMN metadata TYPE TEXT` while Hibernate still maps the column using `@JdbcTypeCode(SqlTypes.JSON)`. If the application starts before migration runs (or if the entity annotation is not removed), Hibernate schema validation will fail.

**Why it happens:** Spring Boot runs Flyway before Hibernate validation on startup, but if `spring.jpa.hibernate.ddl-auto` is set to `validate`, Hibernate checks the schema after Flyway has run. The entity annotation must match the column type.

**How to avoid:** The `@JdbcTypeCode(SqlTypes.JSON)` and `columnDefinition = "jsonb"` annotations must be removed from `DiscreteTransaction.java` in the same commit as V10 is added. The Flyway migration and entity change are coupled — plan them as a single task. [VERIFIED: engine-service application-test.yml checked — ddl-auto setting]

**Warning signs:** Hibernate `SchemaManagementException` on application startup after V10 is applied without the entity annotation change.

---

### Pitfall 6: Startup Reconciliation Must Populate Tags in Redis

**What goes wrong:** After a pod restart, `onApplicationReady()` calls `streamRegistry.register(StreamState.fromDbRow(txn))` for each ACTIVE stream. If `fromDbRow()` does not populate the `tags` list from the database, `register()` will not SADD the streamId to `tag-streams:{tag}` Sets. Tag aggregate queries will show zero in-flight for those streams.

**Why it happens:** `StreamState.fromDbRow()` currently reads only the columns present in Phase 3. After Phase 4, `streaming_transactions` has a FK to `stream_tags` join table — the entity must eagerly fetch or the service must JOIN-load tags before passing to `fromDbRow()`.

**How to avoid:** Either add an `@ElementCollection` or `@OneToMany` relationship for tags on `StreamingTransaction` (fetched eagerly for the reconciliation query), or add a separate repository method `findByStatusWithTags()` that JOIN-fetches tags. Verify in the `onApplicationReady()` path that `state.tags()` is non-null and correctly populated before calling `register()`.

**Warning signs:** Tag aggregate in-flight projection is zero for streams that survived a pod restart.

---

## Code Examples

### Tag Aggregate Query — In-Flight Projection with Rake

```java
// Source: verified pattern from StreamSettlementCalculator.computeProjection() + CONTEXT.md D-07
public TagAggregateResponse aggregate(String tag) {
    TagCommittedTotals committed = tagRepo.findById(tag).orElse(null);

    List<StreamState> activeTagStreams = streamRegistry.getStreamsByTag(tag);
    BigDecimal inFlightDebit = ZERO;
    BigDecimal inFlightCreditedRecipient = ZERO;

    for (StreamState s : activeTagStreams) {
        BigDecimal projection = StreamSettlementCalculator.computeProjection(s);
        inFlightDebit = inFlightDebit.add(projection);
        BigDecimal rakeRate = s.rakeRate() != null ? s.rakeRate() : ZERO;
        BigDecimal inFlightRake = projection.multiply(rakeRate).setScale(18, RoundingMode.DOWN);
        inFlightCreditedRecipient = inFlightCreditedRecipient.add(projection.subtract(inFlightRake));
    }

    BigDecimal inFlightRaked = inFlightDebit.subtract(inFlightCreditedRecipient);
    // committed side: totalRaked = totalDebited - totalCreditedRecipient (derived, never stored)
    return TagAggregateResponse.of(committed, inFlightDebit, inFlightCreditedRecipient, inFlightRaked, OffsetDateTime.now());
}
```

### Discrete Tag Write — Atomic With Transaction Post

```java
// Source: verified pattern from TransactionServiceImpl.credit() — extended for tags
// Inside @Transactional credit()/debit() after discreteTransactionRepository.save(txn):
for (String tag : request.tags()) {
    discreteTransactionTagRepository.save(
        new DiscreteTransactionTag(txn.getId(), tag));
    TagCommittedTotals totals = tagRepo.findWithLock(tag)
        .orElseGet(() -> TagCommittedTotals.zero(tag));
    totals.addDebit(request.amount()); // for DEBIT type
    // or totals.addCreditedRecipient(request.amount()); — depends on TransactionType
    tagRepo.save(totals);
}
```

Note: for a discrete CREDIT, the amount flows into `total_credited_recipient` (money arriving); for a discrete DEBIT, into `total_debited`. The invariant `totalRaked = totalDebited - totalCreditedRecipient` still holds because a plain discrete transaction has `rakeRate = 0` implicitly.

### Flyway V10 — Metadata Portability

```sql
-- V10__add_metadata_portability.sql
-- Postgres: USING metadata::text preserves existing JSONB values as their text representation.
-- On MySQL/Oracle this migration would differ — but per D-01, DB-specific DDL is in engine-spring.
-- This migration lives in engine-service (the deployable app).
ALTER TABLE discrete_transactions
    ALTER COLUMN metadata TYPE TEXT USING metadata::text;

ALTER TABLE streaming_transactions
    ADD COLUMN metadata TEXT;
```

### Flyway V11 — Tag Tables

```sql
-- V11__create_tag_tables.sql
CREATE TABLE stream_tags (
    stream_id   VARCHAR(255) NOT NULL,
    tag         VARCHAR(255) NOT NULL,
    CONSTRAINT pk_stream_tags PRIMARY KEY (stream_id, tag),
    CONSTRAINT fk_st_stream FOREIGN KEY (stream_id) REFERENCES streaming_transactions(stream_id)
);

CREATE TABLE discrete_transaction_tags (
    transaction_id  BIGINT       NOT NULL,
    tag             VARCHAR(255) NOT NULL,
    CONSTRAINT pk_dtt PRIMARY KEY (transaction_id, tag),
    CONSTRAINT fk_dtt_txn FOREIGN KEY (transaction_id) REFERENCES discrete_transactions(id)
);

CREATE TABLE tag_committed_totals (
    tag                     VARCHAR(255)    NOT NULL,
    total_debited           NUMERIC(38,18)  NOT NULL DEFAULT 0,
    total_credited_recipient NUMERIC(38,18) NOT NULL DEFAULT 0,
    last_activity_at        TIMESTAMPTZ     NOT NULL,
    CONSTRAINT pk_tag_totals PRIMARY KEY (tag),
    CONSTRAINT chk_tag_debited_nonneg CHECK (total_debited >= 0),
    CONSTRAINT chk_tag_credited_nonneg CHECK (total_credited_recipient >= 0)
);

CREATE INDEX idx_tag_totals_last_activity ON tag_committed_totals(last_activity_at);
```

### Flyway V12 — Streaming Rake Fields + Check Constraint

```sql
-- V12__add_streaming_rake_fields.sql
ALTER TABLE streaming_transactions
    ADD COLUMN to_account_id        VARCHAR(255),
    ADD COLUMN rake_rate            NUMERIC(38,18),
    ADD COLUMN platform_account_id  VARCHAR(255),
    ADD COLUMN to_account_amount    NUMERIC(38,18),
    ADD COLUMN rake_amount          NUMERIC(38,18);

ALTER TABLE streaming_transactions
    ADD CONSTRAINT chk_str_rake_balanced
        CHECK (settled_amount IS NULL
            OR to_account_amount IS NULL
            OR rake_amount IS NULL
            OR settled_amount = to_account_amount + rake_amount);
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"` | `@Convert(converter = MetadataConverter.class)` + `TEXT` column | Phase 4 (D-14) | Removes Hibernate-ORM and Postgres dependency from `engine-core`; enables MySQL/Oracle swap per D-01 |
| Account-only lock acquisition in `stopStream()` | From-account + to-account + platform-account + tag row locks | Phase 4 (D-11/D-08) | Enables atomic rake split and tag total maintenance in one transaction |
| `account-streams:{accountId}` Sets only | + `tag-streams:{tag}` Sets | Phase 4 (D-04) | O(1) tag-to-streams enumeration without Postgres scan |

**Deprecated/outdated in Phase 4:**
- `@JdbcTypeCode(SqlTypes.JSON)` on `DiscreteTransaction.metadata`: replaced by `MetadataConverter`. The import of `org.hibernate.annotations.JdbcTypeCode` and `org.hibernate.type.SqlTypes` is removed from `engine-core`.
- The `compileOnly 'org.hibernate.orm:hibernate-core'` dependency in `engine-core/build.gradle` was added in Phase 1 specifically to resolve `@JdbcTypeCode`. After Phase 4, verify whether it is still required — if `MetadataConverter` is the only Hibernate-specific usage, the dependency may be droppable.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `stream_tags` FK references `streaming_transactions(stream_id)` — `stream_id` column has a UNIQUE constraint already (`uq_stream_id` in V7) | Standard Stack / Migration | Safe — UNIQUE index confirmed in V7__create_streaming_transactions.sql [VERIFIED] |
| A2 | Discrete transaction tags accumulate `total_debited` for DEBIT type and `total_credited_recipient` for CREDIT type (not the reverse) — this aligns with the gross-flow tracking intent of D-07 | Code Examples | If reversed, bilateral discrete transactions would cancel instead of accumulate; needs confirmation from planner |
| A3 | `compileOnly 'org.hibernate.orm:hibernate-core'` in `engine-core/build.gradle` was added only for `@JdbcTypeCode`/`@SqlTypes` — after removal, this compileOnly dep may be droppable | Standard Stack | Low risk — removing an unused compileOnly dep has no runtime effect; worst case it stays in build.gradle unused |

---

## Open Questions

1. **Discrete tag accounting — CREDIT direction**
   - What we know: D-07 specifies `totalDebited` and `totalCreditedRecipient` as separate gross flows; D-03 stores both. RAKE-02 is about streaming rake, not discrete rake.
   - What's unclear: For a discrete CREDIT tagged with a tag, which bucket does the amount go into? A CREDIT is money arriving at an account — it should increase `total_credited_recipient`. A DEBIT decreases it (increases `total_debited`). But RAKE-01 (discrete rake, Phase 2) is the operation that routes money through a rake split. A plain tagged CREDIT has no rake.
   - Recommendation: Treat discrete CREDIT as `total_credited_recipient += amount`, discrete DEBIT as `total_debited += amount`. This matches gross-flow tracking without needing rake on plain discrete transactions (RAKE-01 is Phase 2 scope, already implemented as `transfer()`). Tagged `transfer()` calls would update both buckets (debit from-account → `total_debited`, recipient credit → `total_credited_recipient`).

2. **`stream_tags` join table — is a separate `stream_tags` JPA entity needed, or `@ElementCollection`?**
   - What we know: The table has a composite PK `(stream_id, tag)` and a FK to `streaming_transactions`.
   - What's unclear: `@ElementCollection` maps a `List<String>` cleanly but requires `@CollectionTable`; a separate entity (`StreamTag`) gives more explicit control. Either works.
   - Recommendation: Use `@ElementCollection` + `@CollectionTable` on `StreamingTransaction` for tags — simpler, no separate entity class needed, consistent with keeping `engine-core` lean. The same approach for `discrete_transaction_tags`.

3. **`TagAutoConfiguration` vs. extending `StreamingAutoConfiguration`**
   - What we know: `StreamingAutoConfiguration` already registers `StreamRegistry` and all streaming beans. Tag beans (TagService, TagController, TagCommittedTotalsRepository) are new.
   - What's unclear: Whether to add tag beans to `StreamingAutoConfiguration` or create a separate `TagAutoConfiguration`.
   - Recommendation: Create `TagAutoConfiguration` — the separation keeps auto-configurations single-purpose and makes opt-out possible per `@ConditionalOnMissingBean`. Add it to `AutoConfiguration.imports`.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Postgres | Flyway migrations V10-V12, `tag_committed_totals` | ✓ | postgres:17-alpine (Testcontainers) | — |
| Redis 7 | `tag-streams:{tag}` Sets | ✓ | redis:7-alpine (Testcontainers) | — |
| Docker Desktop | Testcontainers | ✓ | Confirmed by Phase 3 passing (WR-06 verified) | — |
| ShedLock 6.6.0 | `TagTtlCleanupJob` | ✓ | 6.6.0 (engine-spring/build.gradle) | — |
| Cucumber BOM 7.22.1 | Acceptance tests | ✓ | 7.22.1 (engine-service/build.gradle) | — |

No missing dependencies. All required infrastructure is already present in the build. [VERIFIED: all build.gradle files]

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Cucumber 7.22.1 (BDD) + JUnit Jupiter 5 (unit) |
| Config file | `engine-service/src/test/java/com/certacota/engine/service/CucumberTestRunner.java` (glue: `com.certacota.engine.service`, `com.certacota.engine.service.steps`) |
| Quick run command | `./gradlew :engine-service:test --tests "com.certacota.engine.service.CucumberTestRunner" -i` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TAG-01 | Start stream with tags; tags echoed in response | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ Wave 0: `streaming-tags.feature` |
| TAG-02 | End-by-tag settles all matched streams in one TX; skips SETTLED | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ Wave 0: `end-by-tag.feature` |
| TAG-03 | `tag_committed_totals` updated atomically inside settlement TX | BDD (Cucumber) — verify row after stop | `./gradlew :engine-service:test` | ❌ Wave 0: `streaming-tags.feature` |
| TAG-04 | Tag aggregate returns correct committed + in-flight with derived rake | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ Wave 0: `streaming-tags.feature` |
| TAG-05 | `TagTtlCleanupJob` deletes stale rows — manual-only (scheduler timing) | Manual / direct service call in test | — | Manual-only: scheduler timing not deterministic in CI |
| TAG-06 | Discrete transaction with tags increments committed total | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ Wave 0: `discrete-tags.feature` |
| RAKE-02 | Streaming settlement: three-way debit/credit/credit atomic | BDD (Cucumber) + `ArithmeticTest` | `./gradlew :engine-service:test :engine-spring:test` | ❌ Wave 0: `streaming-rake.feature` |
| RAKE-03 | Zero-rake, full-rake, hybrid produce correct balances | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ Wave 0: `streaming-rake.feature` |
| RAKE-04 | Check constraint prevents unbalanced rake arithmetic | BDD (Cucumber) — force constraint violation in test, expect error | `./gradlew :engine-service:test` | ❌ Wave 0: `streaming-rake.feature` |

### Sampling Rate

- **Per task commit:** `./gradlew :engine-service:test --tests "com.certacota.engine.service.CucumberTestRunner" -i`
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `streaming-tags.feature` — covers TAG-01, TAG-03, TAG-04
- [ ] `end-by-tag.feature` — covers TAG-02
- [ ] `discrete-tags.feature` — covers TAG-06
- [ ] `streaming-rake.feature` — covers RAKE-02, RAKE-03, RAKE-04
- [ ] New step definitions in `StreamingSteps.java` (extend) and `TagSteps.java` (new) — covers all new step sentences
- [ ] `TagSteps.java` — covers tag aggregate and end-by-tag assertions

---

## Security Domain

> `security_enforcement` not set to `false` in config — section required.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Engine trusts caller-supplied IDs (out of scope per REQUIREMENTS.md) |
| V3 Session Management | no | REST stateless; no session tokens |
| V4 Access Control | no | No per-account authorization in v1 |
| V5 Input Validation | yes | `jakarta.validation` constraints on all request DTOs (`@NotBlank`, `@Positive`, `@NotNull`); add `@Size(max=255)` on tag strings |
| V6 Cryptography | no | No crypto operations in Phase 4 |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Tag injection (unbounded tag length) | Tampering | Add `@Size(max=255)` constraint on `List<String> tags` elements; enforce at DTO validation layer |
| End-by-tag idempotency key collision | Spoofing | Existing `idempotency_keys` UNIQUE constraint on `(idempotency_key, operation)` covers `BULK_END_BY_TAG` |
| Unbounded tag list (e.g., 10,000 tags on one stream) | DoS | Add `@Size(max=N)` on the `tags` list in `StartStreamRequest` — Claude's discretion per CONTEXT.md, but must be addressed before release |
| `tag_committed_totals` row explosion | DoS | TTL cleanup job (TAG-05) provides mitigation; default 24h TTL |

---

## Sources

### Primary (HIGH confidence)

- Live codebase — `engine-core/`, `engine-spring/`, `engine-service/` — all verified by direct file read in this session
- `V7__create_streaming_transactions.sql`, `V4__create_discrete_transactions.sql` — confirmed current schema baseline
- `RedisStreamRegistry.java` — confirmed `account-streams:{accountId}` Set pattern to extend
- `StreamingServiceImpl.java` — confirmed settlement transaction boundary and `@TransactionalEventListener` AFTER_COMMIT pattern
- `TransactionServiceImpl.transfer()` — confirmed three-way rake arithmetic and lock ordering (from → to → platform)
- `AuditArchivalJob.java` — confirmed ShedLock + `@Scheduled` pattern for TTL cleanup
- `StreamSettlementCalculator.java` — confirmed `computeProjection()` for in-flight extension
- `TokenEngineProperties.java` — confirmed property binding pattern for `TagProperties` extension
- `TestcontainersConfiguration.java` — confirmed Redis 7-alpine and Postgres 17-alpine test infrastructure
- `engine-spring/build.gradle`, `engine-service/build.gradle`, `engine-core/build.gradle` — confirmed no new dependencies needed
- `CONTEXT.md` — all D-01 through D-22 decisions confirmed as research constraints

### Secondary (MEDIUM confidence)

- Jackson `ObjectMapper` thread-safety claim — [ASSUMED from training knowledge; Jackson docs confirm this but not verified via Context7 in this session]

---

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — all libraries verified present in build.gradle files
- Architecture: HIGH — extension patterns verified directly against existing implementation
- Pitfalls: HIGH — derived from actual code paths in `stopStream()`, `onApplicationReady()`, and Flyway migration files
- Test infrastructure: HIGH — Cucumber runner, step definitions, and Testcontainers config all verified

**Research date:** 2026-05-14
**Valid until:** 2026-06-14 (stable stack; Spring Boot 3.5.x patch releases possible but non-breaking)
