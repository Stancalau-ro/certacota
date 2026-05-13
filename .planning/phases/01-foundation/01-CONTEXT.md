# Phase 1: Foundation - Context

**Gathered:** 2026-05-13
**Status:** Ready for planning

<domain>
## Phase Boundary

Greenfield Spring Boot project scaffold delivering:
- Participant account CRUD (create with initial balance + metadata, retrieve with committed balance, close)
- Balance floor enforcement (configurable global default with per-account override)
- Idempotency on all write operations via DB UNIQUE constraint on caller-supplied idempotency key
- Immutable append-only audit log entry for every balance change
- Spring Actuator health endpoint + Micrometer/Prometheus metrics endpoint
- Testcontainers integration test confirming Actuator UP and Postgres connectivity

This phase produces the foundational infrastructure every subsequent phase builds on. No streaming, no discrete transactions beyond account funding — those are Phase 2+.

</domain>

<decisions>
## Implementation Decisions

### Build System
- **D-01:** Gradle with Groovy DSL (not Kotlin DSL, not Maven)
- **D-02:** Spring Boot 3.4.x
- **D-03:** Java 21
- **D-04:** Flyway for database schema migrations (SQL-first, V{n}__{description}.sql convention)

### Module Structure
- **D-05:** Multi-module Gradle project from day 1: `engine-core`, `engine-spring`, `engine-service`
  - `engine-core`: domain entities, repository interfaces, service interfaces, domain exceptions — zero Spring or web framework dependencies
  - `engine-spring`: Spring autoconfigure beans, `@ConditionalOnMissingBean` wiring, `token-engine.*` property binding
  - `engine-service`: `@SpringBootApplication` main class, REST controllers, Testcontainers integration tests
- **D-06:** All three modules are scaffolded and wired by Phase 1 end; domain logic lives in `engine-core`; `engine-service` is the runnable entry point and integration test home

### Account Identity
- **D-07:** Caller-supplied account ID — the platform passes its own participant reference (any opaque String); engine stores it as the primary key (VARCHAR); no engine-generated UUID
- **D-08:** Account creation request carries: `id` (String, required), `initialBalance` (BigDecimal, optional, default 0), `metadata` (key-value map, optional) — metadata is immutable after creation; stored as JSONB
- **D-09:** Account metadata follows the same open key-value map pattern as transaction metadata (META-01/META-02 in Phase 2)

### Balance Representation and Floor
- **D-10:** All token amounts (balance, floor, initial balance) stored as `BigDecimal` in Java / `NUMERIC(38,18)` in Postgres — consistent with Phase 3's STR-06 requirement (BigDecimal for all rate arithmetic)
- **D-11:** Balance floor: global engine property `token-engine.balance-floor` (default `0`) plus optional per-account override
  - Account creation accepts optional `balanceFloor` (BigDecimal); if null/omitted, the global property is used
  - Stored as a nullable `balance_floor` column; enforcement reads column value with global fallback
- **D-12:** Floor enforcement: reject any operation that would take balance below the effective floor; check occurs before the write

### Testing
- **D-13:** BDD with Cucumber — integration and acceptance tests use Cucumber (cucumber-spring, cucumber-junit5); feature files written in Gherkin; step definitions live in `engine-service` alongside other integration tests
- **D-14:** Cucumber feature files cover the Phase 1 success criteria scenarios (account CRUD, idempotency, floor enforcement, audit log, Actuator UP)

### Claude's Discretion
- Package naming convention inside each module (e.g., `io.certacota.engine.core.*`)
- Exact Testcontainers version and `@ServiceConnection` wiring pattern
- Actuator endpoint exposure configuration in `application.yml`
- REST API path prefix (e.g., `/api/v1/accounts`)
- Idempotency key column naming and index strategy
- Cucumber report format and output directory

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements
- `.planning/REQUIREMENTS.md` §Accounts (ACCT-01, ACCT-02, ACCT-03) — account lifecycle requirements
- `.planning/REQUIREMENTS.md` §Foundation (FUND-01, FUND-02, FUND-03) — idempotency, audit log, observability
- `.planning/REQUIREMENTS.md` §Balance (BAL-01, BAL-03) — balance query and floor enforcement

### Project Context
- `.planning/PROJECT.md` §Constraints — non-negotiable constraints (Java/Spring Boot, REST-only, in-memory hot state + Postgres, correctness over throughput, no payment coupling)
- `.planning/ROADMAP.md` §Phase 1 — success criteria (5 items) that define the phase gate

### Phase 1 Success Criteria (from ROADMAP.md — reproduce here for clarity)
1. Caller can create, retrieve with committed balance, and close an account; closing with active streaming transactions is rejected with an explicit error
2. A write submitted twice with the same idempotency key returns the same result and creates exactly one audit log entry (enforced by DB UNIQUE constraint, not application code)
3. Every balance change produces an immutable append-only audit log entry visible in Postgres immediately
4. Any operation taking balance below the configured floor is rejected before the write occurs
5. Spring Actuator health returns UP; Micrometer/Prometheus metrics endpoint is reachable; Testcontainers integration test confirms both against live Postgres

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None — greenfield project; no existing source code

### Established Patterns
- None yet — Phase 1 establishes the patterns all subsequent phases follow

### Integration Points
- Phase 2 (Discrete Transactions) will add credit/debit operations on top of the account model created here
- Phase 3 (Streaming Transactions) will add StreamRegistry and forward estimation that reads from accounts created here
- Phase 6 (Dual Packaging) will finalize the `engine-core` / `engine-spring` / `engine-service` module boundary that Phase 1 scaffolds

</code_context>

<specifics>
## Specific Ideas

- Account creation, retrieval, and close are synchronous REST operations — no async needed in Phase 1
- The `initialBalance` at account creation should be audit-logged (it is a balance change from 0 to initialBalance)
- Idempotency key is caller-supplied on write operations (account creation, future Phase 2+ operations) — stored in a dedicated `idempotency_keys` table with a UNIQUE constraint; duplicate submission returns the original persisted response, not an error

</specifics>

<deferred>
## Deferred Ideas

- Metadata on accounts (JSONB) is Phase 1 scope because it's part of the account model — but metadata search/filtering is out of scope for all phases (not in requirements)
- Virtual thread configuration (Project Loom) — Java 21 is chosen, enabling virtual threads via `spring.threads.virtual.enabled=true`; whether to enable this is deferred to a performance tuning phase or can be configured at deployment time; not a Phase 1 correctness concern
- OpenTelemetry trace propagation (OBS-01) — deferred to v2
- Paginated transaction history (TX-HIST-01) — deferred to v2

</deferred>

---

*Phase: 1-Foundation*
*Context gathered: 2026-05-13*
