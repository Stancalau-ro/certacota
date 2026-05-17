---
phase: 04-tags-rake-thresholds
fixed_at: 2026-05-14T18:45:00Z
review_path: .planning/phases/04-tags-rake-thresholds/04-REVIEW.md
iteration: 1
findings_in_scope: 12
fixed: 12
skipped: 0
status: all_fixed
---

# Phase 04: Code Review Fix Report

**Fixed at:** 2026-05-14T18:45:00Z
**Source review:** .planning/phases/04-tags-rake-thresholds/04-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 12 (5 Critical + 7 Warning)
- Fixed: 12
- Skipped: 0

## Fixed Issues

### CR-01: stopStream D-13 fallback moved before rake arithmetic

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java`
**Commit:** d07380c
**Applied fix:** Fetched the DB transaction row (`txn`) and resolved `resolvedToAccountId`, `resolvedRakeRate`, `resolvedPlatformAccountId` immediately after clamping — before any rake arithmetic or lock acquisition. `effectiveRakeRate` is now derived from `resolvedRakeRate` (not the raw Redis `state.rakeRate()`). Lock acquisition uses the resolved IDs. The duplicate `txn` fetch block that previously appeared after the credit operations was removed.

---

### CR-02: onStreamSettled wrapped in try/catch

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java`
**Commit:** d07380c
**Applied fix:** Wrapped the body of `onStreamSettled` in a try/catch that logs at WARN but does not rethrow, so a Redis hiccup during post-commit cleanup for one stream in a bulk `endByTag` does not prevent cleanup of subsequent streams in the same batch. Combined in the same commit as CR-01/WR-01 since all three touch `StreamingServiceImpl`.

---

### CR-03: TagTtlCleanupJob uses Java cutoff instead of Postgres interval

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJob.java`
**Commit:** ce34a50
**Applied fix:** Replaced the Postgres-specific `NOW() - (? * INTERVAL '1 hour')` with `OffsetDateTime.now().minusHours(properties.getTags().getTtlHours())` computed in Java, passed as a parameter. Added `import java.time.OffsetDateTime`. Combined with WR-05 in the same commit (same method).

---

### CR-04: credit() no longer calls addCreditedRecipient on tag totals

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java`
**Commit:** 74b4e76
**Applied fix:** Removed the entire `if (request.tags() != null && !request.tags().isEmpty())` tag totals block from `credit()`. Plain external credits have no debited counterpart in the rake-accounting model and were causing `totalRaked` to go negative. Tags on credit transactions are still recorded on the `discrete_transactions` row for auditability.

---

### CR-05: @Pattern no-comma constraint added to all four DTO tag fields

**Files modified:**
- `engine-core/src/main/java/com/certacota/engine/core/dto/StartStreamRequest.java`
- `engine-core/src/main/java/com/certacota/engine/core/dto/CreditRequest.java`
- `engine-core/src/main/java/com/certacota/engine/core/dto/DebitRequest.java`
- `engine-core/src/main/java/com/certacota/engine/core/dto/PostTransferRequest.java`

**Commit:** 0d69d0d
**Applied fix:** Added `@Pattern(regexp = "[^,]+", message = "Tag must not contain a comma")` to the element type in the `tags` list constraint of all four DTOs. Added `import jakarta.validation.constraints.Pattern` to each file.

---

### WR-01: toAccountAmount and rakeAmount stored unconditionally

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java`
**Commit:** d07380c
**Applied fix:** Removed the ternary guard `resolvedToAccountId != null ? ... : null` from `.toAccountAmount()` and `.rakeAmount()` on the settled row builder. Both amounts are now always stored, making the audit record accurate regardless of whether a toAccountId was configured.

---

### WR-02: Explicit afterPropertiesSet() removed from sentinelConnectionFactory

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java`
**Commit:** eb22f7e
**Applied fix:** Removed `factory.afterPropertiesSet()` and changed the factory creation to return `new LettuceConnectionFactory(sentinelConfig)` directly. Spring manages the InitializingBean lifecycle automatically for `@Bean` methods. Combined with WR-03 in the same commit (same method).

---

### WR-03: Sentinel node parsing uses lastIndexOf(':') with clear error

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/autoconfigure/StreamingAutoConfiguration.java`
**Commit:** eb22f7e
**Applied fix:** Replaced `node.trim().split(":")` with `trimmed.lastIndexOf(':')` to handle IPv6 addresses and give a clear `IllegalStateException("Invalid sentinel node format (expected host:port): " + trimmed)` on malformed input instead of an opaque `ArrayIndexOutOfBoundsException`.

---

### WR-04: endByTag falls back to DB when Redis tag set is empty

**Files modified:**
- `engine-core/src/main/java/com/certacota/engine/core/repository/StreamingTransactionRepository.java`
- `engine-spring/src/main/java/com/certacota/engine/spring/service/TagServiceImpl.java`

**Commit:** 7ebfa9b
**Applied fix:** Added `findActiveStreamIdsByTag(@Param("tag") String tag)` JPQL query to `StreamingTransactionRepository` that joins `streaming_transactions.tags` for ACTIVE status. In `TagServiceImpl.endByTag()`, if the Redis candidate list is empty, logs at WARN and falls back to the DB query to find ACTIVE stream IDs, then maps them through `streamRegistry.get()` to reconstruct `StreamState` objects.

---

### WR-05: TTL cleanup guards against deleting rows for tags with active streams

**Files modified:** `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJob.java`
**Also:** `engine-spring/src/test/java/com/certacota/engine/spring/scheduler/TagTtlCleanupJobIT.java`
**Commit:** ce34a50 (production code), 24618a7 (test fix)
**Applied fix:** Added `AND NOT EXISTS (SELECT 1 FROM stream_tags st WHERE st.tag = tag_committed_totals.tag)` to the DELETE query so rows for tags with active streams are never deleted. Updated `TagTtlCleanupJobIT` setUp to create the `stream_tags` table (empty) so the NOT EXISTS subquery parses correctly in the Postgres test container.

---

### WR-06: MetadataConverter uses TypeReference instead of raw Map.class

**Files modified:** `engine-core/src/main/java/com/certacota/engine/core/domain/MetadataConverter.java`
**Commit:** bcfa16b (TypeReference), 5d4e894 (exception type correction)
**Applied fix:** Added a `TypeReference<Map<String, Object>> MAP_TYPE` constant and replaced `MAPPER.readValue(dbData, Map.class)` with `MAPPER.readValue(dbData, MAP_TYPE)`. Removed `@SuppressWarnings("unchecked")`. A follow-up commit corrected the exception type back to `IllegalArgumentException` (from the erroneously changed `IllegalStateException`) to preserve the existing test contract in `MetadataConverterTest`.

---

### WR-07: TagController has @Validated and @NotBlank on path variables

**Files modified:** `engine-service/src/main/java/com/certacota/engine/service/controller/TagController.java`
**Commit:** b803585
**Applied fix:** Added `@Validated` at the class level (with `import org.springframework.validation.annotation.Validated`) and `@NotBlank` on the `tag` `@PathVariable` parameter in both `aggregate()` and `endByTag()`. The `@NotBlank` import was already present.

---

## Skipped Issues

None.

---

## Test Results

All tests pass after fixes:

```
:engine-core:test — 12 tests completed, 0 failed
:engine-spring:test — 18 tests completed, 0 failed
:engine-service:test — (UP-TO-DATE)
BUILD SUCCESSFUL
```

The initial run revealed two test failures introduced by the fixes:
1. `MetadataConverterTest` — exception type changed from `IllegalArgumentException` to `IllegalStateException` (corrected in follow-up commit 5d4e894)
2. `TagTtlCleanupJobIT` — `stream_tags` table not created in test setUp (corrected in commit 24618a7)

Both were diagnosed and corrected before the final test run.

---

_Fixed: 2026-05-14T18:45:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
