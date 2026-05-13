# Real-Time Token Economy Engine

## What This Is

A standalone, payment-rail-agnostic token management engine for real-time multi-party token economies. It manages concurrent streaming (rate-based) and discrete (one-off) token flows across any number of participants, provides forward estimation of each participant's token position, and extracts platform rake atomically — all without knowledge of the underlying payment mechanism.

Deployed as both a standalone Spring Boot service (REST API) and an embeddable Spring Boot autoconfigure library (JAR).

## Core Value

Correct, real-time token accounting across concurrent mixed transaction types in multi-party sessions — something no off-the-shelf ledger or billing system handles.

## Requirements

### Validated

- [x] Discrete credit/debit transactions with caller-supplied metadata — validated in Phase 1 + Phase 2 (DTX-01, DTX-02, META-01, META-02)
- [x] Balance floor enforcement, including under concurrent load — validated in Phase 2 (DTX-02, DTX-03, DTX-04)
- [x] Idempotent write semantics via idempotency key — validated in Phase 1 + Phase 2
- [x] Rake-enabled atomic three-way split on discrete transactions — validated in Phase 2 (RAKE-01)
- [x] Immutable append-only audit log with full transaction linkage — validated in Phase 1 + Phase 2
- [x] Participant account lifecycle (create, balance, close) — validated in Phase 1
- [x] Spring Boot autoconfigure packaging with @ConditionalOnMissingBean — validated in Phase 1 + Phase 2

### Active

- [ ] Concurrent streaming + discrete transactions against a single balance with full correctness (no serialization, no polling)
- [ ] Multi-party session transactions: N participants with individual directional flows in one session
- [ ] Real-time forward estimation: projected balance at any moment given all in-flight streams
- [ ] Configurable rake engine: atomic three-way split (from → to → platform) per transaction type
- [ ] Session lifecycle management: start/pause/end coordinates with token flow lifecycle atomically
- [ ] Open metadata: caller-supplied parameter map flows through to all events and audit records
- [ ] Payment rail decoupling: receives issuance commands, emits redemption events — no payment processor dependency
- [ ] Standalone service artifact: deployable Spring Boot service with REST API
- [ ] Embeddable library artifact: Spring Boot autoconfigure starter (JAR) for platform embedding

### Out of Scope

- Payment processing — the engine issues and redeems tokens; actual money movement is the platform's responsibility
- Crypto/blockchain mechanics — this is an internal accounting engine, not a crypto system
- Billing and invoicing — no concept of invoices, subscription billing cycles, or payment schedules
- Authentication/authorization — the surrounding platform is responsible for identity; the engine trusts caller-supplied participant IDs
- Client UI components — the engine emits events and serves REST endpoints; UI is the platform's concern

## Context

Primary use case that drove the design: adult live cam platforms, where per-minute streaming sessions of unknown duration drain viewer balances in real time, group sessions have N concurrent paying viewers, discrete tips trigger threshold events, and rake is extracted per transfer keyed off caller metadata.

Secondary structural fits: online gambling/poker (multi-party hands, side bets, rake per pot), live interactive entertainment (auctions, tipping), prepaid resource consumption (GPU rental, API credits).

The gap this fills: existing ledger/billing systems are backward-looking (record what happened, not what's happening), discrete-only (no streaming rate concept), bilateral (A pays B, not N-party), or payment-coupled. This engine is all four opposites simultaneously.

Technical context: Java / Spring Boot. Hot state held in-memory; durable state committed to a relational DB (Postgres) for audit, recovery, and dispute resolution.

## Constraints

- **Tech stack**: Java / Spring Boot — engine and library are Spring ecosystem artifacts
- **API style**: REST only — no WebSocket or gRPC in v1; estimation is served via polling or on-demand GET
- **Storage**: In-memory for hot streaming state + Postgres for durable audit and recovery
- **Correctness over throughput**: concurrent correctness is the non-negotiable constraint; latency optimization is secondary
- **No payment coupling**: the engine must never hold a reference to a payment provider SDK or API

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Ship service + library simultaneously | Platforms that self-host need the service; platforms embedding in existing backends need the library | — Pending |
| REST-only API in v1 | Simpler integration surface; estimation served on-demand rather than via push | — Pending |
| In-memory hot state + Postgres durability | Real-time estimation requires sub-millisecond balance reads; Postgres provides recovery and audit trail | — Pending |
| All 6 features in v1 | Features are interdependent — a partial engine (e.g. no rake) isn't production-usable for the primary use case | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-05-13 after Phase 2 completion*
