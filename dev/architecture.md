# Real-Time Token Economy Engine — Architecture

## Overview

Horizontally scalable token economy engine built on Spring Boot 3.x, Redis, and a pluggable relational database. Handles concurrent mixed transaction types (streaming + discrete) against shared balances, multi-party sessions, real-time forward estimation, configurable rake, and event emission — without global locks or polling.

---

## Open-Core Model

The project follows an **open-core** model: the OSS edition is a fully production-capable engine, not a stripped-down preview. The enterprise edition adds pluggable infrastructure backends (additional databases, messaging providers) and operability features (multi-tenancy, advanced observability) without changing the core domain logic.

### OSS Edition (Apache 2.0)
- Full token engine: balances, streaming transactions, discrete transactions, sessions, rake, thresholds, estimation
- PostgreSQL persistence
- Redis real-time layer
- RabbitMQ messaging adapter
- REST API with no authentication or authorization — the OSS engine trusts all callers unconditionally. Auth is the responsibility of whatever sits in front of it (API gateway, reverse proxy, etc.).
- Webhook event delivery

### Enterprise Edition (commercial license, built on top of OSS)
- Additional persistence adapters: MySQL, Oracle, MSSQL
- Additional messaging adapters: Kafka, AWS SQS, Azure Service Bus, Google Pub/Sub
- **Authentication and authorization** — API key management, JWT validation, RBAC per operation and per account. Implemented as an `AuthorizationPort` SPI with a no-op default in OSS.
- **Spending caps and rate limiting** — configurable hard caps (max spend per account per window: daily, weekly, session-scoped) and soft caps (emit event, allow transaction). Implemented as a `SpendingCapPort` SPI with a no-op default in OSS. Cap enforcement runs as a pre-check before any balance mutation reaches Redis.
- Multi-tenancy with tenant isolation
- Advanced observability hooks (Micrometer exporters, distributed tracing integration)
- Admin console
- HA configuration support and deployment guides

### Design Principle

Enterprise features are **separate modules**, not feature flags inside the OSS code. The OSS core defines SPIs (interfaces) with no-op defaults; enterprise modules provide enforcing implementations registered as Spring Boot auto-configurations. An enterprise deployment includes the OSS core plus the relevant enterprise JARs on the classpath — no forking, no branching.

---

## Precision Model

All token amounts are `BigDecimal` in Java with a fixed scale of **8 decimal places** (`TOKEN_SCALE = 8`), enforced as a system-wide constant. Redis stores amounts as scaled integers (`amount × 10^8`, as a string), enabling native `INCRBY`/`DECRBY` in Lua with no floating-point involvement. PostgreSQL stores amounts as `NUMERIC(30, 8)`. No `double`, `float`, or `long` is used for token arithmetic anywhere in the codebase — only `BigDecimal` at the service layer and scaled integers at the Redis/Lua boundary.

```java
// TokenAmount.java — central conversion utility
public final class TokenAmount {
    public static final int SCALE = 8;
    private static final BigDecimal SCALE_FACTOR = BigDecimal.TEN.pow(SCALE);

    public static long toRedisUnits(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_DOWN)
                     .multiply(SCALE_FACTOR)
                     .longValueExact();  // throws if overflow — balances are bounded
    }

    public static BigDecimal fromRedisUnits(long units) {
        return BigDecimal.valueOf(units).divide(SCALE_FACTOR).setScale(SCALE);
    }

    public static BigDecimal fromRedisUnits(String units) {
        return fromRedisUnits(Long.parseLong(units));
    }
}
```

Rake splits use `HALF_DOWN` rounding. All intermediate calculations in `RakeEngine` and `EstimationService` use `BigDecimal` arithmetic; conversion to Redis units happens only at the point of writing to Redis.

---

## 1. Component Map

### Maven Module Layout

```
token-engine/                              (parent POM, dependency management)
├── token-engine-core/                     OSS — domain logic + SPIs (no infrastructure)
├── token-engine-api/                      OSS — REST controllers, DTOs, OpenAPI spec
├── token-engine-redis/                    OSS — Redis real-time layer (Lua scripts, balance ops)
├── token-engine-persistence-postgres/     OSS — PostgreSQL implementation of PersistencePort
├── token-engine-messaging-rabbitmq/       OSS — RabbitMQ implementation of MessagingPort
├── token-engine-app/                      OSS — Spring Boot assembly (OSS defaults)
├── token-engine-js/                       OSS — companion JS projection library (client-side display)
│
├── token-engine-persistence-mysql/        Enterprise — MySQL implementation of PersistencePort
├── token-engine-persistence-oracle/       Enterprise — Oracle implementation of PersistencePort
├── token-engine-messaging-kafka/          Enterprise — Kafka implementation of MessagingPort
├── token-engine-messaging-sqs/            Enterprise — AWS SQS implementation of MessagingPort
├── token-engine-messaging-azure/          Enterprise — Azure Service Bus implementation
├── token-engine-security/                 Enterprise — AuthorizationPort impl (API keys, JWT, RBAC)
├── token-engine-spending-caps/            Enterprise — SpendingCapPort impl (hard/soft caps, windowed limits)
└── token-engine-enterprise-app/           Enterprise — Spring Boot assembly (enterprise defaults)
```

`token-engine-core` depends on nothing except the JDK and Spring context. All infrastructure modules depend on `core` and implement its SPIs. `token-engine-app` and `token-engine-enterprise-app` are thin assembly modules that pull in the relevant implementations via Spring Boot auto-configuration.

### SPIs Defined in `token-engine-core`

```java
// Persistence SPI — implemented per database
public interface PersistencePort {
    // writes
    void saveAccount(Account account);
    void saveSession(Session session);
    void saveStreamingTransaction(StreamingTransaction tx);
    void saveDiscreteTransaction(DiscreteTransaction tx);
    void appendLedgerEntries(List<LedgerEntry> entries);
    void reconcileBalance(UUID accountId, BigDecimal balance, long expectedVersion);
    void saveOutboxEvent(OutboxEvent event);
    void markPublished(List<UUID> eventIds);
    void markDeadLettered(UUID eventId);

    // reads (used by recovery and outbox polling)
    List<OutboxEvent> findUnpublishedEvents(int limit);
    List<Session> findSessionsByStatus(String status);          // recovery: find ACTIVE/PAUSED sessions after Redis loss
    List<StreamingTransaction> findActiveStreamsBySession(UUID sessionId);  // recovery: rebuild stream rows
    Optional<StreamingTransaction> findStreamingTransaction(UUID streamId); // recovery: check if row exists before inserting
    SettledBalance findSettledBalance(UUID accountId);          // recovery: seed Redis from DB floor
    List<Session> findTimedOutSessions();                       // timeout job: WHERE timeout_at <= now AND status IN (ACTIVE, PAUSED)
}

// Messaging SPI — implemented per broker
public interface MessagingPort {
    String adapterType();
    void publish(String destination, DomainEvent event);
    boolean supports(String eventType);  // for selective routing
}

// Lock SPI — Redis by default; ZooKeeper or DB-level lock in enterprise HA
public interface DistributedLockPort {
    boolean tryAcquire(String key, String token, Duration ttl);
    boolean release(String key, String token);
    boolean extend(String key, String token, Duration ttl);
}

// Authorization SPI — no-op in OSS (all callers trusted); enforced in enterprise
public interface AuthorizationPort {
    // Throws AuthorizationException if the caller is not permitted.
    void authorize(CallerContext caller, String operation, UUID accountId);
}

// Spending cap SPI — no-op in OSS; enforced in enterprise
public interface SpendingCapPort {
    // Returns ALLOWED, or throws SpendingCapExceededException (hard cap).
    // Implementations may also emit an event for soft caps without throwing.
    void checkBeforeDebit(UUID accountId, BigDecimal amount, Map<String, Object> metadata);
    void recordDebit(UUID accountId, BigDecimal amount, Map<String, Object> metadata);
}
```

All domain services (`BalanceService`, `SessionService`, `StreamingTransactionService`, etc.) are in `core` and depend only on these interfaces. They have no `import` of any database driver, Kafka client, or RabbitMQ class.

### Schema Portability

The persistence modules own their own Liquibase changelogs. Changesets that require dialect-specific SQL use Liquibase's `dbms` precondition:

```xml
<changeSet id="001-create-accounts" author="token-engine">
    <preConditions onFail="MARK_RAN">
        <dbms type="postgresql"/>
    </preConditions>
    <!-- PostgreSQL-specific: JSONB, gen_random_uuid() -->
</changeSet>
<changeSet id="001-create-accounts-mysql" author="token-engine">
    <preConditions onFail="MARK_RAN">
        <dbms type="mysql"/>
    </preConditions>
    <!-- MySQL-specific: JSON, UUID() -->
</changeSet>
```

Portable SQL types used where possible: `NUMERIC(30, 8)` (ANSI standard), `VARCHAR`, `TIMESTAMP`. Dialect-specific types (`JSONB`, `TIMESTAMPTZ`) are isolated to their respective changeset variants. UUID generation happens in Java — no reliance on `gen_random_uuid()` or equivalent DB functions.

### Package Structure Inside `token-engine-core`

```
com.tokenengine.core/
├── account/
│   ├── AccountService.java
│   └── Account.java                         (domain model)
├── session/
│   ├── SessionService.java
│   └── Session.java
├── transaction/
│   ├── DiscreteTransactionService.java
│   ├── StreamingTransactionService.java
│   └── StreamingTransaction.java
├── ledger/
│   └── LedgerService.java
├── balance/
│   ├── BalanceService.java
│   └── BalanceSyncJob.java
├── estimation/
│   └── EstimationService.java
├── rake/
│   └── RakeEngine.java
├── threshold/
│   └── ThresholdEvaluationService.java
├── event/
│   ├── DomainEventPublisher.java
│   └── OutboxPollingJob.java
├── session/
│   └── SessionTimeoutJob.java               (polls sessions WHERE timeout_at <= now AND status != ENDED)
├── precision/
│   └── TokenAmount.java
└── spi/
    ├── PersistencePort.java                  (SPI)
    ├── MessagingPort.java                    (SPI)
    ├── DistributedLockPort.java              (SPI)
    ├── AuthorizationPort.java                (SPI — no-op default in OSS)
    └── SpendingCapPort.java                  (SPI — no-op default in OSS)
```

### Service Responsibilities

| Service | Module | Responsibility |
|---|---|---|
| `AccountService` | core | Account creation, metadata management, issuance commands. No balance arithmetic. |
| `BalanceService` | core | Delegates balance mutations to `token-engine-redis`. Reads via Redis; sync to DB via `PersistencePort`. |
| `DiscreteTransactionService` | core | One-off transfers. Calls Redis layer for atomic debit/credit; calls `PersistencePort` for ledger. |
| `StreamingTransactionService` | core | Start/stop/pause streams. Manages rate state in `token-engine-redis`. Triggers settlement on stop. |
| `SessionService` | core | Session lifecycle. Atomically stops all child streams on end. Sets `ended_reason`. |
| `LedgerService` | core | Builds `LedgerEntry` records and delegates to `PersistencePort`. Never reads for correctness. |
| `RakeEngine` | core | Computes two-way split from caller-supplied `rake_bps` (default 0). Pure BigDecimal math, no I/O. |
| `EstimationService` | core | Forward projection from Redis stream state. No persistence involvement. |
| `ThresholdEvaluationService` | core | Exactly-once threshold detection via Redis SET NX. |
| `DomainEventPublisher` | core | Writes outbox event via `PersistencePort` within the same transaction; dispatches via `MessagingPort`. |
| `OutboxPollingJob` | core | Polls `PersistencePort` for unpublished events; delivers via `MessagingPort`. |
| `SessionTimeoutJob` | core | Polls every 10s for sessions where `timeout_at <= now`. Ends them via `SessionService` with reason `SESSION_TIMEOUT`. |
| `RedisBalanceOps` | token-engine-redis | Lua script execution, `INCRBY`/`DECRBY`, stream hash management. Implements `DistributedLockPort`. |
| `PostgresPersistenceAdapter` | token-engine-persistence-postgres | Implements `PersistencePort` with JDBC against PostgreSQL. |
| `RabbitMqMessagingAdapter` | token-engine-messaging-rabbitmq | Implements `MessagingPort` via Spring AMQP. OSS default. |
| `AuthorizationPort` (no-op) | token-engine-core | Default implementation allows all operations. Enterprise replaces with API key / JWT / RBAC enforcer. |
| `SpendingCapPort` (no-op) | token-engine-core | Default implementation allows all debits. Enterprise replaces with cap-checking implementation. Called before every balance debit; `recordDebit` called after commit. |

---

## 2. Data Model

### Relational Schema

Shown using ANSI-portable SQL types. Dialect-specific variants (e.g. `JSONB` for PostgreSQL, `JSON` for MySQL, `CLOB` for Oracle) are handled by Liquibase changesets in each persistence module. UUIDs are generated in Java — no database function dependency.

**`accounts`**
```sql
CREATE TABLE accounts (
    id           UUID PRIMARY KEY,          -- generated in Java
    external_ref VARCHAR(255) UNIQUE NOT NULL,
    display_name VARCHAR(255),
    metadata     TEXT NOT NULL DEFAULT '{}', -- JSONB (Postgres) / JSON (MySQL) / CLOB (Oracle) in dialect changeset
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);
```

**`account_balances`** — settled snapshot, written after stream settlement or periodic sync
```sql
CREATE TABLE account_balances (
    account_id      UUID PRIMARY KEY REFERENCES accounts(id),
    settled_balance NUMERIC(30, 8) NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,  -- optimistic locking for reconciliation
    settled_at      TIMESTAMP NOT NULL
);
```

**`sessions`**
```sql
CREATE TABLE sessions (
    id           UUID PRIMARY KEY,
    status       VARCHAR(32) NOT NULL,  -- ACTIVE, PAUSED, ENDING, ENDED
    metadata     TEXT NOT NULL DEFAULT '{}',
    started_at   TIMESTAMP NOT NULL,
    ended_at        TIMESTAMP,
    ended_by        UUID REFERENCES accounts(id),
    ended_reason    VARCHAR(64),       -- CALLER_REQUESTED, BALANCE_EXHAUSTED, THRESHOLD_TRIGGERED, SESSION_TIMEOUT, INSTANCE_RECOVERY, ADMINISTRATIVE
    timeout_at      TIMESTAMP          -- nullable; set at creation if caller supplies a max duration; polled by SessionTimeoutJob
);
```

**`session_participants`**
```sql
CREATE TABLE session_participants (
    session_id UUID REFERENCES sessions(id),
    account_id UUID REFERENCES accounts(id),
    role       VARCHAR(64) NOT NULL,  -- caller-supplied, no schema imposed
    PRIMARY KEY (session_id, account_id)
);
```

**`streaming_transactions`**
```sql
CREATE TABLE streaming_transactions (
    id              UUID PRIMARY KEY,
    session_id      UUID REFERENCES sessions(id),
    from_account    UUID REFERENCES accounts(id),
    to_account      UUID REFERENCES accounts(id),
    rake_account    UUID REFERENCES accounts(id),  -- destination for rake ledger entry at settlement; nullable (rake_bps=0)
    rate_per_second NUMERIC(30, 8) NOT NULL,       -- immutable after start
    rake_bps        INT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL,  -- ACTIVE, PAUSED, SETTLING, SETTLED
    started_at      TIMESTAMP NOT NULL,
    paused_at       TIMESTAMP,
    ended_at        TIMESTAMP,
    settled_at      TIMESTAMP,
    metadata        TEXT NOT NULL DEFAULT '{}'
);
```

**`discrete_transactions`**
```sql
CREATE TABLE discrete_transactions (
    id           UUID PRIMARY KEY,
    session_id   UUID REFERENCES sessions(id),  -- nullable
    from_account UUID REFERENCES accounts(id),
    to_account   UUID REFERENCES accounts(id),
    rake_account UUID REFERENCES accounts(id),  -- destination for rake ledger entry; nullable (rake_bps=0)
    amount       NUMERIC(30, 8) NOT NULL,
    rake_bps     INT NOT NULL DEFAULT 0,
    executed_at  TIMESTAMP NOT NULL,
    metadata     TEXT NOT NULL DEFAULT '{}'
);
```

**`ledger_entries`** — immutable double-entry ledger
```sql
CREATE TABLE ledger_entries (
    id               UUID PRIMARY KEY,
    transaction_id   UUID NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,    -- DISCRETE, STREAMING
    account_id       UUID REFERENCES accounts(id),
    direction        VARCHAR(8) NOT NULL,     -- DEBIT or CREDIT
    amount           NUMERIC(30, 8) NOT NULL,
    balance_after    NUMERIC(30, 8) NOT NULL, -- denormalized for audit
    recorded_at      TIMESTAMP NOT NULL
);
CREATE INDEX ledger_entries_account_time ON ledger_entries(account_id, recorded_at DESC);
```

**`outbox_events`**
```sql
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    event_type    VARCHAR(128) NOT NULL,
    aggregate_id  UUID NOT NULL,
    payload       TEXT NOT NULL,           -- JSON string
    published     BOOLEAN NOT NULL DEFAULT false,
    dead_lettered BOOLEAN NOT NULL DEFAULT false,  -- skipped after N consecutive failures
    created_at    TIMESTAMP NOT NULL
);
-- partial index on published=false is PostgreSQL-specific; MySQL/Oracle use a standard index
CREATE INDEX outbox_events_unpublished ON outbox_events(published, created_at);
```

**`threshold_configs`**
```sql
CREATE TABLE threshold_configs (
    id             UUID PRIMARY KEY,
    account_id     UUID REFERENCES accounts(id),
    session_id     UUID REFERENCES sessions(id),  -- nullable; scopes threshold to session
    threshold_type VARCHAR(32) NOT NULL,           -- BALANCE_BELOW, BALANCE_ABOVE, ACCUMULATED
    amount         NUMERIC(30, 8) NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT true
);
```

**`messaging_queue_configs`**
```sql
CREATE TABLE messaging_queue_configs (
    id          UUID PRIMARY KEY,
    adapter     VARCHAR(32) NOT NULL,    -- RABBITMQ (OSS) | KAFKA, SQS, AZURE (Enterprise)
    destination VARCHAR(512) NOT NULL,   -- topic, queue URL, exchange/routing-key
    event_types TEXT NOT NULL DEFAULT '', -- comma-separated; empty = all event types
    properties  TEXT NOT NULL DEFAULT '{}', -- JSON: adapter-specific config (credentials by reference, not value)
    active      BOOLEAN NOT NULL DEFAULT true
);
```

---

### Redis Data Structures

| Key pattern | Type | Contents |
|---|---|---|
| `balance:{accountId}` | String (integer) | Live balance as scaled integer (`BigDecimal × 10^8`). Mutated only via Lua scripts. No TTL. |
| `streams:active:{accountId}` | Set | Stream IDs currently affecting this account's balance (drain or accumulate). |
| `stream:{sessionId}.{streamId}` | Hash | `rate_per_second_units`, `from_account`, `to_account`, `rake_bps`, `started_at_epoch`, `accrued_before_units`, `status`, `owner_instance` (instanceId of the ticking node) |
| `session:streams:{sessionId}` | Set | Stream IDs belonging to this session. Used for atomic session-end teardown. |
| `streams:drain_rate:{accountId}` | Hash | field = streamId, value = `rate_per_second_units` (scaled integer string). Used to sum total drain for admission checks. Hash preserves BigDecimal precision — sorted set scores are doubles and would lose precision for large scaled integers. |
| `threshold:configs:{accountId}` | Hash | field = thresholdId, value = JSON config. Loaded from DB at startup. |
| `threshold:fired:{accountId}:{thresholdId}` | String | Sentinel `1` set with NX EX 86400. Guarantees exactly-once threshold firing. |
| `lock:session:{sessionId}` | String | Distributed lock token (UUID). SET NX PX 30000. |
| `lock:recovery:{instanceId}` | String | Distributed lock for orphaned stream adoption. |
| `instance:{instanceId}:heartbeat` | String | Timestamp. TTL 30s, refreshed every 10s. Dead when TTL expires. |
| `instance:streams:{instanceId}` | Sorted Set | Score = next_tick_time (unix millis). All stream IDs owned by this instance. |
| `stream:assigned:{instanceId}` | Pub/Sub channel | Notifies an instance that a new stream has been assigned to it for ticking. |
| `pending:db:writes` | Stream | Write-ahead buffer for deferred DB writes during database outage. Drained on DB reconnect. |

Hash tags (`{sessionId}`) ensure session-related keys land on the same Redis Cluster slot, enabling multi-key Lua scripts.

---

## 3. Balance Correctness Strategy

All balance mutations are Redis Lua scripts loaded at startup via `SCRIPT LOAD` and called with `EVALSHA`. Lua executes atomically within a single Redis instance — no other command can interleave.

**All values passed to Lua are scaled integers** (`BigDecimal × 10^8`, converted by `TokenAmount.toRedisUnits()` before the call). Lua treats them as plain integers throughout — no `tonumber()` precision loss, no floating-point arithmetic. This is the only place where `BigDecimal` is converted to a numeric representation; the conversion is centralized in `TokenAmount` and never duplicated.

**Pre-Lua checks (Java, before any Redis call)**:
1. `AuthorizationPort.authorize(caller, "DEBIT", fromAccountId)` — no-op in OSS; throws in enterprise if caller is not permitted.
2. `SpendingCapPort.checkBeforeDebit(fromAccountId, gross, metadata)` — no-op in OSS; throws `SpendingCapExceededException` in enterprise if a hard cap would be breached. For soft caps, emits an event and allows.

These checks run before the Lua script. If either throws, no Redis state is modified.

**Discrete transfer** (`discrete_transfer.lua`):
```lua
-- KEYS[1]=balance:{from}, KEYS[2]=balance:{to}
-- ARGV[1]=gross_units, ARGV[2]=to_units
-- Rake is not applied here; it is recorded to the ledger by Java after this call returns.
-- All values are scaled integers (BigDecimal × 10^8), passed as strings by EVALSHA.
local from_bal = redis.call('GET', KEYS[1])
if from_bal == false or tonumber(from_bal) < tonumber(ARGV[1]) then
    return {-1, from_bal or '0'}  -- insufficient funds
end
redis.call('DECRBY', KEYS[1], ARGV[1])
redis.call('INCRBY', KEYS[2], ARGV[2])
return {1, redis.call('GET', KEYS[1])}
```

The sender's balance is debited by the full gross amount; the receiver is credited by `gross - rake`. The rake amount is not credited to any account in Redis — there is no house balance in the real-time layer. `DECRBY` and `INCRBY` operate on the string-stored integer atomically. Redis internally handles these as 64-bit signed integers. With `TOKEN_SCALE = 8`, the maximum representable balance before overflow is `Long.MAX_VALUE / 10^8 ≈ 92,233,720,368` tokens — sufficient for any realistic balance.

**Streaming settlement interval** (`stream_settle_interval.lua`): reads `stream:{id}` hash fields `rate_per_second_units`, `started_at_epoch`, `accrued_before_units`; computes `elapsed_ms = now_ms - started_at_epoch`; computes `interval_units = rate_per_second_units * elapsed_ms / 1000` using integer arithmetic (millisecond precision, no floats); checks from_account balance; debits `from` by gross, credits `to` by `gross - rake`; resets `started_at_epoch` and zeroes `accrued_before_units`; returns `{signal, new_from_balance, settled_gross_units, rake_units}`. The Java layer converts balances back via `TokenAmount.fromRedisUnits()` and writes the rake as a ledger entry.

**Rake split calculation** happens in Java (`RakeEngine`) using `BigDecimal` with `HALF_DOWN` rounding before the Lua call:
```java
BigDecimal rakeAmount = gross.multiply(BigDecimal.valueOf(rakeBps))
                             .divide(BigDecimal.valueOf(10000), TOKEN_SCALE, RoundingMode.HALF_DOWN);
BigDecimal toAmount = gross.subtract(rakeAmount);
// Only gross and toAmount go to Lua; rakeAmount is used by LedgerService after the fact
long grossUnits = TokenAmount.toRedisUnits(gross);
long toUnits    = TokenAmount.toRedisUnits(toAmount);
```

When `rake_bps = 0` (the default), `rakeAmount = ZERO`, `toAmount = gross`, and no rake ledger entry is written.

**PostgreSQL's role**: not in the write path for live transactions. `account_balances` is reconciled asynchronously using optimistic locking on `version`. The `BigDecimal` value returned from Redis is written directly to the `NUMERIC(30, 8)` column via JDBC — no intermediate conversion:
```sql
UPDATE account_balances
SET settled_balance = :newBalance, version = version + 1, settled_at = now()
WHERE account_id = :accountId AND version = :expectedVersion;
```
Zero rows affected = another instance won the race; discard local value silently.

---

## 4. Streaming Transaction Lifecycle

### Start
1. Validate caller-supplied `rake_bps` (must be 0–10000). Store on the transaction record. Default is 0.
2. Soft admission check: `balance:{fromAccount}` minus sum of all values in `streams:drain_rate:{fromAccount}` (HGETALL, sum as BigDecimal) must be positive.
3. Atomic Redis operations (single Lua call):
   - Create `stream:{sessionId}.{streamId}` Hash with `rate_per_second_units`, `rake_bps`, `started_at_epoch = now_millis`, `accrued_before_units = 0`, `status = ACTIVE`.
   - Add to `streams:active:{fromAccount}` and `streams:active:{toAccount}`.
   - Add to `session:streams:{sessionId}`.
   - `HSET streams:drain_rate:{fromAccount} {streamId} {rate_per_second_units}`.
4. Write `streaming_transactions` row (status `ACTIVE`) to PostgreSQL.
5. Schedule settlement tick on this instance's virtual thread executor. Interval configurable via `token.engine.stream.tick-interval-seconds` (default 5). Shorter intervals reduce unbilled accrual at crash time but increase Redis and DB write load.

### Settlement Tick (every 5 seconds)
1. Call `stream_settle_interval.lua` — computes elapsed × rate, debits `from` by gross, credits `to` by `gross - rake`. Returns `{signal, new_from_balance, settled_gross_units, rake_units}`.
2. On EXHAUSTED signal: trigger stream stop.
3. `LedgerService` writes participant ledger entries plus rake ledger entry (if `rake_bps > 0`) to PostgreSQL asynchronously.
4. Evaluate thresholds via `ThresholdEvaluationService`.

### Stop
1. Call `stream_stop_and_settle.lua` — final settlement, removes all Redis state for the stream.
2. Write final ledger entries including rake entry.
3. Update `streaming_transactions` to `SETTLED`.
4. Update `account_balances` in PostgreSQL (optimistic lock).
5. Emit `StreamSettled` event.

**Rate immutability**: `rate_per_second` in the stream hash is set at start and never mutated. Rate changes require stopping the current stream and starting a new one, producing a clean audit trail.

---

## 5. Real-Time Estimation

`EstimationService.projectBalance(accountId, projectionWindowSeconds)`:

1. `GET balance:{accountId}` → `currentBalance = TokenAmount.fromRedisUnits(raw)`.
2. `SMEMBERS streams:active:{accountId}` → set of stream IDs.
3. Pipeline `HGETALL stream:{id}` for all stream IDs in a single round-trip.
4. For each ACTIVE stream: `rate = TokenAmount.fromRedisUnits(hash.get("rate_per_second_units"))`, signed by direction (negative = drain, positive = accumulate). All arithmetic in `BigDecimal`.
5. `totalNetRate = sum(rates)` — `BigDecimal` addition.
6. If `totalNetRate >= 0`: account never drains → return `Long.MAX_VALUE`.
7. `secondsToZero = currentBalance.divide(totalNetRate.abs(), 3, HALF_DOWN)`.
8. Optional: `projectedBalance = currentBalance.add(totalNetRate.multiply(BigDecimal.valueOf(T)))`, scaled to `TOKEN_SCALE`.

Served entirely from Redis. No PostgreSQL. Target: under 5ms p99. Not cached — computation is cheap and inputs change continuously.

---

## 6. Rake Execution

**Configuration**: `rake_bps` is a parameter supplied by the caller at transaction creation time. It defaults to `0` (no rake). There is no server-side rake rule engine — the caller owns this decision. `rake_account` (the ledger destination for rake) is also supplied at creation; it is nullable when `rake_bps = 0`.

**Split formula** (computed in Java with `BigDecimal` before the Lua call):
```java
BigDecimal rakeAmount = gross.multiply(BigDecimal.valueOf(rakeBps))
                             .divide(BigDecimal.valueOf(10000), TOKEN_SCALE, RoundingMode.HALF_DOWN);
BigDecimal toAmount = gross.subtract(rakeAmount);
```
`HALF_DOWN` rounding is the system standard. The rounding residual stays with the sender (`toAmount + rakeAmount ≤ gross` always).

**Real-time layer (Lua)**: only the two-way balance transfer executes in Redis — `DECRBY from` by gross, `INCRBY to` by `toAmount`. No house account is touched in Redis.

**Settlement layer (Java + PostgreSQL)**: after the Lua call returns, `LedgerService` writes the rake as a separate ledger entry (CREDIT to `rake_account`, DEBIT attributed to the transaction) within the same JDBC operation that records the participant entries. If `rake_bps = 0`, no rake entry is written.

**Consequence**: the platform's total rake is never held as a live Redis balance. It is the sum of rake ledger entries in PostgreSQL — queryable for reporting but not maintained in real-time. This eliminates all write contention on a shared house account under concurrent load.

For multi-consumer/multi-provider sessions: the caller supplies all participant flows as separate transactions within the same session. Each is settled individually. The Lua script per transaction touches only `balance:{from}` and `balance:{to}` — N transactions means N script calls, each on a disjoint pair of keys (unless accounts overlap, in which case Redis serializes correctly via Lua atomicity).

---

## 7. Session Lifecycle Management

### State Machine
```
ACTIVE → PAUSED → ACTIVE
ACTIVE → ENDED  (terminal)
PAUSED → ENDED  (terminal)
```

### Session End — Atomically Stopping All Streams

1. Acquire `lock:session:{sessionId}` (`SET NX PX 30000`). If not acquired within 5s → return 409.
2. Set session status to `ENDING` in PostgreSQL.
3. Execute `session_end.lua`:
   - `SMEMBERS session:streams:{sessionId}` → stream IDs.
   - For each stream: inline settlement and removal sub-functions.
   - Guard: if set cardinality changed mid-script, return error and retry.
   - On success: delete `session:streams:{sessionId}`.
4. Write all final ledger entries to PostgreSQL.
5. Update all `streaming_transactions` rows to `SETTLED`.
6. Update `sessions` to `ENDED`.
7. Release lock. Emit `SessionEnded`.

**Why the lock prevents races**: `startStream` also acquires `lock:session:{sessionId}` before adding to `session:streams:{sessionId}`. Once session-end holds the lock, no new streams can join.

### Session Pause / Resume

Pause: acquires session lock → `stream_pause.lua` on each active stream (settles current interval, sets `status = PAUSED`, records `accrued_before`, stops tick) → updates `sessions` to `PAUSED`.

Resume: reverses above, resets `started_at_epoch = now_millis` on each stream hash.

---

## 8. Threshold Event Detection

**Detection** — after every balance mutation, `ThresholdEvaluationService.evaluate(accountId, newBalance)`:

1. Read `threshold:configs:{accountId}` from Redis.
2. For each config that might trigger at `newBalance`:
3. `SET threshold:fired:{accountId}:{thresholdId} 1 NX EX 86400`
4. If SET returned OK → first to fire → emit event.
5. If SET failed → already fired → no-op.

**Reset**: when balance crosses back above a BALANCE_BELOW threshold (e.g. after top-up), a conditional `DEL threshold:fired:{accountId}:{thresholdId}` inside the transfer Lua script re-arms the threshold for the next crossing.

---

## 9. Horizontal Scaling and Node Statelessness

### Stateless Nodes

Every application instance is fully stateless with respect to durable data. The only process-local state is the virtual thread executor's tick schedule — which streams this instance is currently ticking. This is not authoritative: if an instance crashes, the tick schedule is reconstructed by the watchdog. No in-flight balance state is lost — all balance mutations are committed to Redis atomically before any process-local state is updated.

**There is no session affinity for HTTP request routing.** The load balancer distributes requests evenly across all instances (round-robin or least-connections). Any instance can serve any request — Redis Lua serialises correctness and the session lock serialises concurrent session operations. Affinity would accumulate load on older or busier instances over time without providing any correctness benefit.

### Stream Tick Assignment — Least-Loaded Instance

Stream tick ownership is entirely decoupled from HTTP request routing. When a new stream is created, tick ownership is assigned to the instance with the **fewest currently active ticks**, not the instance handling the creation request:

```java
// At stream creation time — O(N instances) Redis calls, acceptable since streams start infrequently
String tickOwner = registeredInstances.stream()
    .min(Comparator.comparingLong(id ->
        redisTemplate.opsForZSet().size("instance:streams:" + id)))
    .orElse(thisInstanceId);
```

The chosen instance is notified via Redis pub/sub (`stream:assigned:{instanceId}` channel) to schedule the tick locally. This keeps tick load evenly distributed across all instances over time regardless of which instance handled the originating HTTP request.

Ownership is tracked in two places:
1. **`instance:streams:{instanceId}`** (Sorted Set, score = next_tick_epoch_ms).
2. **`owner_instance`** field in `stream:{sessionId}.{streamId}` Hash — authoritative current owner.

Before executing a tick, the instance checks `owner_instance`. If it does not match, the tick is skipped — ownership was transferred. This is an efficiency guard; correctness holds regardless because `stream_settle_interval.lua` resets `started_at_epoch` on every call — a racing second tick on the same stream sees `elapsed ≈ 0` and settles zero tokens.

### Node Failure and Exclusive Recovery

When an instance dies its heartbeat key expires (`instance:{instanceId}:heartbeat`, TTL 30s). Every instance's watchdog scans for expired heartbeats every 15s.

**Exactly one instance performs the recovery** via a distributed lock:

```
1. Watchdog on instance B detects instance A heartbeat expired.
2. B attempts: SET lock:recovery:{instanceA} {tokenB} NX PX 60000
3. Only one instance wins. All others skip.
4. Winner (B) redistributes orphaned streams across all healthy instances
   proportionally by current tick count — not just to itself:
   a. SMEMBERS instance:streams:{instanceA}  →  orphaned stream IDs
   b. For each stream, assign to least-loaded healthy instance (same
      least-loaded logic as stream creation).
   c. HSET stream:{sessionId}.{streamId} owner_instance {assignedId}
   d. ZADD instance:streams:{assignedId} {next_tick_epoch} {streamId}
   e. ZREM instance:streams:{instanceA} {streamId}
   f. Notify assigned instance via pub/sub to schedule its tick.
   g. DEL instance:streams:{instanceA}
5. B releases lock:recovery:{instanceA}.
```

If B crashes mid-recovery, the 60s lock TTL expires and another instance retries. All steps are idempotent. Streams retain `started_at_epoch` and `accrued_before_units` from the dead instance's last Lua call — no accrued tokens are lost.


### OutboxPollingJob Exclusivity

All instances run `OutboxPollingJob`. To prevent duplicate delivery:

```sql
UPDATE outbox_events SET published = true
WHERE id = :id AND published = false
```

Only the instance whose UPDATE affects 1 row proceeds to publish. Concurrent instances see 0 rows and skip. No distributed lock is needed here — the UPDATE is the atomic claim.

### Distributed Lock Pattern

All locks: `SET {key} {uuid-token} NX PX {ttlMs}`. Release checks the token atomically to prevent a slow instance releasing a lock re-acquired by another after its lease expired:

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

### Redis Cluster Key Co-location
Hash tags ensure multi-key Lua scripts access keys within the same slot:
- Stream and session keys: `stream:{sessionId}.{streamId}`, `session:streams:{sessionId}`, `lock:session:{sessionId}` — all tagged with `{sessionId}`.
- Balance keys: `balance:{accountId}` — spread across slots (no co-location needed between different accounts).

---

## 10. Event Emission

This engine is a backend service. Its callers are other services, not end users. Event delivery is entirely via the outbox and a configured message queue. There is no WebSocket or SSE — real-time push to end users is the caller platform's responsibility.

### Architecture

**Transactional outbox**: `OutboxRepository` inserts into `outbox_events` within the same JDBC transaction as the state change. This makes event emission atomic with the operation that caused it — if the operation rolls back, the event is never written. `OutboxPollingJob` runs every 500ms on all instances, dispatches via the active `MessagingPort`, and marks `published = true` on success. At-least-once delivery semantics.

**Configurable MQ**: `OutboxPollingJob` delegates to whichever `MessagingPort` implementations are active. Multiple can be active simultaneously, each routing a subset of event types per `messaging_queue_configs`.

### MessagingPort SPI

Defined in `token-engine-core`. Each messaging module implements it and registers via Spring Boot auto-configuration:

```java
// token-engine-core (SPI)
public interface MessagingPort {
    String adapterType();
    void publish(String destination, DomainEvent event);
    boolean supports(String eventType);
}
```

| Adapter | Module | Edition |
|---|---|---|
| `NoOpMessagingAdapter` | token-engine-core | OSS — default when no config rows exist |
| `RabbitMqMessagingAdapter` | token-engine-messaging-rabbitmq | OSS |
| `KafkaMessagingAdapter` | token-engine-messaging-kafka | Enterprise |
| `AwsSqsMessagingAdapter` | token-engine-messaging-sqs | Enterprise |
| `AzureServiceBusAdapter` | token-engine-messaging-azure | Enterprise |

Adding an enterprise adapter requires: adding the JAR to the classpath + inserting a `messaging_queue_configs` row. No OSS code changes.

Adapter-specific connection properties live in the `properties` column (JSON). Sensitive values are referenced by environment variable name — never stored as plain text.

**RabbitMQ adapter** (OSS): uses `RabbitTemplate`. `destination` is `exchange/routingKey`. Event type maps to routing key suffix for fine-grained subscriber binding.

**Kafka adapter** (Enterprise): uses `KafkaTemplate`. `destination` is the topic name. Partitioned by `accountId` for per-account event ordering.

**SQS adapter** (Enterprise): uses AWS SDK v2 `SqsAsyncClient`. `destination` is the queue URL. Sends JSON with message attributes for event type and aggregate ID.

**No-op adapter** (OSS default): events are written to the outbox for audit; nothing is dispatched externally.

### Client-Side Real-Time Projection

The engine does not push sub-second balance updates. Instead, it emits **state-change events** whenever the balance or burn rate changes. A companion JS library provided alongside the engine consumes these events and handles real-time display locally:

```js
// Conceptual JS library usage
const projection = new TokenProjection({
  committedBalance: event.projection.committedBalance,   // BigDecimal string
  netRatePerSecond: event.projection.netRatePerSecond,   // signed, BigDecimal string
  snapshotAt: event.projection.snapshotAt                // unix millis
});

projection.currentBalance(); // committedBalance + (netRatePerSecond × elapsed)
projection.secondsRemaining(); // committedBalance / abs(netRatePerSecond)
```

Every event that changes balance or active burn rate carries a `projection` object. The caller's platform receives these events via its MQ subscription and forwards them to end users via whatever real-time channel it operates (its own WebSocket, SSE, polling — the engine has no opinion on this).

The `projection` object is computed by the engine at the moment the event is committed:

```json
"projection": {
  "committedBalance": "125.50000000",
  "netRatePerSecond": "-0.83333333",
  "snapshotAt": 1735000000123
}
```

`committedBalance` is the actual Redis balance at event time (not estimated). `netRatePerSecond` is the sum of all still-active stream rates for that account after the event. `snapshotAt` is the millisecond timestamp of the Redis Lua commit. The JS library uses these three values for all display calculations — no server round-trips required between state-change events.

### Event Taxonomy and Payloads

Events marked **carries projection** include the `projection` object described above on the `fromAccount` (and optionally `toAccount`) perspective.

| Event | Trigger | Carries projection | Key Payload Fields |
|---|---|---|---|
| `StreamStarted` | Stream created — burn rate changed | Yes | `streamId`, `sessionId`, `fromAccount`, `toAccount`, `ratePerSecond`, `metadata` |
| `StreamSettled` | Stream stopped — burn rate changed | Yes | `streamId`, `sessionId`, `fromAccount`, `toAccount`, `settledAmount`, `rakeAmount`, `durationSeconds` |
| `TokensIssued` | Top-up credited — balance jumped | Yes | `accountId`, `amount`, `issuanceRef`, `metadata` |
| `DiscreteTransactionExecuted` | One-off transfer — balance changed | Yes (both accounts) | `transactionId`, `fromAccount`, `toAccount`, `amount`, `rakeAmount`, `metadata` |
| `SessionEnded` | All streams stopped — burn rate zeroed | Yes | `sessionId`, `reason` (enum), `totalTokensConsumed`, `participants[]`, `metadata` |
| `BalanceLow` | BALANCE_BELOW threshold fires | Yes | `accountId`, `thresholdAmount` |
| `BalanceExhausted` | Settlement returns EXHAUSTED | Yes | `accountId`, `sessionId`, `streamId` |
| `ThresholdReached` | Any configured threshold fires | No | `accountId`, `thresholdId`, `thresholdType`, `amount`, `metadata` |
| `RedemptionRequired` | Balance withdrawn | Yes | `accountId`, `amount`, `metadata` |

**`SessionEnded.reason`** is a first-class enum, not free text:

```java
public enum SessionEndReason {
    CALLER_REQUESTED,      // explicit API call
    BALANCE_EXHAUSTED,     // from_account ran out of tokens mid-session
    THRESHOLD_TRIGGERED,   // a configured threshold caused auto-termination
    SESSION_TIMEOUT,       // configurable max-duration exceeded
    INSTANCE_RECOVERY,     // orphaned session adopted and closed by watchdog
    ADMINISTRATIVE         // operator-initiated via management API
}
```

The reason is set before the `session_end.lua` Lua script executes and stored on the `sessions` row (`ended_reason VARCHAR(64)`). It is included in the `SessionEnded` event payload and all ledger entries written during that session-end sweep.

### Caller Integration
- **Messaging queue** (primary): configure one or more `messaging_queue_configs` rows. The engine delivers durably via the outbox with at-least-once guarantees. Consumers must be idempotent.
- **Webhook** (fallback): caller registers a URL in `messaging_queue_configs` with adapter `HTTP`. `OutboxPollingJob` POSTs with exponential backoff; dead-letters after N consecutive failures.

---

## 11. Failure Modes and Recovery

### Dependency Roles (failure impact summary)

| Dependency | Role | Down impact |
|---|---|---|
| Redis | Source of truth for live balances and stream state | Hard — real-time operations halt |
| Relational DB | Audit ledger, settled snapshots, outbox | Soft — real-time operations continue; writes deferred |
| Message queue | Event delivery to callers | Soft — events accumulate in outbox; delivery resumes on recovery |

---

### 11.1 Database Failure

**What continues**: all real-time operations — balance mutations, streaming settlement ticks, session management, estimation. Redis holds the full live state. The DB is not in the real-time write path.

**What fails**: new account/session creation (requires a DB row), outbox polling (can't read or mark events), and ledger/settlement writes at the end of each tick.

#### Deferring writes during outage

When a DB write fails, the result is already committed in Redis (balance debited, stream hash updated). The settlement data must not be lost. A Redis Stream `pending:db:writes` acts as a write-ahead buffer:

```
XADD pending:db:writes * \
  type       LEDGER_ENTRY \
  tx_id      {streamId} \
  interval_start {epoch_ms} \
  interval_end   {epoch_ms} \
  gross_units    {scaled_int} \
  rake_units     {scaled_int} \
  from_bal_after {scaled_int}
```

This stream is durable because Redis AOF is enabled (see section 11.2). The application wraps every `PersistencePort` write in a circuit breaker (Resilience4j). On open circuit, two things are buffered instead of discarded:
- Ledger/settlement writes → serialised to `pending:db:writes` as above.
- Outbox event writes (`saveOutboxEvent`) → also serialised to `pending:db:writes` with `type = OUTBOX_EVENT` and `payload = {JSON}`. On recovery, these are written to `outbox_events` before `OutboxPollingJob` resumes, ensuring no events are silently dropped during the outage.

#### Recovery on DB reconnect

`BalanceSyncJob` runs on startup and after circuit close:

1. `XREADGROUP` from `pending:db:writes` — consume in batches.
2. Write each deferred entry to the DB via `PersistencePort`.
3. `XACK` on success; leave on failure for retry.
4. For any stream still active whose `streaming_transactions` row is missing (new stream started during outage): reconstruct the row from the stream hash and write it.
5. Reconcile `account_balances` from Redis via optimistic lock as normal.

**Account/session creation during outage**: these require a DB write and cannot be deferred meaningfully (the caller needs a confirmed ID). These operations fail fast (503) during a DB outage. New operations on existing accounts/sessions are unaffected.

---

### 11.2 Redis Failure

Redis is the hardest failure because it holds live balance state that does not exist anywhere else.

#### Durability configuration (required)

Redis **must** be configured with AOF persistence:

```
appendonly yes
appendfsync everysec
```

This bounds data loss to ~1 second of writes. RDB snapshots alone are insufficient — a snapshot may be minutes old.

For production: Redis Sentinel (OSS) or Redis Cluster (enterprise) provides automatic failover with a replica. Failover time is typically 10–30 seconds. During failover, all balance operations return 503.

#### On Redis restart with AOF intact

1. Redis replays AOF — all balance strings, stream hashes, and set memberships restore to within ~1 second of the crash point.
2. Application reconnects (Lettuce auto-reconnect with exponential backoff).
3. Settlement ticks resume for all streams whose hashes are present.
4. `accrued_before_units` in each stream hash may be up to 1 second stale — this is the maximum unbilled window.
5. `BalanceSyncJob` runs: compares Redis balance against `account_balances.settled_balance` in DB. Takes the **higher value** as authoritative (Redis is more recent; DB is the floor if Redis lost data).

#### On full Redis data loss (AOF corrupted or no persistence)

This is a disaster scenario with unavoidable data loss. Recovery procedure:

1. Seed Redis balances from `account_balances.settled_balance` (last DB reconciliation point).
2. All active streaming transactions are lost — the engine has no record of in-flight streams.
3. `SessionService` marks all sessions with status `ACTIVE` or `PAUSED` as `ENDED` with reason `INSTANCE_RECOVERY` and writes final ledger entries using `settled_balance` as the closing balance.
4. Callers receive `SessionEnded` events and must re-establish sessions.
5. The gap between the last reconciliation and the crash is unrecoverable. The reconciliation interval (default: every 30 seconds in `BalanceSyncJob`) defines the maximum financial exposure of this scenario.

**Operator lever**: decreasing the reconciliation interval reduces maximum exposure at the cost of more DB write load. Configurable via `token.engine.balance-sync.interval-seconds`.

---

### 11.3 Message Queue Failure

The queue is entirely outside the real-time path. All token operations continue normally.

**What fails**: event delivery to external consumers. `OutboxPollingJob` fails to publish and leaves `published = false` rows in the outbox table.

**Behaviour during outage**:
- `OutboxPollingJob` backs off exponentially (up to a configurable max interval, default 60s) on repeated MQ failures.
- The outbox table accumulates undelivered events. This is bounded by the DB's storage — operators should alert on outbox table size.

**Recovery on MQ reconnect**:
- `OutboxPollingJob` resumes on the next poll cycle after the circuit closes.
- Backlog is drained in `created_at` order — events are delivered with original timestamps in the payload so consumers can detect they are processing a backlog.
- No deduplication is guaranteed for events already partially delivered before the outage. Consumers must be idempotent on `id`.

**Dead-letter handling**: after N consecutive delivery failures for a specific event (configurable, default 5), the event is marked `dead_lettered = true` and skipped by the poller. A separate `DeadLetterReviewJob` emits a metric and logs the event for operator inspection. The outbox schema gains a `dead_lettered BOOLEAN NOT NULL DEFAULT false` column.

---

### 11.4 Eventual Consistency Model

The system makes the following explicit consistency guarantees:

| Operation | Consistency |
|---|---|
| Balance debit/credit (discrete or streaming) | **Immediately consistent** — Redis Lua is atomic |
| Balance readable after mutation | **Immediately consistent** — same Redis instance |
| Ledger entry visible in DB | **Eventually consistent** — written async after Redis commit; up to seconds behind |
| `account_balances.settled_balance` in DB | **Eventually consistent** — updated by `BalanceSyncJob`; up to `balance-sync.interval-seconds` behind |
| Event delivery via MQ | **Eventually consistent, at-least-once** — outbox guarantees delivery after queue recovers |
| Estimation (`/estimate`) | **Approximately consistent** — reads Redis live balance but stream `accrued_before` is up to 5s stale between ticks |

Callers must treat the DB-facing read APIs (ledger history, account balance from DB) as eventually consistent views. The only real-time consistent balance view is via the estimation endpoint, which reads Redis directly.

---

### 11.5 Infrastructure HA Recommendations

| Component | OSS minimum | Enterprise recommended |
|---|---|---|
| Redis | Single instance + AOF | Redis Sentinel (3 nodes) or Redis Cluster |
| Database | Single PostgreSQL instance | Primary + read replica + automated failover (e.g. Patroni) |
| Message queue | Single RabbitMQ node | RabbitMQ mirrored queues or Kafka with replication factor ≥ 2 |
| Application | 2+ instances behind load balancer | 3+ instances; round-robin or least-connections load balancing |

---

## Concurrency Problem Summary

| Problem | Solution |
|---|---|
| Concurrent discrete transfer + streaming settlement overdrawing same balance | Both are Redis Lua scripts; Lua is atomic and single-threaded — scripts cannot interleave |
| Session end racing with new stream being added | Both `endSession` and `startStream` acquire `lock:session:{sessionId}`; only one proceeds at a time |
| Threshold fired multiple times under concurrent settlement from N instances | `SET NX` is atomic in Redis; exactly one caller gets OK result |
| Stream settlement by a dead instance leaving stale state | Watchdog detects dead heartbeats via TTL expiry; adopts orphaned streams from dead instance's sorted set |
| Multiple instances attempting the same recovery | `SET lock:recovery:{deadInstanceId} NX` — only one instance acquires; others skip entirely |
| Two instances double-ticking the same stream post-recovery | `owner_instance` field in stream hash; tick skipped if instance ID doesn't match. Also safe without the check: Lua resets `started_at_epoch`, so a racing second tick sees elapsed ≈ 0 and settles zero |
| Duplicate outbox event delivery by concurrent instances | `UPDATE outbox_events SET published=true WHERE id=:id AND published=false` — only one UPDATE hits 1 row; others skip |
| PostgreSQL balance reconciliation conflicting across instances | Optimistic locking on `version`; loser discards silently, no retry needed |
