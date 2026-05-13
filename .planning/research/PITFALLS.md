# Pitfalls Research

**Domain:** Real-time token economy engine — concurrent streaming + discrete ledger with multi-party sessions, rake extraction, and in-memory/Postgres hybrid state
**Researched:** 2026-05-13
**Confidence:** HIGH (concurrency/Postgres/Spring Boot pitfalls); MEDIUM (library/service duality, streaming rate arithmetic)

---

## Critical Pitfalls

### Pitfall 1: Using READ COMMITTED Isolation for Balance Checks

**What goes wrong:**
At READ COMMITTED (Postgres default), a "check then debit" pattern has a window where a second concurrent transaction can drain the same balance between the check and the write. Both transactions read a valid balance, both pass the sufficiency check, and both commit — producing a negative balance. This is the classic lost-update / double-spend anomaly. It does not require bugs; it is the documented behavior of READ COMMITTED.

**Why it happens:**
Developers assume that wrapping a balance check and debit in a single `@Transactional` method prevents the race. It does not. READ COMMITTED re-reads committed data at each statement, so a concurrent commit between `SELECT` and `UPDATE` is invisible to the current transaction.

**How to avoid:**
Use one of three patterns — never mix them within the same table:
1. `SELECT ... FOR UPDATE` with READ COMMITTED: the `FOR UPDATE` row lock prevents concurrent writes until the transaction commits. This is the lowest-overhead option for single-row balance rows.
2. Serializable Snapshot Isolation (SSI): set `spring.jpa.properties.hibernate.connection.isolation=8` (SERIALIZABLE). Postgres SSI detects the read/write dependency and aborts one transaction with `ERROR 40001`. The application must implement retry with exponential backoff for all `40001` errors before this is safe to deploy.
3. Optimistic locking with a `version` column (`@Version` in JPA): write fails with `OptimisticLockException` when the version has changed. Use this when contention is low and retry cost is acceptable.

For this engine — where streaming drains and discrete tips can hit the same balance concurrently — `SELECT ... FOR UPDATE` at READ COMMITTED is the correct choice: it avoids retry complexity while still providing row-level serialization.

**Warning signs:**
- Integration tests pass when run sequentially but produce wrong balances under parallel load
- Balance going negative in load tests that were not designed to overdraw
- Flaky tests that only fail at high thread counts

**Phase to address:** Core ledger phase (first phase that writes balance rows). This is a foundation correctness issue — it cannot be retrofitted.

---

### Pitfall 2: Floating-Point for Rates and Accumulated Drain Amounts

**What goes wrong:**
Using `double` or `float` to represent token rates (e.g., 1.5 tokens/minute) and accumulating duration × rate across ticks produces compounding rounding errors. A 60-minute session at 0.1 tokens/second accumulates 360 ticks; each tick has a small IEEE 754 error, and the sum diverges from the expected value in a way that is not deterministic across JVM implementations. Worse, the error is different each run because it depends on the order of floating-point operations.

The failure is silent. The session ends, the final debit is computed from accumulated `double` arithmetic, and the resulting charge is wrong by a fraction — small enough to miss in manual testing but large enough to matter at scale. One European bank discovered a multi-million euro discrepancy from this pattern over three years.

**Why it happens:**
Rates feel like "scientific" numbers, so developers reach for `double`. The error only appears after many accumulated operations and is invisible in unit tests that check a single tick.

**How to avoid:**
- Store rates as `BigDecimal` with explicit scale (e.g., scale 8, `HALF_EVEN` rounding) — use `BigDecimal.valueOf(double)` never `new BigDecimal(double)`.
- Accumulate elapsed time as `long` nanoseconds (from `System.nanoTime()`) and perform the rate × time multiplication in `BigDecimal` arithmetic at drain time.
- Define a canonical "minimum charge unit" (e.g., 1 micro-token = 0.000001 tokens) and store all intermediate quantities in integer micro-token units. This converts all arithmetic to exact integer math, eliminating rounding entirely for streaming accumulation.

**Warning signs:**
- Session-end balance differs by fractions across otherwise identical test runs
- Sum of individual tick charges does not equal total session charge when checked with `BigDecimal.compareTo`
- Tests use `assertEquals(expected, actual, 0.001)` delta tolerances — the delta is hiding the error

**Phase to address:** Core streaming engine phase. The data type decision must be made before the first rate-drain calculation is written.

---

### Pitfall 3: Wall-Clock Time for Elapsed Duration in Streaming Sessions

**What goes wrong:**
Using `System.currentTimeMillis()` or `Instant.now()` to compute elapsed session time means that NTP corrections, DST transitions, or operator clock adjustments can cause duration to go negative, jump forward, or produce non-monotonic intervals. A session started at wall-clock T1 and measured at T2 where T2 < T1 (after NTP step-backward) produces a negative charge or triggers an error path. A forward jump charges the viewer for time they did not consume.

**Why it happens:**
`System.currentTimeMillis()` is the intuitive choice for "current time." The wall-clock caveat is documented in the JDK but not obvious in everyday usage. NTP adjustments are rare in development but occur routinely in production (cloud VMs are especially prone to clock drift — up to 10 seconds per month on some hypervisors).

**How to avoid:**
- Use `System.nanoTime()` for all elapsed-time measurements within a single JVM session. `nanoTime()` is monotonic and unaffected by NTP or DST.
- Store session start as `System.nanoTime()` in the in-memory session object. Compute elapsed = `nanoTime() - sessionStartNano` at each drain tick.
- Use wall-clock `Instant.now()` only for: timestamps stored in audit records, and external API response fields. Never use it to compute duration.
- Important: `nanoTime()` values are meaningless across JVM restarts. On crash recovery, rehydrate sessions from Postgres using the stored wall-clock start timestamp and treat all in-flight time as zero until the next tick — do not attempt to backfill elapsed time from before the crash.

**Warning signs:**
- Session charges vary significantly on the same host on different days
- Negative elapsed-time values appearing in logs after deployments or VM migrations
- Tests pass on local machine but show inconsistent charges in CI (cloud runners have aggressive NTP sync)

**Phase to address:** Core streaming engine phase. The clock strategy must be decided alongside the drain accumulation model.

---

### Pitfall 4: In-Memory Hot State Diverging from Postgres on Crash

**What goes wrong:**
The engine holds active session state (current running balance, accumulated drain, session start time) in memory for sub-millisecond read performance. When the JVM crashes, any in-memory state that was not yet flushed to Postgres is lost. On restart, the engine reads the last committed Postgres snapshot, which may be seconds or minutes stale. The difference between the in-memory drain and the Postgres snapshot represents tokens that were consumed but never charged.

A more subtle failure: the engine writes to Postgres and also updates in-memory state. If the Postgres write succeeds but the in-memory update is not applied (e.g., exception after the commit), or vice versa, the two sources of truth diverge while the JVM is still running — the dual-write problem.

**Why it happens:**
The dual-source design is correct for performance, but the synchronization protocol between the in-memory state and Postgres is often underspecified. Developers write the happy path and discover the crash scenario during a production outage.

**How to avoid:**
- Treat Postgres as the authoritative record; treat in-memory state as a cache that is rebuilt from Postgres on startup.
- Write to Postgres first (WAL-based durability), then update in-memory state. If the in-memory update fails, the Postgres record is still correct and can be re-read.
- On startup, scan for sessions that were active at the time of the last write (e.g., a `session_status = 'ACTIVE'` flag in Postgres) and either resume them (re-building in-memory state from the DB record) or mark them as terminated with a final reconciliation charge.
- Implement a periodic flush: every N seconds, flush accumulated in-memory drain to Postgres as a partial charge record. This bounds the maximum exposure on crash.

**Warning signs:**
- No reconciliation procedure exists in the design
- Session-end charges are computed entirely from in-memory state with no Postgres cross-check
- Restart after a crash produces sessions that are "open" in Postgres with no owner in-memory

**Phase to address:** Core streaming engine phase for the flush protocol; crash recovery addressed as a dedicated concern in a hardening/reliability phase.

---

### Pitfall 5: Idempotency Key Not Enforced at the Database Level

**What goes wrong:**
The API accepts an idempotency key per transaction submission. The application checks whether the key exists in a table, and if not, processes the transaction. Under concurrent duplicate submissions (network retry, client-side retry, load balancer timeout-and-retry), two threads can simultaneously find the key absent and both proceed to apply the transaction — doubling the balance change.

Application-level idempotency checks (read-then-write in application code) are not safe under concurrent load without a database-enforced unique constraint, because two concurrent reads both see "key not present" before either write lands.

**Why it happens:**
Developers write the idempotency check as a service-layer `if` statement or a JPA `findByKey` followed by `save`. This looks correct in sequential testing. The race window is only exposed under parallel load.

**How to avoid:**
- Enforce idempotency keys with a `UNIQUE` constraint on the idempotency key column in Postgres. Let the database reject the duplicate at the `INSERT` level.
- Catch the unique constraint violation (`DataIntegrityViolationException` in Spring Data JPA), look up the original transaction result, and return it — do not re-apply the transaction.
- Keys must be scoped to both the operation type and the caller (e.g., `(caller_id, idempotency_key)` composite unique index) to prevent cross-caller key collisions.
- Never generate idempotency keys server-side from timestamps — timestamps are unreliable at high concurrency. Require caller-supplied UUIDv4 keys.
- Define a key expiry window (e.g., 24 hours) and enforce it — indefinite key retention creates a growing table scan problem.

**Warning signs:**
- Idempotency check is implemented as `repository.findByKey(key).isPresent()` without a DB constraint
- Load testing with deliberate duplicate requests shows balance charges applied more than once
- Idempotency keys are not part of the database schema definition (added as application logic only)

**Phase to address:** Core API/transaction submission phase. The unique constraint belongs in the initial schema migration.

---

### Pitfall 6: Hot Row Contention on a Single Balance Row

**What goes wrong:**
When multiple concurrent transactions all `SELECT ... FOR UPDATE` the same account balance row, they queue behind each other at the database row lock. At low concurrency this is invisible. At high concurrency (e.g., 50 simultaneous viewers each draining a single group session host's balance) lock wait time accumulates, P99 latency spikes, and throughput falls linearly with concurrency — every transaction serializes through one lock.

The problem is structural: a single frequently-updated row is the textbook definition of a hot row in Postgres.

**Why it happens:**
The locking strategy that is correct for correctness (row-level FOR UPDATE) becomes a throughput bottleneck when many transactions target the same single row. This only appears at realistic load; it is invisible in single-threaded tests.

**How to avoid:**
- For write-heavy accounts, consider an append-only immutable ledger entry pattern: instead of updating a balance row, insert new debit/credit entries. Balance is computed as `SUM` of entries (cached). This eliminates the hot row entirely because inserts do not contend with each other.
- For the engine's hot in-memory state, the in-memory map (e.g., `ConcurrentHashMap` keyed by session ID) handles concurrent reads without Postgres involvement. Postgres writes are batched or periodic, not per-tick.
- Use Postgres advisory locks keyed by account ID for per-account serialization rather than row-level `FOR UPDATE` when advisory lock release semantics are clearer for the use case.
- Tune autovacuum aggressively for the balance table: `autovacuum_vacuum_scale_factor = 0.01` and `autovacuum_vacuum_cost_delay = 0`. The default 20% dead-tuple threshold is catastrophic for a table receiving thousands of updates per second — MVCC dead tuples accumulate faster than vacuum can remove them, causing table bloat and full-table scan degradation.

**Warning signs:**
- Postgres `pg_locks` shows many transactions waiting for the same `relation` + `tid`
- `pg_stat_activity` shows many sessions in `Lock wait` state on the balance table
- P99 latency increases proportionally with concurrent session count
- `pg_stat_user_tables.n_dead_tup` on the balance table grows continuously without flattening

**Phase to address:** Core concurrency and performance phase. The data model (append-only vs. update-in-place) is an architectural decision that cannot be changed cheaply once written.

---

### Pitfall 7: Balance Estimation Race Between Projection and a New Transaction Landing

**What goes wrong:**
The estimation endpoint reads the current committed balance from Postgres, adds the projected drain from all in-flight streams, and returns a "projected balance at time T." Meanwhile, a discrete transaction (tip, debit) commits between the moment the committed balance is read and the moment the in-flight drain projection is computed. The estimation silently ignores the new transaction, reporting a balance that is higher than the real projected balance.

The reverse is also possible: an estimation reads in-flight sessions, then a session ends and commits a final debit before the estimation sums its projection — the estimation counts a drain that has already been committed and the balance appears lower than reality.

**Why it happens:**
Estimation is inherently a snapshot problem. There is no transaction-safe way to atomically read "committed balance + all active in-flight state" unless both live in the same consistent read scope.

**How to avoid:**
- Define estimation as a best-effort snapshot, not a guarantee. Document and surface this in API responses (e.g., `estimated_at` timestamp and `confidence: "APPROXIMATE"` in the response body).
- Take the snapshot atomically from in-memory state only (not by reading Postgres): the in-memory session map has the current committed balance (last flush) + the running accumulated drain. Read both under a read lock or use `ConcurrentHashMap` with volatile fields. Never re-query Postgres mid-estimation.
- Return a staleness bound: the maximum age of the underlying committed balance used in the projection. Callers can decide whether to act on the estimate.
- Estimation must never block transactions. Use a non-blocking read path — the estimation reads a consistent in-memory snapshot while writes proceed concurrently.

**Warning signs:**
- Estimation endpoint reads from Postgres inside the same computation that adds in-flight drain
- No `estimated_at` or staleness field in the estimation response
- Tests for estimation accuracy run in single-threaded scenarios only

**Phase to address:** Estimation feature phase. The estimation model must be designed alongside the in-memory state model, not as an afterthought.

---

### Pitfall 8: Event Ordering Not Enforced — Out-of-Order Events Corrupt Ledger State

**What goes wrong:**
If transaction events are processed out of sequence (e.g., a "session end" event arrives before the last "drain tick" event, or a "tip" event is applied before the "session start" that established the balance), the resulting ledger state is incorrect and the audit trail is non-deterministic.

This is especially acute if any async processing, message queue, or retry mechanism is added later — queues do not guarantee ordering by default. Even within a single JVM, thread scheduling can cause events queued with `CompletableFuture` to complete out of order.

**Why it happens:**
In-process synchronous implementations implicitly order events by call order. When async or distributed elements are introduced (even incrementally), the ordering guarantee silently disappears. Developers often do not notice until an edge case in production produces a corrupted balance.

**How to avoid:**
- Assign a monotonic sequence number to every event within a session at the point of acceptance (not at the point of persistence). Enforce ordering in the event store: reject or requeue any event whose sequence is not `current_sequence + 1`.
- For this engine (single JVM, in-memory hot state), enforce ordering per session through a session-owned reentrant lock or a single-threaded executor per session. Events for the same session must never be processed concurrently.
- In Postgres, use an `event_sequence` column with a unique constraint on `(session_id, sequence)` to prevent out-of-order persistence even if in-memory ordering is violated.
- If any queue or async mechanism is introduced in the future, treat ordering re-verification as a mandatory design gate.

**Warning signs:**
- Events for a session are processed by a shared thread pool without per-session sequencing
- No sequence number on transaction records in the DB schema
- Session end is handled by a different code path than drain ticks, with no ordering enforcement between the two

**Phase to address:** Core session lifecycle phase. Sequence numbering belongs in the initial schema and event model.

---

### Pitfall 9: Library/Service Duality — Spring Context and Configuration Property Clashes

**What goes wrong:**
When the engine is embedded as a library in a host Spring Boot application, three classes of conflict appear:

1. **Bean name collision**: A bean named `transactionService` or `sessionManager` in the engine's autoconfiguration conflicts with a bean of the same name in the host application. Spring Boot 2.1+ throws `BeanDefinitionOverrideException` rather than silently overriding.

2. **Configuration property namespace collision**: Properties under a generic prefix (e.g., `engine.rate`, `engine.session`) collide with host application properties or reserved Spring Boot namespaces. The host's `application.properties` silently overrides engine defaults or the engine's `@ConfigurationProperties` picks up host values it was not designed for.

3. **DataSource / schema management conflict**: If the engine registers its own Flyway or Liquibase `DataSource` bean, it competes with the host application's data source. The host's schema migration runs against the engine's schema or vice versa, producing corrupted DB state at startup.

**Why it happens:**
Library developers test their artifact as a standalone service. The embedded context, where the engine's Spring context is merged with an arbitrary host context, is only exercised when someone actually embeds the JAR. The conflicts are invisible until integration.

**How to avoid:**
- Use a globally unique property prefix owned by this project (e.g., `certacota.*` or `token-engine.*`). Never use generic prefixes like `engine.*` or `session.*`.
- Qualify all bean names with the library namespace using `@Bean(name = "tokenEngine.transactionService")`. Use `@ConditionalOnMissingBean` on all auto-configured beans to allow host override without conflict.
- Make schema management opt-in: provide a `token-engine-schema.sql` but default autoconfigure to `spring.token-engine.schema.auto=false`. Never register a Flyway bean in autoconfigure — provide a documented SQL script the host runs.
- Write an integration test that starts the engine's autoconfigure inside a minimal Spring Boot application that already has a `DataSource` bean and a conflicting `transactionService` bean. This test must pass for the library artifact to be shippable.

**Warning signs:**
- Engine autoconfiguration registers beans with generic, non-namespaced names
- Configuration properties use `@ConfigurationProperties(prefix = "engine")` — too generic
- No test exercises embedding the starter inside a mock host application context

**Phase to address:** Library packaging phase. The namespacing decision must be made before any `@Configuration` class is written — it is expensive to rename beans and properties after callers have adopted them.

---

### Pitfall 10: Unit Tests Cannot Catch Concurrent Ledger Bugs

**What goes wrong:**
A comprehensive unit test suite passes with 100% line coverage. In production, balances go negative under load. Unit tests are inherently single-threaded and deterministic — they cannot reproduce the interleaving of concurrent operations that produces double-spend, lost update, or out-of-order event bugs.

A typical failure pattern: the unit test calls `debit(account, amount)` twice sequentially and asserts the balance is correct. The concurrent failure mode is two threads calling `debit` simultaneously — this requires actual thread interleaving to manifest, which JUnit does not produce by default.

**Why it happens:**
Unit tests are the default testing tool, and they work well for business logic. Developers conflate "tests pass" with "concurrent correctness verified." Concurrency bugs have a non-deterministic manifestation window — they may appear only at specific thread interleavings that are rare and not reproducible on demand.

**How to avoid:**
- Write concurrent integration tests with real Postgres (via Testcontainers): spawn N threads simultaneously invoking the same balance operation, assert the final balance equals expected, assert no duplicate charges exist. This is the minimum bar for concurrent correctness verification.
- Use stress tests (property-based with randomized thread interleavings, or JUnit's `@RepeatedTest` with thread pool execution) to increase the probability of exposing a race window.
- For the most critical invariant (no overdraft, no double-charge), implement a database-level assertion: after every concurrent stress test, query the ledger table directly and verify that the sum of all debits equals the expected total. This is independent of application logic.
- Consider Jepsen-style tests (or a simplified equivalent) for the most critical correctness properties: serialize operations, inject concurrent writes, verify the history is linearizable. This is appropriate for the core balance update path given that correctness is the stated non-negotiable constraint of this engine.

**Warning signs:**
- All concurrency-related test coverage is achieved by `@Test` methods that call service methods sequentially
- No `Testcontainers`-based integration test exists for concurrent balance mutations
- Concurrency is mentioned in the requirements but not in the test plan

**Phase to address:** Every phase. Concurrent integration tests must be written alongside each feature, not as a separate testing phase. The test infrastructure (Testcontainers Postgres) must be established in the first phase.

---

### Pitfall 11: Rake Split Not Atomic with the Primary Transfer

**What goes wrong:**
A three-way split (from → to → platform) is implemented as three sequential database operations: debit sender, credit recipient, credit platform. If the application crashes or throws between any two operations, the ledger is left in a partially applied state. The sender is debited but the recipient is not credited, or the recipient is credited but the platform rake is not taken.

Even within a transaction, if the rake calculation uses application-level arithmetic (e.g., `rake = amount * rakePercent` computed in Java), floating-point or rounding errors can cause `debit != credit1 + credit2` — pennies disappear or appear from nothing.

**Why it happens:**
Developers implement the split as sequential service calls within a `@Transactional` method and assume the transaction guarantees atomicity. The transaction does guarantee atomicity for the database writes, but it does not guarantee that the arithmetic produces a balanced ledger. Rounding distributes error across the three amounts, and the distribution rule (who absorbs the rounding residual) is often left unspecified.

**How to avoid:**
- All three legs of the split must be written in a single database transaction. Use `@Transactional(propagation = REQUIRED)` and verify there is no `REQUIRES_NEW` or nested transaction that could partially commit.
- Compute the rake amount first; derive the recipient amount as `total - rake` rather than computing both independently. This ensures the three amounts sum to exactly the original transfer amount without a residual.
- Add a database-level check constraint: `debit_amount = credit_recipient + credit_platform`. This enforces ledger balance at the schema level and catches any future code path that violates it.
- Store rake percentage as a `NUMERIC(5,4)` in Postgres (not as a Java `double`) and perform the rake calculation in Postgres SQL or in Java `BigDecimal` with explicit scale and `HALF_EVEN` rounding.

**Warning signs:**
- Rake credit is applied in a separate `@Transactional` method called after the primary transfer
- Rake amount is computed as `amount * rakeRate` where both are Java `double`
- No database constraint enforcing that debit equals sum of credits

**Phase to address:** Rake engine phase. The atomicity and arithmetic correctness must be verified before the rake feature is considered complete.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| `double` for token rates | Simpler code | Silent precision errors accumulate; ledger balances drift from reality | Never — use `BigDecimal` from the start |
| `System.currentTimeMillis()` for elapsed time | Familiar API | Wall-clock jumps produce negative or inflated session charges in production | Never for duration — use `System.nanoTime()` |
| Application-level idempotency check only | Faster to code | Concurrent duplicate submissions create double charges | Never — DB unique constraint is required |
| READ COMMITTED without `FOR UPDATE` | Default, no config needed | Double-spend window exists for all concurrent balance operations | Never on balance-affecting writes |
| Sequential unit tests for concurrency features | Fast test suite | Cannot detect race conditions; false confidence | Never as sole verification — concurrent integration tests required |
| Generic `@ConfigurationProperties(prefix = "engine")` | Convenient | Collides with host application properties when embedded | Never — use a project-specific namespace |
| Eager autovacuum defaults on balance table | No configuration effort | MVCC dead-tuple bloat degrades query performance at production write rates | Acceptable at MVP, tune before production load |
| In-memory only session state with no flush | Simple, fast | Crash loses all uncommitted session charges | Acceptable only with crash recovery and reconciliation design documented |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Postgres Serializable isolation | Enabling serializable without retry logic | All `40001` serialization errors must be caught and retried with exponential backoff before deploying |
| Spring Data JPA `@Version` optimistic lock | Catching `OptimisticLockException` and logging, then returning success | Catch the exception, retry the full read-modify-write sequence, surface final failure to caller |
| Testcontainers Postgres in parallel tests | Shared container with shared schema creates test interference | Use `@Testcontainers` with per-test schema isolation or sequential test execution for ledger correctness tests |
| Flyway in embedded library mode | Registering a `Flyway` bean in autoconfiguration | Provide SQL migration scripts; let the host run them. Never auto-configure schema management in a library |
| Postgres autovacuum on balance table | Leaving default scale factor (0.2) on a high-update table | Set `autovacuum_vacuum_scale_factor = 0.01` per-table for the balance/session tables |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Hot balance row under `SELECT FOR UPDATE` | P99 latency grows linearly with concurrent session count | Append-only entries or in-memory state for hot reads | ~10 concurrent sessions against the same account |
| Postgres MVCC dead-tuple accumulation on balance table | Table scans slow down; disk grows; autovacuum runs constantly | Per-table autovacuum tuning (`scale_factor=0.01`, `cost_delay=0`) | Balance table at ~1000 updates/sec with default autovacuum |
| Balance recomputed from full ledger scan on every read | Estimation latency increases with transaction history depth | Balance cache (running total) updated incrementally; never full-scan for current balance | ~10,000 ledger entries per account |
| `BigDecimal` without scale set | `ArithmeticException: Non-terminating decimal expansion` in division | Always specify scale and rounding mode on `divide()` | First time a rate results in a non-terminating decimal |
| Per-request Postgres connection for estimation | Connection pool exhaustion under estimation polling load | Cache estimation result in-memory for N milliseconds; serve from cache | ~100 concurrent estimation pollers |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Idempotency keys accepted without caller scoping | A caller uses another caller's key to suppress a legitimate transaction | Scope idempotency keys to `(caller_id, idempotency_key)` — never accept a raw key without caller identity |
| Rake percentage stored as application config only | Operator misconfiguration silently changes all future rake calculations with no audit trail | Store rake configuration in the database with a timestamp and effective-from version; log all changes |
| Balance estimation response cached indefinitely | Stale estimation used to authorize an over-limit action | Include `estimated_at` and a TTL in estimation responses; document the staleness bound |
| Session IDs that are sequential integers | Enumeration attack — caller probes session IDs to discover session state | Use UUIDv4 for all session and transaction identifiers |
| Trusting caller-supplied timestamps for duration | Caller manipulates duration to reduce charges | Never use caller-supplied timestamps for session duration calculation; derive duration from server-side monotonic clock |

---

## "Looks Done But Isn't" Checklist

- [ ] **Idempotency**: Application check exists — verify a `UNIQUE` DB constraint enforces it at the persistence layer under concurrent load
- [ ] **Isolation level**: `@Transactional` is present — verify `SELECT ... FOR UPDATE` or SERIALIZABLE is used on balance reads, not plain SELECT
- [ ] **Rake atomicity**: Three-way split code exists — verify all three writes occur in a single transaction with no nested `REQUIRES_NEW`
- [ ] **Estimation**: GET endpoint returns a number — verify it includes an `estimated_at` timestamp and documents staleness
- [ ] **Session crash recovery**: Sessions are persisted — verify a startup routine reconciles `ACTIVE` sessions against in-memory state
- [ ] **Library packaging**: JAR artifact builds — verify an integration test embeds it into a host Spring Boot context with pre-existing `DataSource` and `transactionService` beans
- [ ] **Streaming arithmetic**: Rate × time calculation exists — verify it uses `BigDecimal` or integer micro-units, not `double`
- [ ] **Time source**: Elapsed time computed — verify `System.nanoTime()` is used for duration, not `System.currentTimeMillis()`
- [ ] **Concurrent correctness**: Unit tests pass — verify at least one test invokes balance mutation from multiple threads simultaneously against real Postgres

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Floating-point balance drift discovered in production | HIGH | Audit all ledger records, recompute from raw events with `BigDecimal`, reconcile diffs, issue correction entries; requires business sign-off on each affected account |
| Double-spend from missing DB constraint | HIGH | Identify duplicate idempotency keys in the ledger table, determine which was the intended charge, reverse the duplicate with a correction entry, add the missing DB constraint before resuming |
| MVCC bloat causing query degradation | MEDIUM | Run manual `VACUUM FULL` (table lock required, schedule maintenance window), tune per-table autovacuum, monitor dead tuple counts going forward |
| Library property namespace collision in host application | MEDIUM | Rename properties with a migration guide; requires host teams to update their configuration files — coordination cost is the main burden |
| Bean name collision in embedded library | MEDIUM | Rename beans with `@Bean(name = "...")`, rebuild and republish; host teams must clear cached Spring context |
| Lost in-memory session state on crash | MEDIUM | Mark unreconciled sessions as `TERMINATED_UNRECONCILED` in Postgres, issue partial charge based on last flush, notify callers via webhook or audit event |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| READ COMMITTED double-spend | Core ledger — first balance write | Concurrent integration test: two threads simultaneously debit the same account, verify no overdraft |
| Floating-point rate arithmetic | Core streaming engine | Assert that 1-hour session at 0.1 tokens/min charges exactly 6.0 tokens with zero delta (integer or `BigDecimal` comparison) |
| Wall-clock elapsed time | Core streaming engine | Unit test that mocks NTP step-backward and verifies session duration remains non-negative |
| In-memory / Postgres divergence on crash | Core streaming + hardening phase | Test: start session, kill JVM mid-session, restart, verify reconciliation produces expected partial charge |
| Idempotency key race | Core API phase — transaction submission endpoint | Concurrent test: send 50 identical requests simultaneously, verify exactly one charge applied |
| Hot row contention | Core concurrency / data model phase | Load test: 50 concurrent sessions against same account, verify P99 < threshold |
| Balance estimation staleness | Estimation feature phase | Test: submit a transaction mid-estimation, verify response includes `estimated_at` and staleness bound |
| Out-of-order event corruption | Core session lifecycle phase | Test: inject session-end before last drain-tick, verify engine rejects or sequences correctly |
| Library/service duality conflicts | Library packaging phase | Integration test: embed starter in mock host app with conflicting bean; verify startup succeeds |
| Unit tests missing concurrency | All feature phases | CI gate: concurrent integration tests must pass before feature branch merges |
| Rake split non-atomicity | Rake engine phase | Test: inject exception after first write, verify no partial state in ledger |

---

## Sources

- Modern Treasury: "Designing the Ledgers API with Optimistic Locking" — https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking
- Modern Treasury: "How to Handle Concurrent Transactions" — https://www.moderntreasury.com/journal/how-to-handle-concurrent-transactions
- Modern Treasury: "Behind the Scenes: How We Built Ledgers for High Throughput" — https://www.moderntreasury.com/journal/behind-the-scenes-how-we-built-ledgers-for-high-throughput
- PostgreSQL Documentation: Transaction Isolation — https://www.postgresql.org/docs/current/transaction-iso.html
- PostgreSQL Documentation: Explicit Locking — https://www.postgresql.org/docs/current/explicit-locking.html
- Baeldung: Optimistic Locking in JPA — https://www.baeldung.com/jpa-optimistic-locking
- Vlad Mihalcea: Optimistic vs. Pessimistic Locking — https://vladmihalcea.com/optimistic-vs-pessimistic-locking/
- Confluent: The Dual-Write Problem — https://www.confluent.io/blog/dual-write-problem/
- CockroachLabs: Idempotency in Finance — https://www.cockroachlabs.com/blog/idempotency-in-finance/
- Baeldung: Measuring Elapsed Time in Java — https://www.baeldung.com/java-measure-elapsed-time
- CMU Andy Pavlo: The Part of PostgreSQL We Hate the Most (MVCC bloat) — https://www.cs.cmu.edu/~pavlo/blog/2023/04/the-part-of-postgresql-we-hate-the-most.html
- Tembo: Optimizing Postgres Autovacuum for High-Churn Tables — https://www.tembo.io/blog/optimizing-postgres-auto-vacuum
- Spring Boot Reference: Creating Your Own Auto-configuration — https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html
- Baeldung: BeanDefinitionOverrideException in Spring Boot — https://www.baeldung.com/spring-boot-bean-definition-override-exception
- Medium: The Floating Point Standard Breaking Financial Software — https://medium.com/@sohail_saifii/the-floating-point-standard-thats-silently-breaking-financial-software-7f7e93430dbb

---
*Pitfalls research for: Real-time token economy engine (Java / Spring Boot / Postgres)*
*Researched: 2026-05-13*
