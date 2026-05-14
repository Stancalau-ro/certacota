# Roadmap: Real-Time Token Economy Engine

## Overview

Six phases deliver a correct, real-time token accounting engine that handles concurrent streaming and discrete transactions across multi-party sessions. The build sequence is strict: accounts and correctness invariants first, then discrete transactions, then the novel streaming layer, then higher-order features (tags, rake, thresholds), then external event emission, and finally dual-packaging as a standalone service and embeddable starter. No phase is skippable — each is a prerequisite for the next. Correctness under concurrency is the non-negotiable constraint throughout; every phase gate is an integration test suite running against a real Postgres instance via Testcontainers.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Foundation** - Accounts, balance floor, idempotency, audit log, and observability scaffold (completed 2026-05-13)
- [x] **Phase 2: Discrete Transactions** - Credits, debits, floor enforcement, concurrency correctness, open metadata, and rake on discrete transactions (completed 2026-05-13)
- [x] **Phase 3: Streaming Transactions** - Rate-based drain, mathematical projection settlement, in-memory StreamRegistry, forward balance estimation, minimum amount, increment billing, and auto-termination (completed 2026-05-14)
- [ ] **Phase 4: Tags and Rake on Streaming** - Tag grouping, atomic multi-stream settlement, three-way rake splits on streaming transactions, and tag aggregate queries
- [ ] **Phase 5: External Event Emission** - Transactional outbox pattern with at-least-once delivery
- [ ] **Phase 6: Dual Packaging** - Module split into engine-core / engine-spring / engine-service; embedding verification

## Phase Details

### Phase 1: Foundation
**Goal**: Participant accounts exist, balances are readable and floor-enforced, every balance change is permanently audited, all writes are idempotent, and the engine is observable
**Mode:** mvp
**Depends on**: Nothing (first phase)
**Requirements**: ACCT-01, ACCT-02, ACCT-03, FUND-01, FUND-02, FUND-03, BAL-01, BAL-03
**Success Criteria** (what must be TRUE):
  1. Caller can create an account, retrieve it with its committed balance, and close it; closing an account that has active streaming transactions is rejected with an explicit error
  2. A balance-changing operation submitted twice with the same idempotency key returns the same result both times and creates exactly one audit log entry (enforced by DB UNIQUE constraint, not application code)
  3. Every balance change produces an immutable append-only audit log entry visible in Postgres immediately after the operation completes
  4. Any operation that would take a balance below the configured floor (default 0) is rejected before the write occurs
  5. The Spring Actuator health endpoint returns UP and the Micrometer/Prometheus metrics endpoint is reachable; a Testcontainers integration test confirms both against a live Postgres instance
**Plans**: 3 plans
Plans:
- [x] 01-01-PLAN.md — Walking Skeleton: Gradle multi-module scaffold, Flyway DDL migrations, Testcontainers + Cucumber test infrastructure
- [x] 01-02-PLAN.md — Domain layer: engine-core entities/repositories/service interface, engine-spring AccountServiceImpl + autoconfigure wiring
- [x] 01-03-PLAN.md — REST layer + acceptance tests: AccountController, GlobalExceptionHandler, all 5 Cucumber feature files green

### Phase 2: Discrete Transactions
**Goal**: Callers can post credits and debits with metadata and optional rake; the engine rejects floor violations even under concurrent load, produces no double-spends or lost updates, and executes rake-enabled discrete transactions as atomic three-way splits
**Mode:** mvp
**Depends on**: Phase 1
**Requirements**: DTX-01, DTX-02, DTX-03, DTX-04, META-01, META-02, RAKE-01
**Success Criteria** (what must be TRUE):
  1. Caller can post a discrete credit and a discrete debit; both appear in the audit log with the caller-supplied key-value metadata map unchanged
  2. A debit that would take balance below the floor is rejected, including when concurrent streaming transactions are already in flight against the same account
  3. A Testcontainers integration test fires N concurrent discrete debits against a single account; the final committed balance equals the mathematically correct result with no double-spend and no lost update
  4. Metadata supplied at transaction creation flows through unchanged to all audit log entries associated with that transaction; metadata cannot be altered after creation
  5. A rake-enabled discrete transaction executes as an atomic three-way debit/credit/credit; rake rate is resolved from caller-supplied metadata at post time; the debit to the from-account equals the sum of credits; zero-rake, full-rake, and hybrid configurations all produce balanced arithmetic
**Plans**: 3 plans
Plans:
**Wave 1**
- [x] 02-01-PLAN.md — Domain foundation: Flyway V4/V5 migrations, engine-core domain contracts (entity, enum, repositories, DTOs, service interface, findWithLock)

**Wave 2** *(blocked on Wave 1 completion)*
- [x] 02-02-PLAN.md — Service layer: TransactionServiceImpl (credit, debit, rake), TokenEngineProperties RakeProperties, TransactionController, autoconfigure wiring

**Wave 3** *(blocked on Wave 2 completion)*
- [x] 02-03-PLAN.md — Acceptance tests: four Cucumber feature files (credit, debit, metadata, rake) + concurrent debit stress test (DTX-04)

Cross-cutting constraints:
- `PESSIMISTIC_WRITE` (`SELECT FOR UPDATE`) on account row must be acquired BEFORE the balance floor check in every debit path
- Three-row rake model: one DEBIT + two CREDIT rows per rake-enabled transaction, inside a single `@Transactional`
- BigDecimal with `RoundingMode.DOWN` for all token arithmetic; no floating-point in any rake path

### Phase 3: Streaming Transactions
**Goal**: Rate-based streaming drains start, run in-memory, and settle to Postgres using mathematical projection; forward balance estimation is correct across all concurrent in-flight streams; minimum amount and increment billing parameters are enforced on settlement; the engine auto-terminates streams at balance exhaustion using a priority-queue scheduler
**Mode:** mvp
**Depends on**: Phase 2
**Requirements**: STR-01, STR-02, STR-03, STR-04, STR-05, STR-06, STR-07, STR-08, STR-09, AUTO-01, AUTO-02, AUTO-03, BAL-02
**Success Criteria** (what must be TRUE):
  1. Caller can start a streaming drain specifying a rate; the stream is registered in the in-memory StreamRegistry without a Postgres write per active tick
  2. Caller can stop a stream; the engine settles the exact amount using rate x elapsed time via System.nanoTime() mathematical projection (not tick accumulation), commits atomically to Postgres, and removes the entry from StreamRegistry
  3. Forward balance estimation returns the committed balance minus the sum of (rate x elapsed) across all in-flight streams for that account, with an `estimated_at` timestamp; no database read is required per estimation query
  4. A Testcontainers integration test fires concurrent streaming and discrete transactions against the same account simultaneously; the final settled balance in Postgres is correct with no double-spend, no lost update, and no permanent divergence between in-memory and durable state
  5. All token rate arithmetic uses BigDecimal throughout; floating-point types are absent from rate calculation code paths
  6. When a stream with `minimumAmount` is stopped by a client-initiated close before `minimumAmount` has been drained, the engine settles `minimumAmount` by default; the stop API accepts `ignoreMinimum=true` to waive the minimum on a client-initiated close and settle actual elapsed instead; error-terminated and auto-terminated streams always settle actual elapsed (minimum always waived)
  7. When a stream with `increment` is stopped, the engine settles `floor(elapsed / incrementDuration) x increment` rather than exact elapsed; remaining fractional tokens stay on the account; auto-termination fires when the account's remaining balance falls below one full increment before a new increment period can begin
  8. The auto-termination scheduler maintains a priority queue keyed by estimated exhaustion time; on stream start or any balance change affecting an active stream, the estimated exhaustion time is recalculated and the scheduler reschedules rather than polling at a fixed interval; auto-termination commits a settlement and emits a distinct event with `reason=balance_exhaustion` distinguishable from client-initiated stops
**Plans**: 4 plans
Plans:
**Wave 1**
- [x] 03-01-PLAN.md — Infrastructure + engine-core contracts + test scaffolding: Flyway V7/V8/V9 DDL migrations, Redis/Redisson/ShedLock dependencies, StreamingTransaction entity, StreamState record, StreamRegistry interface, StreamingService interface, all DTOs and exceptions, TestcontainersConfiguration Redis, 6 Cucumber feature files, StreamingSteps pending stubs

**Wave 2** *(blocked on Wave 1 completion)*
- [x] 03-02-PLAN.md — Core streaming slice: RedisStreamRegistry, StreamingServiceImpl (start/stop/estimateBalance/startup reconciliation), TokenEngineProperties extensions, StreamingAutoConfiguration, StreamController, EstimationController, GlobalExceptionHandler additions

**Wave 3** *(blocked on Wave 2 completion)*
- [x] 03-03-PLAN.md — Auto-termination scheduler (Redisson primary + ShedLock-guarded Postgres fallback), OPS-02 archival job + idempotency TTL sweep, TransactionServiceImpl estimated-floor modification, AccountServiceImpl active-stream close check

**Wave 4** *(blocked on Wave 3 completion)*
- [x] 03-04-PLAN.md — All acceptance tests: StreamingSteps step implementations (6 feature files), StreamingConcurrencyTest (STR-04), ArithmeticTest (STR-06)

Cross-cutting constraints:
- `PESSIMISTIC_WRITE` (`SELECT FOR UPDATE`) on account row must be acquired BEFORE StreamRegistry read BEFORE balance check in every streaming write path
- Settlement race (D-19): clamp settled amount to min(projected, availableBalance - floor); never throw floor violation at settlement time
- BigDecimal with `RoundingMode.DOWN` for all rate arithmetic; nanoTime for same-JVM precision, wall-clock millis for cross-pod/post-restart elapsed
- Redis failure: 503 on stream start/stop/estimation and discrete debit with active streams; discrete credits always allowed

### Phase 4: Tags and Rake on Streaming
**Goal**: Streaming and discrete transactions carry tags that aggregate in real time; rake splits streaming transfers three ways atomically
**Mode:** mvp
**Depends on**: Phase 3
**Requirements**: TAG-01, TAG-02, TAG-03, TAG-04, TAG-05, TAG-06, RAKE-02, RAKE-03, RAKE-04
**Success Criteria** (what must be TRUE):
  1. A streaming transaction created with tags can be stopped individually or as part of a bulk end-by-tag operation; all matched streams settle in a single DB transaction; the `tag_committed_totals` row is updated inside the same DB transaction as each settlement
  2. A tag aggregate query returns committed total (Postgres-backed) plus in-flight projection (sum of rate x elapsed for all active streams carrying that tag) without a full table scan; a discrete transaction carrying a tag contributes its posted amount to the tag committed total; the response separates totalDebited, totalCreditedRecipient, and derived totalRaked on both the committed and in-flight sides
  3. A rake-enabled streaming transaction executes as an atomic three-way debit/credit/credit on settlement; the debit to the from-account equals the sum of credits (enforced by DB check constraint); zero-rake, full-rake, and hybrid configurations all produce balanced arithmetic
  4. Tag cache entries are evicted by TTL after the configured inactivity period; a background job cleans up `tag_committed_totals` rows keyed on `last_activity_at`
**Plans**: 4 plans
Plans:

**Wave 0**
- [x] 04-01-PLAN.md — Wave 0: metadata portability retrofit (V10, MetadataConverter, DiscreteTransaction/StreamingTransaction entity changes) + Phase 4 Cucumber feature files + TagSteps skeleton

**Wave 1** *(blocked on Wave 0)*
- [ ] 04-02-PLAN.md — Tags vertical slice: V11 schema, TagCommittedTotals entity/repo, tags fields on StartStreamRequest/CreditRequest/DebitRequest/PostTransferRequest, RedisStreamRegistry tag-set extension, StreamingServiceImpl + TransactionServiceImpl tag wiring, TagServiceImpl.aggregate, TagController, TagAutoConfiguration

**Wave 2** *(blocked on Wave 1)*
- [ ] 04-03-PLAN.md — Streaming rake settlement vertical slice: V12 schema with check constraint, StreamingServiceImpl.stopStream three-way debit/credit/credit, lock ordering from→to→platform→tags(alphabetical)

**Wave 3** *(blocked on Wave 2)*
- [ ] 04-04-PLAN.md — End-by-tag bulk settlement + TagTtlCleanupJob: TagServiceImpl.endByTag full implementation, TokenEngineProperties.TagProperties, ShedLock-guarded scheduled cleanup
**UI hint**: no

### Phase 5: External Event Emission
**Goal**: Every significant ledger operation produces a domain event written inside the same DB transaction via the transactional outbox pattern, with at least one delivery mechanism available
**Mode:** mvp
**Depends on**: Phase 4
**Requirements**: EMIT-01, EMIT-02, EMIT-03
**Success Criteria** (what must be TRUE):
  1. Account creation, transaction posting, stream start/stop, and end-by-tag completion each produce an outbox row written inside the same DB transaction as the ledger operation — never in a separate write
  2. A Testcontainers integration test confirms that simulating a crash between outbox write and delivery leaves the outbox row durable and re-deliverable; no event is lost and no duplicate ledger write occurs
  3. At least one delivery mechanism (polling endpoint or webhook dispatch) is operational and delivers outbox events to a caller-controlled consumer in the integration test
**Plans**: TBD

### Phase 6: Dual Packaging
**Goal**: The engine ships as three modules — engine-core (no framework dependencies), engine-spring (autoconfigure starter), and engine-service (deployable fat JAR) — and embedding the starter into a host application causes no bean collisions or property conflicts
**Mode:** mvp
**Depends on**: Phase 5
**Requirements**: PKG-01, PKG-02, PKG-03
**Success Criteria** (what must be TRUE):
  1. `engine-core` compiles and its tests pass with zero Spring or web framework dependencies on the classpath; all domain logic lives here
  2. `engine-spring` registers all beans via `AutoConfiguration.imports`, uses `@ConditionalOnMissingBean` on every bean (including repository adapters — see PKG-EXT-01 in STATE.md deferred items), and namespaces all configuration properties under `token-engine.*`
  3. A Testcontainers integration test embeds `engine-spring` as a starter inside a synthetic host Spring Boot application; the combined context starts without bean name collisions, property namespace conflicts, or duplicate auto-configuration; all engine endpoints respond correctly inside the host
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5 -> 6

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation | 3/3 | Complete   | 2026-05-13 |
| 2. Discrete Transactions | 3/3 | Complete   | 2026-05-13 |
| 3. Streaming Transactions | 4/4 | Complete   | 2026-05-14 |
| 4. Tags and Rake on Streaming | 1/4 | In progress | - |
| 5. External Event Emission | 0/TBD | Not started | - |
| 6. Dual Packaging | 0/TBD | Not started | - |
