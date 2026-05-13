# Stack Research

**Domain:** Real-time token economy engine (concurrent streaming ledger, multi-party sessions, in-memory hot state, Postgres durability)
**Researched:** 2026-05-13
**Confidence:** HIGH (core primitives and Spring ecosystem); MEDIUM (DB tuning specifics); LOW (off-heap alternatives)

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Java | 21 LTS | Runtime | Minimum for virtual threads (Project Loom). Virtual threads are stable and production-ready since Java 21; required for `spring.threads.virtual.enabled=true`. Java 25 is the next LTS but 21 is the safe choice for library compatibility. |
| Spring Boot | 3.5.x | Application framework | 3.5.0 GA released May 2025, latest stable 3.x line. Spring Boot 4.0 (Nov 2025) requires Java 17+ and is production-ready but ecosystem library compatibility (autoconfigure starters, third-party integrations) is still maturing. Use 3.5.x for greenfield library packaging in 2025. |
| Spring Web MVC | (bundled with Boot 3.5) | REST API layer | Imperative MVC + virtual threads gives equivalent throughput to WebFlux without reactive complexity. For a token engine that is I/O-bound (DB writes) rather than CPU-bound, virtual threads are the correct model. WebFlux is not recommended — see "What NOT to Use". |
| PostgreSQL | 17 | Durable event log / audit ledger | PG17 introduces partition-wise aggregate pushdown (up to 70% reduction in aggregate query cost), improved partition pruning, and multi-level declarative partitioning without extensions. Production-grade event sourcing fits well at <10K events/second, which covers this domain. |
| Spring Data JDBC | 3.5.x | Database access layer | Thin SQL-centric layer with no persistence context overhead. 20-30% better insert throughput vs JPA for bulk appends. Provides repository abstraction without Hibernate session management, dirty-checking, or L1 cache complexity — all of which are liabilities for append-only ledger writes. |
| Flyway | 10.x | Schema migration | SQL-first migrations, zero-config Spring Boot autoconfigure, minimal setup for single-Postgres deployments. Correct default for this domain. |

### In-Memory Concurrency Primitives

| Primitive / Library | Version | Purpose | Why This Choice |
|---------------------|---------|---------|-----------------|
| `ConcurrentHashMap<SessionId, SessionState>` | JDK 21 stdlib | Hot session index | CAS-based internals (no global lock since Java 8). Segment-based contention scales to high thread counts. Use as primary index for in-flight session state objects. |
| `AtomicLong` | JDK 21 stdlib | Per-balance discrete mutation (compare-and-swap) | Use for balance fields where correctness requires read-modify-write atomicity and contention is low-to-moderate. CAS retry on failure is the correct pattern. Benchmark: 680ms vs LongAdder's 74ms at 100 threads — swap to LongAdder if profiling shows contention. |
| `LongAdder` | JDK 21 stdlib | High-contention streaming accumulators | Designed for high-frequency accumulation with multiple writers. Maintains per-cell counters, aggregates on `sum()`. Use for session-level streaming byte/token accumulators where many readers and writers hit the same counter. Does NOT support compare-and-swap — only for accumulation, not conditional update. |
| `StampedLock` | JDK 21 stdlib | Composite session state reads | Use the `tryOptimisticRead()` / `validate(stamp)` pattern when session state objects have multiple fields that must be read consistently. Requires fallback to write lock on validation failure. Do not use if write rate is high (optimistic reads fail frequently). Not reentrant — design carefully. |
| `ReentrantReadWriteLock` | JDK 21 stdlib | Complex state transitions (pause/resume/end) | Use for lifecycle state machines where the write path is complex (e.g., session pause atomically stops all in-flight streams). Simpler correctness model than StampedLock when write operations are non-trivial. |

### Postgres Schema Patterns

| Pattern | Purpose | Rationale |
|---------|---------|-----------|
| Append-only `token_events` table partitioned `RANGE` on `created_at` (monthly) | Event ledger durability | Monthly partitions enable: (1) independent VACUUM per partition, (2) efficient archival of old data, (3) partition pruning eliminates irrelevant time ranges from scans. PG17 declarative syntax handles routing automatically. |
| `BRIN` index on `created_at` | Time-range event queries | BRIN stores summary per block range, not per row. For append-only, time-correlated data, BRIN index size is megabytes vs gigabytes for B-Tree equivalents. Dramatically lower insert overhead. Not suitable for point lookups by ID — pair with a B-Tree on `(session_id, sequence_number)`. |
| `BTREE` index on `(session_id, sequence_number)` | Per-session event replay | Required for dispute resolution / recovery — fetching all events for a session in order. Must be B-Tree for point lookups and range scans by session. |
| Optimistic locking with `sequence_number` unique constraint | Prevent duplicate appends | `UNIQUE(session_id, sequence_number)` at DB level prevents concurrent duplicate inserts without serializable transactions. Application increments sequence via CAS before DB write; DB enforces uniqueness as safety net. |
| `synchronous_commit = off` for the event writer connection pool | Write throughput | Allows Postgres to acknowledge the write after WAL is buffered but before it is flushed to disk. Acceptable for hot-path event appends when in-memory state is authoritative; the audit log catches up asynchronously. Risk: a crash can lose the last few buffered events — acceptable if in-memory state survives restart (not acceptable if the DB is the recovery source). Choose based on recovery architecture. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Caffeine | 3.x | On-heap session state cache (L1) | Per-JVM bounded cache for session state objects with LRU eviction. Sub-microsecond reads with no network hop. Use as the primary hot-state store for active sessions. Caffeine 3.x is the de-facto Java in-process cache and is Spring Cache's recommended local provider. |
| HikariCP | 5.x (bundled with Boot) | JDBC connection pool | Lowest-latency JDBC pool, default in Spring Boot. No change needed — Boot auto-configures it. Size the pool to match virtual thread concurrency (target: pool size = DB max_connections budget per service instance). |
| Micrometer | 1.13.x (bundled with Boot 3.5) | Metrics: balance mutation rates, session counts, event append latency | Boot autoconfigures Micrometer. Register custom meters for: active session count, streaming balance drain rate, event append p99, CAS retry rate. |
| Spring Boot Actuator | 3.5.x | Health, metrics, readiness probe | Standard operational endpoint. Enable `management.endpoints.web.exposure.include=health,metrics,info`. |
| Jackson | 2.17.x (bundled) | Metadata parameter map serialization (caller-supplied open metadata) | Use `JsonNode` or `Map<String, Object>` for the open metadata field stored in event payload. Jackson is Boot's default; no extra dependency. |
| Flyway Core | 10.x | Schema migration | SQL versioned migrations in `db/migration/`. Boot auto-runs on startup. |

### Autoconfigure Library Packaging

| Convention | Detail | Why |
|------------|--------|-----|
| Module split: `token-engine-autoconfigure` + `token-engine-starter` | `autoconfigure` jar contains `@Configuration` classes and `@ConfigurationProperties`. `starter` jar has zero code — only a `pom.xml` that depends on `autoconfigure` + `spring-boot-starter`. | Standard Spring Boot starter convention. Callers include only the starter; the autoconfigure module is a transitive dependency. Matches Spring's own pattern (e.g., `spring-boot-autoconfigure` + `spring-boot-starter-web`). |
| Registration file: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | One fully-qualified class name per line for each `@AutoConfiguration` class | Required mechanism since Spring Boot 2.7+. The old `spring.factories` approach still works for backward compatibility but is deprecated. Boot 3.x uses only the `.imports` file. |
| `spring-boot-autoconfigure-processor` annotation processor | Add as `optional` dependency in `autoconfigure` module | Generates `META-INF/spring-autoconfigure-metadata.properties` at compile time. Allows Boot to eagerly filter non-matching auto-configurations during startup, reducing startup time. Without this, Boot must load and evaluate every `@Conditional` at runtime. |
| `@ConditionalOnMissingBean` on all autoconfigured beans | Caller override safety | Every bean the library exposes must be wrapped in `@ConditionalOnMissingBean`. This lets embedding applications replace any component without fighting the autoconfigure. |
| `@ConfigurationProperties(prefix = "token-engine")` | Externalized configuration | All tuning parameters (partition strategy, rake defaults, pool sizes) exposed as typed properties under `token-engine.*`. Pair with `spring-boot-configuration-processor` for IDE completion metadata. |

### Testing Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JUnit 5 (Jupiter) | 5.11.x (bundled with Boot 3.5) | Test harness | Standard. All test types run on the JUnit Platform. |
| JetBrains Lincheck | 3.5 | Concurrent correctness: linearizability verification | Use for every concurrent data structure (balance registry, session state map). Lincheck generates random concurrent operation schedules and verifies linearizability via model checking or stress testing. It found real bugs in `ConcurrentLinkedDeque` and `AbstractQueuedSynchronizer` — exactly the class of bugs this engine is at risk of. Artifact: `org.jetbrains.lincheck:lincheck:3.5` (testImplementation). |
| jqwik | 1.9.3 | Property-based testing: invariant verification | Use for business invariants: "balance never goes negative", "rake always sums to gross amount", "forward estimate is always >= actual consumed". jqwik integrates with JUnit 5, generates diverse inputs including edge cases. Current stable; in maintenance mode (no new features, bug fixes only). Adequate for this use case. |
| JCStress | 0.16 | Low-level concurrency stress harness | Use selectively for specific hotspot primitives (e.g., the balance CAS loop) when Lincheck's model checker is too slow for the state space. JCStress requires writing explicit outcome annotations — more verbose than Lincheck but allows finer-grained hardware-level stress. |
| Testcontainers | 1.20.x | Integration tests with real Postgres | Spin up PG17 containers for schema migration tests, partition tests, and index behavior verification. Essential — do not mock the DB for ledger correctness tests. |
| AssertJ | 3.26.x (bundled with Boot test) | Fluent assertions | Standard. No change. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Gradle (Kotlin DSL) | Build system | Preferred over Maven for multi-module library projects. Kotlin DSL provides type-safe build scripts. Spring's own autoconfigure modules use Gradle. |
| `spring-boot-configuration-processor` | IDE metadata for `@ConfigurationProperties` | Add as `annotationProcessor` in the autoconfigure module. Generates `META-INF/spring-configuration-metadata.json` for IDE autocompletion on `token-engine.*` properties. |
| pgAdmin / psql | Postgres query analysis | Use `EXPLAIN (ANALYZE, BUFFERS)` to verify BRIN vs B-Tree usage and partition pruning behavior during development. |

---

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Spring Web MVC + virtual threads | Spring WebFlux (Reactor) | Only if the service itself needs to consume reactive streams as inputs (e.g., SSE sources). For REST in/REST out with blocking JDBC, reactive adds complexity with no throughput benefit over virtual threads. |
| Spring Data JDBC | Spring Data JPA / Hibernate | When the domain has complex object graphs, bidirectional relationships, or requires lazy loading. Not this project. |
| Spring Data JDBC | JDBI 3 | JDBI is excellent for SQL-centric work and a valid alternative. Spring Data JDBC is preferred here because it integrates with Spring's transaction management, repository abstraction, and autoconfigure infrastructure more naturally for a library artifact. |
| On-heap Caffeine | Redis | Redis adds network latency (microseconds vs nanoseconds for on-heap reads) and infrastructure complexity. For a single-node service where session state is local, Caffeine is strictly superior. Add Redis only when the service scales horizontally and session state must be shared across nodes. |
| On-heap Caffeine | Off-heap (Chronicle Map, EhCache with off-heap) | Off-heap avoids GC pressure for very large datasets (>1M sessions). For this domain, active session counts are expected to be thousands-to-tens-of-thousands; on-heap Caffeine is adequate and dramatically simpler. |
| PostgreSQL | MySQL / MariaDB | No advantage for this domain. PG's declarative partitioning, BRIN indexes, and advisory locks are superior for event sourcing workloads. |
| Lincheck | Manual concurrent JUnit tests with Thread.sleep / CountDownLatch | Manual concurrent tests are unreliable (scheduling-dependent), don't exhaustively explore interleavings, and can't prove linearizability. Lincheck does all three automatically. |
| Flyway | Liquibase | Liquibase is the better choice when: deploying to multiple DB vendors, needing XML/YAML migration definitions, or requiring policy-driven rollbacks. None of these apply to a Postgres-only service. |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Hibernate / Spring Data JPA for the hot write path | JPA's persistence context (L1 cache) tracks every entity in memory, runs dirty-checking on flush, and generates unpredictable SQL. For append-only event inserts, this overhead is pure waste. First-level cache also holds references to events that should be immediately discardable. | Spring Data JDBC with `JdbcTemplate` or `NamedParameterJdbcTemplate` for batch inserts on the hot path. |
| Spring WebFlux / Project Reactor | Virtual threads on Spring MVC provides equivalent scalability for this workload without the reactive programming model, backpressure complexity, or debugging difficulty. The domain logic (balance tracking, CAS loops) is inherently imperative — wrapping it in Mono/Flux chains adds cognitive overhead with zero architectural benefit. | Spring Web MVC with `spring.threads.virtual.enabled=true` |
| `synchronized` blocks on balance operations | `synchronized` acquires a monitor lock and pins the virtual thread's carrier thread during contention, defeating virtual thread scalability. Also has coarser granularity than necessary. | `AtomicLong.compareAndSet()` for single-field updates; `StampedLock` for multi-field composite reads; `ReentrantReadWriteLock` for lifecycle transitions. |
| `LongAdder` for conditional balance checks | `LongAdder` supports only accumulation and `sum()` — it has no compare-and-swap. A "deduct only if balance sufficient" check cannot be implemented correctly with LongAdder alone. | `AtomicLong.compareAndSet()` in a CAS retry loop for conditional deductions. |
| Redis as primary hot-state store (v1) | Adds network hop (microseconds) to every balance read and write. For a correctness-first engine where balance checks happen on every streaming tick, this latency compounds. Also adds infrastructure dependency. | On-heap `ConcurrentHashMap` + Caffeine for session state. Postgres for durable state. Add Redis only if horizontal scaling of the stateful layer is required. |
| jqwik for concurrent correctness testing | jqwik is single-threaded property generation — it cannot explore concurrent interleavings or verify linearizability. Using it for concurrent correctness gives false confidence. | Lincheck for concurrent structure verification. jqwik is still valuable for single-threaded invariant properties (rake arithmetic, balance arithmetic). |
| Spring Boot 4.0 (for v1) | Spring Boot 4.0 was released November 2025 and requires Java 17+. The `spring-boot-autoconfigure` API surface changed (modularized jars). Third-party library compatibility with Boot 4.0 is still maturing. For a greenfield library that must be embeddable in existing Boot 3.x applications (the project's stated deployment model), targeting Boot 3.5.x is the pragmatic choice. | Spring Boot 3.5.x. Migrate to 4.x in a future milestone once the ecosystem stabilizes. |

---

## Stack Patterns by Variant

**Standalone service artifact (Spring Boot fat JAR):**
- Include `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-data-jdbc`
- `spring.threads.virtual.enabled=true` in `application.properties`
- Flyway runs on startup, manages schema
- Micrometer + Actuator exposes `/actuator/metrics`

**Embeddable library artifact (Spring Boot autoconfigure starter JAR):**
- `token-engine-autoconfigure` module: `@AutoConfiguration`, `@ConfigurationProperties`, all beans with `@ConditionalOnMissingBean`
- `token-engine-starter` module: zero code, declares dependency on `autoconfigure` + `spring-boot-starter`
- Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Include `spring-boot-autoconfigure-processor` as `optional` compile-time dependency
- Flyway migrations shipped as classpath resources; embedding app controls whether to run them (use `@ConditionalOnProperty(prefix="token-engine", name="auto-migrate", havingValue="true", matchIfMissing=true)`)

**If horizontal scaling is required (future):**
- Replace Caffeine with Redis (Lettuce client, Spring Data Redis)
- Use Redis sorted sets for session balance state with Lua scripts for atomic balance checks
- Add sticky routing at the load balancer to minimize cross-node session access

---

## Version Compatibility

| Package | Compatible With | Notes |
|---------|-----------------|-------|
| Spring Boot 3.5.x | Java 21 LTS | Java 21 required for virtual threads (`spring.threads.virtual.enabled=true`) |
| Spring Boot 3.5.x | Spring Data JDBC 3.5.x | Same version family, managed by Boot BOM |
| Spring Boot 3.5.x | Flyway 10.x | Boot 3.5 BOM manages Flyway version; override only if a newer Flyway patch is needed |
| Lincheck 3.5 | JVM 21 | Group ID changed to `org.jetbrains.lincheck` (was `org.jetbrains.kotlinx`) in 3.x — update Maven coordinates if migrating from 2.x |
| jqwik 1.9.3 | JUnit 5.11.x | Requires Maven Surefire 2.22.0+ for JUnit Platform support |
| Testcontainers 1.20.x | Spring Boot 3.5 | Boot 3.1+ has `@ServiceConnection` annotation for zero-config Testcontainers integration |
| Caffeine 3.x | Java 11+ | Caffeine 3.x dropped Java 8 support; compatible with Java 21 |

---

## Maven / Gradle Coordinates (Key Dependencies)

```xml
<!-- Lincheck (testing) -->
<dependency>
    <groupId>org.jetbrains.lincheck</groupId>
    <artifactId>lincheck</artifactId>
    <version>3.5</version>
    <scope>test</scope>
</dependency>

<!-- jqwik (testing) -->
<dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>1.9.3</version>
    <scope>test</scope>
</dependency>

<!-- Caffeine (managed by Boot BOM, explicit if needed) -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>

<!-- Spring Boot autoconfigure processor (optional, compile-time only) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-autoconfigure-processor</artifactId>
    <optional>true</optional>
</dependency>

<!-- Spring Boot configuration processor (for @ConfigurationProperties IDE support) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

---

## Sources

- [Spring Boot 3.5.0 GA release announcement](https://spring.io/blog/2025/05/22/spring-boot-3-5-0-available-now/) — version confirmed HIGH confidence
- [Spring Boot 3.2 Release Notes (virtual threads)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.2-Release-Notes) — `spring.threads.virtual.enabled` confirmed HIGH confidence
- [Spring.io: Embracing Virtual Threads](https://spring.io/blog/2022/10/11/embracing-virtual-threads/) — imperative vs reactive recommendation HIGH confidence
- [Spring Boot Creating Auto-configuration (official docs)](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html) — `.imports` file, processor, `@ConditionalOnMissingBean` HIGH confidence
- [Spring Boot 4.0.0 release announcement](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/) — Boot 4.0 status verified, MEDIUM confidence on ecosystem maturity
- [JetBrains Lincheck GitHub](https://github.com/JetBrains/lincheck) — version 3.5, new artifact coordinates HIGH confidence
- [jqwik.net](https://jqwik.net/) — version 1.9.3, maintenance mode status HIGH confidence
- [Postgres BRIN vs B-Tree (Crunchy Data)](https://www.crunchydata.com/blog/postgres-indexing-when-does-brin-win) — BRIN for append-only time-series MEDIUM confidence
- [Write Amplification in Postgres (Tiger Data)](https://www.tigerdata.com/blog/write-amplification-in-postgres-the-3-4x-tax-on-every-insert) — BRIN index overhead reduction MEDIUM confidence
- [PostgreSQL 17 partitioning deep dive](https://johal.in/deep-dive-postgresql-17-partitioning-optimize-queries-1b/) — PG17 partition improvements MEDIUM confidence
- [Spring Data JPA vs JDBC (Reintech)](https://reintech.io/blog/spring-data-jpa-vs-spring-data-jdbc) — throughput differential MEDIUM confidence
- [LongAdder vs AtomicLong benchmark](https://macronepal.com/2025/11/04/taming-the-chaos-using-longadder-for-high-contention-counters-in-java/blog/) — 680ms vs 74ms at 100 threads MEDIUM confidence (single source)
- [StampedLock optimistic read pattern](https://medium.com/@apusingh1967/low-latency-programming-stampedlock-is-the-champion-a8b07f8c95be) — cache line bouncing rationale MEDIUM confidence
- [Caffeine vs Redis comparison (Java Code Geeks)](https://www.javacodegeeks.com/2025/10/save-the-day-and-memory-java-caching-strategies-using-caffeine-and-redis.html) — on-heap vs distributed tradeoffs HIGH confidence
- [Building a Production-Ready Event Store in PostgreSQL (DEV)](https://dev.to/tim_derzhavets/building-a-production-ready-event-store-in-postgresql-schema-design-projections-and-replay-25o8) — schema patterns MEDIUM confidence
- [Flyway vs Liquibase (Baeldung)](https://www.baeldung.com/liquibase-vs-flyway) — recommendation rationale HIGH confidence

---

*Stack research for: Real-time token economy engine (Java / Spring Boot)*
*Researched: 2026-05-13*
