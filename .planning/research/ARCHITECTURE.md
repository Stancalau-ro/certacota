# Architecture Research

**Domain:** Real-time token economy / ledger engine
**Researched:** 2026-05-13
**Confidence:** HIGH (multiple corroborating authoritative sources)

## Standard Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           API Layer                                      │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐        │
│   │  Account REST   │  │  Session REST   │  │  Estimation REST│        │
│   │  Controller     │  │  Controller     │  │  Controller     │        │
│   └────────┬────────┘  └────────┬────────┘  └────────┬────────┘        │
└────────────┼────────────────────┼────────────────────┼─────────────────┘
             │                    │                    │
┌────────────┼────────────────────┼────────────────────┼─────────────────┐
│                         Domain Layer                                     │
│   ┌────────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐        │
│   │ AccountService  │  │ SessionService  │  │ EstimationService│       │
│   │                 │  │ (orchestrator)  │  │                 │        │
│   └────────┬────────┘  └────────┬────────┘  └────────┬────────┘        │
│            │                    │                    │                  │
│   ┌────────▼────────────────────▼────────────────────▼────────┐        │
│   │              TransactionEngine                              │        │
│   │   (atomic commit, rake calculation, version check)         │        │
│   └────────────────────────────┬────────────────────────────--┘        │
│                                │                                         │
│   ┌────────────────────────────▼────────────────────────────--┐        │
│   │              RakeEngine                                     │        │
│   │   (configurable three-way split per transaction type)       │        │
│   └────────────────────────────┬────────────────────────────--┘        │
└────────────────────────────────┼────────────────────────────────────────┘
                                 │
┌────────────────────────────────┼────────────────────────────────────────┐
│                         State Layer                                      │
│   ┌──────────────────┐         │        ┌──────────────────────────┐   │
│   │  In-Memory Store │         │        │  Outbox Table (Postgres) │   │
│   │  (hot session    │         │        │  (domain events pending  │   │
│   │   state, streams)│         │        │   external dispatch)     │   │
│   └──────────────────┘         │        └──────────────────────────┘   │
│                       ┌────────▼───────────────────────────────────┐   │
│                       │          Postgres                           │   │
│                       │  accounts | transactions | ledger_entries   │   │
│                       │  sessions | outbox_events                   │   │
│                       └────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                 │
┌────────────────────────────────┼────────────────────────────────────────┐
│                      Event Emission Layer                                │
│   ┌──────────────────┐         │        ┌──────────────────────────┐   │
│   │ Spring           │         │        │  Outbox Poller / CDC     │   │
│   │ ApplicationEvent │         │        │  (Debezium or scheduler) │   │
│   │ (intra-process)  │         │        │  → Kafka / webhook       │   │
│   └──────────────────┘         │        └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Implementation Approach |
|-----------|----------------|-------------------------|
| AccountService | Create/read accounts, enforce balance constraints | Spring service, delegates writes to TransactionEngine |
| SessionService | Session lifecycle (start/pause/end), coordinate multi-party flows | Orchestration saga within a single DB transaction where possible |
| TransactionEngine | Atomic commit of ledger entries with optimistic locking | Version check in WHERE clause, exponential-backoff retry |
| RakeEngine | Three-way split calculation (from → to → platform) per transaction type | Pure function; called inside TransactionEngine commit |
| EstimationService | Real-time projected balance given active streams | Mathematical projection over in-memory state; no DB read required |
| In-Memory Store | Hot session state: active streams, their rates, start timestamps | ConcurrentHashMap keyed by sessionId, stream entries per participant |
| Outbox Poller | Read undelivered outbox rows, dispatch to Kafka/webhook, mark delivered | Scheduled Spring task or CDC via Debezium |

---

## Recommended Project Structure

```
certacota-engine/                  # parent Maven/Gradle project
├── engine-core/                   # pure domain — no Spring, no I/O
│   └── src/main/java/
│       └── com/certacota/core/
│           ├── model/             # Account, Transaction, LedgerEntry, Session, Stream
│           ├── service/           # TransactionEngine, RakeEngine, EstimationEngine
│           └── event/             # domain event types (POJOs)
│
├── engine-spring/                 # Spring integration — autoconfigure starter
│   └── src/main/java/
│       └── com/certacota/spring/
│           ├── autoconfigure/     # @Configuration, @ConditionalOnMissingBean
│           ├── repository/        # Spring Data JPA repos
│           ├── outbox/            # OutboxTable entity, poller
│           └── event/             # ApplicationEvent bridge, Kafka adapter (optional)
│   └── src/main/resources/
│       └── META-INF/spring/
│           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│
└── engine-service/                # runnable Spring Boot service
    └── src/main/java/
        └── com/certacota/service/
            ├── Application.java   # @SpringBootApplication
            └── rest/              # @RestController classes
```

### Structure Rationale

- **engine-core/:** Zero Spring dependency. Contains the domain model and pure business logic. This is the testable heart — unit tests run without a Spring context. Keeps engine-spring and engine-service interchangeable.
- **engine-spring/:** The autoconfigure starter. Embedders add `engine-spring` to their pom and get all beans wired automatically via `AutoConfiguration.imports`. Does not start an HTTP server.
- **engine-service/:** Thin shell. Depends on engine-spring, adds REST controllers, and boots the server. Produces the deployable fat JAR.

---

## Architectural Patterns

### Pattern 1: Optimistic Locking with Version-Gated Commit

**What:** Each `LedgerAccount` row carries a `lock_version` integer. The commit UPDATE includes `WHERE id = ? AND lock_version = ?`. Zero rows updated means another writer won; retry with exponential backoff + full jitter.

**When to use:** Whenever two or more concurrent requests touch the same account balance — the normal case for shared session participants.

**Trade-offs:** Throughput degrades under extreme contention (Martin Richards benchmark: 434 req/s low contention → 159 req/s with 2 hot accounts, zero transactions lost). Acceptable because correctness is the non-negotiable constraint per PROJECT.md.

**Why not actor-per-account:** Akka actors would provide per-account serialization with no retry, but add a non-Spring dependency and complicate the library packaging. Optimistic locking achieves the same correctness guarantee with stock Spring Data JPA and Postgres.

**Why not pessimistic locking:** Exclusive row locks block concurrent reads. Under streaming sessions with many readers and periodic writers, this would serialize unnecessarily.

```java
// Commit step in TransactionEngine
int rows = jdbcTemplate.update(
    "UPDATE ledger_account SET balance = ?, lock_version = lock_version + 1 " +
    "WHERE id = ? AND lock_version = ?",
    newBalance, accountId, expectedVersion);
if (rows == 0) throw new OptimisticLockConflict(accountId);
```

### Pattern 2: Mathematical Projection for Streaming Balance Estimation

**What:** A streaming session (rate drain over unknown duration) is NOT modeled as a series of periodic tick transactions. Instead, the session's start time, rate, and all discrete transactions since start are recorded in-memory. The projected balance at any instant is:

```
projectedBalance = committedBalance
                 - (rate × (now - streamStart).toSeconds())
                 - sum(discreteDebitsSinceStreamStart)
```

**When to use:** Any GET /accounts/{id}/balance?projected=true request during an active session.

**Trade-offs:** The committed balance in Postgres falls behind the real-world depletion. Recovery requires replaying the in-memory session state. This is explicitly acknowledged in PROJECT.md: "Hot state held in-memory; durable state committed to a relational DB for audit, recovery, and dispute resolution."

**Why not periodic ticks:** Tick-based drains create database write storms proportional to (active_sessions × tick_frequency), add latency jitter to debit events, and make session-end settlement messy when the last tick doesn't align with actual end time.

**Why not virtual transactions:** Writing a "virtual" pending transaction that updates as time passes requires either mutable append-only records (contradicts immutability) or a separate projection table that must be kept consistent with real transactions.

### Pattern 3: Transactional Outbox for External Event Emission

**What:** Every committed ledger operation writes a row to an `outbox_events` table inside the same Postgres transaction. A separate poller (Spring @Scheduled task or Debezium CDC) reads undelivered rows and dispatches to Kafka, webhook, or any platform-registered listener, then marks the row delivered.

**When to use:** Any event that must survive a crash and be delivered to external consumers (payment rail integrations, audit subscribers, platform webhooks).

**Trade-offs:** At-least-once delivery — idempotency keys on events are required. Adds an outbox table and a poller. The alternative (direct Kafka publish in the same transaction) is a dual-write that can produce silent failures.

**Intra-process events (Spring ApplicationEvent):** Use for module-to-module coordination within the same JVM (e.g., EstimationService reacting to SessionService lifecycle changes). These are fire-and-forget and do not survive crashes — acceptable for cache invalidation and in-memory state updates.

```java
// Inside @Transactional method
transactionRepository.save(ledgerTransaction);
outboxRepository.save(OutboxEvent.of("TRANSACTION_COMMITTED", ledgerTransaction));
// Poller reads outbox and dispatches asynchronously
```

### Pattern 4: Dual-Mode Packaging (Library + Service)

**What:** Three-module Maven/Gradle project: `engine-core` (pure domain, no Spring), `engine-spring` (autoconfigure starter JAR), `engine-service` (runnable fat JAR). Spring Boot's `AutoConfiguration.imports` mechanism wires the starter automatically when the JAR is on the classpath.

**When to use:** Any project that must ship both as an embeddable library and as a standalone deployable service.

**Trade-offs:** Three modules to maintain. But the alternative — a monolithic Spring Boot app that embedders import — pulls in a web server, opinionated logging, and fixed dependency versions that conflict with host applications.

**Boundary rule:** `engine-core` must have zero Spring dependencies. `engine-spring` depends on `spring-boot` (not `spring-boot-starter`). `engine-service` depends on `spring-boot-starter-web`. This prevents the starter from forcing a web server on embedders.

### Pattern 5: Orchestration Saga for Multi-Party Session Transactions

**What:** A session transaction across N participants is orchestrated by `SessionService` as a sequence of steps within a single DB transaction where all participants share the same Postgres instance, or as a compensating saga (with outbox-mediated rollback events) if participants span service boundaries.

**When to use:** Session start/end that must atomically set all participant balances, flow states, and rake entries. The saga coordinates, not the participants.

**Why not choreography saga:** Choreography (each event triggers the next participant's local transaction) requires per-participant idempotency and makes debugging session state extremely hard. Orchestration centralizes the flow in `SessionService`, which can inspect and compensate the whole session.

---

## Data Flow

### Discrete Transaction Flow (single transfer)

```
POST /transactions
    ↓
TransactionController
    ↓ (validates request, extracts participantIds, amount, metadata)
TransactionEngine.commit()
    ↓ (1) READ: SELECT account + lock_version for each participant
    ↓ (2) COMPUTE: RakeEngine.split(amount, txType) → {toAmount, rakeAmount}
    ↓ (3) WRITE (single @Transactional):
    │       UPDATE ledger_account WHERE lock_version = ?  [optimistic check]
    │       INSERT ledger_transaction
    │       INSERT ledger_entry × N (one per participant, incl. platform)
    │       INSERT outbox_event
    ↓ (4) on OptimisticLockConflict → retry with jitter (max 3 attempts)
    ↓
ApplicationEventPublisher.publishEvent(TransactionCommittedEvent)
    ↓ (sync, intra-process)
EstimationService.invalidateCache(sessionId)
    ↓
OutboxPoller (async, separate thread)
    ↓ → Kafka / webhook
HTTP 201
```

### Streaming Session Start Flow

```
POST /sessions/{id}/start
    ↓
SessionService.start()
    ↓ (1) Validate all participants have sufficient projected balance
    ↓ (2) @Transactional:
    │       UPDATE session SET status = ACTIVE
    │       INSERT outbox_event(SESSION_STARTED)
    ↓ (3) In-memory store:
    │       streamRegistry.put(sessionId, StreamState{rate, startTime, participants})
    ↓
ApplicationEventPublisher → EstimationService primes projection cache
HTTP 200
```

### Real-Time Balance Estimation Flow

```
GET /accounts/{id}/balance?projected=true
    ↓
EstimationController
    ↓
EstimationService.project(accountId, now)
    ↓ (1) READ from Postgres: committed_balance (latest committed ledger entry sum)
    ↓ (2) READ from in-memory StreamRegistry: active streams for this account
    ↓ (3) COMPUTE: projectedBalance = committed - Σ(rate × elapsed) - discretePendingDebits
    ↓
HTTP 200 {committedBalance, projectedBalance, projectedAt}
```

### Session End / Settlement Flow

```
POST /sessions/{id}/end
    ↓
SessionService.end()
    ↓ (1) Pull StreamState from in-memory registry
    ↓ (2) Calculate final amounts: rate × actualDuration
    ↓ (3) TransactionEngine.commit() — writes final drain as a discrete transaction
    │       (same optimistic lock flow as discrete transaction)
    ↓ (4) streamRegistry.remove(sessionId)
    ↓ (5) outbox_event(SESSION_ENDED, finalAmounts)
HTTP 200
```

---

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| Single instance, <100 concurrent sessions | Monolith is correct. In-memory StreamRegistry lives in the single JVM. No changes needed. |
| Multi-instance, shared Postgres | In-memory StreamRegistry must become a distributed cache (Redis) or each instance must own a shard of sessions. Session affinity via load balancer is simplest: route all requests for a sessionId to the same instance. |
| High write contention on popular accounts | Optimistic locking retry rate rises. Consider account-level partitioning: each account gets a dedicated worker queue (actor-like sharding without Akka, using Java virtual threads or Executor per account segment). |
| Outbox throughput ceiling | Add Debezium CDC to replace polling. Debezium reads Postgres WAL directly, eliminating the polling query and providing sub-second latency at high volume. |

### Scaling Priorities

1. **First bottleneck:** Optimistic lock retry storms on accounts with extreme concurrent write rates (e.g., a session with 1000 simultaneous viewers). Mitigation: credit batching — accumulate all per-tick credits for a session end settlement rather than per-tick writes.
2. **Second bottleneck:** In-memory StreamRegistry becomes a split-brain problem on multi-instance deployment. Mitigation: Redis for session state or load-balancer session affinity.

---

## Anti-Patterns

### Anti-Pattern 1: Tick-Based Streaming Drain

**What people do:** Emit a "drain" transaction every N seconds for each active stream, writing it to the ledger on a schedule.

**Why it's wrong:** Write amplification proportional to active_sessions × (1/tick_interval). A 1-second tick with 500 sessions generates 500 writes/second of pure accounting overhead. Settlement on session end is inexact (fractional ticks). Recovery after crash requires replaying ticks vs actual end time.

**Do this instead:** Store stream metadata (rate, start time) in-memory. Project balance mathematically. Commit a single settlement transaction at session end.

### Anti-Pattern 2: Separate Version Column on Account

**What people do:** Lock the entire account row for each balance update.

**Why it's wrong:** Updating account metadata (display name, status) contends with balance updates even though they're logically unrelated. Results in spurious conflicts and unnecessary retries.

**Do this instead:** Follow Modern Treasury's pattern: a separate `ledger_account_version` table with a one-to-one relationship. Only balance-affecting operations touch the version row.

### Anti-Pattern 3: Direct Kafka Publish in the Same @Transactional Method

**What people do:** Publish events to Kafka inside the same Spring transaction that commits the ledger entry.

**Why it's wrong:** If the Kafka publish succeeds but the DB transaction rolls back (or vice versa), you have a dual-write inconsistency — either a phantom event with no backing ledger entry, or a committed entry with no event. Silent failures are common.

**Do this instead:** Write to the outbox table inside the DB transaction. Dispatch from the outbox asynchronously. This guarantees exactly the set of events that corresponds to committed ledger entries.

### Anti-Pattern 4: Spring Component Scan Leaking into the Library

**What people do:** Use @SpringBootApplication or wide @ComponentScan in the autoconfigure module.

**Why it's wrong:** Embedders get all the engine's beans whether they want them or not. Bean name conflicts, unexpected auto-wiring, and startup failures in the host application.

**Do this instead:** Use explicit @Bean definitions in @Configuration classes, guarded by @ConditionalOnMissingBean. Register via AutoConfiguration.imports. Never use @ComponentScan in the starter.

### Anti-Pattern 5: Blocking the Estimation Path with a DB Query

**What people do:** Compute projected balance by summing all ledger entries in Postgres for every GET request during an active session.

**Why it's wrong:** A session with 10 minutes of streaming has many ledger entries only at the edges (start/end), but the projection query reads them all. More importantly, it defeats the purpose of real-time estimation — the query itself takes longer than the streaming tick.

**Do this instead:** Read the committed balance once (or cache it), then apply the mathematical projection using in-memory stream state. The estimation path must be sub-millisecond.

---

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| Payment rail (upstream issuer) | Engine receives issuance commands via REST POST — no dependency on issuer SDK | Issuer calls /accounts/{id}/issue; engine credits balance and records it |
| Platform event consumers | Outbox pattern → Kafka topic or HTTP webhook | Engine publishes domain events; platform reacts (e.g., triggers payment settlement) |
| Monitoring / observability | Micrometer counters on TransactionEngine commit, conflict rate, session count | Expose via /actuator/metrics; no special integration needed |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| engine-core ↔ engine-spring | engine-spring depends on engine-core; core has zero Spring imports | Core is the stable API; spring module adapts it to Spring idioms |
| SessionService ↔ TransactionEngine | Direct Java method call within the same Spring context | SessionService is the orchestrator; TransactionEngine is stateless |
| TransactionEngine ↔ In-Memory StreamRegistry | Direct Java method call; StreamRegistry is a Spring bean | Registry is the only stateful in-process component beyond Postgres |
| EstimationService ↔ StreamRegistry | Read-only access to StreamRegistry | No writes from EstimationService; pure projection logic |
| Outbox Poller ↔ External Broker | Outbox table row → Kafka producer or HTTP client | Decoupled from ledger transaction path; failure here does not block commits |

---

## Build Order Implications

The component dependency graph determines phase sequencing. Each phase must deliver components that later phases depend on.

```
Phase 1 — Domain Model + Ledger Core
    Account, LedgerTransaction, LedgerEntry entities
    Postgres schema (accounts, transactions, entries, outbox)
    TransactionEngine with optimistic locking
    RakeEngine (pure function, no I/O)
    → Required by: everything else

Phase 2 — Account API + Discrete Transactions
    AccountService + AccountController
    TransactionController (single-party, discrete)
    Basic outbox + ApplicationEvent emission
    → Required by: session orchestration (needs accounts to exist)

Phase 3 — Session Lifecycle + Streaming
    SessionService (start/pause/end)
    In-Memory StreamRegistry
    EstimationService + EstimationController
    Multi-party transaction commit (N participants)
    → Required by: estimation is meaningless without active sessions

Phase 4 — Dual Packaging
    engine-core / engine-spring / engine-service module split
    AutoConfiguration setup
    Integration test: embed starter into a test Spring Boot app
    → Required by: library consumers; does not block feature development

Phase 5 — External Event Emission
    Outbox poller (polling or Debezium CDC)
    Kafka integration (optional, platform configures)
    Event schema documentation
    → Can be done last; internal consistency does not depend on it
```

---

## Sources

- Modern Treasury: Designing Ledgers API with Optimistic Locking — https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking (HIGH confidence)
- Martin Richards: Real-Time Ledger with Optimistic Locking — https://www.martinrichards.me/post/ledger_p1_optimistic_locking_real_time_ledger/ (HIGH confidence)
- TigerBeetle System Architecture — https://docs.tigerbeetle.com/coding/system-architecture/ (HIGH confidence)
- microservices.io: Transactional Outbox Pattern — https://microservices.io/patterns/data/transactional-outbox.html (HIGH confidence)
- microservices.io: Saga Pattern — https://microservices.io/patterns/data/saga.html (HIGH confidence)
- Spring Boot Multi-Module Guide — https://spring.io/guides/gs/multi-module/ (HIGH confidence)
- Piotr Minkowski: Guide to Building Spring Boot Library — https://piotrminkowski.com/2020/08/04/guide-to-building-spring-boot-library/ (MEDIUM confidence)
- Event Sourcing and Concurrent Updates (Teiva Harsanyi) — https://teivah.medium.com/event-sourcing-and-concurrent-updates-32354ec26a4c (MEDIUM confidence)
- Optimistic Concurrency for Pessimistic Times (event-driven.io) — https://event-driven.io/en/optimistic_concurrency_for_pessimistic_times/ (MEDIUM confidence)

---
*Architecture research for: real-time token economy engine*
*Researched: 2026-05-13*
