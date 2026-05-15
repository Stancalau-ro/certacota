---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Phase 04.1 context gathered
last_updated: "2026-05-15T07:09:05.877Z"
last_activity: 2026-05-14 -- Phase 4 complete (04-04 done)
progress:
  total_phases: 7
  completed_phases: 4
  total_plans: 14
  completed_plans: 14
  percent: 57
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-05-13)

**Core value:** Correct, real-time token accounting across concurrent mixed transaction types in multi-party sessions
**Current focus:** Phase 4 — Tags, Rake on Streaming, and Threshold Events

## Current Position

Phase: 4 of 6 (Tags, Rake on Streaming, and Threshold Events)
Plan: 4 of 4 in current phase (04-04 complete — Phase 4 DONE)
Status: Executing
Last activity: 2026-05-14 -- Phase 4 complete (04-04 done)

Progress: [██████████] 100%

## Performance Metrics

**Velocity:**

- Total plans completed: 1
- Average duration: 6 min
- Total execution time: 0.1 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Foundation | 2/3 | 18 min | 9 min |

**Recent Trend:**

- Last 5 plans: 01-01 (6 min), 01-02 (12 min)
- Trend: domain layer slightly longer due to dependency resolution deviations

*Updated after each plan completion*
| Phase 01-foundation P03 | 90 | 2 tasks | 19 files |

## Accumulated Context

### Roadmap Evolution

- Phase 04.1 inserted after Phase 4: Performance, Concurrency, and Disaster Recovery Integration Test Suite (URGENT)

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Init]: All 6 features included in v1 — features are interdependent; a partial engine is not production-usable
- [Init]: REST-only API in v1; streaming estimation served on-demand via GET, not push
- [Init]: In-memory hot state (StreamRegistry) + Postgres for durable audit and settlement
- [Init]: Session lifecycle replaced by tag-based grouping; pause/resume removed from scope
- [01-01]: Spring Boot 3.5.3 used (D-02 specified 3.4.x; 3.4 OSS support ended 2025-12-31; 3.5.3 is drop-in compatible)
- [01-01]: flyway-database-postgresql required as separate dependency alongside flyway-core (Flyway 10+ split Postgres support)
- [01-01]: @ServiceConnection handles datasource injection in tests — no spring.datasource.url in any properties file
- [01-02]: Java records used for DTOs (CreateAccountRequest, AccountResponse) — immutable, no Lombok needed
- [01-02]: hibernate-core compileOnly added to engine-core for @JdbcTypeCode/@SqlTypes annotation resolution
- [01-02]: jackson-databind added to engine-spring for ObjectMapper injection in AccountServiceImpl
- [Phase ?]: Check-then-insert idempotency: findByIdempotencyKeyAndOperation before doCreateAccount avoids DataIntegrityViolationException poisoning the outer transaction
- [Phase ?]: [01-03]: Jackson UTC normalization required for deterministic idempotency JSON body comparison
- [Phase ?]: [01-03]: DOCKER_HOST=tcp://localhost:2375 + api.version=1.44 required for Docker Desktop 4.60+ Testcontainers compatibility
- [Phase ?]: [01-03]: @EntityScan + @EnableJpaRepositories required on EngineServiceApplication to discover engine-core JPA classes
- [03-02]: StringRedisTemplate used instead of RedisTemplate<String,String> to avoid Spring Boot bean ambiguity with autoconfigured RedisTemplate<Object,Object>
- [03-02]: transactionId omitted from streaming audit log entries — fk_audit_dtx FK only covers discrete_transactions table
- [03-03]: AutoTerminationScheduler uses @Lazy @Autowired on StreamingService injection to break circular dependency with StreamingServiceImpl
- [03-03]: OPS-02 resolved — AuditArchivalJob and idempotency TTL sweep implemented with ShedLock guards
- [04-01]: jackson-databind added as compileOnly to engine-core — keeps engine-core framework-light while enabling MetadataConverter compilation
- [04-01]: Account.metadata also retrofitted from @JdbcTypeCode to @Convert — all three entity metadata fields now portable
- [04-01]: Spring Boot 3.5 @TransactionalEventListener requires @Transactional(NOT_SUPPORTED) propagation on methods in @Transactional-annotated classes
- [04-02]: V12 migration created in plan 04-02 (not 04-03) because StreamingTransaction entity mappings (toAccountId, rakeRate, platformAccountId) were added in Task 1; schema and entity must match on first context load
- [04-02]: endByTag is stubbed (UnsupportedOperationException) in TagServiceImpl — full implementation deferred to plan 04-04 which owns bulk end-by-tag and TagTtlCleanupJob
- [04-02]: TagAutoConfiguration conditionally exposes TagService bean with @ConditionalOnMissingBean allowing host app override
- [04-02]: TransactionController /credit, /debit, /transfer sub-endpoints accept accountId in body (not path) to carry tags alongside amount
- [04-03]: stopStream lock order from→to→platform mirrors TransactionServiceImpl.transfer() exactly; prevents deadlock in concurrent rake stream settlements
- [04-03]: tag total_credited_recipient set to toAccountAmount (not settledAmount) so derived totalRaked = totalDebited - totalCreditedRecipient holds per D-07
- [04-03]: D-13 fallback — stopStream reads rake fields from Postgres when Redis StreamState lacks them
- [04-04]: All-or-nothing @Transactional for endByTag — no try/catch around inner stopStream(); exception rolls back entire batch including pending idempotency key for clean retry (TAG-02, Pitfall 1)
- [04-04]: doCleanup() package-private split in TagTtlCleanupJob — IT calls doCleanup() to bypass assertLocked(); runCleanup() delegates after assertLocked() for production use
- [04-04]: endByTag uses ignoreMinimum=false — client-initiated stop, not auto-termination; minimumAmount semantics apply normally per STR-07

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 6 risk]: Embedding test must verify the starter inside a synthetic host application with no bean collisions — requires careful @ConditionalOnMissingBean coverage in engine-spring.

## Deferred Items

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Transactions | TX-REV-01: Reversal entries | v2 | Init |
| Transactions | TX-HIST-01: Paginated history | v2 | Init |
| Tags | TAG-HIST-01: Historical aggregate query | v2 | Init |
| Observability | OBS-01: OpenTelemetry trace context | v2 | Init |
| Clients | CLT-01: Typed Java client library | v2 | Init |
| Operations | OPS-02: Data retention — tables must not grow infinitely. balance_audit_log needs Postgres range partitioning on recorded_at with partition pruning; idempotency_keys needs a TTL sweep (delete rows older than replay window, e.g. 48h); closed accounts need a soft-archive or hard-delete policy. Must be resolved before Phase 3 (streaming generates high-volume audit log entries). | pre-Phase-3 | Phase 1 |
| Architecture | PKG-EXT-01: Repository port interfaces for enterprise swapping — engine-core repos currently extend JpaRepository directly; for clean enterprise-tier override (JDBC, custom ORM) they should be plain port interfaces with JPA adapters in engine-spring guarded by @ConditionalOnMissingBean | Phase 6 | Phase 1 |

## Session Continuity

Last session: 2026-05-15T07:09:05.870Z
Stopped at: Phase 04.1 context gathered
Resume file: .planning/phases/04.1-performance-concurrency-and-disaster-recovery-integration-te/04.1-CONTEXT.md
