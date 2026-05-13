# Phase 1: Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-13
**Phase:** 1-Foundation
**Areas discussed:** Build system, Module structure, Account identity, Balance floor config

---

## Build System

| Option | Description | Selected |
|--------|-------------|----------|
| Maven | Better multi-module support, strong Spring Boot POM heritage, verbose XML | |
| Gradle (Kotlin DSL) | Faster incremental builds, expressive config, Spring Boot Gradle plugin well-supported | |
| **Gradle (Groovy DSL)** | Groovy DSL variant of Gradle | ✓ |

**User's choice:** Gradle with Groovy DSL

---

| Option | Description | Selected |
|--------|-------------|----------|
| Spring Boot 3.4.x | Latest stable; best Testcontainers @ServiceConnection integration | ✓ |
| Spring Boot 3.3.x | Previous stable; still supported | |
| Spring Boot 3.2.x | Older 3.x line; security patches only | |

**User's choice:** Spring Boot 3.4.x

---

| Option | Description | Selected |
|--------|-------------|----------|
| Java 21 | LTS; virtual threads (Project Loom); records stable | ✓ |
| Java 17 | Previous LTS baseline; no virtual threads | |

**User's choice:** Java 21

---

| Option | Description | Selected |
|--------|-------------|----------|
| Flyway | SQL-first migrations; simple file naming; first-class Spring Boot autoconfigure | ✓ |
| Liquibase | XML/YAML/SQL changelogs with rollback support; more ceremony | |

**User's choice:** Flyway

---

## Module Structure

| Option | Description | Selected |
|--------|-------------|----------|
| Single module now, split in Phase 6 | Simpler start; Phase 6 handles restructure | |
| **Multi-module from day 1** | Root build.gradle + engine-core/engine-spring/engine-service; clean boundaries enforced from commit 1 | ✓ |
| Two modules (core + service), add engine-spring in Phase 6 | Middle ground | |

**User's choice:** Multi-module from day 1

---

| Option | Description | Selected |
|--------|-------------|----------|
| engine-core only (domain code) | Domain entities, repositories, service interfaces in engine-core; engine-spring wires them | ✓ |
| engine-core + engine-service only | Skip engine-spring in Phase 1 | |
| All three modules get stubs | Empty packages in all three | |

**User's choice:** engine-core gets domain logic; engine-spring and engine-service also scaffolded (clarified in follow-up)

---

| Option | Description | Selected |
|--------|-------------|----------|
| engine-service has main class + integration tests | Cleanest boundary separation | ✓ |
| engine-core has the test too | Blurs domain/framework boundary | |

**User's choice:** engine-service has the @SpringBootApplication main class and Testcontainers integration tests

---

## Account Identity

| Option | Description | Selected |
|--------|-------------|----------|
| Caller supplies the ID | Platform passes its own participant reference; no ID mapping layer | ✓ |
| Engine generates UUID, caller stores it | Engine assigns UUID on creation | |
| Both: engine UUID + caller externalId | Most flexible; two ID fields | |

**User's choice:** Caller supplies the ID

---

| Option | Description | Selected |
|--------|-------------|----------|
| String (opaque) | Maximum flexibility; VARCHAR primary key; no format enforcement | ✓ |
| UUID (enforced) | Engine validates UUID format on creation | |

**User's choice:** String

---

| Option | Description | Selected |
|--------|-------------|----------|
| ID + initialBalance only | Minimal account creation payload | |
| **ID + initialBalance + metadata** | Open key-value metadata map (JSONB) on the account | ✓ |
| ID only | Always starts at 0; fund via discrete credit | |

**User's choice:** ID + initialBalance + metadata

---

## Balance Floor Config

| Option | Description | Selected |
|--------|-------------|----------|
| Global engine property (default 0) | One `token-engine.balance-floor` applies to all accounts | |
| Per-account at creation | Each account has its own floor column | |
| **Both: global default + per-account override** | Global default unless caller supplies override at creation | ✓ |

**User's choice:** Both — global default with per-account override

---

| Option | Description | Selected |
|--------|-------------|----------|
| Optional field, null = global default | Account creation accepts optional `balanceFloor`; omit to use global | ✓ |
| Always required | Every creation must supply explicit floor | |

**User's choice:** Optional field, null = use global default

---

| Option | Description | Selected |
|--------|-------------|----------|
| BigDecimal / NUMERIC(38,18) | Consistent with Phase 3 streaming rate arithmetic requirement | ✓ |
| BigDecimal / NUMERIC(19,4) | 4 decimal places; sufficient for currency-like amounts | |
| Long (integer tokens only) | Incompatible with Phase 3 rate arithmetic | |

**User's choice:** BigDecimal / NUMERIC(38,18)

---

## Claude's Discretion

- Package naming convention (e.g., `io.certacota.engine.core.*`)
- Testcontainers version and `@ServiceConnection` wiring
- Actuator endpoint exposure configuration
- REST API path prefix (e.g., `/api/v1/accounts`)
- Idempotency key column naming and index strategy

## Deferred Ideas

- Virtual thread configuration (Project Loom) — Java 21 chosen; `spring.threads.virtual.enabled=true` is an option but not a Phase 1 concern
- Metadata search/filtering — not in requirements for any phase
- OpenTelemetry (OBS-01) — v2
- Paginated transaction history (TX-HIST-01) — v2
