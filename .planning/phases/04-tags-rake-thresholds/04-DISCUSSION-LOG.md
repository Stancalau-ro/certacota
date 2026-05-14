# Phase 4: Tags and Rake on Streaming - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-14
**Phase:** 04-tags-rake-thresholds
**Areas discussed:** Tag model and API, Streaming rake configuration, End-by-tag behavior, Metadata portability (cross-cutting retrofit)

---

## Tag model and API

| Option | Description | Selected |
|--------|-------------|----------|
| TEXT[] array column | Postgres-native array, GIN-indexed | |
| JSONB array column | Reuses existing JSONB pattern | |
| Normalized join tables | stream_tags + discrete_transaction_tags, standard SQL | ✓ |

**User's choice:** Normalized join tables — needs to work in MySQL and Oracle too by changing only the repository bean.
**Notes:** User surfaced cross-DB portability as a first-class constraint early in the discussion. This drove the tag storage decision away from Postgres-specific types.

| Option | Description | Selected |
|--------|-------------|----------|
| tag-streams:{tag} Redis Sets | Mirror account-streams pattern, O(1) tag lookup | ✓ |
| Postgres query on each tag request | No Redis tag index, query streaming_transactions WHERE ACTIVE | |

**User's choice:** tag-streams:{tag} Redis Sets — after clarification that Redis is in-memory state only, Postgres is still the source of truth.
**Notes:** User initially questioned whether tags would only be in Redis; clarified that normalized join tables persist in Postgres, Redis is the in-flight index.

| Option | Description | Selected |
|--------|-------------|----------|
| Single aggregate figure | committedTotal + inFlightDrain summed | |
| Separate committed + in-flight | Two nested objects, each with dimensional breakdown | ✓ |

**User's choice (evolved):** Three-dimensional breakdown on both sides: `{totalDebited, totalCreditedRecipient, totalRaked (derived)}` for committed and `{inFlightDebit, inFlightCreditedRecipient, inFlightRaked (derived)}` for in-flight.
**Notes:** User raised the key insight that A→B and B→A bilateral streams would produce an "infinite sum spent" with a naive running total, but the "absolute moved sum" (net) would be 0. This led to tracking gross flows (totalDebited, totalCreditedRecipient) separately rather than a net figure. User confirmed the derived totalRaked formula holds even in bilateral scenarios (verified algebraically during discussion).

| Option | Description | Selected |
|--------|-------------|----------|
| tags: List<String> in request DTOs | Optional field on StartStreamRequest, CreditRequest, DebitRequest | ✓ |
| Separate POST after creation | POST /streams/{id}/tags as a separate call | |

| Option | Description | Selected |
|--------|-------------|----------|
| GET /api/v1/tags/{tag}/aggregate | Explicit aggregate sub-resource | ✓ |
| GET /api/v1/tags/{tag} | Tag as top-level resource | |

---

## Streaming rake configuration

| Option | Description | Selected |
|--------|-------------|----------|
| TokenEngineProperties metadata key lookup | Global config, rate resolved from caller metadata | |
| Explicit rakeRate field in StartStreamRequest | Per-stream: toAccountId, rakeRate, platformAccountId | ✓ |

**User's choice:** Explicit per-stream fields — consistent with existing PostTransferRequest pattern.
**Notes:** User corrected initial misunderstanding: there is no RakeProperties in TokenEngineProperties. The existing transfer() method already takes rakeRate, toAccountId, platformAccountId as explicit request fields. No global config lookup.

| Option | Description | Selected |
|--------|-------------|----------|
| Rake fields in streaming_transactions + Redis hash | Mirrored, no DB read needed at settlement | ✓ |
| Postgres only, read at settle time | Simpler Redis model, one extra DB read | |

| Option | Description | Selected |
|--------|-------------|----------|
| from → to → platform lock order | Consistent with transfer() | ✓ |
| from account only, conditional | Skip to/platform locks for non-rake streams | |

| Option | Description | Selected |
|--------|-------------|----------|
| Rake applies on auto-termination | Same three-way split | ✓ |
| No rake on auto-termination | Rake waived on balance exhaustion | |

| Option | Description | Selected |
|--------|-------------|----------|
| Fallback to Postgres for rake fields on Redis failure | Settlement proceeds | ✓ |
| Return 503 if Redis unavailable during settlement | Strict 503 policy | |

**Notes:** User raised whether totalRaked being derived (totalDebited - totalCreditedRecipient) remains accurate for bilateral streams (A→B and B→A with same tag and same rake rate). Algebraically verified: the per-transaction invariant (debit = creditedRecipient + rake) sums linearly, so the derivation holds regardless of bilateral patterns.

User clarified: platform rake goes to the platform account ledger and audit log only — it is NOT tracked separately in tag_committed_totals. tag_committed_totals stores total_debited and total_credited_recipient; totalRaked is derived at query time.

---

## Threshold design

**Area removed from scope.** User decided threshold events (EVT-01 through EVT-04) are not needed at this stage. Removed from Phase 4 ROADMAP requirements and success criteria. EVT-01–04 moved to v2 in REQUIREMENTS.md. Phase 5 EMIT-01 updated to remove "threshold crossed" from outbox event list.

---

## End-by-tag behavior

| Option | Description | Selected |
|--------|-------------|----------|
| POST /api/v1/tags/{tag}/end | Explicit action, consistent with POST /streams/{id}/stop | ✓ |
| DELETE /api/v1/tags/{tag}/streams | REST-style delete | |

| Option | Description | Selected |
|--------|-------------|----------|
| Roll back all — all-or-nothing | Natural @Transactional behaviour | — |
| Settle what succeeds, skip failures | Best-effort | — |

**Notes:** User questioned when an account could "not exist" during end-by-tag. Determined this is not a real failure case — accounts cannot be deleted, and account close is rejected if active streams exist. Real race condition is a stream being auto-terminated between Redis Set read and settlement attempt.

| Option | Description | Selected |
|--------|-------------|----------|
| Skip already-settled streams, settle rest | Check status inside @Transactional | ✓ |
| Return 409 if any stream already settled | Strict, forces retry | |

| Option | Description | Selected |
|--------|-------------|----------|
| settledCount, skippedCount, totalSettledAmount | Summary only | |
| Full list of settled stream IDs and amounts | Verbose per-stream response | ✓ |

| Option | Description | Selected |
|--------|-------------|----------|
| Idempotency key required | Consistent with all write operations | ✓ |
| No idempotency key needed | Tag end is naturally idempotent | |

| Option | Description | Selected |
|--------|-------------|----------|
| Fixed string "end_by_tag" | Consistent with auto-termination's fixed reason | |
| Caller-supplied reason in request body | Optional field, defaults to "end_by_tag" | ✓ |

| Option | Description | Selected |
|--------|-------------|----------|
| 200 with settledCount=0, empty list | No-error on empty tag | ✓ |
| 404 — tag not found or no active streams | Strict not-found error | |

---

## Metadata portability (cross-cutting retrofit)

| Option | Description | Selected |
|--------|-------------|----------|
| JSONB — same as DiscreteTransaction | Postgres-specific, consistent | |
| Normalized key-value table | Portable but verbose for read-only payload | |
| TEXT + JPA @Convert AttributeConverter | Standard SQL, portable, no join needed | ✓ |

**User's choice:** TEXT + JPA @Convert — cross-DB portability required for all transaction structures.
**Notes:** User raised the portability question as a cross-cutting concern after discussing streaming metadata. Identified that DiscreteTransaction.metadata is already non-compliant (JSONB). Decision: retrofit DiscreteTransaction.metadata via Flyway ALTER COLUMN + swap @JdbcTypeCode for @Convert. MetadataConverter placed in engine-core using Jackson (already on classpath). Metadata is never queried by key, so losing JSONB indexing has zero cost.

---

## Claude's Discretion

- Exact Redis field encoding for tags in `stream:{streamId}` hash (comma-separated vs. JSON)
- Flyway migration numbering (V10+)
- GIN index configuration on join tables
- Tag name constraints (max length, character set, case sensitivity)
- Cucumber feature file structure for Phase 4
- tag_committed_totals TTL cleanup job schedule and ShedLock configuration

## Deferred Ideas

- **Threshold events (EVT-01–04)** — removed from Phase 4, moved to v2. User decision: not needed at this stage.
- **Historical tag aggregate (TAG-HIST-01)** — v2: query over past time windows from audit log
- **Tag name validation** — Claude's discretion now, can be tightened in v2
