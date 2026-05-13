# Feature Research

**Domain:** Real-time token economy / ledger engine (streaming + discrete, multi-party, rake-extracting)
**Researched:** 2026-05-13
**Confidence:** HIGH for table stakes (well-established fintech conventions); MEDIUM for differentiators (thin ecosystem for this exact combination)

---

## Feature Landscape

### Table Stakes (Integrating Platforms Will Reject It Without These)

Features that any caller embedding or deploying this engine will assume are present. Missing these signals an immature or unsafe engine.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Idempotent transaction submission | Retries are inevitable (network timeout, client crash); duplicates corrupt balances | LOW | Idempotency key (caller-supplied reference) stored and deduplicated at write time; duplicate requests return the original result, not an error |
| Immutable audit log | Every credit/debit must be permanently recorded with timestamp and actor; required for dispute resolution and compliance | MEDIUM | Append-only journal; corrections via reversal entries, never mutations. Must be queryable by account, time range, and reference |
| Atomic balance updates | Balance drift caused by partial writes is catastrophic; callers assume all-or-nothing semantics | MEDIUM | Database transaction wraps all balance updates in a session; partial success must not be observable |
| Balance inquiry API | Callers need to read current balance at any time without side effects | LOW | `GET /accounts/{id}/balance` returning available, held, and settled sub-totals |
| Transaction history with pagination | Operators need full audit history; history can be arbitrarily long | LOW | Cursor-based pagination preferred over offset (stable under concurrent inserts); filter by time range, status, type |
| Overdraft / balance floor enforcement | Balances going negative corrupt the economy; platforms expect the engine to enforce floors | MEDIUM | Configurable per-account minimum balance (default zero); reject transactions that would breach the floor |
| Transaction status lifecycle | Callers need to distinguish pending, settled, and voided/reversed states | LOW | Statuses: PENDING → POSTED; or PENDING → VOIDED. Immutable once POSTED |
| Account creation and management | The engine must manage the accounts it operates on | LOW | Create, read accounts; account carries metadata supplied by caller; no delete (soft-only) |
| Token issuance (credit) | Platforms issue tokens when real money is collected externally; engine must accept top-up commands | LOW | Credit endpoint adds to balance; paired with idempotency key tied to the external payment reference |
| Token redemption event emission | When a participant's balance is drawn, the platform must receive a signal to trigger real money movement | LOW | Engine emits a redemption event (webhook or poll endpoint); does NOT move money itself |
| Error responses with machine-readable codes | Callers must be able to programmatically handle balance-insufficient vs. invalid-parameter vs. conflict | LOW | Structured error body: `{ "code": "BALANCE_INSUFFICIENT", "detail": "..." }` |
| Health endpoint | Deployment infrastructure (Kubernetes liveness/readiness, load balancers) requires `/health` | LOW | Returns 200 with dependency status (DB reachable, in-memory state initialized) |

---

### Differentiators (What Makes This Engine Worth Building vs. Using an Existing Library)

Features that no off-the-shelf ledger or billing engine provides, or provides only partially. These are the engine's reason to exist.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Rate-based streaming transactions | Continuous per-second/per-minute balance drain without discrete event per tick — no existing general-purpose ledger supports this | HIGH | In-memory state tracks rate + start time; balance is derived at read time as `balance - (rate × elapsed)`; Postgres only sees stream-start and stream-end events |
| Concurrent streaming + discrete on a single balance | A viewer can be in a streaming session AND send a tip simultaneously; both must drain the same balance without corruption | HIGH | Requires lock-free or carefully ordered in-memory coordination; naive locking causes either deadlock or serialization bottleneck |
| Real-time forward balance estimation | "What will my balance be in 30 seconds given all active streams?" — backward-looking ledgers cannot answer this | MEDIUM | Computed on demand: `settled_balance - sum(active_stream_rate × elapsed_per_stream) - sum(pending_discretes)` |
| Multi-party session transactions | N viewers simultaneously pay, flows go to M recipients, all sharing one atomic session lifecycle | HIGH | Session object owns all participant flows; session start/pause/end coordinates all flows atomically |
| Configurable rake extraction | Platform commission extracted atomically on every transfer, keyed off caller-supplied metadata | MEDIUM | Rake rule: `{from, to, platform, percentage_or_fixed, trigger_metadata_key}`; applied at post time; not a separate step |
| Atomic three-way split (from → to → platform) | Single transaction simultaneously debits one account and credits two (recipient + platform) — standard ledgers are bilateral | MEDIUM | Double-entry extended to N-entry; all legs post or none post |
| Session lifecycle coordination | Start/pause/resume/end of a session must coordinate with all in-flight streams atomically — no partial teardown observable | HIGH | Session state machine: ACTIVE → PAUSED → ACTIVE → ENDED; transitions flush and commit in-memory state to DB atomically |
| Open metadata pass-through | Caller supplies arbitrary key-value pairs that flow through to every event, audit record, and webhook payload | LOW | Metadata stored as JSONB on transaction; no engine-defined schema constraints |
| Dual deployment artifact (service + library) | Platform can choose: deploy as a sidecar/microservice, or embed as a Spring Boot autoconfigure JAR | MEDIUM | Two build targets sharing the same core; service wraps library in a web layer |
| Payment-rail decoupling | Engine never holds a reference to a payment processor; accepts issuance commands, emits redemption events | LOW | Enforced at design time (no payment SDK dependency in the JAR); makes the engine portable across payment providers |

---

### Anti-Features (Deliberately NOT Built)

| Feature | Why Requested | Why It Is Wrong for This Engine | What to Do Instead |
|---------|---------------|--------------------------------|-------------------|
| Payment processing (charging cards, moving money) | Seems natural since tokens represent money | Couples the engine to a specific payment provider; destroys portability; creates regulatory surface the engine does not need | Engine emits redemption events; calling platform handles money movement using its own payment rails |
| Invoice / subscription billing cycles | Billing engines often include this | This is not a billing system; it is an accounting engine. Invoice lifecycle is a platform concern | Platform generates invoices from the engine's redemption event stream |
| Authentication and authorization | Every HTTP service needs AuthN/AuthZ | The engine trusts caller-supplied participant IDs; adding its own auth layer duplicates the surrounding platform's auth and creates a configuration burden | Platform's API gateway enforces auth before forwarding calls to the engine |
| WebSocket / push streaming of balance state | Realtime UX benefit | Significantly increases infrastructure complexity; estimation is accurate enough via polling in v1 | Serve estimation via `GET /accounts/{id}/estimate` on demand; platform polls at whatever rate UX requires |
| Blockchain / distributed ledger mechanics | Token economy sounds like crypto | This is an internal accounting engine; blockchain adds latency, complexity, and operational burden with no benefit for a trusted internal system | Postgres with append-only writes and immutable audit log achieves equivalent guarantees without blockchain overhead |
| Multi-currency and FX conversion | General ledger products support multi-currency | Tokens are a single dimensionless unit within the platform's economy; FX is the platform's responsibility when converting real money to tokens | Issuance commands carry the token quantity already computed by the platform |
| Chargebacks and dispute workflow UI | Fintech products include dispute UIs | UI is the platform's concern; the engine provides the data (immutable audit log, reversal entry API); building a UI adds frontend complexity that belongs in the platform | Provide `/transactions/{id}/reverse` (creates a reversal entry); platform builds dispute UI on top |
| Scheduled / recurring transactions | Billing systems do this | Scheduling belongs in the platform's job layer; the engine executes commands, it does not originate them | Platform schedules issuance commands and sends them to the engine at the right time |
| General-purpose double-entry accounting (chart of accounts) | Ledger = accounting | This engine has a fixed account topology (participant balances + platform account); a full chart-of-accounts system would require configuration complexity that the use case does not justify | Keep account model simple: balance per participant, one platform account per tenant; do not expose debit/credit journal API to callers |

---

## Feature Dependencies

```
Token Issuance (credit)
    └──requires──> Account Creation

Discrete Transaction (tip, purchase)
    └──requires──> Account Creation
    └──requires──> Balance Floor Enforcement
    └──requires──> Idempotent Submission
    └──requires──> Atomic Balance Updates

Streaming Transaction (rate-based drain)
    └──requires──> Account Creation
    └──requires──> Balance Floor Enforcement
    └──requires──> Atomic Balance Updates
    └──requires──> Real-time Forward Balance Estimation (to answer "can session continue?")

Multi-party Session
    └──requires──> Streaming Transaction
    └──requires──> Discrete Transaction
    └──requires──> Session Lifecycle Coordination
    └──requires──> Atomic Three-way Split

Rake Extraction
    └──requires──> Atomic Three-way Split
    └──requires──> Open Metadata Pass-through (rake rules keyed off metadata)

Real-time Forward Balance Estimation
    └──requires──> Streaming Transaction (in-memory rate state must exist)
    └──enhances──> Balance Floor Enforcement (can predict breach before it happens)

Redemption Event Emission
    └──requires──> Streaming Transaction (session end triggers redemption)
    └──requires──> Discrete Transaction (tip settlement triggers redemption)

Immutable Audit Log
    └──enhances──> Transaction History with Pagination
    └──required by──> Dispute Resolution (reversal entry)

Session Lifecycle Coordination
    └──requires──> Streaming Transaction
    └──conflicts──> Stateless REST design (session state must live somewhere — in-memory)
```

### Dependency Notes

- **Streaming Transaction requires Real-time Forward Balance Estimation**: the engine must know the projected balance at the moment a new discrete transaction is requested against an account with an active stream, otherwise it cannot accurately enforce the balance floor.
- **Rake Extraction requires Open Metadata Pass-through**: rake rules reference caller-supplied metadata keys (e.g., `session_type = "private"`); without metadata, rules cannot be conditionally applied.
- **Session Lifecycle Coordination conflicts with pure stateless REST**: the in-memory streaming state is the session's hot state; it is intentionally not reconstructed from Postgres on each request (too slow); this is a design commitment, not a gap.
- **Multi-party Session requires all atomic primitives**: it is the most complex feature and must come last in the build order; all lower-level primitives must be solid first.

---

## MVP Definition

The PROJECT.md explicitly states all six features ship together in v1 because a partial engine is not production-usable for the primary use case. The MVP is therefore the full feature set.

### Launch With (v1)

- [x] Account creation and management — prerequisite for everything
- [x] Token issuance (credit) — nothing works without balance
- [x] Idempotent transaction submission — non-negotiable correctness guarantee
- [x] Atomic balance updates + balance floor enforcement — prevents corruption
- [x] Discrete transactions (tips, one-off transfers) — simplest transaction type
- [x] Immutable audit log + transaction history — table stakes for any ledger
- [x] Rate-based streaming transactions — core differentiator
- [x] Real-time forward balance estimation — required to operate streaming correctly
- [x] Concurrent streaming + discrete on single balance — the correctness problem to solve
- [x] Configurable rake extraction (atomic three-way split) — platform revenue model
- [x] Multi-party session lifecycle coordination — the full use case
- [x] Open metadata pass-through — required for rake rules and operator tracing
- [x] Redemption event emission (webhook or poll endpoint) — how platforms know to move real money
- [x] Balance inquiry API — standard operator tool
- [x] Health endpoint — operational requirement
- [x] Dual artifact (service JAR + autoconfigure library JAR) — both deployment targets

### Add After Validation (v1.x)

- [ ] Balance threshold alerts (webhook when balance drops below configured level) — useful for UX (warn viewer before session force-terminates), but polling estimation suffices at launch
- [ ] Prometheus metrics endpoint (`/actuator/prometheus`) — operational observability; Spring Boot Actuator makes this nearly free to add
- [ ] Structured event log (per-account event feed queryable by external consumers) — useful when multiple downstream services need to react to ledger events independently
- [ ] Per-account rate limiting (maximum transactions per second) — protection against runaway clients; add when abuse patterns emerge

### Future Consideration (v2+)

- [ ] WebSocket push for balance updates — significantly increases infrastructure; defer until polling latency is proven insufficient
- [ ] Batch transaction API (post N transactions atomically) — useful for promotional credit distributions; low urgency
- [ ] Time-travel balance query (balance at arbitrary past timestamp) — powerful for audit; requires event-sourcing reconstruction; high implementation cost

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Account + issuance + balance inquiry | HIGH | LOW | P1 |
| Idempotency + atomic updates + floor enforcement | HIGH | LOW | P1 |
| Discrete transactions | HIGH | LOW | P1 |
| Immutable audit log + history | HIGH | LOW | P1 |
| Health endpoint | HIGH | LOW | P1 |
| Rate-based streaming transactions | HIGH | HIGH | P1 |
| Real-time forward balance estimation | HIGH | MEDIUM | P1 |
| Concurrent streaming + discrete correctness | HIGH | HIGH | P1 |
| Rake extraction (atomic three-way split) | HIGH | MEDIUM | P1 |
| Multi-party session lifecycle | HIGH | HIGH | P1 |
| Open metadata pass-through | HIGH | LOW | P1 |
| Redemption event emission | HIGH | LOW | P1 |
| Dual artifact (service + library) | HIGH | MEDIUM | P1 |
| Balance threshold alerts | MEDIUM | LOW | P2 |
| Prometheus metrics endpoint | MEDIUM | LOW | P2 |
| Structured event log | MEDIUM | MEDIUM | P2 |
| Per-account rate limiting | LOW | MEDIUM | P3 |
| WebSocket push | LOW | HIGH | P3 |
| Time-travel balance query | LOW | HIGH | P3 |

**Priority key:** P1 = v1 launch, P2 = v1.x after validation, P3 = v2+

---

## Competitor / Reference System Feature Analysis

| Feature | Modern Treasury Ledgers | Blnk Finance | Standard Usage-Based Billing (Orb, Ordway) | This Engine |
|---------|------------------------|--------------|---------------------------------------------|-------------|
| Idempotency keys | Yes (lock_version + idempotency) | Yes (reference field) | Yes | Yes — caller-supplied reference |
| Immutable audit log | Yes | Yes | Yes | Yes |
| Balance states (pending/posted) | Yes | Yes (inflight) | Partial | Yes — PENDING / POSTED / VOIDED |
| Multi-currency | Yes | Yes | Yes | No (single token unit by design) |
| Double-entry journal | Yes | Yes | No (event-based) | No — fixed account topology, not a general journal |
| Rate-based streaming drain | No | No | Partial (metered events, not continuous) | Yes — core feature |
| Forward balance estimation | No | No | No | Yes — differentiator |
| Multi-party session (N payers, M recipients, one lifecycle) | No | No | No | Yes — differentiator |
| Atomic rake per transfer | No (post-hoc fee calc) | No | No | Yes — differentiator |
| Concurrent stream + discrete on single balance | No | No | No | Yes — the hard problem |
| Embeddable Spring Boot autoconfigure JAR | No | No | No | Yes — differentiator |
| Payment rail decoupled | Partial (wraps Modern Treasury payments) | Yes | No (billing is the payment) | Yes |
| Webhooks / event emission | Yes | Yes | Yes | Yes (redemption events, threshold alerts v1.x) |

---

## Sources

- Modern Treasury — Ledger API overview and concurrency control design: https://www.moderntreasury.com/learn/ledger-api and https://www.moderntreasury.com/journal/designing-ledgers-with-optimistic-locking
- Blnk Finance — open-source ledger features and idempotency patterns: https://www.blnkfinance.com/ and https://www.blnkfinance.com/blog/how-to-handle-idempotency-in-your-financial-app-using-the-blnk-ledger
- DashDevs — multi-account ledger system architecture: https://dashdevs.com/blog/multi-account-ledger-system-fintech-scale/
- FinLego — real-time ledger feature set: https://finlego.com/ledger
- Rightfoot — balance threshold alert webhook pattern: https://docs.rightfoot.com/api-reference/webhooks/balance-threshold-alert
- Flexprice — credit-based billing for AI/GPU applications: https://flexprice.io/blog/how-to-implement-credit-based-billing-for-ai-applications
- Wikipedia — poker rake mechanics (multi-party commission model): https://en.wikipedia.org/wiki/Rake_(poker)
- ByteByteGo — REST API idempotency and pagination design: https://blog.bytebytego.com/p/the-art-of-rest-api-design-idempotency
- Modern Treasury — transaction status lifecycle: https://www.moderntreasury.com/journal/the-ledger-transaction-status

---

*Feature research for: real-time token economy / ledger engine*
*Researched: 2026-05-13*
