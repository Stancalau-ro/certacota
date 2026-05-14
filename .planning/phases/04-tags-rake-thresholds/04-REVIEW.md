---
phase: 04-tags-rake-thresholds
reviewed: 2026-05-14T18:00:00Z
depth: standard
files_reviewed: 38
files_reviewed_list:
  - engine-core/src/main/java/com/certacota/engine/core/domain/MetadataConverter.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/Account.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/TagCommittedTotals.java
  - engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java
  - engine-core/src/main/java/com/certacota/engine/core/repository/TagCommittedTotalsRepository.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/TagAggregateResponse.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/EndByTagResponse.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/StartStreamRequest.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/CreditRequest.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/DebitRequest.java
  - engine-core/src/main/java/com/certacota/engine/core/dto/PostTransferRequest.java
  - engine-core/src/main/java/com/certacota/engine/core/service/TagService.java
  - engine-core/src/main/java/com/certacota/engine/core/service/StreamRegistry.java
  - engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java
  - engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java
  - engine-spring/src/main/java/com/certacota/engine/spring/service/TagServiceImpl.java
  - engine-spring/src/main/java/com/certacota/engine/spring/redis/RedisStreamRegistry.java
  - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TagAutoConfiguration.java
  - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java
  - engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java
  - engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java
  - engine-spring/src/main/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJob.java
  - engine-service/src/main/java/com/certacota/engine/service/controller/TagController.java
  - engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java
  - engine-service/src/main/resources/db/migration/V10__add_metadata_portability.sql
  - engine-service/src/main/resources/db/migration/V11__create_tag_tables.sql
  - engine-service/src/main/resources/db/migration/V12__add_streaming_rake_fields.sql
  - engine-core/src/test/java/com/certacota/engine/core/domain/MetadataConverterTest.java
  - engine-core/src/test/java/com/certacota/engine/core/domain/TagCommittedTotalsTest.java
  - engine-spring/src/test/java/com/certacota/engine/spring/redis/RedisStreamRegistryTagIT.java
  - engine-spring/src/test/java/com/certacota/engine/spring/repository/StreamingTransactionRakeConstraintIT.java
  - engine-spring/src/test/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJobIT.java
  - engine-service/src/test/java/com/certacota/engine/service/steps/TagSteps.java
  - engine-service/src/test/java/com/certacota/engine/service/TagAutoConfigurationIT.java
  - engine-spring/src/test/java/com/certacota/engine/spring/ArithmeticTest.java
findings:
  critical: 5
  warning: 7
  info: 3
  total: 15
status: issues_found
---

# Phase 04: Code Review Report

**Reviewed:** 2026-05-14T18:00:00Z
**Depth:** standard
**Files Reviewed:** 38
**Status:** issues_found

## Summary

This phase introduces tag aggregation, bulk end-by-tag, streaming rake distribution, and TTL-based
tag cleanup. The core domain and migration work is solid. Five critical defects were found spanning
data-loss, incorrect accounting logic, a SQL portability violation, and a security gap. Seven
warnings cover correctness risks and robustness gaps that will surface under realistic load.

---

## Critical Issues

### CR-01: `stopStream` writes rake and toAccount amounts based on _pre-resolution_ values, then resolves from DB too late

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java:214-294`

**Issue:** Rake arithmetic (lines 214-216) uses `state.rakeRate()` and `state.toAccountId()` from
Redis. The "D-13 fallback" that resolves missing fields from the DB row only runs _after_ the rake
arithmetic and the `toAccount`/`platformAccount` lock acquisitions are already committed (lines
272-274). If Redis state is missing `rakeRate` (e.g., after a crash-recovery re-registration from
`fromDbRow`), the arithmetic path computes `rakeAmount = 0` and `toAccountAmount = clampedAmount`
(line 215-216: `rakeRate = BigDecimal.ZERO`), credits the full amount to `toAccount`, and then
records the DB row with `resolvedRakeRate` from the DB. The audit trail says rake was applied; the
account balances say it was not. This is silent data-loss on every post-restart stop of a stream
that had a rake configured.

The root cause: `StreamState.fromDbRow()` at line 69-84 of `StreamState.java` always sets
`startedAtNanoFromCurrentJvm = false` but never re-populates `rakeRate` or `toAccountId` from the
transaction row — it _does_ read both fields correctly. So the fallback _would_ work if it ran
first. The fix is to move the D-13 resolution block before the arithmetic:

```java
// Resolve rake fields FIRST (D-13 fallback)
String resolvedToAccountId = state.toAccountId() != null ? state.toAccountId() : txn.getToAccountId();
BigDecimal resolvedRakeRate = state.rakeRate() != null ? state.rakeRate() : txn.getRakeRate();
String resolvedPlatformAccountId = state.platformAccountId() != null
    ? state.platformAccountId() : txn.getPlatformAccountId();

// THEN compute rake arithmetic using resolved values
BigDecimal effectiveRakeRate = resolvedRakeRate != null ? resolvedRakeRate : BigDecimal.ZERO;
BigDecimal rakeAmount = clampedAmount.multiply(effectiveRakeRate).setScale(18, RoundingMode.DOWN);
BigDecimal toAccountAmount = clampedAmount.subtract(rakeAmount);
```

---

### CR-02: `endByTag` publishes `StreamSettledEvent` via inner `stopStream` call, which triggers `onStreamSettled` and removes streams from Redis mid-transaction

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TagServiceImpl.java:122`

**Issue:** `stopStream` (called from `endByTag`) publishes a `StreamSettledEvent`. The listener
`onStreamSettled` is annotated `@TransactionalEventListener(phase = AFTER_COMMIT)` — however, the
outer `endByTag` transaction has not committed yet. When `stopStream` is called with
`Propagation.REQUIRED` it joins the outer transaction. The event fires AFTER that outer transaction
commits, not after each individual inner call.

The real danger is different: because `stopStream` also calls `streamRegistry.remove()` inside the
`@TransactionalEventListener`, and the event fires after the _outer_ `endByTag` transaction
commits, the Redis removal of stream N will happen while later loop iterations in `endByTag`
(streams N+1, N+2 …) have already been settled in the same commit. This means that if the outer
transaction commits successfully but the JVM crashes before `onStreamSettled` completes for all
streams, some tag-set entries in Redis will never be cleaned up. This is a known acknowledged gap
(startup reconciliation), but the _iterator_ pattern in `endByTag` reads `candidates` from Redis
_before_ the loop (line 100), so the list is stable. The actual blocking issue is: because all
`StreamSettledEvent` instances are published inside the outer transaction scope, and
`@TransactionalEventListener` fires after commit, if the event publisher delivers them
synchronously after commit, one failing `streamRegistry.remove()` call (e.g., Redis hiccup) will
leave all subsequent streams' Redis sets dirty. `onStreamSettled` is `Propagation.NOT_SUPPORTED`,
so any exception there is uncaught and propagates, crashing the post-commit phase for the entire
batch. There is no try/catch around `streamRegistry.remove()` in `onStreamSettled`. The result is
a partial Redis cleanup with no retry and no log of which streams were skipped.

**Fix:** Wrap the body of `onStreamSettled` in a try/catch that logs failures but does not
rethrow, consistent with the fault-tolerant remove contract already established in
`RedisStreamRegistry.remove()`:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public void onStreamSettled(StreamSettledEvent event) {
    try {
        autoTerminationScheduler.cancel(event.streamId());
        streamRegistry.remove(event.streamId(), event.accountId(), event.tags());
    } catch (Exception e) {
        log.warn("Post-commit cleanup failed for stream {}; startup reconciliation will resync: {}",
            event.streamId(), e.getMessage());
    }
}
```

---

### CR-03: `TagTtlCleanupJob.doCleanup()` uses a Postgres-specific interval expression that breaks the cross-DB portability constraint

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJob.java:38-41`

**Issue:** The DELETE query uses `NOW() - (? * INTERVAL '1 hour')`, which is PostgreSQL-specific
syntax. The CLAUDE.md and project memory both state an explicit constraint: "No Postgres-specific
types; swap repository bean for MySQL/Oracle support." MySQL does not support multiplying an
integer parameter by `INTERVAL '1 hour'`; this query will fail at runtime on any non-Postgres
database, and the TTL cleanup job will silently not run (since the `@Scheduled` method catches no
exceptions — Spring's scheduler will log the error and continue).

**Fix:** Use a portable timestamp computation via Java, passing an `OffsetDateTime` cutoff to the
query instead of computing it in SQL:

```java
int doCleanup() {
    OffsetDateTime cutoff = OffsetDateTime.now()
        .minusHours(properties.getTags().getTtlHours());
    int deleted = jdbcTemplate.update(
        "DELETE FROM tag_committed_totals WHERE last_activity_at < ?",
        cutoff);
    log.info("Tag TTL cleanup: deleted {} stale tag_committed_totals rows", deleted);
    return deleted;
}
```

Note that `TagCommittedTotalsRepository` already defines `deleteByLastActivityAtBefore(OffsetDateTime)`,
which is a fully portable Spring Data derived-query alternative. The cleanup job should delegate to
that method (within a `@Transactional` boundary) rather than using raw JDBC.

---

### CR-04: `credit()` in `TransactionServiceImpl` records the credit amount in `tag_committed_totals.totalCreditedRecipient` — semantically incorrect for plain credit transactions

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java:109-117`

**Issue:** `addCreditedRecipient` is called when a CREDIT transaction has tags (lines 109-116).
The semantic of `totalCreditedRecipient` throughout the system is "the net amount credited to the
recipient after rake extraction" — meaning the counterpart to `totalDebited` in a transfer or
stream settlement. A plain external credit (money deposited into an account from outside the
engine) is not a transfer and has no debited side. Recording it as `totalCreditedRecipient` causes
the computed `totalRaked = totalDebited - totalCreditedRecipient` in `TagServiceImpl.aggregate()`
(line 55) to produce a negative rake value whenever pure credits exceed transfers on the same tag.
This corrupts the aggregate view for any tag that mixes plain credits with streams or transfers.

The fix depends on design intent. If plain credits should not affect tag aggregates at all, remove
the tag totals update from `credit()`. If they should appear, a separate column or a separate
accounting path (e.g., `totalExternalCredit`) is required. At minimum the current code produces
definitively wrong numbers:

```java
// REMOVE this block from credit() — external credits are not "recipient credits"
// from a rake-accounting perspective and poison the totalRaked computation.
if (request.tags() != null && !request.tags().isEmpty()) {
    // ... addCreditedRecipient block
}
```

---

### CR-05: Tags stored in Redis as comma-separated string corrupt any tag value that contains a comma

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/redis/RedisStreamRegistry.java:45`
**Also:** `engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java:36-38`

**Issue:** Tags are serialized to Redis as `String.join(",", state.tags())` and deserialized with
`tagsStr.split(",")`. No validation prevents a tag value from containing a comma. The
`@Size(max = 255)` and `@NotBlank` constraints on the DTO fields do not exclude commas. If a
caller submits a tag like `"session,abc"`, it is silently split into two tags on read-back:
`"session"` and `"abc"`. Every operation that reads tags from Redis — `getStreamsByTag`,
`getActiveStreams`, `remove`, `onStreamSettled` — will operate on the wrong tag set. The
`TAG_STREAMS_PREFIX + tag` keys will be wrong, the `remove()` call will fail to clean up the
correct Redis set members, and the corrupted tags will propagate into `tag_committed_totals`.

**Fix (two parts):**

1. Add a validation constraint to block commas in tag values, either a custom annotation or a
   `@Pattern` constraint:

```java
// In StartStreamRequest, CreditRequest, DebitRequest, PostTransferRequest:
@Size(max = 50) List<@NotBlank @Size(max = 255) @Pattern(regexp = "[^,]+",
    message = "Tag must not contain a comma") String> tags
```

2. As defense-in-depth, use a delimiter that cannot appear in a `VARCHAR(255)` tag name (e.g.,
   ` ` / null byte) or switch to a proper multi-value Redis structure (e.g., a dedicated
   Redis set per stream for its tags). The comma-join approach is inherently fragile.

---

## Warnings

### WR-01: `rakeAmount` and `toAccountAmount` stored as `null` on the settled row when `toAccountId` is null, even though rake was correctly computed and _not_ applied

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java:293-295`

**Issue:** The settled `StreamingTransaction` row sets:

```java
.toAccountAmount(resolvedToAccountId != null ? toAccountAmount : null)
.rakeAmount(resolvedToAccountId != null ? rakeAmount : null)
```

When `toAccountId` is null but `rakeRate` is non-zero (which the validation does not prevent —
`rakeRate` is optional and independent of `toAccountId` in `StartStreamRequest`), rake is
computed, no credit is issued, and the row records `null` for both amounts. The DB constraint
`chk_str_rake_balanced` treats NULL as "not applicable" and passes. The problem is that the rake
was economically applied (the full `clampedAmount` was debited from the source account), the
`toAccount` was never credited, and the rake was never deposited anywhere — it is destroyed. An
operator looking at the row sees `NULL` rake fields and cannot distinguish "no rake configured"
from "rake was configured but silently dropped."

**Fix:** Either validate at `startStream` time that `rakeRate` requires `toAccountId` (or
`platformAccountId`), or store the computed amounts regardless of `toAccountId` being null so the
audit record is accurate.

---

### WR-02: `sentinelConnectionFactory` calls `factory.afterPropertiesSet()` manually, which may double-initialize on Spring's lifecycle

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java:113-114`

**Issue:** `LettuceConnectionFactory` implements `InitializingBean`. Spring calls
`afterPropertiesSet()` automatically on any bean that implements it. Calling it explicitly inside
a `@Bean` factory method and then returning the bean means Spring will call it a second time. For
`LettuceConnectionFactory` this is harmless in current Lettuce versions, but it is an incorrect
pattern that can cause double-initialization warnings or, in future Lettuce versions, establish two
connection pools. Additionally the created factory is never registered for Spring's destroy
lifecycle (`factory.destroy()` will not be called on shutdown unless the bean is managed correctly).

**Fix:** Remove the explicit call and let Spring manage the lifecycle:

```java
@Bean
@ConditionalOnProperty(name = "token-engine.redis.sentinel.master")
public RedisConnectionFactory sentinelConnectionFactory(TokenEngineProperties properties) {
    RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
        .master(properties.getRedis().getSentinelMaster());
    for (String node : properties.getRedis().getSentinelNodes().split(",")) {
        String[] parts = node.trim().split(":");
        sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
    }
    return new LettuceConnectionFactory(sentinelConfig);
    // Spring calls afterPropertiesSet() automatically
}
```

---

### WR-03: `sentinelConnectionFactory` will throw `ArrayIndexOutOfBoundsException` on a malformed sentinel node string

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java:109-111`

**Issue:** `node.trim().split(":")` followed by `parts[0]` and `Integer.parseInt(parts[1])` will
throw `ArrayIndexOutOfBoundsException` if `sentinelNodes` contains an entry without a colon (e.g.,
`"redis-host"` with no port). The error message will point into framework startup code and be
difficult to diagnose. `Integer.parseInt(parts[1])` will throw `NumberFormatException` if the
port is not numeric. Neither exception is caught.

**Fix:** Validate the sentinel node format at property-binding time or add defensive parsing:

```java
for (String node : properties.getRedis().getSentinelNodes().split(",")) {
    String trimmed = node.trim();
    int colonIdx = trimmed.lastIndexOf(':');
    if (colonIdx < 1) {
        throw new IllegalStateException("Invalid sentinel node format (expected host:port): " + trimmed);
    }
    String host = trimmed.substring(0, colonIdx);
    int port = Integer.parseInt(trimmed.substring(colonIdx + 1));
    sentinelConfig.sentinel(host, port);
}
```

---

### WR-04: `endByTag` uses `streamRegistry.getStreamsByTag(tag)` from Redis as the authoritative candidate list, which misses streams whose Redis registration was lost after a crash

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TagServiceImpl.java:100`

**Issue:** Redis is the hot-path source of truth for active streams. However, after a crash and
before startup reconciliation completes, or when Redis is partially unavailable, the tag-based
Redis set `tag-streams:{tag}` may not contain all ACTIVE streams. `endByTag` will silently skip
any stream that is ACTIVE in Postgres but not present in the Redis set — these streams continue
accruing charges with no way to stop them via the tag endpoint. The in-DB check (lines 106-116)
only filters streams found in Redis; it does not add streams found only in Postgres.

**Fix:** Add a DB-fallback query for ACTIVE streams with the given tag that runs if the Redis set
is empty or Redis is unavailable:

```java
List<StreamState> candidates = streamRegistry.getStreamsByTag(tag);
if (candidates.isEmpty()) {
    // Fallback: query DB for ACTIVE stream_ids with this tag
    List<String> dbStreamIds = streamingTransactionRepository.findActiveStreamIdsByTag(tag);
    candidates = dbStreamIds.stream()
        .flatMap(id -> streamRegistry.get(id).stream())
        .toList();
}
```

---

### WR-05: `TagCommittedTotals` is deleted by TTL even when the tag is still active in running streams

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJob.java:38`

**Issue:** `doCleanup()` deletes rows from `tag_committed_totals` where `last_activity_at` is
older than `ttlHours`. There is no cross-check against `stream_tags` or `tag-streams:*` in Redis
to verify the tag has no active streams. A long-running stream (running longer than `ttlHours`
without any discrete transaction touching the same tag) will cause its aggregate row to be deleted.
When the stream finally settles, `stopStream` will find no existing row and call
`TagCommittedTotals.zero(tag)` before adding the settled amounts — correct for that stream in
isolation. But any previously committed amounts from prior discrete transactions on the same tag
are permanently lost from the aggregate.

**Fix:** Add a `LEFT JOIN` exclusion to the cleanup query against `stream_tags`, or update
`last_activity_at` on every active-stream heartbeat, or simply not clean up any tag that has an
entry in `stream_tags`:

```sql
DELETE FROM tag_committed_totals tct
WHERE tct.last_activity_at < NOW() - (interval)
  AND NOT EXISTS (
      SELECT 1 FROM stream_tags st WHERE st.tag = tct.tag
  )
```

(This also needs the portability fix from CR-03.)

---

### WR-06: `MetadataConverter` deserializes into raw `Map.class`, producing unchecked `Map<String, Object>` with potential type-unsafe nested structures on read

**File:** `engine-core/src/main/java/com/certacota/engine/core/domain/MetadataConverter.java:30`

**Issue:** `MAPPER.readValue(dbData, Map.class)` uses the raw type. Jackson will deserialize JSON
objects to `LinkedHashMap<String, Object>`, arrays to `ArrayList<Object>`, numbers to `Integer` or
`Long` or `Double` depending on size — none of which is guaranteed to be stable across Jackson
versions or with `BigDecimal` deserialization mode changes. The `@SuppressWarnings("unchecked")`
suppresses the cast warning without fixing it. Any caller that casts `metadata.get("amount")` to
`BigDecimal` will get a `ClassCastException` at runtime since Jackson defaults to `Double`.

**Fix:** Use `TypeReference` for a type-safe deserialize:

```java
private static final TypeReference<Map<String, Object>> MAP_TYPE =
    new TypeReference<>() {};

return MAPPER.readValue(dbData, MAP_TYPE);
```

This does not change the runtime behavior for `Object` values but documents intent clearly and
removes the raw-type cast.

---

### WR-07: `TagController.aggregate()` does not validate the `{tag}` path variable — an empty or blank tag reaches the service layer

**File:** `engine-service/src/main/java/com/certacota/engine/service/controller/TagController.java:29`

**Issue:** `@PathVariable String tag` has no `@NotBlank` constraint, and the controller class has
no `@Validated` annotation. A request to `/api/v1/tags/ /aggregate` (URL-encoded space) or an
empty path segment will reach `tagService.aggregate("")` / `tagService.aggregate(" ")`. The
service will query `tagCommittedTotalsRepository.findById("")` and return a zero-value aggregate,
which is a valid response — but also a misleading one. More critically, it then calls
`streamRegistry.getStreamsByTag("")` which queries Redis key `tag-streams:` (empty suffix), a
potentially poisoned key that could contain stale entries.

**Fix:**

```java
@GetMapping("/{tag}/aggregate")
@ResponseStatus(HttpStatus.OK)
public TagAggregateResponse aggregate(
        @PathVariable @NotBlank String tag) {
    ...
}
// Also add @Validated to the class declaration.
```

---

## Info

### IN-01: `TransactionController` exposes two separate endpoints for the same `transfer` operation

**File:** `engine-service/src/main/java/com/certacota/engine/service/controller/TransactionController.java:34-64`

**Issue:** `POST /api/v1/transactions` (line 34-38) and `POST /api/v1/transactions/transfer` (line
57-64) both invoke `transactionService.transfer()` but accept slightly different request shapes
(`PostTransferRequest` vs `TaggedTransferRequest`). The original endpoint (line 34) has no account
ID naming consistency with the tagged variant (`accountId`/`toAccountId` vs
`fromAccountId`/`toAccountId`). Having two endpoints for the same operation will cause API client
confusion and split the idempotency key namespace unexpectedly if callers use the same key on both
paths.

**Fix:** Consolidate to a single endpoint or clearly deprecate one, and ensure the request shape
is consistent.

---

### IN-02: `StreamingAutoConfiguration` registers `@EnableScheduling` and `@EnableSchedulerLock` unconditionally

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java:42-43`

**Issue:** `@EnableScheduling` and `@EnableSchedulerLock` are class-level annotations that apply
to the whole application context, not just the beans in this autoconfiguration. If a consuming
application has already configured its own scheduler with different settings, or if the application
is embedded in a test context that does not need scheduling, these annotations activate the Spring
scheduler globally. There is no `@ConditionalOn...` guard.

**Fix:** Document the behavior explicitly and consider exposing a property
`token-engine.scheduling.enabled=true` to allow opt-out.

---

### IN-03: `TagAutoConfiguration` and `StreamingAutoConfiguration` both apply `@EnableConfigurationProperties(TokenEngineProperties.class)` — redundant registration

**File:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TagAutoConfiguration.java:20`
**Also:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java:40`
**Also:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/TokenEngineAutoConfiguration.java` (not present but implied)

**Issue:** `@EnableConfigurationProperties(TokenEngineProperties.class)` on multiple autoconfiguration
classes registers the same properties bean multiple times. Spring deduplicates this correctly at
runtime, but it is misleading and can cause unexpected ordering issues if the auto-configurations
load in a non-deterministic order in edge cases.

**Fix:** Register `@EnableConfigurationProperties` on only one autoconfiguration class (the base
one), or use a dedicated `@AutoConfiguration` base that owns property binding.

---

_Reviewed: 2026-05-14T18:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
