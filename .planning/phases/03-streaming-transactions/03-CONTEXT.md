# Phase 3: Streaming Transactions - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Rate-based streaming drain transactions that run in-memory (Redis-backed StreamRegistry) and settle to Postgres via mathematical projection. Delivers: stream start/stop REST API, forward balance estimation, minimum amount enforcement, increment billing, auto-termination scheduler (Redisson primary + Postgres fallback), Redis failure resilience, and OPS-02 data retention (audit log archival + idempotency key TTL sweep).

No tags, no rake on streaming, no threshold events — those are Phase 4. No external event emission (outbox) — Phase 5. No dual-packaging changes — Phase 6.

</domain>

<decisions>
## Implementation Decisions

### Deployment Topology
- **D-01:** Multi-pod deployment with shared Redis StreamRegistry — NOT a JVM-local ConcurrentHashMap. Any pod can start, stop, or estimate any stream. This is the v1 target topology.
- **D-02:** Spring Data Redis with Lettuce driver. Redis Sentinel supported via `token-engine.redis.sentinel.master` and `token-engine.redis.sentinel.nodes` properties. Single-node config also supported.

### Stream Identity and API Shape
- **D-03:** Caller-supplied stream ID (opaque String) — consistent with account ID pattern. Caller guarantees uniqueness across concurrent streams.
- **D-04:** Rate expressed as `ratePerSecond: BigDecimal` (tokens per second). Single fixed unit — no rateUnit enum. All settlement math in seconds.
- **D-05:** `increment: BigDecimal` (optional). When present: `settled = floor(ratePerSecond × elapsedSeconds / increment) × increment`. When absent: `settled = ratePerSecond × elapsedSeconds` (exact, full BigDecimal precision). Auto-termination fires when `remainingBalance < increment`.
- **D-06:** `minimumAmount: BigDecimal` (optional). If stream stopped by client-initiated close before minimumAmount has been drained: settle `max(actual, minimumAmount)`. `ignoreMinimum: boolean` (default `false`) on the stop API waives the minimum on client-initiated close. Auto-termination and error-terminated streams always waive minimum (settle actual elapsed).

### Stream Start Request/Response
- **D-07:** Stream start request fields:
  - `streamId: String` (required, caller-supplied)
  - `accountId: String` (required)
  - `ratePerSecond: BigDecimal` (required)
  - `idempotencyKey: String` (required)
  - `minimumAmount: BigDecimal` (optional)
  - `increment: BigDecimal` (optional)
- **D-08:** Stream start response: `streamId`, `accountId`, `ratePerSecond`, `startedAt` (ISO-8601), plus `minimumAmount` and `increment` echoed back if provided.

### Stream Stop Request/Response
- **D-09:** `POST /streams/{streamId}/stop` accepts:
  - `ignoreMinimum: boolean` (optional, default `false`)
  - `reason: String` (optional, default `"stop endpoint call"`)
- **D-10:** Stream stop response: `settledAmount`, `stoppedAt` (ISO-8601), `reason`.
- **D-11:** Auto-termination always uses `reason = "balance_exhaustion"` — distinguishable from client-initiated stops in audit log.

### Forward Balance Estimation
- **D-12:** `GET /accounts/{accountId}/estimated-balance` returns:
  - `estimatedBalance` — `committedBalance` minus sum of `(ratePerSecond × elapsedSeconds)` across all active streams for that account
  - `committedBalance` — current Postgres committed balance
  - `estimatedAt` — ISO-8601 timestamp of the projection
  - `estimatedDrainAt` — Unix epoch milliseconds when balance is projected to reach floor at current drain rate; `null` when no active streams or net drain rate ≤ 0. Formula: `estimatedAt.toEpochMilli() + ((estimatedBalance - floor) / totalActiveRatePerSecond × 1000)`
- **D-13:** No database read required per estimation query — reads committed balance once from Postgres (via account lock or read), projection computed from Redis StreamRegistry.

### StreamRegistry Data Model (Redis)
- **D-14:** Each active stream stored in Redis as a hash keyed by `stream:{streamId}`. Fields: `accountId`, `ratePerSecond`, `startedAt` (epoch millis), `minimumAmount` (nullable), `increment` (nullable), `status` (`ACTIVE`).
- **D-15:** Secondary index in Redis: `account-streams:{accountId}` → Set of streamIds. Used to enumerate all streams for an account during estimation and floor checks.
- **D-16:** On startup (or Redis reconnect): rebuild StreamRegistry from `streaming_transactions` Postgres table where `status = 'ACTIVE'`. Re-enqueue exhaustion times in Redisson DelayedQueue.

### Settlement and Floor Enforcement
- **D-17:** Discrete debit floor check uses **estimated balance** = `committedBalance - sum of active stream projections for that account`. StreamRegistry (Redis) consulted inside the `@Transactional` boundary after the pessimistic write lock on the account. Rejects debit if `estimatedBalance - debitAmount < floor`.
- **D-18:** Stream start floor check: reject if `estimatedBalance < newStream.minimumAmount + Σ max(0, activeStream.minimumAmount - activeStream.projectedDrained)`. This ensures the account can cover outstanding minimum obligations for all active streams plus the new one.
- **D-19:** Settlement race (committed balance insufficient at settle time due to concurrent discrete debits): settle `min(projectedAmount, availableBalance - floor)`. Account reaches floor but never goes below it. Stream is marked SETTLED.
- **D-20:** Reject stream start on CLOSED account — check after acquiring pessimistic write lock. Return 409.
- **D-21:** Reject account close if any ACTIVE streams exist in StreamRegistry (Redis) or `streaming_transactions` (Postgres). Return 409. `AccountService.close()` must check both.
- **D-22:** Auto-termination settle-only — account stays OPEN after auto-termination. Caller decides next action (refund, close, start new stream).

### Crash Recovery and Restart
- **D-23:** Resume-on-restart. The `streaming_transactions` Postgres table stores the full stream config (`startedAt`, `ratePerSecond`, `minimumAmount`, `increment`, `accountId`, `status`). On startup, all `ACTIVE` rows are loaded into Redis StreamRegistry.
- **D-24:** Downtime counts as elapsed. `elapsedSeconds = (System.currentTimeMillis() - startedAt.toEpochMilli()) / 1000.0`. nanoTime precision is lost across JVM restarts; wall-clock elapsed is used after restart.
- **D-25:** On startup reconciliation: for each ACTIVE stream, compute `estimatedBalance`. If `estimatedBalance ≤ floor` → auto-terminate immediately (settle actual elapsed, emit `balance_exhaustion`, mark SETTLED). Otherwise → load into Redis, re-enqueue in Redisson.

### Auto-Termination Scheduler
- **D-26:** Primary scheduler: Redisson DelayedQueue. All pods are competing consumers. Stream start enqueues an exhaustion entry at `startedAt + (estimatedBalance - floor) / ratePerSecond`. First pod to dequeue settles the stream.
- **D-27:** Reschedule on balance change: remove old exhaustion entry from Redisson DelayedQueue, enqueue new entry with recalculated exhaustion time.
- **D-28:** Fallback scheduler (Redis-independent safety net): ShedLock-guarded `@Scheduled` Postgres poller. Queries all ACTIVE `streaming_transactions`, calculates elapsed and estimated balance for each, settles any where `estimatedBalance ≤ floor`. Default polling interval: 5 minutes, configurable via `token-engine.streaming.fallback-sweep-seconds`. Runs independently of Redis — catches streams missed by Redisson during Redis outages.

### Redis Failure Behavior
- **D-29:** Stream start, stop, and estimation return 503 when Redis is unreachable.
- **D-30:** Discrete debit on an account with any `ACTIVE` rows in `streaming_transactions` (Postgres) returns 503 during Redis outage — floor check cannot account for in-flight streams without Redis.
- **D-31:** Discrete credits always allowed regardless of Redis availability.
- **D-32:** Account close with ACTIVE streams returns 503 during Redis outage (can't check StreamRegistry).
- **D-33:** Streams cannot be auto-terminated via Redisson during Redis outage (queue unavailable). The Postgres fallback sweep (D-28) handles termination during outages. On Redis recovery, reconciliation (D-25) catches up any remaining floor-exhausted streams.

### OPS-02 Data Retention
- **D-34:** Audit log archival in Phase 3 scope. Audit log entries must be archived before deletion — no direct DELETE from `balance_audit_log`.
- **D-35:** Archive destination: `audit_archive` Postgres schema (same instance). Table: `audit_archive.balance_audit_log` with identical schema. Enterprise operators can pg_dump/restore the `audit_archive` schema to cold storage on their schedule.
- **D-36:** Retention window: 90 days, configurable via `token-engine.audit.retention-days`. Postgres range partitioning on `balance_audit_log.recorded_at` — old partitions are copied to `audit_archive` then dropped.
- **D-37:** Closed accounts: stay in `accounts` table indefinitely. No archival.
- **D-38:** `idempotency_keys` TTL sweep: delete rows older than configurable window, default 48 hours (`token-engine.idempotency.ttl-hours`). No archival needed — operational records, not audit records.
- **D-39:** Archival/sweep job triggered by Spring `@Scheduled` (default cron: `0 0 2 * * *`, configurable). Guarded by ShedLock to prevent concurrent execution across pods. ShedLock `lockAtMostFor` and `lockAtLeastFor` configurable via `token-engine.audit.lock-at-most-hours` and `token-engine.audit.lock-at-least-minutes`.

### Claude's Discretion
- Exact Redis key TTL (if any) for StreamRegistry entries vs. explicit removal on settlement
- Lettuce connection pool configuration
- Exact HTTP status codes for validation errors (400 vs 422) beyond the specified 409s
- Flyway migration numbering for new streaming/archival tables (continue V6+)
- Cucumber feature file structure for streaming acceptance tests
- Package structure within each module for streaming classes
- Exact ShedLock table name and configuration

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` §Streaming Transactions (STR-01 through STR-09) — all streaming requirements
- `.planning/REQUIREMENTS.md` §Auto-Termination (AUTO-01, AUTO-02, AUTO-03) — scheduler and event requirements
- `.planning/REQUIREMENTS.md` §Balance (BAL-02) — forward estimation requirement
- `.planning/REQUIREMENTS.md` §Open Metadata (META-01, META-02) — metadata on streaming transactions

### Phase 3 Success Criteria (from ROADMAP.md — reproduce for clarity)
1. Stream start registers in Redis StreamRegistry without Postgres write per active tick
2. Stream stop settles exact amount via `ratePerSecond × elapsedSeconds` (System.nanoTime() for running streams, wall-clock for post-restart), commits atomically, removes from StreamRegistry
3. Forward estimation returns `committedBalance - Σ(rate × elapsed)` across all in-flight streams with `estimatedAt` and `estimatedDrainAt`; no database read per estimation query
4. Concurrent streaming + discrete transactions against same account: final Postgres balance correct, no double-spend, no lost update, no permanent in-memory/durable divergence
5. All rate arithmetic uses BigDecimal; floating-point absent from rate calculation paths
6. minimumAmount enforcement and ignoreMinimum flag correct across all stop scenarios
7. Increment billing settles `floor(elapsed × rate / increment) × increment`; auto-termination fires when `remainingBalance < increment`
8. Auto-termination scheduler: Redisson DelayedQueue (primary) + Postgres sweep (fallback); emits `balance_exhaustion` event distinguishable from client-initiated stops

### Prior Phase Context
- `.planning/phases/01-foundation/01-CONTEXT.md` — foundational decisions (BigDecimal NUMERIC(38,18), Gradle multi-module, Flyway, Cucumber/BDD, caller-supplied IDs)
- `.planning/STATE.md` §Accumulated Context — cross-phase decisions and blockers

### Established Implementation Patterns
- `engine-spring/src/main/java/com/certacota/engine/spring/service/TransactionServiceImpl.java` — pessimistic write lock pattern, idempotency check after lock, audit log structure
- `engine-spring/src/main/java/com/certacota/engine/spring/config/TokenEngineProperties.java` — `token-engine.*` property binding pattern
- `engine-core/src/main/java/com/certacota/engine/core/domain/Account.java` — entity pattern, BigDecimal balance fields

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Account.credit()` / `Account.debit()` — balance mutation methods; stream settlement calls `account.debit(settledAmount)` after acquiring pessimistic lock
- `BalanceAuditLog` entity — stream settlement produces a `STREAMING_SETTLE` audit log entry using the same builder pattern
- `IdempotencyKey` entity + `IdempotencyKeyRepository` — stream start uses the same idempotency key table and check-after-lock pattern
- `AccountRepository.findWithLock()` — pessimistic write lock acquisition; reused in all stream start, stop, and settlement paths
- `TokenEngineProperties` — extend with new nested properties blocks: `streaming.*`, `audit.*`, `redis.*`
- `TokenEngineAutoConfiguration` — register new beans (StreamRegistry, StreamingService, AuditArchivalJob, etc.) via `@ConditionalOnMissingBean`

### Established Patterns
- Lock ordering: acquire account lock BEFORE balance check BEFORE write — must be maintained in stream settlement (prevents deadlock with concurrent discrete transactions)
- Idempotency: check after lock acquisition (not before) to prevent duplicate execution under race conditions
- `RoundingMode.DOWN` for all token arithmetic — apply to streaming settlement and increment calculation
- `@Transactional` on service write methods; controllers are thin delegates
- `@RequiredArgsConstructor` + constructor injection; no field injection

### Integration Points
- `AccountService.close()` must check `StreamRegistry.hasActiveStreams(accountId)` (and Postgres fallback when Redis unavailable) before allowing close
- Discrete debit path in `TransactionServiceImpl.debit()` must consult StreamRegistry for estimated balance before floor check
- `TokenEngineProperties` needs nested `RakeProperties`-style classes for `StreamingProperties`, `AuditProperties`, `RedisProperties`
- New Flyway migrations (V6+) for: `streaming_transactions`, `audit_archive` schema, `shedlock` table, partitioning DDL for `balance_audit_log`

</code_context>

<specifics>
## Specific Ideas

- `estimatedDrainAt` in estimation response is Unix epoch milliseconds (long), not seconds or ISO-8601 string
- Stop response does NOT include finalBalance — caller queries `GET /accounts/{accountId}` for committed balance after settlement
- `reason` field on stream stop flows through to the audit log entry and the auto-termination event
- During Redis outage: Postgres `streaming_transactions` table is queried directly as fallback for active stream checks (discrete debit blocking, account close blocking)
- ShedLock for archival job uses configurable `lockAtMostFor` to handle both pod crash and long-running cleanup scenarios

</specifics>

<deferred>
## Deferred Ideas

- Tags on streaming transactions (TAG-01, TAG-06) — Phase 4
- Rake on streaming transactions (RAKE-02, RAKE-03, RAKE-04) — Phase 4
- Threshold events triggered by streaming settlements (EVT-01 through EVT-04) — Phase 4
- External event emission via transactional outbox (EMIT-01 through EMIT-03) — Phase 5
- AuditLogArchiver port interface for pluggable archive destinations (S3, cold Postgres) — could be Phase 6 or v2 enhancement; Phase 3 uses concrete Postgres archive_schema approach
- Redis Cluster support (beyond Sentinel) — v2
- Streaming accumulation (credits via streaming) — out of scope per ROADMAP

</deferred>

---

*Phase: 3-streaming-transactions*
*Context gathered: 2026-05-14*
