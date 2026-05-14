---
phase: 4
slug: tags-rake-thresholds
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-14
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Cucumber 7.22.1 (BDD) + JUnit Jupiter 5 (unit) |
| **Config file** | `engine-service/src/test/java/com/certacota/engine/service/CucumberTestRunner.java` |
| **Quick run command** | `./gradlew :engine-service:test --tests "com.certacota.engine.service.CucumberTestRunner" -i` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~60 seconds (Testcontainers warm) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :engine-service:test --tests "com.certacota.engine.service.CucumberTestRunner" -i`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| Wave 0 | — | 0 | ALL | — | N/A | BDD/unit stubs | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| TAG-01 | 01 | 1 | TAG-01 | T-4-05 | `@Size(max=255)` on tag strings | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| TAG-02 | 02 | 2 | TAG-02 | T-4-01 | Idempotency key enforced via UNIQUE constraint | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| TAG-03 | 02 | 2 | TAG-03 | — | `tag_committed_totals` updated in same TX as settlement | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| TAG-04 | 03 | 2 | TAG-04 | — | Aggregate query returns derived totalRaked | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| TAG-05 | 04 | 3 | TAG-05 | T-4-04 | TTL cleanup removes stale rows | Manual | — | Manual-only | ⬜ pending |
| TAG-06 | 01 | 1 | TAG-06 | — | Discrete TX with tags increments committed total | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| RAKE-02 | 02 | 2 | RAKE-02 | — | Three-way split atomic; debit = creditedRecipient + rake | BDD + unit | `./gradlew :engine-service:test :engine-spring:test` | ❌ W0 | ⬜ pending |
| RAKE-03 | 02 | 2 | RAKE-03 | — | Zero-rake, full-rake, hybrid all balance | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |
| RAKE-04 | 02 | 2 | RAKE-04 | — | Check constraint rejects unbalanced write | BDD (Cucumber) | `./gradlew :engine-service:test` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `streaming-tags.feature` — covers TAG-01, TAG-03, TAG-04
- [ ] `end-by-tag.feature` — covers TAG-02
- [ ] `discrete-tags.feature` — covers TAG-06
- [ ] `streaming-rake.feature` — covers RAKE-02, RAKE-03, RAKE-04
- [ ] `TagSteps.java` — new step definitions for tag aggregate and end-by-tag assertions
- [ ] Extended `StreamingSteps.java` — new step sentences for tags and rake fields

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `TagTtlCleanupJob` deletes stale rows | TAG-05 | Scheduler timing not deterministic in CI | (1) Insert row with `last_activity_at` in the past beyond TTL; (2) call `tagTtlCleanupJob.runCleanup()` directly in test; (3) assert row absent |

---

## Threat Model Summary

| ID | Pattern | STRIDE | Mitigation |
|----|---------|--------|------------|
| T-4-01 | End-by-tag idempotency key collision | Spoofing | `idempotency_keys` UNIQUE on `(idempotency_key, operation)` — existing constraint covers `BULK_END_BY_TAG` |
| T-4-02 | Lock ordering deadlock on multi-tag streams | Tampering | Alphabetical ascending tag lock order per D-08 |
| T-4-03 | Unbalanced rake arithmetic | Tampering | DB check constraint `debit = creditedRecipient + rake` enforced at settlement write |
| T-4-04 | `tag_committed_totals` row explosion | DoS | TTL cleanup job (TAG-05) with configurable inactivity period |
| T-4-05 | Unbounded tag list / tag length | DoS | `@Size(max=255)` on tag strings; `@Size(max=N)` on tags list in `StartStreamRequest` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
