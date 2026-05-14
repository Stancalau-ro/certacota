# Phase 4: Tags and Rake on Streaming - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Tags on streaming and discrete transactions with real-time aggregate queries; rake splits applied atomically at stream settlement; bulk end-by-tag operation. Also includes a cross-cutting metadata portability retrofit: all transaction metadata moves from Postgres-specific JSONB to a cross-DB portable TEXT + JPA @Convert pattern.

No threshold events — deferred to v2. No external event emission (outbox) — Phase 5. No dual-packaging changes — Phase 6.

</domain>

<decisions>
## Implementation Decisions

### DB-Agnostic Design Principle (Cross-Cutting)
- **D-01:** All Phase 4 data structures are designed for cross-database portability (Postgres, MySQL, Oracle). Database-specific behaviour lives exclusively in swappable repository bean implementations in `engine-spring`. `engine-core` defines port interfaces only. This applies to: tag join tables, `tag_committed_totals`, metadata storage, and concurrent update patterns.

### Tag Storage — Postgres
- **D-02:** Tags stored in normalized join tables — NOT arrays or JSONB columns. Two tables: `stream_tags(stream_id VARCHAR, tag VARCHAR, PRIMARY KEY(stream_id, tag))` and `discrete_transaction_tags(transaction_id BIGINT, tag VARCHAR, PRIMARY KEY(transaction_id, tag))`. Standard SQL, portable across all target databases.
- **D-03:** `tag_committed_totals` table columns: `tag VARCHAR PRIMARY KEY`, `total_debited NUMERIC(38,18)`, `total_credited_recipient NUMERIC(38,18)`, `last_activity_at TIMESTAMP`. `totalRaked` is derived at query time as `totalDebited - totalCreditedRecipient` — not stored. Invariant: `totalDebited = totalCreditedRecipient + totalRaked` enforced by DB check constraint on settlement writes.

### Tag Storage — Redis
- **D-04:** Redis maintains `tag-streams:{tag}` Sets alongside the existing `account-streams:{accountId}` Sets. On stream register: `SADD tag-streams:{tag} {streamId}` for each tag. On stream remove: `SREM tag-streams:{tag} {streamId}` for each tag. This enables O(1) tag-to-streams enumeration for in-flight projection and end-by-tag without a full scan.
- **D-05:** Tags stored in Redis `stream:{streamId}` hash as a single field (e.g., comma-separated or JSON string) so the full stream state is self-contained per hash. Exact encoding is Claude's discretion.

### Tag API
- **D-06:** Tags attach to transactions via `tags: List<String>` field added to `StartStreamRequest`, `CreditRequest`, and `DebitRequest` DTOs. Field is optional; defaults to empty list. Tags are immutable after creation (TAG-01).
- **D-07:** Tag aggregate endpoint: `GET /api/v1/tags/{tag}/aggregate`. Response shape:
  ```
  committed:
    totalDebited          (gross outflow from all from-accounts)
    totalCreditedRecipient (gross inflow to all to-accounts)
    totalRaked            (derived: totalDebited - totalCreditedRecipient)
  inFlight:
    inFlightDebit         (Σ ratePerSecond × elapsed for active tagged streams)
    inFlightCreditedRecipient (Σ ratePerSecond × elapsed × (1 - rakeRate))
    inFlightRaked         (derived: inFlightDebit - inFlightCreditedRecipient)
  estimatedAt             (ISO-8601 timestamp)
  ```
  Invariant holds on both sides independently. Gross flows are tracked (not net), so bilateral streams (A→B and B→A, same tag) accumulate correctly without cancellation.

### tag_committed_totals Concurrent Update
- **D-08:** `tag_committed_totals` updated inside the same `@Transactional` as stream settlement (TAG-03). Concurrency: `SELECT FOR UPDATE` on the `tag_committed_totals` row acquired **after** all account locks, then UPDATE (row exists) or INSERT (first settlement for this tag). Lock ordering when a stream carries multiple tags: alphabetical ascending by tag name to prevent deadlock across concurrent settlements.

### Streaming Rake Configuration
- **D-09:** Rake parameters are explicit per-stream in `StartStreamRequest` (consistent with `PostTransferRequest` pattern): `toAccountId: String` (optional), `rakeRate: BigDecimal` (optional), `platformAccountId: String` (optional). When all three are absent, stream is non-rake. No global `RakeProperties` lookup — rate is caller-supplied per stream.
- **D-10:** Rake fields (`toAccountId`, `rakeRate`, `platformAccountId`) stored in `streaming_transactions` Postgres row AND mirrored in Redis `stream:{streamId}` hash alongside `ratePerSecond`, `minimumAmount`, `increment`. This enables settlement without a DB read in the common path.
- **D-11:** Settlement lock order for rake-enabled streams: from-account → to-account → platform-account. Consistent with existing `TransactionServiceImpl.transfer()` lock ordering. Prevents deadlock with concurrent transfers touching the same accounts.
- **D-12:** Auto-termination applies rake — same three-way split as client-initiated stop. Auto-termination waives `minimumAmount`, not rake.
- **D-13:** Redis unavailable during settlement: fall back to `streaming_transactions` Postgres row for rake fields. Settlement proceeds. Consistent with existing Postgres fallback pattern (D-17 from Phase 3 CONTEXT.md).

### Metadata Portability (Cross-Cutting Retrofit)
- **D-14:** `metadata` on ALL transactions (`DiscreteTransaction`, `StreamingTransaction`) uses `TEXT` column + standard JPA `@Convert(converter = MetadataConverter.class)`. Removes `@JdbcTypeCode(SqlTypes.JSON)` and `columnDefinition = "jsonb"` — both Hibernate/Postgres-specific.
- **D-15:** `MetadataConverter` is an `AttributeConverter<Map<String, Object>, String>` placed in `engine-core`. Uses Jackson `ObjectMapper` to serialize Map → JSON string on write, deserialize on read. Jackson is already on the `engine-core` classpath.
- **D-16:** Flyway migration: `ALTER TABLE discrete_transactions ALTER COLUMN metadata TYPE TEXT USING metadata::text` — Postgres preserves data. New `streaming_transactions.metadata` column added as `TEXT` from the start (no alter needed). Engine never queries metadata by key (META-01/META-02: read-only payload), so JSONB indexing is not needed.

### End-By-Tag
- **D-17:** Endpoint: `POST /api/v1/tags/{tag}/end`. Body: `{idempotencyKey: String (required), reason: String (optional, defaults to "end_by_tag")}`.
- **D-18:** Idempotency key required — stored with operation `BULK_END_BY_TAG` in `idempotency_keys` table. Consistent with all other write operations.
- **D-19:** `reason` field (or default `"end_by_tag"`) written to `streaming_transactions.reason` and audit log entries for each settled stream.
- **D-20:** Already-settled race condition: streams found in `tag-streams:{tag}` Redis Set but with status SETTLED or ERROR in Postgres are **skipped silently** — not an error. Remaining ACTIVE streams settle normally inside the same `@Transactional`.
- **D-21:** Response: full list of settled streams (each entry: `streamId`, `settledAmount`) plus summary fields `settledCount` and `skippedCount`.
- **D-22:** No active streams for tag: return HTTP 200 with `settledCount=0`, empty list — not an error (desired end state already achieved).

### Claude's Discretion
- Exact Redis field encoding for tags in `stream:{streamId}` hash (comma-separated string vs. JSON array)
- Flyway migration numbering for Phase 4 tables (continue from V10+)
- Exact GIN index configuration on join tables (if any)
- Tag name constraints (max length, allowed characters, case sensitivity)
- Cucumber feature file structure for Phase 4 acceptance tests
- `tag_committed_totals` TTL eviction job cron schedule and ShedLock configuration (pattern established in Phase 3 AuditArchivalJob)
- Package structure for new Phase 4 classes within each module

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` §Tags (TAG-01 through TAG-06) — all tag requirements
- `.planning/REQUIREMENTS.md` §Rake Engine (RAKE-02, RAKE-03, RAKE-04) — streaming rake requirements
- `.planning/REQUIREMENTS.md` §Open Metadata (META-01, META-02) — metadata on all transactions (cross-cutting)

### Prior Phase Context
- `.planning/phases/03-streaming-transactions/03-CONTEXT.md` — D-01 through D-39: Redis data model, settlement patterns, lock ordering, Redis failure behaviour, startup reconciliation, auto-termination scheduler
- `.planning/phases/02-discrete-transactions/02-RESEARCH.md` — rake arithmetic pattern, three-way split, `PostTransferRequest` lock ordering
- `.planning/STATE.md` §Accumulated Context — cross-phase decisions and blockers

### Key Implementation Files (read before planning)
- `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java` — `transfer()` method: lock ordering (from → to → platform), three-way rake arithmetic, idempotency after lock pattern
- `engine-spring/src/main/java/com/certacota/engine/spring/redis/RedisStreamRegistry.java` — `account-streams:{accountId}` Set pattern to extend for `tag-streams:{tag}` Sets; `register()` and `remove()` methods
- `engine-core/src/main/java/com/certacota/engine/core/domain/StreamState.java` — record to extend with tags and rake fields (`toAccountId`, `rakeRate`, `platformAccountId`)
- `engine-core/src/main/java/com/certacota/engine/core/domain/StreamingTransaction.java` — entity to extend with `tags`, `metadata`, `toAccountId`, `rakeRate`, `platformAccountId` columns
- `engine-core/src/main/java/com/certacota/engine/core/domain/DiscreteTransaction.java` — metadata retrofit target: `@JdbcTypeCode(SqlTypes.JSON)` → `@Convert(converter = MetadataConverter.class)`, column type JSONB → TEXT
- `engine-spring/src/main/java/com/certacota/engine/spring/service/StreamingServiceImpl.java` — settlement path to extend with rake split and tag_committed_totals update
- `engine-spring/src/main/java/com/certacota/engine/spring/scheduler/AuditArchivalJob.java` — ShedLock-guarded @Scheduled job pattern; reuse for tag TTL cleanup job

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `TransactionServiceImpl.transfer()` — complete rake three-way split implementation with lock ordering; streaming settlement extends this arithmetic directly
- `RedisStreamRegistry.register()` / `remove()` — add `SADD`/`SREM` calls for `tag-streams:{tag}` Sets alongside existing account Set operations
- `AuditArchivalJob` + `FallbackSweepJob` — ShedLock-guarded `@Scheduled` pattern; reuse for `tag_committed_totals` TTL cleanup job
- `StreamSettlementCalculator.computeProjection()` — reuse for per-stream in-flight projection in tag aggregate; extend with rake fields for `inFlightCreditedRecipient` and `inFlightRaked`
- `AccountRepository.findWithLock()` — pessimistic write lock; same pattern needed for `tag_committed_totals` repository

### Established Patterns
- Lock ordering: acquire all account locks (from → to → platform) BEFORE tag_committed_totals lock BEFORE write
- Idempotency: check after lock acquisition — apply to end-by-tag (`BULK_END_BY_TAG` operation key)
- `RoundingMode.DOWN` on all token arithmetic — applies to rake split: `rakeAmount = settledAmount.multiply(rakeRate).setScale(18, RoundingMode.DOWN)`
- `@Transactional` on service write methods; controllers thin delegates
- `@RequiredArgsConstructor` + constructor injection; no field injection
- Settlement D-19 race rule (Phase 3): clamp settled amount to `min(projected, availableBalance - floor)` — unchanged for rake-enabled streams

### Integration Points
- `StreamingServiceImpl.settle()` (internal) — extend to: (1) read rake fields from Redis/Postgres, (2) acquire to-account and platform-account locks, (3) three-way credit split, (4) update `tag_committed_totals` for each tag, (5) `SREM tag-streams:{tag}` for each tag
- `StreamRegistry` interface — add `getStreamsByTag(String tag): List<StreamState>` method backed by `SMEMBERS tag-streams:{tag}`
- `RedisStreamRegistry.register()` — extend to add tags to hash field and `SADD` to `tag-streams:{tag}` Sets
- `TokenEngineAutoConfiguration` — register new beans: `TagService`, `TagController`, `TagCommittedTotalsRepository`

</code_context>

<specifics>
## Specific Ideas

- Tag aggregate `totalRaked` is always derived (`totalDebited - totalCreditedRecipient`), never stored — this holds because the check constraint enforces `debit = creditedRecipient + rake` per transaction, making the sum linear
- Bilateral streams (A→B and B→A with same tag) produce non-zero `totalDebited` and `totalCreditedRecipient` that cancel only at the net level — the gross tracking is intentional and correct
- In-flight projection uses the same rake rate stored at stream start — consistent with D-10 (rake params mirrored in Redis hash), so no DB read needed per estimation
- `tag_committed_totals.last_activity_at` updated on every settlement that touches the tag — used by the TTL cleanup background job (TAG-05)
- `MetadataConverter` must handle `null` metadata gracefully (return `null` / empty map without NPE)

</specifics>

<deferred>
## Deferred Ideas

- Threshold events (EVT-01 through EVT-04) — moved to v2. Account and tag threshold registration, exactly-once crossing detection, and threshold event emission are explicitly out of Phase 4 scope.
- Historical tag aggregate query (TAG-HIST-01) — v2: query tag totals over a past time window from audit log for evicted/completed tags
- Tag name constraints (max length, regex validation) — Claude's discretion for now; can be tightened in v2 if platform requirements emerge
- Streaming accumulation (credits via streaming) — permanently out of scope per ROADMAP

</deferred>

---

*Phase: 04-tags-rake-thresholds*
*Context gathered: 2026-05-14*
