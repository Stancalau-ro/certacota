# Walking Skeleton — Phase 1: Foundation

**Created:** 2026-05-13
**Phase:** 01-foundation
**Mode:** Walking Skeleton (first deliverable of MVP Phase 1)

---

## What the Skeleton Delivers

The thinnest possible end-to-end slice that proves the architecture is connected and correctly wired before domain logic is layered on:

1. Three Gradle modules compile without errors
2. `engine-service` boots with embedded Tomcat
3. Flyway runs V1/V2/V3 migrations against a Testcontainers Postgres instance on startup
4. `GET /actuator/health` returns `{"status":"UP"}`
5. `GET /actuator/prometheus` returns HTTP 200 with `text/plain` content type
6. The Testcontainers Cucumber scenario for the Actuator passes green

The skeleton has **no domain logic** — no account CRUD, no floor enforcement, no idempotency. Domain capabilities are layered on in Plans 02 and 03.

---

## Architectural Decisions Established by the Skeleton

These decisions are locked in by Plan 01 and must not be renegotiated in subsequent phases.

| Decision | Value | Rationale |
|----------|-------|-----------|
| Build system | Gradle with Groovy DSL | D-01 — locked |
| Spring Boot version | 3.5.3 | D-02 specified 3.4.x; RESEARCH confirmed 3.4 OSS support ended 2025-12-31; 3.5.3 is the current OSS release and is drop-in compatible |
| Java version | 21 (toolchain) | D-03 — locked |
| Module structure | `engine-core` / `engine-spring` / `engine-service` | D-05 / D-06 — locked |
| Framework dependencies | `org.springframework.boot` plugin on `engine-service` ONLY; `io.spring.dependency-management` on `engine-core` and `engine-spring` | Prevents fat-JAR artifact on library modules |
| Database migrations | Flyway SQL-first (`V{n}__{description}.sql`); both `flyway-core` AND `flyway-database-postgresql` required | D-04; Flyway 10+ split Postgres support |
| Test infrastructure | Cucumber 7.22.1 + JUnit 5 Platform + Testcontainers 1.21.3 + `@ServiceConnection` | D-13 — locked |
| Package root | `io.certacota.engine` | Claude's discretion — mirrors module names |
| REST path prefix | `/api/v1` | Claude's discretion |
| Observability | Spring Actuator + Micrometer Prometheus registry; endpoints: `health,prometheus,info,metrics` | FUND-03 |
| Schema management | `spring.jpa.hibernate.ddl-auto: validate` — Flyway manages schema, JPA only validates | D-04 |
| BigDecimal deserialisation | `spring.jackson.deserialization.use-big-decimal-for-floats: true` | Prevents Double coercion of JSON numbers |
| Global balance floor property | `token-engine.balance-floor` (default 0) | D-11 |
| Autoconfigure registration | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot 3.x standard (replaces spring.factories) |

---

## Module Dependency Graph

```
engine-core
  (no Spring, no web — jakarta.persistence-api compileOnly)
      ↑
engine-spring
  (spring-boot-autoconfigure, spring-boot-starter-data-jpa)
      ↑
engine-service
  (org.springframework.boot plugin, spring-boot-starter-web, Flyway, Actuator, Prometheus, Cucumber, Testcontainers)
```

`engine-core` compiles against `jakarta.persistence-api` as `compileOnly`. The Hibernate runtime is provided by `engine-spring` via `spring-boot-starter-data-jpa`. `engine-service` is the only module with the Spring Boot fat-JAR plugin.

---

## Directory Layout (established by Plan 01)

```
token-engine/                                    ← git root (C:\Mergebine\certacota)
├── settings.gradle                              ← includes engine-core, engine-spring, engine-service
├── build.gradle                                 ← subprojects block: Java 21 toolchain, mavenCentral
├── gradlew / gradlew.bat / gradle/wrapper/      ← Gradle wrapper (created by gradle init or wrapper task)
├── engine-core/
│   ├── build.gradle                             ← dependency-management only, NO bootJar
│   └── src/main/java/io/certacota/engine/core/
│       ├── domain/                              ← Account, BalanceAuditLog, IdempotencyKey, AccountStatus
│       ├── repository/                          ← AccountRepository, BalanceAuditLogRepository, IdempotencyKeyRepository
│       ├── service/                             ← AccountService interface, DTOs
│       └── exception/                           ← domain exception classes
├── engine-spring/
│   ├── build.gradle                             ← dependency-management only, NO bootJar
│   └── src/main/
│       ├── java/io/certacota/engine/spring/
│       │   ├── autoconfigure/TokenEngineAutoConfiguration.java
│       │   ├── config/TokenEngineProperties.java
│       │   └── service/AccountServiceImpl.java
│       └── resources/META-INF/spring/
│           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── engine-service/
    ├── build.gradle                             ← applies org.springframework.boot plugin
    └── src/
        ├── main/
        │   ├── java/io/certacota/engine/service/
        │   │   ├── EngineServiceApplication.java
        │   │   └── controller/
        │   │       ├── AccountController.java
        │   │       └── GlobalExceptionHandler.java
        │   └── resources/
        │       ├── application.yml
        │       ├── application-test.yml
        │       └── db/migration/
        │           ├── V1__create_accounts.sql
        │           ├── V2__create_idempotency_keys.sql
        │           └── V3__create_balance_audit_log.sql
        └── test/
            ├── java/io/certacota/engine/service/
            │   ├── CucumberTestRunner.java
            │   ├── CucumberSpringConfiguration.java
            │   ├── TestcontainersConfiguration.java
            │   └── steps/
            │       ├── AccountSteps.java
            │       ├── IdempotencySteps.java
            │       ├── AuditLogSteps.java
            │       ├── BalanceFloorSteps.java
            │       └── ActuatorSteps.java
            └── resources/
                ├── features/
                │   ├── account-lifecycle.feature
                │   ├── idempotency.feature
                │   ├── audit-log.feature
                │   ├── balance-floor.feature
                │   └── observability.feature
                └── junit-platform.properties
```

---

## Skeleton Gate (Plan 01 completion criterion)

After Plan 01 executes, the following must be true before Plan 02 work begins:

```
./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i
```

Expected result: Cucumber reports the `observability.feature` scenarios green; all other feature files have pending/undefined steps (not failing compilation errors).

```
./gradlew :engine-core:build :engine-spring:build :engine-service:build
```

Expected result: exits 0; no fat JAR in `engine-core/build/libs/` or `engine-spring/build/libs/`.

---

## What Comes After the Skeleton

| Plan | Adds |
|------|------|
| 02-PLAN (Wave 1, parallel) | Domain model layer: Account entity, repositories, AccountService interface, DTOs, exceptions, AccountServiceImpl, TokenEngineAutoConfiguration, TokenEngineProperties |
| 03-PLAN (Wave 2) | REST layer + Cucumber acceptance tests: AccountController, GlobalExceptionHandler, all 5 feature files with full step implementations — all Phase 1 success criteria green |

---

*Walking Skeleton — produced alongside 01-01-PLAN.md*
