---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: "Completed 04-01: Phase 4 Wave 0 complete — metadata portability + test scaffolding"
last_updated: "2026-05-14T16:55:00.000Z"
last_activity: 2026-05-14 -- Phase 4 plan 01 complete
progress:
  total_phases: 6
  completed_phases: 3
  total_plans: 14
  completed_plans: 11
  percent: 54
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-05-13)

**Core value:** Correct, real-time token accounting across concurrent mixed transaction types in multi-party sessions
**Current focus:** Phase 4 — Tags, Rake on Streaming, and Threshold Events

## Current Position

Phase: 4 of 6 (Tags, Rake on Streaming, and Threshold Events)
Plan: 1 of 4 in current phase (04-01 complete)
Status: Executing
Last activity: 2026-05-14 -- Phase 4 plan 01 complete

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

Last session: 2026-05-14T16:55:00.000Z
Stopped at: Completed 04-01: Phase 4 Wave 0 complete — metadata portability + test scaffolding
Resume file: None
