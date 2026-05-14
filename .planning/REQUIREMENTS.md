# Requirements: Real-Time Token Economy Engine

**Defined:** 2026-05-13
**Core Value:** Correct, real-time token accounting across concurrent mixed transaction types with flexible grouping via tags

## v1 Requirements

### Accounts

- [x] **ACCT-01**: Caller can create a participant account with an initial token balance
- [x] **ACCT-02**: Caller can close a participant account (rejected if active streaming transactions exist)
- [x] **ACCT-03**: Caller can retrieve a participant account and its current committed balance

### Foundation

- [x] **FUND-01**: Engine enforces idempotency on all write operations via caller-supplied idempotency key (enforced by DB UNIQUE constraint — not application-layer check)
- [x] **FUND-02**: Engine records an immutable append-only audit log entry for every balance change
- [x] **FUND-03**: Engine exposes health endpoint (Spring Actuator) and metrics (Micrometer / Prometheus)

### Balance

- [x] **BAL-01**: Caller can query a participant's current committed balance
- [ ] **BAL-02**: Caller can query a participant's forward-estimated balance given all currently in-flight streaming transactions, with an `estimated_at` timestamp
- [x] **BAL-03**: Engine enforces a minimum balance floor (default 0); rejects any operation that would take a balance below the floor

### Discrete Transactions

- [ ] **DTX-01**: Caller can post a discrete credit to a participant account
- [ ] **DTX-02**: Caller can post a discrete debit from a participant account
- [ ] **DTX-03**: Engine rejects a discrete debit that would take balance below floor, even when concurrent streaming transactions are in flight against the same account
- [ ] **DTX-04**: Engine processes concurrent discrete transactions against a single balance with full correctness (no double-spend, no lost update) under concurrent load

### Streaming Transactions

- [ ] **STR-01**: Caller can start a streaming drain transaction specifying a rate (tokens per time unit) against a participant account
- [ ] **STR-02**: Caller can stop an individual streaming transaction; engine settles the exact amount using mathematical projection (rate × elapsed via System.nanoTime()), not tick accumulation
- [ ] **STR-03**: Engine holds active streaming state in-memory; forward balance estimation reflects all in-flight streams without a database read per query
- [ ] **STR-04**: Engine processes concurrent streaming and discrete transactions against the same balance with full correctness
- [ ] **STR-05**: Engine settles streaming drain atomically to Postgres on stream stop; in-memory and durable state are never permanently divergent
- [ ] **STR-06**: Engine uses BigDecimal (not floating-point) for all token rate arithmetic
- [ ] **STR-07**: Streaming TX accepts an optional `minimumAmount` parameter; if the stream is stopped by a client-initiated close before `minimumAmount` has been drained, the engine charges `minimumAmount` instead of actual elapsed amount; the stop API accepts an optional `ignoreMinimum` flag (default false) that allows the caller to explicitly waive the minimum on a client-initiated close; error-terminated and auto-terminated streams always charge actual elapsed amount (minimum is always waived)
- [ ] **STR-08**: Streaming TX accepts an optional `increment` parameter (token amount per billing unit); when set, settlement rounds down to the nearest complete increment (`floor(elapsed / incrementDuration) × increment`) rather than exact elapsed; remaining fractional tokens are left on the account
- [ ] **STR-09**: When `increment` is set on a streaming TX, auto-termination triggers when the account's remaining balance falls below one full increment's worth of tokens — the stream stops before a partial increment can begin

### Auto-Termination

- [ ] **AUTO-01**: Engine automatically terminates any streaming transaction when the draining account's estimated balance would reach the configured floor (or, if `increment` is set, when remaining balance drops below one increment); auto-termination is treated as an error close (minimum amount waived)
- [ ] **AUTO-02**: Engine schedules termination checks efficiently: on stream start (or on any balance change affecting an active stream), the estimated exhaustion time is calculated and a precise wake-up is scheduled rather than constant polling; the scheduler uses a priority queue keyed by exhaustion time
- [ ] **AUTO-03**: Auto-termination emits a distinct event identifying the reason (balance exhaustion) so callers can distinguish it from client-initiated stops

### Tags

- [ ] **TAG-01**: Caller can create a streaming transaction with zero or more string tags; tags are immutable after creation
- [ ] **TAG-02**: Caller can end all active streaming transactions matching a given tag in a single atomic operation (all matched streams settle in one DB transaction)
- [ ] **TAG-03**: Engine maintains a committed total per tag in a `tag_committed_totals` Postgres table; the row is updated inside the same DB transaction as each settlement — never as a separate write
- [ ] **TAG-04**: Caller can query the real-time aggregate for a tag: committed total (from Postgres-backed cache) plus in-flight projection (sum of rate × elapsed across all active streams carrying that tag)
- [ ] **TAG-05**: In-memory tag cache entries are evicted by TTL after configurable inactivity period (default 24h); `tag_committed_totals` rows are cleaned up by a background job keyed on `last_activity_at`
- [ ] **TAG-06**: A discrete transaction can also carry tags; its posted amount is included in the tag committed total when settled

### Rake Engine

- [ ] **RAKE-01**: Caller can configure rake rules per transaction type (applies to both discrete and streaming transactions); rake rate is resolved from caller-supplied metadata at transaction post time
- [ ] **RAKE-02**: Engine executes rake-enabled transactions as an atomic three-way operation: debit from-account → credit to-account → credit platform-account, with no intermediate inconsistent state
- [ ] **RAKE-03**: Engine supports zero-rake (spread-only), full-rake, and hybrid configurations
- [ ] **RAKE-04**: Rake arithmetic is balanced: debit to from-account equals sum of credits (enforced by DB check constraint)

### Open Metadata

- [ ] **META-01**: Every transaction (discrete and streaming) accepts a caller-supplied key-value metadata map; metadata is immutable after creation
- [ ] **META-02**: Metadata flows through unchanged to all audit log entries and emitted events associated with that transaction

### External Event Emission

- [ ] **EMIT-01**: Engine emits a domain event for every significant ledger operation via the transactional outbox pattern (account created, transaction posted, stream started/stopped, end-by-tag completed)
- [ ] **EMIT-02**: Outbox events are written inside the same DB transaction as the ledger operation — never outside it
- [ ] **EMIT-03**: Engine provides at least one delivery mechanism for outbox events in v1 (polling endpoint or webhook dispatch)

### Packaging

- [ ] **PKG-01**: `engine-core` module contains all domain logic with zero Spring or web framework dependencies; ships as a standalone JAR
- [ ] **PKG-02**: `engine-spring` module ships as a Spring Boot autoconfigure starter; registers beans via `AutoConfiguration.imports`; uses `@ConditionalOnMissingBean` throughout; all configuration properties namespaced under `token-engine.*`
- [ ] **PKG-03**: `engine-service` module ships as a deployable Spring Boot fat JAR with a complete REST API; embedding the starter into a host application must not cause bean name collisions or property namespace conflicts

## v2 Requirements

### Transactions

- **TX-REV-01**: Caller can post a reversal entry compensating a previously posted discrete transaction
- **TX-HIST-01**: Caller can retrieve cursor-paginated transaction history for a participant account

### Tags

- **TAG-HIST-01**: Historical tag aggregate query (tag total earned over a past time window) served from audit log for evicted or completed tags

### Threshold Events

- **EVT-01**: Caller can register a threshold on a participant account (a token balance target, drain or accumulation direction)
- **EVT-02**: Caller can register a threshold on a tag (a tag aggregate target)
- **EVT-03**: Engine detects threshold crossing exactly once, even under concurrent discrete transactions or simultaneous stream settlements against the same account or tag
- **EVT-04**: Engine emits a threshold event when a crossing is detected; the event carries the account or tag identifier, the threshold value, and the open metadata of the triggering transaction

### Observability

- **OBS-01**: Structured trace context propagated through all engine operations (OpenTelemetry)

### Clients

- **CLT-01**: Typed Java client library for the `engine-service` REST API

## Out of Scope

| Feature | Reason |
|---------|--------|
| Session lifecycle (start/pause/resume/end) | Replaced by tag-based grouping; pause/resume not needed |
| Streaming accumulation | No identified use case; accumulation is always the result of another participant draining |
| Payment processing | Engine issues/redeems tokens only; real money movement is the platform's responsibility |
| Crypto/blockchain mechanics | Internal accounting engine only |
| Billing and invoicing | No invoice lifecycle or subscription billing |
| Authentication / authorization | Engine trusts caller-supplied participant IDs; identity is the platform's concern |
| Client UI components | Engine is an API; frontend belongs to the calling platform |
| Multi-currency | Single token unit per engine instance in v1 |
| Dispute UI | Audit log supports dispute resolution; dispute workflow belongs in the calling platform |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| ACCT-01 | Phase 1 | Complete |
| ACCT-02 | Phase 1 | Complete |
| ACCT-03 | Phase 1 | Complete |
| FUND-01 | Phase 1 | Complete |
| FUND-02 | Phase 1 | Complete |
| FUND-03 | Phase 1 | Complete (01-01) |
| BAL-01 | Phase 1 | Complete |
| BAL-03 | Phase 1 | Complete |
| DTX-01 | Phase 2 | Pending |
| DTX-02 | Phase 2 | Pending |
| DTX-03 | Phase 2 | Pending |
| DTX-04 | Phase 2 | Pending |
| META-01 | Phase 2 | Pending |
| META-02 | Phase 2 | Pending |
| RAKE-01 | Phase 2 | Pending |
| STR-01 | Phase 3 | Pending |
| STR-02 | Phase 3 | Pending |
| STR-03 | Phase 3 | Pending |
| STR-04 | Phase 3 | Pending |
| STR-05 | Phase 3 | Pending |
| STR-06 | Phase 3 | Pending |
| STR-07 | Phase 3 | Pending |
| STR-08 | Phase 3 | Pending |
| STR-09 | Phase 3 | Pending |
| AUTO-01 | Phase 3 | Pending |
| AUTO-02 | Phase 3 | Pending |
| AUTO-03 | Phase 3 | Pending |
| BAL-02 | Phase 3 | Pending |
| TAG-01 | Phase 4 | Pending |
| TAG-02 | Phase 4 | Pending |
| TAG-03 | Phase 4 | Pending |
| TAG-04 | Phase 4 | Pending |
| TAG-05 | Phase 4 | Pending |
| TAG-06 | Phase 4 | Pending |
| RAKE-02 | Phase 4 | Pending |
| RAKE-03 | Phase 4 | Pending |
| RAKE-04 | Phase 4 | Pending |
| EVT-01 | v2 | Deferred |
| EVT-02 | v2 | Deferred |
| EVT-03 | v2 | Deferred |
| EVT-04 | v2 | Deferred |
| EMIT-01 | Phase 5 | Pending |
| EMIT-02 | Phase 5 | Pending |
| EMIT-03 | Phase 5 | Pending |
| PKG-01 | Phase 6 | Pending |
| PKG-02 | Phase 6 | Pending |
| PKG-03 | Phase 6 | Pending |

**Coverage:**
- v1 requirements: 44 total (EVT-01–04 moved to v2 — 2026-05-14)
- Mapped to phases: 44
- Unmapped: 0 ✓

---
*Requirements defined: 2026-05-13*
*Last updated: 2026-05-13 — RAKE-01 remapped from Phase 4 to Phase 2 (rake on discrete belongs with discrete transaction build)*
