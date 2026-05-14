---
phase: 04-tags-rake-thresholds
type: human-uat
created: 2026-05-14
status: pending
---

# Phase 4: Human Verification Checklist

Automated verification passed 9/9 must-haves. The following items require human testing.

## Items

### 1. Full Test Suite — Live Testcontainers

Run the full Gradle test suite against live Docker containers:

```powershell
./gradlew test
```

**Expected:** `BUILD SUCCESSFUL` — 88 tests, 0 failures, 0 errors

| Suite | Tests |
|-------|-------|
| CucumberTestRunner (engine-service) | 54 |
| DiscreteTransactionConcurrencyTest | 1 |
| StreamingConcurrencyTest | 1 |
| TagAutoConfigurationIT | 2 |
| ArithmeticTest (engine-spring) | 5 |
| RedisStreamRegistryTagIT | 4 |
| StreamingTransactionRakeConstraintIT | 4 |
| TagTtlCleanupJobIT | 5 |
| MetadataConverterTest (engine-core) | 7 |
| TagCommittedTotalsTest | 5 |
| **Total** | **88** |

**Requires:** Docker running (Testcontainers will spin up Postgres + Redis containers automatically).

## Outcome

- "approved" → Phase 4 complete; advance to Phase 5
- Report failures → gap closure plans created
