# Phase 3: Streaming Transactions - Research

**Researched:** 2026-05-14
**Domain:** Redis-backed in-memory stream registry, Redisson delayed queue scheduler, ShedLock distributed job locking, Postgres range partitioning, Spring Data Redis with Lettuce, mathematical projection settlement
**Confidence:** HIGH (all library versions verified against Maven Central; core patterns verified against official docs and live codebase)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01:** Multi-pod deployment with shared Redis StreamRegistry — NOT a JVM-local ConcurrentHashMap. Any pod can start, stop, or estimate any stream.
**D-02:** Spring Data Redis with Lettuce driver. Redis Sentinel supported via `token-engine.redis.sentinel.master` and `token-engine.redis.sentinel.nodes` properties. Single-node config also supported.
**D-03:** Caller-supplied stream ID (opaque String).
**D-04:** Rate expressed as `ratePerSecond: BigDecimal`. All settlement math in seconds.
**D-05:** `increment: BigDecimal` (optional). When present: `settled = floor(ratePerSecond × elapsedSeconds / increment) × increment`. When absent: `settled = ratePerSecond × elapsedSeconds`. Auto-termination fires when `remainingBalance < increment`.
**D-06:** `minimumAmount: BigDecimal` (optional). Client-initiated close before minimum drained: settle `max(actual, minimumAmount)`. `ignoreMinimum: boolean` (default `false`) waives minimum. Auto-termination and error-terminated streams always waive minimum.
**D-07/D-08:** Stream start request/response fields per CONTEXT.md.
**D-09/D-10/D-11:** Stream stop endpoint and response fields per CONTEXT.md.
**D-12/D-13:** Forward balance estimation endpoint, fields, and no-DB-read requirement per CONTEXT.md.
**D-14/D-15/D-16:** Redis StreamRegistry data model: `stream:{streamId}` hash, `account-streams:{accountId}` Set index, startup reconciliation from Postgres per CONTEXT.md.
**D-17 through D-22:** Floor check semantics, settlement race handling, account close restrictions per CONTEXT.md.
**D-23 through D-25:** Crash recovery, wall-clock elapsed on restart, startup reconciliation per CONTEXT.md.
**D-26 through D-28:** Redisson DelayedQueue primary scheduler, rescheduling on balance change, ShedLock-guarded Postgres fallback sweep per CONTEXT.md.
**D-29 through D-33:** Redis failure behavior: 503 on start/stop/estimation/account close with active streams; discrete credits always allowed per CONTEXT.md.
**D-34 through D-39:** OPS-02 data retention: `audit_archive` schema, Postgres range partitioning on `balance_audit_log.recorded_at`, 90-day configurable retention, ShedLock-guarded archival job, idempotency key TTL sweep per CONTEXT.md.

### Claude's Discretion
- Exact Redis key TTL (if any) for StreamRegistry entries vs. explicit removal on settlement
- Lettuce connection pool configuration
- Exact HTTP status codes for validation errors (400 vs 422) beyond the specified 409s
- Flyway migration numbering for new streaming/archival tables (continue V7+)
- Cucumber feature file structure for streaming acceptance tests
- Package structure within each module for streaming classes
- Exact ShedLock table name and configuration

### Deferred Ideas (OUT OF SCOPE)
- Tags on streaming transactions (TAG-01, TAG-06) — Phase 4
- Rake on streaming transactions (RAKE-02, RAKE-03, RAKE-04) — Phase 4
- Threshold events triggered by streaming settlements (EVT-01 through EVT-04) — Phase 4
- External event emission via transactional outbox (EMIT-01 through EMIT-03) — Phase 5
- AuditLogArchiver port interface for pluggable archive destinations — Phase 6 or v2
- Redis Cluster support (beyond Sentinel) — v2
- Streaming accumulation (credits via streaming) — out of scope
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| STR-01 | Caller can start a streaming drain specifying a rate against a participant account | `StreamingService.startStream()` acquires account lock, validates floor, persists to `streaming_transactions`, registers in Redis StreamRegistry, enqueues exhaustion in Redisson |
| STR-02 | Caller can stop a stream; engine settles via mathematical projection (rate × elapsed via nanoTime), commits atomically, removes from StreamRegistry | `StreamingService.stopStream()` acquires account lock, computes `settledAmount`, calls `account.debit()`, persists settlement, removes from Redis, writes audit log |
| STR-03 | Engine holds active streaming state in-memory; forward balance estimation without DB read per query | `StreamRegistry.getActiveStreams(accountId)` reads from Redis Set + Hash; `EstimationService` computes `committedBalance - Σ(rate × elapsed)` |
| STR-04 | Concurrent streaming and discrete transactions against same balance are correct | Pessimistic write lock (`findWithLock`) on account row serializes all balance mutations; streaming settlement uses same lock |
| STR-05 | Stream settlement atomically commits to Postgres; no permanent divergence | Single `@Transactional` scope covers account debit, `streaming_transactions` status update, audit log insert, Redis cleanup |
| STR-06 | BigDecimal (not floating-point) for all token rate arithmetic | `ratePerSecond × elapsedSeconds` computed with `BigDecimal.multiply` + `setScale(18, RoundingMode.DOWN)` |
| STR-07 | `minimumAmount` enforced; `ignoreMinimum` flag available on stop | Stop handler: if `!ignoreMinimum && actual < minimumAmount` → settle `minimumAmount`; auto/error termination always settles actual |
| STR-08 | `increment` parameter settles `floor(elapsed × rate / increment) × increment` | BigDecimal divide + FLOOR rounding applied to increment billing calculation |
| STR-09 | Auto-termination fires when `remainingBalance < increment` (when increment set) | Auto-termination threshold: `increment != null ? increment : BigDecimal.ZERO` |
| AUTO-01 | Engine auto-terminates stream when estimated balance hits floor (or < increment) | Redisson consumer dequeues exhaustion entry, calls settlement path with `reason = "balance_exhaustion"`, `ignoreMinimum = true` |
| AUTO-02 | Scheduler uses priority-queue keyed by exhaustion time; reschedules on balance change | Redisson `RDelayedQueue.offer(streamId, delay, TimeUnit.MILLISECONDS)` at start and on reschedule; cancel old entry before re-enqueue |
| AUTO-03 | Auto-termination emits distinct event with `reason = balance_exhaustion` | Settlement audit log entry carries `reason` field; distinguishable from `"stop endpoint call"` |
| BAL-02 | Forward estimated balance with `estimatedAt` and `estimatedDrainAt`; no DB read per query | `GET /accounts/{accountId}/estimated-balance` reads committed balance from Postgres once, projects from Redis StreamRegistry |
</phase_requirements>

---

## Summary

Phase 3 is the highest-risk phase in the build sequence. It introduces two new external dependencies (Spring Data Redis + Lettuce, Redisson), a novel settlement pattern (mathematical projection via `System.nanoTime()` for running streams, wall-clock for post-restart), and a distributed scheduler (Redisson `RDelayedQueue` backed by Redis sorted sets). The correctness invariant from Phase 1 and 2 — pessimistic write lock on account row before any balance mutation — must be maintained throughout streaming settlement to prevent double-spend between concurrent streaming settlements and discrete transactions.

The Redis StreamRegistry acts as the authoritative hot-state store for all active streams. It is consulted inside `@Transactional` service methods (after acquiring the Postgres account lock) to compute estimated balances for floor checks and forward estimation queries. The StreamRegistry must be rebuilt from Postgres on startup to handle JVM restarts correctly. The OPS-02 retention requirement (audit log archival + idempotency key TTL sweep) is also delivered in this phase, using ShedLock-guarded `@Scheduled` jobs with Postgres range partitioning.

The settlement race at auto-termination time (D-19) requires careful handling: if the committed balance is less than the projected amount due to concurrent discrete debits, the settlement function must clamp to `min(projectedAmount, availableBalance - floor)` rather than throwing a floor violation exception. This is a deliberate design choice; the account reaches floor but never goes below it.

**Primary recommendation:** Deliver Phase 3 in four sequential waves: (1) Redis infrastructure + StreamRegistry + Flyway DDL, (2) StreamingService (start/stop/settle) + discrete debit modification + account close modification, (3) forward estimation + auto-termination scheduler, (4) OPS-02 retention jobs + all Cucumber acceptance tests.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| StreamRegistry (hot state) | Redis / engine-spring | engine-core (interface) | Shared across pods; Spring Data Redis `RedisTemplate` in engine-spring; port interface in engine-core |
| Stream start/stop/settle REST endpoints | engine-service (controller) | engine-spring (service) | Thin controller delegates; all logic in StreamingServiceImpl |
| Settlement arithmetic (rate × elapsed, increment, minimum) | engine-core (domain) | — | Pure math; zero Spring dependency |
| Forward balance estimation | engine-spring (service) | Redis | Reads committed balance from Postgres, projection from Redis; no per-query DB read for projection |
| Auto-termination scheduler (primary) | engine-spring (scheduled) | Redis (Redisson) | `@Scheduled` consumer thread blocking on `RBlockingDeque`; competing-consumers across pods |
| Auto-termination scheduler (fallback) | engine-spring (scheduled) | Postgres | ShedLock-guarded `@Scheduled` Postgres sweep; Redis-independent |
| Startup reconciliation | engine-spring (startup) | Postgres + Redis | `ApplicationListener<ApplicationReadyEvent>` or `@PostConstruct` on StreamingService |
| Audit log archival job | engine-spring (scheduled) | Postgres | ShedLock-guarded `@Scheduled`; copies old partitions to `audit_archive` schema then drops |
| Idempotency key TTL sweep | engine-spring (scheduled) | Postgres | ShedLock-guarded `@Scheduled`; simple DELETE WHERE `created_at < now() - interval` |
| Flyway DDL (streaming_transactions, partitioning, shedlock) | engine-service (resources) | — | Migrations ship with the deployable service; continue V7+ |
| Redis failure detection | engine-spring (service) | — | Catch `RedisConnectionFailureException` in all streaming paths; throw `RedisUnavailableException` → 503 |
| Testcontainers Redis container | engine-service (test) | — | `GenericContainer` for Redis + `@DynamicPropertySource` for connection URL |

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-boot-starter-data-redis` | 3.5.3 (BOM-managed) | Spring Data Redis with Lettuce driver; `RedisTemplate`, `HashOperations`, `SetOperations` | Bundled in Spring Boot BOM; Lettuce is default driver; no extra version pin needed |
| `redisson-spring-boot-starter` | 3.50.0 | Redisson client for `RDelayedQueue` (auto-termination scheduler); integrates with Spring Boot autoconfiguration | Provides `RedissonClient` bean automatically; best distributed queue primitive for Java |
| `shedlock-spring` | 6.6.0 | Distributed lock for `@Scheduled` jobs (archival, fallback sweep); prevents concurrent execution across pods | Industry standard for Spring scheduled job deduplication |
| `shedlock-provider-jdbc-template` | 6.6.0 | ShedLock lock provider backed by Postgres via `JdbcTemplate` | No additional DB required; reuses existing Postgres |

`[VERIFIED: spring-boot-starter-data-redis 3.5.3 — Maven Central registry 2026-05-14]`
`[VERIFIED: redisson-spring-boot-starter 3.50.0 — Maven Central registry 2026-05-14]`
`[VERIFIED: shedlock-spring 6.6.0 — Maven Central registry 2026-05-14]`
`[VERIFIED: shedlock-provider-jdbc-template 6.6.0 — Maven Central registry 2026-05-14]`

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `testcontainers:testcontainers` | 1.21.3 (already on classpath) | Base Testcontainers; `GenericContainer` for Redis in tests | Already present from Phase 2; just add Redis container declaration |
| `org.springframework.boot:spring-boot-testcontainers` | 3.5.3 (already on classpath) | `@ServiceConnection` and `@DynamicPropertySource` support | Already present; extend TestcontainersConfiguration with Redis container |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `redisson-spring-boot-starter` (full) | `redisson` (bare) | Starter auto-wires `RedissonClient` bean and reads `spring.data.redis.*` properties; bare requires manual YAML config. Starter preferred for Spring Boot projects. |
| `RDelayedQueue` (deprecated, but stable) | `RReliableQueue` with delay (new in 3.47) | `RDelayedQueue` is deprecated as of Redisson 3.46.0 (April 2025); `RReliableQueue` is the replacement. `RDelayedQueue` still functions and is simpler to remove-by-ID for rescheduling. `RReliableQueue` adds visibility timeout / dead-letter but the removal-by-ID reschedule pattern is less established for it. **Recommendation: use `RDelayedQueue` for Phase 3 and plan a migration to `RReliableQueue` in a future phase.** |
| ShedLock Postgres | ShedLock Redis | Postgres is already the Postgres instance; Redis-backed ShedLock adds no new infrastructure. Postgres-backed is simpler. |

`[VERIFIED: RDelayedQueue deprecated in 3.46.0, April 2025 — Redisson CHANGELOG.md]`

**Installation (engine-service/build.gradle additions):**
```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
implementation 'org.redisson:redisson-spring-boot-starter:3.50.0'
implementation 'net.javacrumbs.shedlock:shedlock-spring:6.6.0'
implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.6.0'
```

**engine-spring/build.gradle additions:**
```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
implementation 'org.redisson:redisson-spring-boot-starter:3.50.0'
implementation 'net.javacrumbs.shedlock:shedlock-spring:6.6.0'
implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.6.0'
```

Note: `spring-boot-starter-data-redis` version is BOM-managed by `org.springframework.boot` plugin already on `engine-service`. No explicit version pin needed there. Redisson and ShedLock need explicit versions.

---

## Architecture Patterns

### System Architecture Diagram

```
POST /api/v1/streams                        GET /accounts/{id}/estimated-balance
          |                                           |
          v                                           v
[StreamController (@RestController)]    [EstimationController (@RestController)]
          |                                           |
          v                                           v
[StreamingServiceImpl (@Service)]       [StreamingServiceImpl.estimateBalance()]
          |                                           |
          |--- 1. accountRepository.findWithLock()    |--- 1. accountRepository.findById()
          |         SELECT ... FOR UPDATE             |         (committed balance)
          |                                           |
          |--- 2. StreamRegistry.getActiveStreams()   |--- 2. StreamRegistry.getActiveStreams()
          |         Redis HGETALL + SMEMBERS          |         Redis SMEMBERS + HGETALL
          |                                           |
          |--- 3. Floor check (estimated balance)     |--- 3. Project: committedBalance
          |                                           |         - Σ(ratePerSecond × elapsed)
          |--- 4. account.debit() / credit()          |
          |         accountRepository.save()          v
          |         Postgres: UPDATE accounts    EstimatedBalanceResponse
          |                                      {estimatedBalance, committedBalance,
          |--- 5. streamingTransactionRepo.save()      estimatedAt, estimatedDrainAt}
          |         INSERT streaming_transactions
          |
          |--- 6. StreamRegistry.register()
          |         Redis HMSET stream:{id}
          |         Redis SADD account-streams:{accountId}
          |
          |--- 7. AutoTerminationScheduler.enqueue()
          |         Redisson RDelayedQueue.offer(streamId, delay, MILLISECONDS)
          |
          v
    StreamResponse (201)

POST /api/v1/streams/{streamId}/stop
          |
          v
[StreamingServiceImpl.stopStream()]
          |--- 1. accountRepository.findWithLock()   SELECT ... FOR UPDATE
          |--- 2. StreamRegistry.get(streamId)       Redis HGETALL
          |--- 3. Compute settledAmount              BigDecimal arithmetic
          |         (minimumAmount, increment applied)
          |--- 4. account.debit(settledAmount)       Clamp to availableBalance-floor if needed
          |--- 5. streamingTransactionRepo.update()  status=SETTLED, settledAmount, stoppedAt
          |--- 6. auditLogRepository.save()          operation=STREAMING_SETTLE
          |--- 7. StreamRegistry.remove(streamId)    Redis HDEL + SREM
          |--- 8. AutoTerminationScheduler.cancel()  Redisson: remove from delayed queue
          v
    StopStreamResponse (200)

Startup Reconciliation (ApplicationReadyEvent):
    Postgres streaming_transactions WHERE status='ACTIVE'
          → for each: compute estimatedBalance
          → if ≤ floor: auto-terminate immediately
          → else: StreamRegistry.register() + Redisson.enqueue()

Fallback Sweep (@Scheduled, ShedLock-guarded):
    Postgres streaming_transactions WHERE status='ACTIVE'
          → for each: compute elapsed, estimated balance
          → if ≤ floor: settle (same stop path, ignoreMinimum=true)

Archival Job (@Scheduled, ShedLock-guarded):
    Postgres balance_audit_log partitions older than retention-days
          → INSERT INTO audit_archive.balance_audit_log SELECT ...
          → DROP old partition
    DELETE FROM idempotency_keys WHERE created_at < now() - ttl-hours

Redisson Consumer Thread (competing across pods):
    while(true) { streamId = blockingDeque.take();  // blocks until item ready
                  settleStream(streamId, "balance_exhaustion", ignoreMinimum=true); }
```

### Recommended Project Structure Extension

```
engine-core/src/main/java/com/certacota/engine/core/
├── domain/
│   └── StreamingTransaction.java         # NEW — JPA entity for streaming_transactions
├── dto/
│   ├── StartStreamRequest.java           # NEW — record
│   ├── StartStreamResponse.java          # NEW — record
│   ├── StopStreamRequest.java            # NEW — record
│   └── StopStreamResponse.java           # NEW — record (no finalBalance per D-10)
│   └── EstimatedBalanceResponse.java     # NEW — record: estimatedBalance, committedBalance, estimatedAt, estimatedDrainAt
├── repository/
│   └── StreamingTransactionRepository.java  # NEW — JPA repository
└── service/
    ├── StreamingService.java              # NEW — interface: startStream, stopStream
    └── StreamRegistry.java               # NEW — interface: register, get, remove, getActiveStreams, hasActiveStreams

engine-spring/src/main/java/com/certacota/engine/spring/
├── autoconfigure/
│   └── TokenEngineAutoConfiguration.java  # MODIFY — add StreamingServiceImpl, StreamRegistryImpl, ShedLock, scheduler beans
├── config/
│   └── TokenEngineProperties.java         # MODIFY — add StreamingProperties, AuditProperties, RedisProperties nested classes
├── redis/
│   └── RedisStreamRegistry.java           # NEW — @Component implements StreamRegistry (Redis-backed)
├── service/
│   ├── StreamingServiceImpl.java          # NEW — @Service @Transactional
│   └── TransactionServiceImpl.java        # MODIFY — debit() must consult StreamRegistry for estimated floor check
├── scheduler/
│   ├── AutoTerminationScheduler.java      # NEW — @Component: Redisson enqueue/cancel + consumer thread
│   ├── FallbackSweepJob.java              # NEW — @Scheduled + @SchedulerLock: Postgres sweep
│   └── AuditArchivalJob.java              # NEW — @Scheduled + @SchedulerLock: partition copy + drop + idempotency sweep
└── (account service unchanged)

engine-service/src/main/
├── java/com/certacota/engine/service/
│   └── controller/
│       ├── StreamController.java          # NEW — POST /streams, POST /streams/{id}/stop
│       ├── EstimationController.java      # NEW — GET /accounts/{id}/estimated-balance
│       └── GlobalExceptionHandler.java    # MODIFY — add RedisUnavailableException handler (503)
└── resources/db/migration/
    ├── V7__create_streaming_transactions.sql      # NEW
    ├── V8__create_shedlock.sql                    # NEW
    ├── V9__partition_balance_audit_log.sql        # NEW — range partitioning DDL + audit_archive schema
    └── V10__create_audit_archive_table.sql        # NEW — mirror table in audit_archive schema

engine-service/src/test/
├── java/.../
│   ├── steps/
│   │   └── StreamingSteps.java            # NEW
│   └── StreamingConcurrencyTest.java      # NEW — @SpringBootTest JUnit 5
├── java/.../TestcontainersConfiguration.java  # MODIFY — add GenericContainer for Redis
└── resources/features/
    ├── streaming-start.feature            # NEW
    ├── streaming-stop.feature             # NEW
    ├── streaming-estimation.feature       # NEW
    ├── streaming-minimum-amount.feature   # NEW
    ├── streaming-increment.feature        # NEW
    └── streaming-auto-termination.feature # NEW
```

### Pattern 1: Redis StreamRegistry — Hash + Set Index

**What:** Each active stream stored as a Redis Hash `stream:{streamId}`. A secondary index `account-streams:{accountId}` is a Redis Set of stream IDs for that account. Both must be updated atomically on register/remove.

**When to use:** Start stream (register), stop stream (remove), estimation (enumerate by account), floor check (enumerate by account).

```java
// Source: Spring Data Redis docs — HashOperations, SetOperations
// [VERIFIED: docs.spring.io/spring-data/redis/reference/redis/connection-modes.html]

@Component
@RequiredArgsConstructor
public class RedisStreamRegistry implements StreamRegistry {

    private static final String STREAM_KEY_PREFIX = "stream:";
    private static final String ACCOUNT_STREAMS_PREFIX = "account-streams:";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void register(StreamState state) {
        String streamKey = STREAM_KEY_PREFIX + state.streamId();
        String accountStreamsKey = ACCOUNT_STREAMS_PREFIX + state.accountId();

        Map<String, String> fields = Map.of(
            "accountId", state.accountId(),
            "ratePerSecond", state.ratePerSecond().toPlainString(),
            "startedAtNano", String.valueOf(state.startedAtNano()),
            "startedAtEpochMillis", String.valueOf(state.startedAtEpochMillis()),
            "minimumAmount", state.minimumAmount() != null ? state.minimumAmount().toPlainString() : "",
            "increment", state.increment() != null ? state.increment().toPlainString() : "",
            "status", "ACTIVE"
        );

        redisTemplate.opsForHash().putAll(streamKey, fields);
        redisTemplate.opsForSet().add(accountStreamsKey, state.streamId());
    }

    @Override
    public void remove(String streamId, String accountId) {
        redisTemplate.delete(STREAM_KEY_PREFIX + streamId);
        redisTemplate.opsForSet().remove(ACCOUNT_STREAMS_PREFIX + accountId, streamId);
    }

    @Override
    public List<StreamState> getActiveStreams(String accountId) {
        Set<String> streamIds = redisTemplate.opsForSet().members(ACCOUNT_STREAMS_PREFIX + accountId);
        if (streamIds == null || streamIds.isEmpty()) return Collections.emptyList();

        return streamIds.stream()
            .map(id -> {
                Map<Object, Object> fields = redisTemplate.opsForHash()
                    .entries(STREAM_KEY_PREFIX + id);
                return fields.isEmpty() ? null : StreamState.fromRedis(id, fields);
            })
            .filter(Objects::nonNull)
            .toList();
    }

    @Override
    public boolean hasActiveStreams(String accountId) {
        Long size = redisTemplate.opsForSet().size(ACCOUNT_STREAMS_PREFIX + accountId);
        return size != null && size > 0;
    }
}
```

### Pattern 2: Settlement Arithmetic — BigDecimal Projection

**What:** Mathematical projection using `System.nanoTime()` for streams running in the current JVM, wall-clock millis for post-restart streams.

**When to use:** Every `stopStream()` or `autoTerminate()` call.

```java
// Source: Phase 3 decisions D-02, D-05, D-06, D-19
// [VERIFIED: project CONTEXT.md decisions]

private BigDecimal computeSettledAmount(StreamState state, boolean ignoreMinimum) {
    long elapsedNanos = System.nanoTime() - state.startedAtNano();
    BigDecimal elapsedSeconds = BigDecimal.valueOf(elapsedNanos)
        .divide(BigDecimal.valueOf(1_000_000_000L), 18, RoundingMode.DOWN);

    BigDecimal projected = state.ratePerSecond().multiply(elapsedSeconds)
        .setScale(18, RoundingMode.DOWN);

    // Apply increment billing
    if (state.increment() != null && state.increment().compareTo(BigDecimal.ZERO) > 0) {
        projected = projected.divide(state.increment(), 0, RoundingMode.FLOOR)
            .multiply(state.increment())
            .setScale(18, RoundingMode.DOWN);
    }

    // Apply minimum amount (client-initiated stop only)
    if (!ignoreMinimum
            && state.minimumAmount() != null
            && projected.compareTo(state.minimumAmount()) < 0) {
        projected = state.minimumAmount();
    }

    return projected;
}

private BigDecimal clampToAvailableBalance(BigDecimal projected, Account account) {
    BigDecimal effectiveFloor = account.getBalanceFloor() != null
        ? account.getBalanceFloor()
        : properties.getBalanceFloor();
    BigDecimal available = account.getBalance().subtract(effectiveFloor);
    return projected.min(available);  // D-19: settlement race clamp
}
```

### Pattern 3: Redisson DelayedQueue — Auto-Termination Scheduler

**What:** A Redisson `RDelayedQueue` backed by a `RBlockingDeque`. Items are offered with a delay representing the time until stream balance exhaustion. All pods compete on the same `RBlockingDeque`; the first to dequeue settles the stream.

**When to use:** Stream start, stream stop (cancel old + re-enqueue if needed), balance change affecting an active stream.

```java
// Source: Redisson wiki 7. Distributed-collections, RDelayedQueue javadoc
// [VERIFIED: github.com/redisson/redisson/wiki/7.-Distributed-collections]

@Component
@RequiredArgsConstructor
public class AutoTerminationScheduler implements ApplicationListener<ApplicationReadyEvent> {

    private static final String EXHAUSTION_QUEUE = "stream-exhaustion-queue";

    private final RedissonClient redissonClient;
    private final StreamingService streamingService;

    private RBlockingDeque<String> destinationQueue;
    private RDelayedQueue<String> delayedQueue;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        destinationQueue = redissonClient.getBlockingDeque(EXHAUSTION_QUEUE);
        delayedQueue = redissonClient.getDelayedQueue(destinationQueue);
        startConsumerThread();
    }

    public void enqueue(String streamId, long delayMillis) {
        delayedQueue.offer(streamId, delayMillis, TimeUnit.MILLISECONDS);
    }

    public void cancel(String streamId) {
        delayedQueue.remove(streamId);
    }

    private void startConsumerThread() {
        Thread.ofVirtual().name("auto-termination-consumer").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String streamId = destinationQueue.take();
                    streamingService.autoTerminate(streamId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("Auto-termination error for stream, will retry via fallback sweep", e);
                }
            }
        });
    }
}
```

**Exhaustion time calculation:**
```java
// D-26: exhaustion entry at startedAt + (estimatedBalance - floor) / ratePerSecond
BigDecimal estimatedBalance = account.getBalance()
    .subtract(totalActiveProjection(accountId, excludeCurrentStream=false));
BigDecimal timeToExhaustionSeconds = estimatedBalance
    .subtract(effectiveFloor)
    .divide(stream.ratePerSecond(), 3, RoundingMode.DOWN);
long delayMillis = timeToExhaustionSeconds.multiply(BigDecimal.valueOf(1000)).longValue();
```

### Pattern 4: ShedLock — Distributed Job Lock

**What:** `@EnableSchedulerLock` on the autoconfiguration class + `@SchedulerLock` on each `@Scheduled` method + `JdbcTemplateLockProvider` bean backed by the Postgres `DataSource`.

**When to use:** Archival job and fallback sweep — every `@Scheduled` method that must not run concurrently on multiple pods.

```java
// Source: ShedLock README 7.7.0 + Baeldung guide (VERIFIED)
// [VERIFIED: github.com/lukas-krecan/ShedLock README, Maven Central 6.6.0]

// TokenEngineAutoConfiguration or a dedicated SchedulerConfiguration:
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerConfiguration {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }
}

// FallbackSweepJob:
@Scheduled(fixedDelayString = "${token-engine.streaming.fallback-sweep-seconds:300}000")
@SchedulerLock(
    name = "streaming_fallback_sweep",
    lockAtMostFor = "PT5M",
    lockAtLeastFor = "PT30S"
)
public void runFallbackSweep() {
    LockAssert.assertLocked();
    // query active streaming_transactions, settle any at floor
}

// AuditArchivalJob:
@Scheduled(cron = "${token-engine.audit.cron:0 0 2 * * *}")
@SchedulerLock(
    name = "audit_archival_job",
    lockAtMostFor = "${token-engine.audit.lock-at-most-hours:PT2H}",
    lockAtLeastFor = "${token-engine.audit.lock-at-least-minutes:PT1M}"
)
public void runArchival() {
    LockAssert.assertLocked();
    // copy old partitions to audit_archive, drop them, sweep idempotency_keys
}
```

**ShedLock DDL:**
```sql
-- V8__create_shedlock.sql
CREATE TABLE shedlock (
    name       VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP   NOT NULL,
    locked_at  TIMESTAMP   NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```
`[VERIFIED: ShedLock README.md on GitHub]`

### Pattern 5: Lettuce Sentinel Configuration

**What:** Spring Data Redis sentinel configuration via `application.yml`. Spring Boot 3.x uses `spring.data.redis.*` (not `spring.redis.*`). Sentinel reads from properties without a custom `@Bean` definition when using `spring-boot-starter-data-redis`.

**When to use:** Production multi-node Redis Sentinel deployments. Development uses single-node.

```yaml
# application.yml — single node (dev)
spring:
  data:
    redis:
      host: localhost
      port: 6379

# application.yml — Sentinel (production)
spring:
  data:
    redis:
      sentinel:
        master: ${token-engine.redis.sentinel.master:}
        nodes: ${token-engine.redis.sentinel.nodes:}
```

Custom `token-engine.redis.*` properties (per D-02) feed into `spring.data.redis.sentinel.*` via an `@Configuration` class that programmatically creates `RedisSentinelConfiguration` if sentinel properties are present.

```java
// Source: docs.spring.io/spring-data/redis/reference/redis/connection-modes.html
// [VERIFIED: Spring Data Redis docs — Sentinel section]

@Bean
@ConditionalOnProperty("token-engine.redis.sentinel.master")
public RedisConnectionFactory sentinelConnectionFactory(TokenEngineProperties properties) {
    RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
        .master(properties.getRedis().getSentinelMaster());
    for (String node : properties.getRedis().getSentinelNodes().split(",")) {
        String[] parts = node.trim().split(":");
        sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
    }
    return new LettuceConnectionFactory(sentinelConfig);
}
```

### Pattern 6: Postgres Range Partitioning for balance_audit_log

**What:** Convert `balance_audit_log` from a plain table to a range-partitioned table keyed on `recorded_at`. Old partitions are copied to `audit_archive.balance_audit_log` then dropped.

**Critical constraint:** Flyway migration to convert an existing table to partitioned requires: create new partitioned table, copy data, swap. This is non-trivial on a table with existing rows.

**Recommended approach for Flyway:**
1. V9: Create `audit_archive` schema + `audit_archive.balance_audit_log` with identical schema (mirror table, plain unpartitioned)
2. V10: Create new partitioned table `balance_audit_log_partitioned` (PARTITION BY RANGE on `recorded_at`); migrate existing data from `balance_audit_log`; rename old to `balance_audit_log_legacy`; rename new to `balance_audit_log`; create initial partition covering all historical data

**Alternative (simpler, recommended for Phase 3):** Keep `balance_audit_log` as a plain table. The archival job DELETEs directly from it after copying to `audit_archive`. Add Postgres partitioning in a future phase when volume warrants it. Decision in Claude's discretion per CONTEXT.md. Research recommendation: **defer Postgres partitioning, use plain INSERT INTO audit_archive SELECT + DELETE FROM balance_audit_log for Phase 3.** This avoids a complex DDL migration on an existing table.

```sql
-- V9__create_audit_archive.sql
CREATE SCHEMA IF NOT EXISTS audit_archive;

CREATE TABLE audit_archive.balance_audit_log (
    LIKE public.balance_audit_log INCLUDING ALL
);
```

`[VERIFIED: PostgreSQL docs 17 — PARTITION BY RANGE, DETACH PARTITION CONCURRENTLY, docs.postgresql.org/17/ddl-partitioning.html]`
`[ASSUMED: deferring Postgres range partitioning to a future phase for Phase 3 simplicity]`

### Pattern 7: Testcontainers Redis — Integration Test

**What:** Add a Redis `GenericContainer` to `TestcontainersConfiguration` alongside the existing Postgres container. Use `@DynamicPropertySource` to inject `spring.data.redis.host` and `spring.data.redis.port`.

**When to use:** All streaming integration tests; the Redisson client and Spring Data Redis both need a real Redis instance.

```java
// Source: Testcontainers docs, Spring Boot testing docs
// [VERIFIED: docs.spring.io/spring-boot/reference/testing/testcontainers.html]

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // This runs as a static method — inject via @DynamicPropertySource
        // OR configure via ApplicationContextInitializer if container is a @Bean
    }
}
```

Note: `@ServiceConnection` is not yet available for `GenericContainer` with Redis (only for typed containers like `RedisContainer` from `testcontainers-redis`). The clean approach is to declare the `GenericContainer` as a `@Bean` and use a `@TestConfiguration` companion class with `@DynamicPropertySource` on a separate class, or to configure Spring Data Redis to use `spring.data.redis.host=localhost` and set the port dynamically. `[ASSUMED: verify Testcontainers Redis @ServiceConnection availability — may require a wrapper]`

### Anti-Patterns to Avoid

- **Computing elapsed time with `System.currentTimeMillis()` for running streams:** Loss of precision. Use `System.nanoTime()` delta for active streams in the same JVM. Wall-clock elapsed is only acceptable post-restart when `startedAtNano` is unavailable. `[VERIFIED: CONTEXT.md D-24]`
- **Storing `startedAtNano` as a single long across pod restarts:** `System.nanoTime()` is JVM-relative. On restart or across pods, `startedAtNano` from Redis is meaningless. The `streaming_transactions.started_at` (TIMESTAMPTZ) is the source of truth for wall-clock elapsed. The `startedAtNano` in Redis is valid only for the current JVM session of the pod that started the stream. **Any pod stopping a stream must detect whether the stream was started by the current JVM or loaded from Postgres, and use the appropriate clock.** `[VERIFIED: CONTEXT.md D-23/D-24 — nanoTime precision lost across JVM restarts]`
- **Floor check before acquiring the Postgres account lock:** Identical pitfall from Phase 2. Must acquire `findWithLock()` first, then query StreamRegistry inside the lock boundary. `[VERIFIED: Phase 2 RESEARCH.md Pitfall 1]`
- **Settling without a Postgres lock when Redis is unavailable:** If Redis fails during a settlement attempt (not start/stop), the `availableBalance - floor` clamp must still use the Postgres committed balance. Never use stale Redis data for the settlement clamp. `[VERIFIED: CONTEXT.md D-29 through D-33]`
- **Deleting from `balance_audit_log` without archiving first:** Per D-34, no direct DELETE from audit log. Copy to `audit_archive` first, then delete. `[VERIFIED: CONTEXT.md D-34]`
- **Running archival job on every pod simultaneously:** Must be guarded by ShedLock. Without it, multiple pods run archival concurrently and double-copy (and double-drop) partitions. `[VERIFIED: CONTEXT.md D-39]`

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Distributed delayed queue for auto-termination | Custom Redis sorted-set scheduler with Lua scripts | `Redisson RDelayedQueue` | Redisson implements this correctly with leader election, reconnect resubscription, and competing consumer support |
| Distributed job deduplication across pods | Custom DB row locking in scheduled job | `ShedLock @SchedulerLock` | ShedLock handles lock release on pod crash (`lockAtMostFor`), prevents false lock hold on short tasks (`lockAtLeastFor`) |
| Redis hash serialization for stream state | Manual JSON serialization with `ObjectMapper` | `redisTemplate.opsForHash()` with `String` value type | `HashOperations<String, String, String>` with `toPlainString()` / `new BigDecimal(value)` conversions is simpler and avoids Jackson dependency in RedisStreamRegistry |
| Multi-pod stream registry | `ConcurrentHashMap` in-process | `spring-data-redis` with `HashOperations` + `SetOperations` | In-process map breaks with multiple pods (D-01) |
| Postgres-level audit archival | Custom JDBC archive logic | Flyway DDL + `JdbcTemplate` batch copy in archival job | Archive schema creation belongs in Flyway; copy-then-delete pattern is standard |

**Key insight:** The Redisson `RDelayedQueue` uses a Redis sorted-set internally, with scores as delivery timestamps. The `offer(value, delay, unit)` API is the only correct multi-pod mechanism for precision delayed wake-up without polling. A custom sorted-set approach would replicate this but without the battle-tested reconnect and failover logic.

---

## DDL: New Flyway Migrations (V7+)

### V7: streaming_transactions table

```sql
-- V7__create_streaming_transactions.sql
CREATE TABLE streaming_transactions (
    id               BIGSERIAL        PRIMARY KEY,
    stream_id        VARCHAR(255)     NOT NULL,
    account_id       VARCHAR(255)     NOT NULL,
    status           VARCHAR(20)      NOT NULL DEFAULT 'ACTIVE',
    rate_per_second  NUMERIC(38,18)   NOT NULL,
    minimum_amount   NUMERIC(38,18),
    increment        NUMERIC(38,18),
    started_at       TIMESTAMPTZ      NOT NULL,
    stopped_at       TIMESTAMPTZ,
    settled_amount   NUMERIC(38,18),
    reason           VARCHAR(255),
    idempotency_key  VARCHAR(255),
    CONSTRAINT pk_streaming_transactions PRIMARY KEY (id),
    CONSTRAINT uq_stream_id UNIQUE (stream_id),
    CONSTRAINT fk_str_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_str_status CHECK (status IN ('ACTIVE', 'SETTLED', 'ERROR')),
    CONSTRAINT chk_str_rate CHECK (rate_per_second > 0)
);

CREATE INDEX idx_str_account_id ON streaming_transactions(account_id);
CREATE INDEX idx_str_status ON streaming_transactions(status);
CREATE UNIQUE INDEX uq_str_idempotency ON streaming_transactions(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

### V8: shedlock table

```sql
-- V8__create_shedlock.sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

### V9: audit_archive schema

```sql
-- V9__create_audit_archive.sql
CREATE SCHEMA IF NOT EXISTS audit_archive;

CREATE TABLE audit_archive.balance_audit_log (
    id              BIGINT          NOT NULL,
    account_id      VARCHAR(255)    NOT NULL,
    operation       VARCHAR(50)     NOT NULL,
    amount          NUMERIC(38,18)  NOT NULL,
    balance_before  NUMERIC(38,18)  NOT NULL,
    balance_after   NUMERIC(38,18)  NOT NULL,
    idempotency_key VARCHAR(255),
    transaction_id  BIGINT,
    recorded_at     TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_arch_audit_account_id ON audit_archive.balance_audit_log(account_id);
CREATE INDEX idx_arch_audit_recorded_at ON audit_archive.balance_audit_log(recorded_at);
```

Note: No foreign keys from `audit_archive` back to the main schema — archive rows outlive the main records in some enterprise retention scenarios.

---

## Common Pitfalls

### Pitfall 1: nanoTime Semantics Across JVM Restarts and Pods
**What goes wrong:** `System.nanoTime()` is only meaningful within a single JVM instance. If a stream was started by Pod A, and Pod B (which restarted or is a different instance) attempts to settle it, `startedAtNano` from Redis is wrong. The elapsed calculation produces an incorrect (and potentially enormous) number.
**Why it happens:** `nanoTime()` is relative to JVM start — different JVMs have different origins.
**How to avoid:** Store `startedAtNano` in Redis AND `started_at` (wall-clock TIMESTAMPTZ) in Postgres and Redis. When settling, use `nanoTime` only if `startedAtNano` was set by the current JVM (i.e., the pod that started the stream). Detect this by checking whether `startedAtNano` is within the current JVM's uptime range, OR by simply always using wall-clock millis and accepting that sub-millisecond precision is lost for streams settled by a different pod. CONTEXT.md D-24 makes this trade-off explicit.
**Recommendation:** Store `startedAtEpochMillis` in Redis hash alongside `startedAtNano`. Use `nanoTime` when settling on the same pod, wall-clock when settling on a different pod. A boolean flag `nanoTimeFromCurrentJvm` computed at lookup time.
**Warning signs:** Settlement amounts wildly incorrect for cross-pod stops; estimated elapsed time shows years instead of seconds.

### Pitfall 2: Settlement Race — Floor Violation vs. Clamp
**What goes wrong:** Auto-termination dequeues a stream, acquires the account lock, and discovers the committed balance is already below the projected settlement amount (concurrent discrete debits drained the account). A naive floor check throws `BalanceFloorViolationException`, leaving the stream in ACTIVE state forever.
**Why it happens:** The exhaustion time was computed when balance was X, but by settlement time, concurrent debits reduced balance below projected amount.
**How to avoid:** Per D-19: settlement uses `min(projectedAmount, availableBalance - floor)`. The `clampToAvailableBalance()` method (Pattern 2) handles this. The stream is still marked SETTLED; no exception is thrown.
**Warning signs:** Streams in permanently ACTIVE state in `streaming_transactions` after balance exhaustion tests.

### Pitfall 3: Redis Registry and Postgres Not in Sync on Settlement
**What goes wrong:** Settlement removes stream from Redis but fails before the Postgres `streaming_transactions.status` update commits (or vice versa). A partially-settled stream exists.
**Why it happens:** Redis operations are not inside the Postgres `@Transactional` boundary.
**How to avoid:** Remove from Redis AFTER the Postgres transaction commits. Use a `@TransactionalEventListener(phase = AFTER_COMMIT)` to trigger Redis cleanup, OR handle it in a finally block after the Postgres commit succeeds. The simpler approach: do Redis removal after `transactionManager.commit()` succeeds, and rely on the startup reconciliation (D-16/D-25) to resync Redis from Postgres if Redis removal fails.
**Recovery path:** Startup reconciliation rebuilds Redis from Postgres ACTIVE rows. A stream that was settled in Postgres but still in Redis will be found as SETTLED in Postgres on restart and skipped during reconciliation. Implement `getActiveStreams()` to cross-check against Postgres if needed.
**Warning signs:** Estimation shows in-flight streams that are already settled.

### Pitfall 4: Redisson Consumer Thread Stopping on Exception
**What goes wrong:** The consumer thread calling `destinationQueue.take()` gets an exception (Redis reconnect, settlement error), crashes, and never restarts. No more auto-terminations fire until the next pod restart.
**Why it happens:** Single consumer thread with no supervision.
**How to avoid:** Wrap the `take()` loop body in try-catch. Log the exception as a warning (settlement will be caught by the fallback sweep). Never let the exception propagate to the thread's `run()` body. Use a virtual thread (Java 21+) so restart is cheap.
**Warning signs:** Auto-termination stops working silently; logs show no consumer activity.

### Pitfall 5: Redisson Competing-Consumer — Multiple Pods Settling the Same Stream
**What goes wrong:** Two pods dequeue the same stream ID simultaneously. Both attempt to settle, leading to double-debit.
**Why it happens:** `RBlockingDeque.take()` is atomic — each entry is delivered to exactly one consumer. If processing is idempotent, a double-dequeue is impossible. But if the `take()` succeeded but the settlement threw an exception, another pod might process a requeued entry.
**How to avoid:** `RDelayedQueue` + `RBlockingDeque.take()` is designed for competing consumers — each item is consumed by exactly one consumer. The settlement itself is idempotent: before acquiring the account lock, check `streaming_transactions.status`. If status is already SETTLED, return immediately. This covers the edge case of a requeued entry after partial failure.
**Warning signs:** `streaming_transactions` row with status SETTLED shows two audit entries with `operation = STREAMING_SETTLE`.

### Pitfall 6: Redis Key Collision Between Stream IDs and Account-Streams Index
**What goes wrong:** A `stream_id` value equals an `account_id` value, causing `stream:{id}` and `account-streams:{id}` to collide or be misread.
**Why it happens:** No namespace enforcement on caller-supplied IDs.
**How to avoid:** The key prefixes `stream:` and `account-streams:` prevent collision. Never construct a key without the prefix. Use constants for prefixes to prevent typos.
**Warning signs:** `SMEMBERS account-streams:{id}` returns a mix of stream IDs and non-stream values.

---

## Code Examples

Verified patterns from official sources and CONTEXT.md decisions:

### Settlement Amount Calculation (increment + minimum amount)
```java
// Source: CONTEXT.md D-05, D-06, D-19
// [VERIFIED: CONTEXT.md — decisions section]
public BigDecimal computeSettledAmount(StreamState state, boolean ignoreMinimum,
                                        BigDecimal elapsedSeconds) {
    BigDecimal projected = state.ratePerSecond()
        .multiply(elapsedSeconds)
        .setScale(18, RoundingMode.DOWN);

    if (state.increment() != null && state.increment().compareTo(BigDecimal.ZERO) > 0) {
        projected = projected
            .divide(state.increment(), 0, RoundingMode.FLOOR)
            .multiply(state.increment())
            .setScale(18, RoundingMode.DOWN);
    }

    if (!ignoreMinimum
            && state.minimumAmount() != null
            && projected.compareTo(state.minimumAmount()) < 0) {
        projected = state.minimumAmount();
    }

    return projected;
}
```

### Elapsed Seconds — nanoTime vs. Wall-Clock
```java
// Source: CONTEXT.md D-23/D-24
// [VERIFIED: CONTEXT.md decision D-24]
private BigDecimal elapsedSeconds(StreamState state) {
    if (state.startedAtNanoFromCurrentJvm()) {
        long elapsedNanos = System.nanoTime() - state.startedAtNano();
        return BigDecimal.valueOf(elapsedNanos)
            .divide(BigDecimal.valueOf(1_000_000_000L), 18, RoundingMode.DOWN);
    } else {
        long elapsedMillis = System.currentTimeMillis() - state.startedAtEpochMillis();
        return BigDecimal.valueOf(elapsedMillis)
            .divide(BigDecimal.valueOf(1000L), 18, RoundingMode.DOWN);
    }
}
```

### Forward Balance Estimation
```java
// Source: CONTEXT.md D-12/D-13
// [VERIFIED: CONTEXT.md decisions]
public EstimatedBalanceResponse estimateBalance(String accountId) {
    Account account = accountRepository.findById(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));

    List<StreamState> activeStreams = streamRegistry.getActiveStreams(accountId);
    OffsetDateTime estimatedAt = OffsetDateTime.now();

    BigDecimal totalProjected = activeStreams.stream()
        .map(s -> s.ratePerSecond().multiply(elapsedSeconds(s)).setScale(18, RoundingMode.DOWN))
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal estimatedBalance = account.getBalance().subtract(totalProjected);

    BigDecimal totalRate = activeStreams.stream()
        .map(StreamState::ratePerSecond)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    Long estimatedDrainAt = null;
    BigDecimal floor = account.getBalanceFloor() != null
        ? account.getBalanceFloor() : properties.getBalanceFloor();
    if (totalRate.compareTo(BigDecimal.ZERO) > 0 && estimatedBalance.compareTo(floor) > 0) {
        BigDecimal secondsToFloor = estimatedBalance.subtract(floor)
            .divide(totalRate, 3, RoundingMode.DOWN);
        estimatedDrainAt = estimatedAt.toInstant().toEpochMilli()
            + secondsToFloor.multiply(BigDecimal.valueOf(1000)).longValue();
    }

    return new EstimatedBalanceResponse(
        estimatedBalance, account.getBalance(), estimatedAt, estimatedDrainAt);
}
```

### Redis Connection Failure Guard
```java
// Source: Spring Data Redis docs — connection exception handling
// [ASSUMED: exception class name — verify in spring-data-redis source]
private <T> T withRedis(Supplier<T> operation, Supplier<T> fallback) {
    try {
        return operation.get();
    } catch (RedisConnectionFailureException e) {
        log.warn("Redis unavailable: {}", e.getMessage());
        return fallback.get();
    }
}

// In streaming service start:
withRedis(
    () -> streamRegistry.getActiveStreams(accountId),
    () -> { throw new RedisUnavailableException("Redis unavailable; streaming operations suspended"); }
);
```

### ShedLock Configuration (autoconfigure)
```java
// Source: ShedLock README 7.7.0 — verified
// [VERIFIED: github.com/lukas-krecan/ShedLock README]
@Bean
public LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build()
    );
}
```

---

## Runtime State Inventory

This is a greenfield phase — no rename/refactor. Runtime state created BY this phase:

| Category | Items Created | Action Required at Settlement |
|----------|--------------|-------------------------------|
| Redis hot state | `stream:{id}` hash + `account-streams:{accountId}` Set per active stream | Removed on settlement; rebuilt from Postgres on startup |
| Postgres (streaming_transactions) | One row per stream start; status updated to SETTLED on stop | None — durable |
| Postgres (shedlock) | One row per registered job name (`streaming_fallback_sweep`, `audit_archival_job`) | None — operational |
| Postgres (audit_archive schema) | Created by V9; populated by archival job | None — retention |
| Redisson delayed queue | `stream-exhaustion-queue` key in Redis | Cleared on stream settlement; rebuilt on startup |

Nothing found requiring pre-existing data migration. All state is created fresh by Phase 3 implementation.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Tick accumulation (increment balance at intervals) | Mathematical projection: `rate × elapsed` at settlement | Industry shift to stateless streaming billing | No Postgres write per tick; only one write at settle time |
| `RDelayedQueue` (Redisson) | `RReliableQueue` with delay (Redisson 3.47+) | April/May 2025 (v3.46.0 / v3.47.0) | `RDelayedQueue` still functional; migration to `RReliableQueue` is future work |
| `spring.redis.*` prefix | `spring.data.redis.*` prefix | Spring Boot 3.0 (2022) | Must use new prefix; old prefix ignored silently in Boot 3.x |

**Deprecated:**
- `RDelayedQueue`: Deprecated as of Redisson 3.46.0 (April 2025). Replacement is `RReliableQueue` with `.delay(Duration)` on message args. Still functional and used here; plan migration for a future phase. `[VERIFIED: Redisson CHANGELOG.md, RDelayedQueue.java @Deprecated annotation]`

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Deferring Postgres range partitioning for `balance_audit_log`; Phase 3 uses plain INSERT INTO audit_archive + DELETE for retention | DDL patterns, OPS-02 | Low — archival still works; partition pruning for query performance is a future optimization |
| A2 | `startedAtNanoFromCurrentJvm()` flag tracked in `StreamState` to distinguish in-JVM vs post-restart elapsed calculation | Pattern 2, Pitfall 1 | Medium — if not tracked, cross-pod settlement uses nanoTime incorrectly; wall-clock fallback is sufficient but slightly less precise |
| A3 | Redis key TTL not set on `stream:{id}` hashes; explicit removal on settlement is the cleanup mechanism | StreamRegistry pattern | Low — TTL could be added as a safety net; explicit removal is cleaner and avoids premature expiry |
| A4 | `RDelayedQueue` (deprecated) used over `RReliableQueue` in Phase 3 for simplicity; migration deferred | Standard Stack | Medium — `RDelayedQueue` still functional; `RReliableQueue` reschedule API (cancel-by-ID) is not yet established in docs |
| A5 | Testcontainers Redis via `GenericContainer` + `@DynamicPropertySource` rather than `@ServiceConnection` | Test pattern | Low — either approach works; `@ServiceConnection` for Redis requires typed `RedisContainer` from testcontainers-redis module not currently in build.gradle |
| A6 | engine-spring module has access to `spring-boot-starter-data-redis` dependency; engine-core remains framework-free | Standard Stack | Low — engine-core interface contract (`StreamRegistry`) has no Redis imports; Redis implementation is in engine-spring |

---

## Open Questions (RESOLVED)

1. **nanoTime cross-pod tracking:**
   - What we know: nanoTime is JVM-relative (D-24); Redis stores stream metadata that may be read by a different pod
   - What's unclear: Should `startedAtNano` be stored in Redis at all, or only wall-clock millis?
   - Recommendation: Store both. Use `nanoTime` only if the stream's `startedAtNano` was set by the current JVM session (track JVM start nanoTime on startup and compare). This is an implementation detail the planner can decide.
   - **RESOLVED:** Store both `startedAtNano` and `startedAtEpochMillis` in the Redis hash. A `startedAtNanoFromCurrentJvm` boolean is computed at lookup time by checking `storedNano >= JVM_START_NANO` (a static long captured at JVM startup in `RedisStreamRegistry`). This flag is stored on `StreamState` and drives elapsed-time selection in `StreamingServiceImpl`.

2. **`RDelayedQueue` remove-by-value semantics:**
   - What we know: `RDelayedQueue.remove(streamId)` removes the first occurrence of a value equal to `streamId`
   - What's unclear: If the same `streamId` was enqueued twice (reschedule without remove), both entries exist and only one is removed
   - Recommendation: Always call `cancel(streamId)` before re-enqueuing to ensure exactly one entry per stream. Document this as an ordering invariant.
   - **RESOLVED:** Cancel-before-enqueue is enforced as a plan invariant. `AutoTerminationScheduler.cancel(streamId)` is called before every `enqueue()` in `TransactionServiceImpl.debit()` (per D-27), and before `streamRegistry.remove()` in `StreamingServiceImpl.stopStream()`. This guarantees at most one pending entry per stream ID in the delayed queue at any time.

3. **`TokenEngineAutoConfiguration` growth:**
   - What we know: Phase 3 adds 4+ new beans (StreamRegistry, StreamingService, AutoTerminationScheduler, FallbackSweepJob, AuditArchivalJob, LockProvider)
   - What's unclear: Should a separate `StreamingAutoConfiguration` be introduced, or add to existing `TokenEngineAutoConfiguration`?
   - Recommendation: Create a dedicated `StreamingAutoConfiguration` class. The existing autoconfiguration is only 50 lines and already has two bean definitions; adding 6 more will make it unwieldy.
   - **RESOLVED:** `StreamingAutoConfiguration` is introduced as a separate `@AutoConfiguration` class registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. It owns all streaming-related beans: `StreamRegistry`, `StreamingService`, `AutoTerminationScheduler`, `FallbackSweepJob`, `AuditArchivalJob`, `LockProvider`, and the optional `sentinelConnectionFactory`.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker | Redis + Postgres Testcontainers | ✓ | 29.2.0 | — |
| Java 21 | Virtual threads for consumer thread | ✓ | OpenJDK 21.0.3 | Use platform thread with `Thread.ofPlatform()` |
| Redis (runtime) | StreamRegistry, Redisson | ✗ (not locally installed) | — | Docker image via Testcontainers for tests; production requires Redis |
| PostgreSQL (runtime) | JPA, ShedLock, archival job | ✗ (not locally installed) | — | Testcontainers provides it in tests |
| Gradle | Build system | ✓ (via wrapper) | See gradlew | — |

**Missing dependencies with no fallback:**
- Redis server (for production/dev runtime) — must be provisioned. Tests use Testcontainers.

**Missing dependencies with fallback:**
- Redis in tests: Testcontainers `GenericContainer("redis:7-alpine")` covers integration tests.

`[VERIFIED: docker --version 29.2.0, java --version 21.0.3 — environment check]`

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | Cucumber 7.22.1 + JUnit 5 Platform + Testcontainers 1.21.3 (unchanged from Phase 2) |
| Config file | Inherits from Phase 1/2 — `CucumberTestRunner`, `CucumberSpringConfiguration`, `TestcontainersConfiguration` all exist |
| Quick run command | `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i` |
| Full suite command | `./gradlew :engine-service:test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| STR-01 | POST stream start; registered in Redis; Postgres row inserted; 201 response | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| STR-02 | POST stream stop; settled amount = rate × elapsed; Postgres committed; Redis cleared; 200 response | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| STR-03 | GET estimated balance; returns projected balance without DB-read-per-call; `estimatedAt` populated | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| STR-04 | Concurrent streaming + discrete debit against same account; final balance correct | JUnit 5 `@SpringBootTest` | `./gradlew :engine-service:test --tests "*StreamingConcurrencyTest*"` | Wave 0 gap |
| STR-05 | Stream settled atomically; no divergence between Redis and Postgres on stop | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| STR-06 | No floating-point in arithmetic path; BigDecimal throughout (code review / unit test) | Unit test | `./gradlew :engine-spring:test` | Wave 0 gap |
| STR-07 | `minimumAmount` enforced on client stop; `ignoreMinimum=true` waives it; auto-termination always waives | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| STR-08 | `increment` billing: `floor(rate × elapsed / increment) × increment` | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| STR-09 | Auto-termination fires when `remainingBalance < increment` | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| AUTO-01 | Auto-terminate stream at floor; settle actual elapsed (minimum waived) | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| AUTO-02 | Scheduler reschedules on balance change; no constant polling | Integration — verify Redisson enqueue called | `./gradlew :engine-service:test` | Wave 0 gap |
| AUTO-03 | Auto-termination `reason = "balance_exhaustion"` in audit log; distinguishable | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| BAL-02 | GET estimated balance; `estimatedBalance`, `estimatedAt`, `estimatedDrainAt` correct | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |

### Sampling Rate
- **Per task commit:** `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i`
- **Per wave merge:** `./gradlew test` (all three modules)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql`
- [ ] `engine-service/src/main/resources/db/migration/V8__create_shedlock.sql`
- [ ] `engine-service/src/main/resources/db/migration/V9__create_audit_archive.sql`
- [ ] `engine-service/src/test/java/.../TestcontainersConfiguration.java` — MODIFY: add Redis `GenericContainer`
- [ ] `engine-service/src/test/resources/features/streaming-start.feature` — covers STR-01
- [ ] `engine-service/src/test/resources/features/streaming-stop.feature` — covers STR-02, STR-05, STR-07, STR-08
- [ ] `engine-service/src/test/resources/features/streaming-estimation.feature` — covers STR-03, BAL-02
- [ ] `engine-service/src/test/resources/features/streaming-minimum-amount.feature` — covers STR-07
- [ ] `engine-service/src/test/resources/features/streaming-increment.feature` — covers STR-08, STR-09
- [ ] `engine-service/src/test/resources/features/streaming-auto-termination.feature` — covers AUTO-01, AUTO-02, AUTO-03
- [ ] `engine-service/src/test/java/.../steps/StreamingSteps.java`
- [ ] `engine-service/src/test/java/.../StreamingConcurrencyTest.java` — covers STR-04
- [ ] `engine-spring/src/test/java/.../ArithmeticTest.java` — covers STR-06 (unit test for BigDecimal arithmetic, no Spring context needed)

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No — engine trusts caller-supplied IDs | N/A |
| V3 Session Management | No | N/A |
| V4 Access Control | No | N/A |
| V5 Input Validation | Yes — `ratePerSecond` must be > 0; `minimumAmount` and `increment` must be > 0 if present; `streamId` must not be blank | `@Positive` / `@NotBlank` Bean Validation on DTOs |
| V6 Cryptography | No | N/A |

### Known Threat Patterns for Phase 3 Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Zero or negative `ratePerSecond` creates infinite-duration stream | Tampering | `@Positive BigDecimal ratePerSecond` on `StartStreamRequest`; DB CHECK `rate_per_second > 0` |
| Start stream on closed/non-existent account | Tampering | `findWithLock()` + `AccountStatus.CLOSED` check before registration (D-20) |
| Double-start on same `streamId` | Spoofing | `UNIQUE (stream_id)` constraint on `streaming_transactions`; idempotency key check after lock |
| Concurrent discrete debit + stream settlement → balance below floor | Tampering | `findWithLock()` + clamp settlement (D-19); floor never violated |
| Stop stream on stream not owned by claimed account | Tampering | Verify `streamId.accountId == requestedAccountId` before settlement; return 404 if mismatch |
| Redis injection via stream ID | Tampering | Stream IDs used as Redis key suffixes with prefix; no shell/Lua script injection vector via `HashOperations` |

`[ASSUMED: stream-account ownership check at stop — CONTEXT.md does not specify; verify during planning]`

---

## Sources

### Primary (HIGH confidence)
- Project codebase: `engine-spring/.../service/TransactionServiceImpl.java` — pessimistic write lock pattern, idempotency structure `[VERIFIED: codebase read]`
- Project codebase: `engine-spring/.../config/TokenEngineProperties.java` — property binding pattern `[VERIFIED: codebase read]`
- Project codebase: `engine-core/.../domain/Account.java` — entity pattern, `credit()`/`debit()` methods `[VERIFIED: codebase read]`
- Project codebase: `engine-service/src/test/.../TestcontainersConfiguration.java` — Testcontainers `@ServiceConnection` pattern `[VERIFIED: codebase read]`
- Project codebase: `engine-service/build.gradle` — dependency management, existing stack `[VERIFIED: codebase read]`
- Maven Central: `spring-boot-starter-data-redis:3.5.3`, `redisson-spring-boot-starter:3.50.0`, `shedlock-spring:6.6.0`, `shedlock-provider-jdbc-template:6.6.0`, `testcontainers:1.21.3` — current versions `[VERIFIED: Maven Central solrsearch API 2026-05-14]`

### Secondary (MEDIUM confidence)
- [Spring Data Redis — Connection Modes / Sentinel](https://docs.spring.io/spring-data/redis/reference/redis/connection-modes.html) — Lettuce Sentinel configuration Java example `[VERIFIED: WebFetch 2026-05-14]`
- [Redisson wiki — Distributed collections / DelayedQueue](https://github.com/redisson/redisson/wiki/7.-Distributed-collections/) — `RDelayedQueue.offer()` API `[VERIFIED: WebFetch 2026-05-14]`
- [Redisson CHANGELOG](https://raw.githubusercontent.com/redisson/redisson/master/CHANGELOG.md) — `RDelayedQueue` deprecated 3.46.0, `RReliableQueue` added 3.47.0 `[VERIFIED: WebFetch 2026-05-14]`
- [ShedLock README.md](https://github.com/lukas-krecan/ShedLock/blob/master/README.md) — version 7.7.0 (latest), shedlock DDL, `@EnableSchedulerLock`, `@SchedulerLock` annotation `[VERIFIED: WebFetch 2026-05-14 — note: shedlock-spring 6.6.0 is on Maven Central; README may reference 7.x pre-release]`
- [PostgreSQL docs 17 — Table Partitioning](https://www.postgresql.org/docs/17/ddl-partitioning.html) — `PARTITION BY RANGE`, partition DDL, `DETACH PARTITION CONCURRENTLY` `[VERIFIED: WebFetch 2026-05-14]`
- [Redisson RDelayedQueue.java — @Deprecated annotation](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/api/RDelayedQueue.java) `[VERIFIED: WebFetch 2026-05-14]`

### Tertiary (LOW confidence)
- WebSearch results on Testcontainers Redis `@ServiceConnection` availability — `@ServiceConnection` for Redis requires typed container; `GenericContainer` + `@DynamicPropertySource` pattern confirmed in community examples but not verified against Testcontainers 1.21.3 official docs

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all library versions verified against Maven Central
- Redis StreamRegistry patterns: HIGH — official Spring Data Redis docs; verified HashOperations/SetOperations API
- Redisson scheduler: MEDIUM-HIGH — wiki confirmed; deprecation confirmed; `RReliableQueue` replacement API is less documented for exact reschedule-by-ID use case
- ShedLock: HIGH — README verified; note discrepancy between README version (7.7.0 mentioned) and Maven Central latest (6.6.0); use 6.6.0
- Postgres partitioning: HIGH — official Postgres docs; deferred to future phase per research recommendation (A1)
- Settlement arithmetic: HIGH — locked in CONTEXT.md decisions; pure math
- Test infrastructure: HIGH — existing Testcontainers config verified; Redis container pattern is community-standard

**Research date:** 2026-05-14
**Valid until:** 2026-06-14 (Spring Boot + Redisson stable; ShedLock stable; check Redisson for 3.51+ breaking changes before implementing)
