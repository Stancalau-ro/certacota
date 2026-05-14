# Phase 3: Streaming Transactions - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-14
**Phase:** 3-streaming-transactions
**Areas discussed:** Crash recovery on restart, Rate and billing unit API shape, Discrete-vs-stream floor enforcement, OPS-02 data retention scope, Auto-termination scheduler (emerged during discussion), Redis failure and recovery (emerged during discussion)

---

## Crash Recovery on Restart

| Option | Description | Selected |
|--------|-------------|----------|
| Settle-on-restart | Settle all ACTIVE streams using now - started_at on startup, emit termination events | |
| Resume-on-restart | Reload ACTIVE rows from Postgres into StreamRegistry, continue from startedAt baseline | ✓ |
| Orphan-on-restart | Leave ACTIVE in Postgres, require manual caller resolution | |

**User's choice:** Resume-on-restart
**Notes:** Caller-supplied stream IDs chosen (consistent with account ID pattern). Downtime counts as elapsed (continuous wall-clock time, simpler and consistent with mathematical-projection model).

---

## Stream ID Ownership

| Option | Description | Selected |
|--------|-------------|----------|
| Engine generates UUID | Consistent with discrete transaction IDs | |
| Caller supplies opaque ID | Consistent with account IDs; caller guarantees uniqueness | ✓ |

**User's choice:** Caller supplies opaque ID

---

## Downtime Gap Counting

| Option | Description | Selected |
|--------|-------------|----------|
| Downtime counts as elapsed | elapsed = now - started_at, continuous | ✓ |
| Downtime frozen | Requires checkpoint nanoTime on shutdown; complex | |

**User's choice:** Downtime counts as elapsed

---

## Rate Expression

| Option | Description | Selected |
|--------|-------------|----------|
| ratePerSecond: BigDecimal | Fixed unit; all math in seconds | ✓ |
| rate + rateUnit enum | SECOND/MINUTE/HOUR; normalized internally | |
| rate in tokens/nanosecond | Matches nanoTime precision; unusual for public API | |

**User's choice:** ratePerSecond: BigDecimal

---

## Increment Billing

| Option | Description | Selected |
|--------|-------------|----------|
| increment + incrementSeconds | Separate token block size and period | |
| increment + incrementDuration (ISO-8601) | Duration parsing required | |
| increment only (token block size) | Period derived as increment / ratePerSecond; no division/conversion | ✓ |

**User's choice:** Single `increment: BigDecimal` parameter. Optional — when absent, exact elapsed settlement. When present: `floor(ratePerSecond × elapsed / increment) × increment`.
**Notes:** User explicitly said "use increment, sounds simpler and does not need so many divisions and conversions."

---

## Stream Stop API Fields

**User additions:** Stop API must also accept an optional `reason: String` (default `"stop endpoint call"`). Clients need trackable reason for stream stops. Auto-termination uses `reason = "balance_exhaustion"`.

---

## Stop Response Contents

| Option | Description | Selected |
|--------|-------------|----------|
| settledAmount + stoppedAt + reason + finalBalance | Full confirmation including post-settlement balance | |
| settledAmount + stoppedAt + reason | Settlement confirmation only; caller queries balance separately | ✓ |

**User's choice:** settledAmount + stoppedAt + reason only

---

## Forward Balance Estimation Response

| Option | Description | Selected |
|--------|-------------|----------|
| estimatedBalance + estimatedAt only | Minimal | |
| estimatedBalance + committedBalance + estimatedAt | Both numbers | |
| estimatedBalance + committedBalance + estimatedAt + estimatedDrainAt | Full projection including drain timestamp | ✓ |

**User's choice:** All four fields. `estimatedDrainAt` as Unix epoch milliseconds (long), not seconds or ISO-8601.
**Notes:** User specified "estimatedDrainSeconds should be a datetime in unix format, not a seconds count."

---

## Discrete Debit Floor Check with In-flight Streams

| Option | Description | Selected |
|--------|-------------|----------|
| Use estimated balance | committedBalance minus active stream projections | ✓ |
| Use committed balance only | Simpler; floor breach handled at settlement | |

**User's choice:** Use estimated balance (Redis StreamRegistry consulted inside @Transactional after pessimistic lock)

---

## Settlement Race Condition

| Option | Description | Selected |
|--------|-------------|----------|
| Settle whatever remains | min(projected, available - floor); never below floor | ✓ |
| Reject settlement, leave ACTIVE | Risk of stuck streams | |
| Allow floor breach (debt mode) | Violates core invariant | |

**User's choice:** Settle whatever remains

---

## Stream Start Reserve Check

**User's choice:** Reject start if `estimatedBalance < newStream.minimumAmount + Σ max(0, activeStream.minimumAmount - activeStream.projectedDrained)`. Both new stream AND outstanding minimum obligations of all active streams must be coverable.
**Notes:** User clarified "both" when asked whether to check just new stream minimum or all streams.

---

## Closed Account Behavior

Stream start on CLOSED account → reject at stream start (409). Consistent with Phase 2 discrete transaction behavior.

Account close with ACTIVE streams → reject (409). Phase 1 success criteria already implied this; AccountService.close() must check StreamRegistry.

Auto-termination → settle only, account stays OPEN.

---

## OPS-02 Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Include in Phase 3 | Add retention tasks to Phase 3 plans | ✓ |
| Separate phase / deferred | Keep Phase 3 focused on streaming correctness | |

**User's choice:** Include in Phase 3
**Notes:** User emphasized that in enterprise products, audit log deletion is not allowed — records must be moved to archive storage first. Architecture must support this.

---

## Archive Destination

| Option | Description | Selected |
|--------|-------------|----------|
| Archive interface + no-op default | Pluggable archiver; enterprise provides S3 etc. | |
| Archive to separate Postgres schema/table | Concrete: audit_archive schema | ✓ |
| Log-only for Phase 3 | Deferred archival implementation | |

**User's choice:** audit_archive Postgres schema (concrete implementation). Enterprise operators can pg_dump/restore to cold storage.

---

## Retention Window

**User's choice:** 90 days, configurable via `token-engine.audit.retention-days`

## Idempotency Key TTL

**User's choice:** Delete after configurable window, default 48h (`token-engine.idempotency.ttl-hours`). No archival.

## Closed Account Archival

**User's choice:** Keep in accounts table indefinitely. No archival for closed accounts.

---

## Archival Job Trigger

**User's choice:** Spring `@Scheduled` with ShedLock for multi-pod safety. ShedLock `lockAtMostFor` and `lockAtLeastFor` configurable to handle pod crashes and long-running cleanups.

---

## Deployment Topology

| Option | Description | Selected |
|--------|-------------|----------|
| Single active pod | JVM-local StreamRegistry | |
| Multi-pod with sticky routing | Account-level load balancer affinity | |
| Multi-pod with shared Redis StreamRegistry | Any pod reads/writes stream state | ✓ |

**User's choice:** Multi-pod with shared Redis StreamRegistry
**Notes:** User raised "what happens when a pod dies or restarts" when JVM-local schedulers were proposed — drove the Redis architecture decision.

---

## Auto-Termination Scheduler

| Option | Description | Selected |
|--------|-------------|----------|
| Spring TaskScheduler + ConcurrentTaskScheduler | Spring-managed, single-pod bottleneck | |
| Java DelayQueue + background thread | Manual lifecycle, single-pod | |
| Redis Sorted Set + ShedLock single-pod poller | Distributed but bottlenecked | |
| Redisson DelayedQueue, all pods compete | Competing consumers, no single bottleneck | ✓ |

**User's choice:** Redisson DelayedQueue with all pods as competing consumers
**Notes:** User asked "can't Redisson be used by all pods? why is there only one pod reading the queue?" — clarifying that competing consumers was the intent.

## Scheduler Reschedule on Balance Change

**User's choice:** Remove old entry + enqueue new entry in Redisson DelayedQueue when exhaustion time changes.

---

## Redis Failure Behavior

**User's choice:**
- Stream operations → 503 when Redis unreachable
- Discrete debits on accounts with ACTIVE streaming_transactions rows → 503 (Postgres fallback check)
- Discrete credits → always allowed
- Account close with ACTIVE streams → 503

## Redis Recovery

**User's choice:** Rebuild StreamRegistry from Postgres ACTIVE rows on reconnect. Retroactively settle floor-exhausted streams. Re-enqueue remainder in Redisson.
**Notes:** User specifically asked "what happens to accounts reaching floor during outage?" — drove the reconciliation-on-recovery design.

## Fallback Termination During Redis Outage

**User's choice:** Postgres-backed fallback sweep (ShedLock-guarded @Scheduled) polling every 5 minutes (configurable). Handles auto-termination when Redisson unavailable.
**Notes:** User said "Need fallback termination without Redis" when offered the option to accept outage as freeze-then-catchup.

## Redis Sentinel

**User's choice:** Support Redis Sentinel via `token-engine.redis.sentinel.master` and `token-engine.redis.sentinel.nodes`. Deployment-agnostic (user rejected Kubernetes-specific assumptions).

---

## Claude's Discretion

- Exact Redis key TTL (if any) for StreamRegistry entries
- Lettuce connection pool configuration  
- HTTP status codes for validation errors (400 vs 422) beyond specified 409s
- Flyway migration numbering (V6+)
- Cucumber feature file structure for streaming tests
- Package structure within modules for streaming classes
- Exact ShedLock table name and configuration
- `streaming_transactions` Postgres table column names and indexes

## Deferred Ideas

- Tags on streaming transactions (TAG-01, TAG-06) — Phase 4
- Rake on streaming transactions (RAKE-02 through RAKE-04) — Phase 4
- Threshold events triggered by streaming settlements — Phase 4
- External event emission via transactional outbox — Phase 5
- AuditLogArchiver port interface for pluggable archive destinations (S3) — Phase 6 / v2
- Redis Cluster support beyond Sentinel — v2
- Streaming accumulation — out of scope
