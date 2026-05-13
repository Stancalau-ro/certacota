# Phase 1: Foundation - Research

**Researched:** 2026-05-13
**Domain:** Spring Boot 3.x multi-module Gradle, JPA/Flyway/Postgres, Cucumber BDD, Testcontainers
**Confidence:** HIGH (standard stack verified against official docs and Maven Central)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Gradle with Groovy DSL (not Kotlin DSL, not Maven)
- **D-02:** Spring Boot 3.4.x
- **D-03:** Java 21
- **D-04:** Flyway for database schema migrations (SQL-first, V{n}__{description}.sql convention)
- **D-05:** Multi-module Gradle project from day 1: `engine-core`, `engine-spring`, `engine-service`
  - `engine-core`: domain entities, repository interfaces, service interfaces, domain exceptions — zero Spring or web framework dependencies
  - `engine-spring`: Spring autoconfigure beans, `@ConditionalOnMissingBean` wiring, `token-engine.*` property binding
  - `engine-service`: `@SpringBootApplication` main class, REST controllers, Testcontainers integration tests
- **D-06:** All three modules scaffolded and wired by Phase 1 end; domain logic lives in `engine-core`; `engine-service` is the runnable entry point and integration test home
- **D-07:** Caller-supplied account ID stored as VARCHAR primary key; no engine-generated UUID
- **D-08:** Account creation carries `id` (String), `initialBalance` (BigDecimal, optional, default 0), `metadata` (key-value map, optional, immutable) stored as JSONB
- **D-09:** Account metadata follows the same open key-value pattern as transaction metadata
- **D-10:** All token amounts: `BigDecimal` in Java / `NUMERIC(38,18)` in Postgres
- **D-11:** Balance floor: global property `token-engine.balance-floor` (default 0) + optional per-account nullable `balance_floor` column
- **D-12:** Floor enforcement: reject before write; check occurs at service layer before the DB write
- **D-13:** BDD with Cucumber — `cucumber-spring`, `cucumber-junit-platform-engine`; feature files in Gherkin; step definitions in `engine-service`
- **D-14:** Cucumber feature files cover Phase 1 success criteria scenarios

### Claude's Discretion
- Package naming convention inside each module (e.g., `io.certacota.engine.core.*`)
- Exact Testcontainers version and `@ServiceConnection` wiring pattern
- Actuator endpoint exposure configuration in `application.yml`
- REST API path prefix (e.g., `/api/v1/accounts`)
- Idempotency key column naming and index strategy
- Cucumber report format and output directory

### Deferred Ideas (OUT OF SCOPE)
- Metadata search/filtering (not in requirements)
- Virtual thread configuration (Project Loom) — deferred to performance tuning
- OpenTelemetry trace propagation (OBS-01) — deferred to v2
- Paginated transaction history (TX-HIST-01) — deferred to v2
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| ACCT-01 | Caller can create a participant account with an initial token balance | REST POST, JPA entity, Flyway DDL, idempotency key table, audit log write |
| ACCT-02 | Caller can close a participant account (rejected if active streaming transactions exist) | REST DELETE/PATCH, account status field, guard check before close |
| ACCT-03 | Caller can retrieve a participant account and its current committed balance | REST GET, JPA select, BigDecimal mapping |
| FUND-01 | Idempotency on all write operations via DB UNIQUE constraint on caller-supplied key | `idempotency_keys` table with UNIQUE constraint; DB rejects duplicate; service returns stored response |
| FUND-02 | Immutable append-only audit log for every balance change | `balance_audit_log` table; INSERT-only from service layer; no UPDATE/DELETE |
| FUND-03 | Spring Actuator health + Micrometer/Prometheus metrics | `spring-boot-starter-actuator`, `micrometer-registry-prometheus`; Testcontainers confirms UP |
| BAL-01 | Query current committed balance | Same as ACCT-03 — balance is a column on the accounts table |
| BAL-03 | Floor enforcement — reject before write | Service layer pre-write check; effective floor = account override ?? global property |
</phase_requirements>

---

## Summary

Phase 1 scaffolds a greenfield Spring Boot multi-module Gradle project (Groovy DSL) that delivers participant account CRUD, balance floor enforcement, append-only audit logging, idempotent writes, and observability endpoints. All five success criteria depend on correctly wiring three modules (`engine-core`, `engine-spring`, `engine-service`), establishing Flyway migration conventions, and assembling the Testcontainers + Cucumber testing stack.

**Version alert:** Decision D-02 specifies Spring Boot 3.4.x. OSS support for Spring Boot 3.4 ended December 31, 2025 — it is now in commercial-only support. Spring Boot 3.5.x (latest 3.5.3 as of 2026-05-13) is the current OSS-supported 3.x line and is a drop-in compatible upgrade. The planner should surface this to the user: using 3.4.x is technically viable (it works) but uses an EOL release. The recommended version is **3.5.3** (or the latest 3.5.x patch at implementation time). `[VERIFIED: Maven Central registry, endoflife.date]`

**Primary recommendation:** Scaffold the three-module Gradle structure first (Wave 0 / Walking Skeleton), then add Flyway migrations and the accounts table, then layer idempotency, audit log, and observability on top. Every wave ends with a green Testcontainers integration test.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Account domain model (entity, repository interface, service interface) | engine-core | — | Zero-framework domain objects; must compile without Spring on classpath (PKG-01 prerequisite) |
| Bean wiring, property binding, autoconfigure | engine-spring | — | Spring-specific infrastructure; isolated so it can be embedded as a starter later |
| REST controllers, application entry point, integration tests | engine-service | — | Runnable service layer; depends on engine-spring which depends on engine-core |
| Flyway migrations (DDL) | engine-service / resources | — | Migrations ship with the deployable service; classpathed from `engine-service/src/main/resources/db/migration` |
| Balance floor enforcement (check before write) | Service layer (engine-core service impl in engine-spring) | — | Pre-write check is business logic; belongs in the service, not the controller or DB |
| Idempotency enforcement | DB UNIQUE constraint + service layer return | — | DB constraint is the enforcer; service catches the constraint violation and returns the stored response |
| Audit log writes | Service layer, inside the same `@Transactional` method | — | Atomicity requires the audit INSERT and balance UPDATE to share one transaction |
| Actuator / Prometheus | engine-service application.yml | engine-spring (if you want autoconfigure to expose them) | Endpoints are registered by the Spring Boot runtime in the service module |

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.3 (or latest 3.5.x) | Application framework, autoconfigure, embedded Tomcat | Decision D-02 specified 3.4.x; 3.5.x is drop-in compatible and OSS-supported |
| Spring Boot Starter Web | (BOM-managed) | REST controllers, Jackson serialization | Standard Spring MVC REST stack |
| Spring Boot Starter Data JPA | (BOM-managed) | Hibernate 6 ORM, Spring Data repositories | JPA standard for Postgres persistence |
| Spring Boot Starter Actuator | (BOM-managed) | Health endpoint, metrics infrastructure | Required by FUND-03 |
| Micrometer Registry Prometheus | (BOM-managed; standalone ~1.16.x) | Prometheus metrics scraping endpoint | Required by FUND-03; auto-configured when on classpath |
| Spring Boot Starter Flyway | (BOM-managed) | DB migration on startup | Decision D-04 |
| PostgreSQL JDBC Driver | (BOM-managed) | Postgres connectivity | Runtime JDBC driver |
| Testcontainers PostgreSQL | 1.21.3 | Real Postgres in tests | Standard approach since Spring Boot 3.1+ |
| spring-boot-testcontainers | (BOM-managed) | `@ServiceConnection` support | Eliminates `@DynamicPropertySource` boilerplate |

`[VERIFIED: Maven Central registry — Spring Boot 3.5.3 published 2026-05, Testcontainers 1.21.3 published 2025, cucumber-bom 7.22.1 published 2025-04]`

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| io.cucumber:cucumber-bom | 7.22.1 | BOM for Cucumber version alignment | Always — avoids version skew across cucumber-* artifacts |
| io.cucumber:cucumber-java | (BOM-managed) | Step definition annotations | Needed for every Cucumber test |
| io.cucumber:cucumber-spring | (BOM-managed) | Spring context injection into step definitions | Required by D-13 |
| io.cucumber:cucumber-junit-platform-engine | (BOM-managed) | JUnit Platform engine for Cucumber | Required by D-13; replaces old `@RunWith(Cucumber.class)` |
| org.junit.platform:junit-platform-suite | (Spring Boot BOM) | `@Suite` runner class | Required when using Cucumber with JUnit 5 Platform |
| Lombok | (BOM-managed) | Boilerplate reduction: `@RequiredArgsConstructor`, `@Slf4j`, `@Builder` | Project convention from CLAUDE.md |
| io.spring.dependency-management | 1.1.x Gradle plugin | Spring BOM import in non-Spring modules | Used in `engine-core` and `engine-spring` to get managed versions without the Boot plugin |

`[VERIFIED: Context7 /cucumber/cucumber-jvm, cucumber.io/docs/installation/java]`

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Spring Boot 3.5.x | Spring Boot 3.4.x (as specified in D-02) | 3.4.x works but OSS support ended Dec 31 2025; 3.5.x is a safe upgrade |
| Flyway SQL migrations | Liquibase | Liquibase XML/YAML is more verbose; SQL-first Flyway is simpler for this domain |
| `@ServiceConnection` | `@DynamicPropertySource` | `@ServiceConnection` is the Spring Boot 3.1+ standard; eliminates property key coupling |
| Cucumber JUnit Platform | RestAssured + Spring MockMvc | Both work; Cucumber was explicitly decided in D-13 for BDD |

**Installation (engine-service build.gradle — test dependencies):**
```groovy
testImplementation(platform("io.cucumber:cucumber-bom:7.22.1"))
testImplementation("io.cucumber:cucumber-java")
testImplementation("io.cucumber:cucumber-spring")
testImplementation("io.cucumber:cucumber-junit-platform-engine")
testImplementation("org.junit.platform:junit-platform-suite")
testImplementation("org.testcontainers:postgresql")
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

---

## Architecture Patterns

### System Architecture Diagram

```
HTTP Request
     |
     v
[engine-service: REST Controller (@RestController)]
     |  (delegates immediately — thin controller)
     v
[engine-spring: AccountService impl (@Service, @Transactional)]
     |
     |---> [engine-core: IdempotencyKey check → DB UNIQUE constraint]
     |---> [engine-core: BalanceFloor check → effective floor = account.balanceFloor ?? globalProperty]
     |---> [engine-core: AccountRepository.save(account)]        ---> [Postgres: accounts table]
     |---> [engine-core: AuditLogRepository.save(entry)]        ---> [Postgres: balance_audit_log table]
     |
     v
AccountResponse (serialized by Jackson)
     |
     v
HTTP Response

[Spring Boot Actuator]
     |---> GET /actuator/health  --> HealthIndicator (DB connectivity + disk)
     |---> GET /actuator/prometheus --> MicrometerPrometheusRegistry

[Flyway] (runs on startup, before any request)
     |---> classpath:db/migration/V1__create_accounts.sql
     |---> classpath:db/migration/V2__create_idempotency_keys.sql
     |---> classpath:db/migration/V3__create_balance_audit_log.sql
```

### Recommended Project Structure
```
engine-core/
├── build.gradle                         # Java only — no Spring Boot plugin
└── src/main/java/io/certacota/engine/core/
    ├── domain/
    │   ├── Account.java                 # JPA entity (jakarta.persistence only)
    │   ├── BalanceAuditLog.java         # JPA entity, insert-only
    │   └── IdempotencyKey.java          # JPA entity
    ├── repository/
    │   ├── AccountRepository.java       # extends JpaRepository
    │   ├── BalanceAuditLogRepository.java
    │   └── IdempotencyKeyRepository.java
    ├── service/
    │   └── AccountService.java          # interface only — implementation in engine-spring
    └── exception/
        ├── AccountNotFoundException.java
        ├── BalanceFloorViolationException.java
        ├── DuplicateIdempotencyKeyException.java
        └── AccountClosedException.java

engine-spring/
├── build.gradle                         # Spring dependency-management, no Boot plugin
└── src/main/java/io/certacota/engine/spring/
    ├── autoconfigure/
    │   └── TokenEngineAutoConfiguration.java   # @AutoConfiguration
    ├── config/
    │   └── TokenEngineProperties.java          # @ConfigurationProperties("token-engine")
    ├── service/
    │   └── AccountServiceImpl.java             # @Service, @Transactional
    └── META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports

engine-service/
├── build.gradle                         # applies 'org.springframework.boot' plugin
└── src/
    ├── main/
    │   ├── java/io/certacota/engine/service/
    │   │   ├── EngineServiceApplication.java   # @SpringBootApplication
    │   │   └── controller/
    │   │       └── AccountController.java       # @RestController
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__create_accounts.sql
    │           ├── V2__create_idempotency_keys.sql
    │           └── V3__create_balance_audit_log.sql
    └── test/
        ├── java/io/certacota/engine/service/
        │   ├── CucumberTestRunner.java          # @Suite entry point
        │   ├── CucumberSpringConfiguration.java # @CucumberContextConfiguration + @SpringBootTest
        │   └── steps/
        │       ├── AccountSteps.java
        │       ├── IdempotencySteps.java
        │       ├── AuditLogSteps.java
        │       ├── BalanceFloorSteps.java
        │       └── ActuatorSteps.java
        └── resources/features/
            ├── account-lifecycle.feature
            ├── idempotency.feature
            ├── audit-log.feature
            ├── balance-floor.feature
            └── observability.feature
```

### Pattern 1: Multi-Module Gradle Structure (Groovy DSL)

**What:** Three-module build with `settings.gradle` declaring all modules. Modules share the Spring BOM via `io.spring.dependency-management` without each needing the Boot plugin.

**When to use:** Always — decided in D-05.

```groovy
// settings.gradle  (root)
// Source: https://spring.io/guides/gs/multi-module/
rootProject.name = 'token-engine'
include 'engine-core'
include 'engine-spring'
include 'engine-service'
```

```groovy
// build.gradle (root — shared config)
// Source: https://docs.gradle.org/current/userguide/multi_project_builds.html
plugins {
    id 'java'
}

subprojects {
    apply plugin: 'java'

    group = 'io.certacota.engine'
    version = '0.1.0-SNAPSHOT'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }
}
```

```groovy
// engine-core/build.gradle
// Source: https://github.com/spring-projects/spring-boot/blob/main/.../managing-dependencies.adoc
plugins {
    id 'java'
    id 'io.spring.dependency-management' version '1.1.7'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:3.5.3"
    }
}

dependencies {
    // Jakarta Persistence (JPA annotations only — no Spring)
    compileOnly 'jakarta.persistence:jakarta.persistence-api'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

```groovy
// engine-spring/build.gradle
plugins {
    id 'java'
    id 'io.spring.dependency-management' version '1.1.7'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:3.5.3"
    }
}

dependencies {
    implementation project(':engine-core')
    implementation 'org.springframework.boot:spring-boot-autoconfigure'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    annotationProcessor 'org.springframework.boot:spring-boot-autoconfigure-processor'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

```groovy
// engine-service/build.gradle
plugins {
    id 'org.springframework.boot' version '3.5.3'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'java'
}

dependencies {
    implementation project(':engine-spring')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation(platform("io.cucumber:cucumber-bom:7.22.1"))
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'io.cucumber:cucumber-java'
    testImplementation 'io.cucumber:cucumber-spring'
    testImplementation 'io.cucumber:cucumber-junit-platform-engine'
    testImplementation 'org.junit.platform:junit-platform-suite'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
    systemProperty 'cucumber.junit-platform.naming-strategy', 'long'
    systemProperty 'cucumber.plugin', 'pretty,html:build/reports/cucumber.html'
}
```

`[VERIFIED: Context7 /spring-projects/spring-boot managing-dependencies, spring.io/guides/gs/multi-module]`

### Pattern 2: `@ServiceConnection` with Testcontainers

**What:** Spring Boot 3.1+ `@ServiceConnection` auto-creates `ConnectionDetails` beans from a container, eliminating `@DynamicPropertySource` boilerplate.

**When to use:** All integration tests in `engine-service`.

```java
// Source: https://medium.com/@aleksanderkolata/integration-tests-with-testcontainers-and-spring-boot-3-1-39103ff95bd7
// [VERIFIED: Context7 /spring-projects/spring-boot dev-services.adoc, testing/testcontainers.adoc]

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.0-alpine"));
    }
}
```

### Pattern 3: Cucumber JUnit 5 Suite Runner

**What:** `@Suite` + `@IncludeEngines("cucumber")` replaces the deprecated `@RunWith(Cucumber.class)`. Feature files resolve from `classpath:features/` relative to the test source root.

**When to use:** Cucumber entry point class in `engine-service` test sources.

```java
// Source: Context7 /cucumber/cucumber-jvm cucumber-junit-platform-engine/README.md
// [VERIFIED]

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "io.certacota.engine.service.steps")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,html:build/reports/cucumber.html")
public class CucumberTestRunner {
}
```

### Pattern 4: `@CucumberContextConfiguration` with `@SpringBootTest`

**What:** One class annotated with both `@CucumberContextConfiguration` and `@SpringBootTest` creates and manages the Spring application context for all step definitions.

**When to use:** Single configuration class shared by all Cucumber step files.

```java
// Source: Context7 /cucumber/cucumber-jvm cucumber-spring/README.md
// [VERIFIED]

@CucumberContextConfiguration
@SpringBootTest(
    classes = EngineServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(TestcontainersConfiguration.class)
public class CucumberSpringConfiguration {
    // Spring injects the server port via @LocalServerPort in step definitions
}
```

### Pattern 5: Flyway Migration Naming

**What:** SQL files named `V{n}__{description}.sql` in `classpath:db/migration/`. Flyway auto-runs on startup when `spring-boot-starter-flyway` is on classpath.

**When to use:** Always — decided in D-04.

```yaml
# application.yml — Flyway auto-configured; no additional properties needed for defaults
# [VERIFIED: Context7 /spring-projects/spring-boot data-initialization.adoc]
spring:
  flyway:
    locations: classpath:db/migration   # default; explicit for clarity
  datasource:
    driver-class-name: org.postgresql.Driver
```

**Critical Flyway note for Postgres:** As of Flyway 10+, `flyway-database-postgresql` is a separate module from `flyway-core` and must be declared explicitly.
`[VERIFIED: Flyway docs — the split happened in Flyway 10]`

### Pattern 6: Autoconfigure Registration (`engine-spring`)

**What:** `AutoConfiguration.imports` replaces the old `spring.factories` in Spring Boot 3.x.

```
// engine-spring/src/main/resources/META-INF/spring/
// org.springframework.boot.autoconfigure.AutoConfiguration.imports
// Source: Context7 /spring-projects/spring-boot developing-auto-configuration.adoc
// [VERIFIED]

io.certacota.engine.spring.autoconfigure.TokenEngineAutoConfiguration
```

```java
// TokenEngineAutoConfiguration.java
// [VERIFIED: Context7 /spring-projects/spring-boot]

@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(TokenEngineProperties.class)
public class TokenEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AccountService accountService(
            AccountRepository accountRepository,
            BalanceAuditLogRepository auditLogRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            TokenEngineProperties properties) {
        return new AccountServiceImpl(
            accountRepository, auditLogRepository, idempotencyKeyRepository, properties);
    }
}
```

### Anti-Patterns to Avoid
- **Applying the Spring Boot plugin to `engine-core` or `engine-spring`:** The Boot plugin adds the `bootJar` task and makes the JAR executable. Library modules must not be executable fat JARs. Use `io.spring.dependency-management` instead to import the BOM.
- **Using `spring.factories` instead of `AutoConfiguration.imports`:** `spring.factories` was deprecated in Spring Boot 2.7 and removed in Spring Boot 3+. Use `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- **`@DynamicPropertySource` instead of `@ServiceConnection`:** The `@ServiceConnection` approach is the current standard from Spring Boot 3.1+. `@DynamicPropertySource` is more fragile (string-coupling to property names) and should not be used in new code.
- **Checking idempotency in application code before the DB write:** The UNIQUE constraint is the enforcer (per D-13 / FUND-01). Application-level pre-checks create a TOCTOU race under concurrent load. Catch `DataIntegrityViolationException` after the INSERT attempt instead.
- **Logging audit entries in a separate transaction:** Audit INSERTs and balance UPDATEs must share one `@Transactional` scope. If the business write fails and rolls back, the audit entry must also roll back.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| DB migrations | Custom SQL runner on startup | Flyway | Versioned, checksummed, repeatable, handles baseline; hand-rolled runners lack all of this |
| JSON serialization of JSONB | Manual `ObjectMapper` in entity methods | Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)` | Hibernate 6 handles the Postgres JSONB type natively with Jackson; no custom type needed |
| Container DB setup in tests | Manual `DriverManager` connections | Testcontainers `@ServiceConnection` | Lifecycle management, port allocation, credential injection are handled automatically |
| Version alignment across cucumber-* jars | Manual version coordination | `io.cucumber:cucumber-bom` | BOM ensures `cucumber-java`, `cucumber-spring`, `cucumber-junit-platform-engine` stay aligned |
| Prometheus metrics collection | Custom counter/gauge code | Micrometer + `micrometer-registry-prometheus` | Auto-instruments JVM, HTTP, connection pool metrics; scraping format handled by the registry |

**Key insight:** Every hand-rolled solution in this list has been superseded by framework-native solutions in Spring Boot 3.x + Hibernate 6. The ecosystem already solved these problems correctly.

---

## Runtime State Inventory

Step 2.5 SKIPPED — This is a greenfield phase. No existing runtime state to inventory. The project does not yet exist on disk.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 | D-03; engine compilation | Yes | OpenJDK 21.0.3 (Temurin LTS) | — |
| Docker | Testcontainers (Postgres) | Yes | 29.2.0 | — |
| Gradle | Build system | Not detected separately | Ships via Gradle wrapper; no separate install needed | Use `./gradlew` wrapper |
| Node.js | Hook scripts only (not engine) | Yes | v24.5.0 | — |
| Postgres (local) | Dev/run | Not checked | — | Use Testcontainers for all tests; local Postgres not required |

**Missing dependencies with no fallback:** None — all runtime dependencies are pulled by Gradle/Maven or managed by Testcontainers.

`[VERIFIED: Bash — java -version, docker info]`

---

## Common Pitfalls

### Pitfall 1: Spring Boot Plugin on Library Modules
**What goes wrong:** `engine-core` or `engine-spring` has `id 'org.springframework.boot'` applied. Gradle's `bootJar` task packages a fat JAR with an embedded manifest. When `engine-service` depends on this module via `project(':engine-core')`, it gets the wrong JAR artifact, causing `ClassNotFoundException` at runtime.
**Why it happens:** Developers apply the Spring Boot plugin to all modules by habit or by copy-paste.
**How to avoid:** Library modules use `id 'io.spring.dependency-management'` only. Apply `'org.springframework.boot'` exclusively in `engine-service`.
**Warning signs:** `error: Main class not found` when running `engine-service`; fat JARs appearing in `engine-core/build/libs/`.

### Pitfall 2: `flyway-database-postgresql` Missing Separately
**What goes wrong:** `flyway-core` is declared but `flyway-database-postgresql` is omitted. Flyway 10+ split database support into separate modules. Startup fails with `No database found to handle <jdbc:postgresql:...>`.
**Why it happens:** Old tutorials only show `flyway-core`.
**How to avoid:** Declare both `implementation 'org.flywaydb:flyway-core'` and `implementation 'org.flywaydb:flyway-database-postgresql'` in `engine-service/build.gradle`.
**Warning signs:** Flyway autoconfigure starts but throws "Unsupported database" on the first migration attempt.

### Pitfall 3: Idempotency Race Condition via Application-Layer Pre-Check
**What goes wrong:** Code does `if (idempotencyKeyRepository.existsById(key)) return cached; else saveAndProceed();` — this TOCTOU pattern fails under concurrent duplicate submissions because two threads can both find the key absent simultaneously.
**Why it happens:** Developers implement idempotency as an application-level cache check before the write.
**How to avoid:** Always attempt the INSERT; catch `DataIntegrityViolationException` (Spring) or `ConstraintViolationException` (JPA); then query for the existing record and return it. The UNIQUE constraint is the atomic gate — per FUND-01.
**Warning signs:** Duplicate audit log entries appearing; idempotency test passes in serial but fails under concurrent load.

### Pitfall 4: Cucumber `@CucumberContextConfiguration` Found on Multiple Classes
**What goes wrong:** Cucumber throws `CucumberContextConfigurationException: expected exactly one class annotated with @CucumberContextConfiguration on the glue path`.
**Why it happens:** Each step definition file is annotated with its own `@SpringBootTest`.
**How to avoid:** Exactly one class in the glue package carries `@CucumberContextConfiguration`. All step definition classes get Spring injection via `@Autowired` — they do NOT carry `@SpringBootTest`.
**Warning signs:** Tests run fine individually but fail when the suite runner (`CucumberTestRunner`) is used.

### Pitfall 5: Flyway Conflicts Between Testcontainers and Local DB on Port 5432
**What goes wrong:** A local Postgres is running on 5432. Tests configure `spring.datasource.url=jdbc:postgresql://localhost:5432/testdb`. Flyway runs migrations against local DB, not the container.
**Why it happens:** `@ServiceConnection` wasn't configured or was overridden by `application.yml` properties.
**How to avoid:** Use `@ServiceConnection` — it overrides datasource properties at the Spring context level, taking precedence over `application.yml`. Never put `spring.datasource.url` with a hardcoded localhost in `application.yml` if you rely on `@ServiceConnection`.
**Warning signs:** Tests pass on CI (no local Postgres) but fail locally; schema state bleeds between test runs.

### Pitfall 6: `@Transactional` on Controller Layer
**What goes wrong:** The `@Transactional` annotation is placed on the REST controller method. The balance check, balance update, and audit log INSERT are separate transactions. A failure after the balance UPDATE but before the audit INSERT leaves the DB in an inconsistent state.
**Why it happens:** Controller-level `@Transactional` feels natural when the controller calls the service directly.
**How to avoid:** `@Transactional` belongs on the service implementation method, not the controller. Controllers are always thin delegators. This is a project convention per CLAUDE.md.
**Warning signs:** Audit log entries missing for some balance changes; partial writes appearing in the DB.

### Pitfall 7: BigDecimal Scale Loss in HTTP Request Binding
**What goes wrong:** Jackson deserializes `"initialBalance": 10` as `BigDecimal("10")` with scale 0. When stored, Postgres rounds to `NUMERIC(38,18)` correctly, but comparison logic (`compareTo`) must be used — never `equals()` across different scales.
**Why it happens:** `BigDecimal.equals()` considers scale: `10.compareTo(10.00)` is 0 but `10.equals(10.00)` is false.
**How to avoid:** Always use `compareTo(BigDecimal.ZERO)` for floor checks, never `equals`. Configure Jackson: `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS = true` to ensure JSON number nodes are parsed as `BigDecimal`, not `Double`.
**Warning signs:** Floor enforcement passes for values it should reject; test failures on balance equality assertions.

---

## Code Examples

### DDL — V1: Accounts Table
```sql
-- Source: Flyway convention; NUMERIC(38,18) per D-10
-- File: db/migration/V1__create_accounts.sql
CREATE TABLE accounts (
    id              VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    balance         NUMERIC(38,18)  NOT NULL DEFAULT 0,
    balance_floor   NUMERIC(38,18),                            -- NULL = use global property
    metadata        JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);
```

### DDL — V2: Idempotency Keys Table
```sql
-- Source: FUND-01 requirement; DB UNIQUE constraint is the enforcer
-- File: db/migration/V2__create_idempotency_keys.sql
CREATE TABLE idempotency_keys (
    id              BIGSERIAL       PRIMARY KEY,
    idempotency_key VARCHAR(255)    NOT NULL,
    operation       VARCHAR(50)     NOT NULL,      -- e.g. 'ACCOUNT_CREATE'
    response_body   TEXT            NOT NULL,      -- serialized original response
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key, operation)
);
```

### DDL — V3: Audit Log Table
```sql
-- Source: FUND-02 requirement; append-only (no UPDATE, no DELETE allowed)
-- File: db/migration/V3__create_balance_audit_log.sql
CREATE TABLE balance_audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    account_id      VARCHAR(255)    NOT NULL,
    operation       VARCHAR(50)     NOT NULL,      -- e.g. 'ACCOUNT_CREATED', 'CREDIT', 'DEBIT'
    amount          NUMERIC(38,18)  NOT NULL,
    balance_before  NUMERIC(38,18)  NOT NULL,
    balance_after   NUMERIC(38,18)  NOT NULL,
    idempotency_key VARCHAR(255),
    recorded_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);
-- No FK to idempotency_keys — audit log is append-only and idempotency key may not always apply
```

### JPA Entity — Account (engine-core)
```java
// Source: Context7 /spring-projects/spring-boot JPA patterns, Hibernate 6 @JdbcTypeCode
// [VERIFIED: Context7 JSONB mapping — @JdbcTypeCode(SqlTypes.JSON)]

@Entity
@Table(name = "accounts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    @Column(name = "balance", nullable = false, precision = 38, scale = 18)
    private BigDecimal balance;

    @Column(name = "balance_floor", precision = 38, scale = 18)   // nullable — null = use global
    private BigDecimal balanceFloor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
```

### Service Layer — Floor Enforcement Pattern
```java
// Source: D-12 — check before write; [ASSUMED: pattern based on requirement]
// Effective floor = account.balanceFloor ?? globalProperty

private void enforceBalanceFloor(Account account, BigDecimal resultingBalance) {
    BigDecimal effectiveFloor = account.getBalanceFloor() != null
        ? account.getBalanceFloor()
        : properties.getBalanceFloor();   // token-engine.balance-floor from TokenEngineProperties
    if (resultingBalance.compareTo(effectiveFloor) < 0) {
        throw new BalanceFloorViolationException(
            "Operation would bring balance to " + resultingBalance
            + " below floor " + effectiveFloor);
    }
}
```

### Idempotency — Catch Constraint Violation Pattern
```java
// Source: Based on DB-constraint idempotency pattern; [ASSUMED: specific catch chain]
// Per FUND-01: UNIQUE constraint is the enforcer, not application pre-check

@Transactional
public AccountResponse createAccount(CreateAccountRequest request) {
    try {
        // 1. Write idempotency key first (will fail if duplicate)
        idempotencyKeyRepository.save(IdempotencyKey.of(
            request.idempotencyKey(), "ACCOUNT_CREATE"));

        // 2. Enforce floor on initial balance
        enforceBalanceFloor(request.initialBalance(), resolveGlobalFloor());

        // 3. Create account
        Account account = accountRepository.save(Account.builder()
            .id(request.id())
            .balance(request.initialBalance() != null ? request.initialBalance() : BigDecimal.ZERO)
            .status(AccountStatus.ACTIVE)
            .metadata(request.metadata())
            .build());

        // 4. Audit log — inside same transaction
        auditLogRepository.save(BalanceAuditLog.of(
            account.getId(), "ACCOUNT_CREATED",
            account.getBalance(), BigDecimal.ZERO, account.getBalance()));

        AccountResponse response = AccountResponse.from(account);

        // 5. Persist original response for idempotency replay
        idempotencyKeyRepository.storeResponse(request.idempotencyKey(), serialize(response));

        return response;

    } catch (DataIntegrityViolationException ex) {
        // Duplicate idempotency key — return original response
        return idempotencyKeyRepository
            .findByKeyAndOperation(request.idempotencyKey(), "ACCOUNT_CREATE")
            .map(ik -> deserialize(ik.getResponseBody(), AccountResponse.class))
            .orElseThrow(() -> new IllegalStateException("Constraint violation but no cached response", ex));
    }
}
```

### Actuator + Prometheus Configuration (`application.yml`)
```yaml
# Source: Context7 /spring-projects/spring-boot actuator monitoring
# [VERIFIED]

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info,metrics
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: token-engine

spring:
  jpa:
    hibernate:
      ddl-auto: validate        # Flyway manages schema; JPA only validates
    show-sql: false
  flyway:
    locations: classpath:db/migration
```

### `@ServiceConnection` Test Configuration
```java
// Source: https://medium.com/@aleksanderkolata/integration-tests-with-testcontainers-and-spring-boot-3-1-39103ff95bd7
// [VERIFIED: Context7 /spring-projects/spring-boot dev-services.adoc]

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `spring.factories` for autoconfigure registration | `AutoConfiguration.imports` | Spring Boot 3.0 (2022) | Old file is silently ignored in Boot 3; starter won't register |
| `@RunWith(Cucumber.class)` (JUnit 4) | `@Suite` + `@IncludeEngines("cucumber")` (JUnit 5) | Cucumber 7.0 (2021) | Old annotation deprecated; causes test discovery failures with JUnit Platform |
| `@DynamicPropertySource` for Testcontainers | `@ServiceConnection` | Spring Boot 3.1 (2023) | `@ServiceConnection` is zero-boilerplate and type-safe |
| Hibernate custom `UserType` for JSONB | `@JdbcTypeCode(SqlTypes.JSON)` | Hibernate 6.0 / Spring Boot 3.0 (2022) | Hypersistence Utils or custom types no longer needed for basic JSON mapping |
| `flyway-core` covers all databases | `flyway-core` + `flyway-database-postgresql` | Flyway 10.0 (2023) | Database support modules must be declared explicitly |
| `spring.factories` style Boot 2.x starters | `AutoConfiguration.imports` + `@AutoConfiguration` | Spring Boot 3.0 | Old `@Configuration` on autoconfigure classes still works but `@AutoConfiguration` is the standard |
| Spring Boot 3.4.x (OSS) | Spring Boot 3.5.x (current OSS) | EOL Dec 31 2025 | D-02 specifies 3.4.x; 3.5.x is the actively supported line |

**Deprecated/outdated:**
- `spring.factories` (autoconfigure): silently ignored in Spring Boot 3+, replaced by `AutoConfiguration.imports`
- Hypersistence Utils `JsonType` for JSONB: no longer needed in Hibernate 6 — use `@JdbcTypeCode`
- `@RunWith(Cucumber.class)`: JUnit 4 runner, deprecated since Cucumber 7; `@Suite` is the replacement
- Spring Boot 3.4.x: OSS support ended 2025-12-31; use 3.5.x

---

## BigDecimal / NUMERIC(38,18) Mapping Details

**Hibernate 6 default behavior:** `BigDecimal` maps to `NUMERIC` JDBC type automatically. Precision and scale from `@Column` are used for DDL generation.

**JPA annotation form:**
```java
@Column(name = "balance", nullable = false, precision = 38, scale = 18)
private BigDecimal balance;
```

This maps to `NUMERIC(38,18)` in Postgres DDL when using `spring.jpa.hibernate.ddl-auto=create` or `update`. Since Flyway manages the schema (D-04), use `ddl-auto: validate` — Hibernate will validate that the DB column matches the entity annotation.

**Critical:** Never use `Double` or `double` for any token amount. `NUMERIC` in Postgres is an exact type; `DOUBLE PRECISION` is floating-point. The `@Column` precision/scale annotations on `BigDecimal` fields enforce this.

**Jackson BigDecimal from JSON:** Configure:
```java
// In ObjectMapper customization or via properties
spring.jackson.deserialization.use-big-decimal-for-floats=true
```
This prevents `10.5` in request JSON from being deserialized as `Double` before conversion.

`[VERIFIED: Context7 JPA patterns; Hibernate ORM User Guide on NUMERIC mapping — ASSUMED: specific Jackson property name — verify against current Spring Boot 3.5 docs during implementation]`

---

## Walking Skeleton Pattern

The walking skeleton is the thinnest possible end-to-end slice that proves the architecture is connected — not complete functionality, just the path working. For this project:

**Walking Skeleton = the "do nothing but prove everything is wired" state:**
1. Three Gradle modules compile
2. `engine-service` starts with an embedded Tomcat
3. Flyway runs V1 migration against Postgres (from Testcontainers)
4. `GET /actuator/health` returns `{"status":"UP"}`
5. The Testcontainers Cucumber scenario for the Actuator passes

**This is Wave 0 / Wave 1 boundary.** Every subsequent wave adds one domain capability on top of a green skeleton.

The walking skeleton should NOT include any domain logic. It includes:
- The three-module Gradle wiring
- One trivial Flyway migration (even just the `accounts` table shell)
- `TokenEngineProperties` binding
- `@SpringBootApplication` main class
- Actuator health endpoint configuration
- One Cucumber scenario: "Given the service starts, When I GET /actuator/health, Then status is UP"
- The Testcontainers + `@ServiceConnection` infrastructure

`[ASSUMED: walking skeleton scope — standard agile definition applied to this project context]`

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Spring Boot 3.5.3 is drop-in compatible with 3.4.x for Phase 1 scope | Standard Stack / State of the Art | Low — Spring follows strict semver within 3.x; incompatibilities would be documented in release notes |
| A2 | `spring.jackson.deserialization.use-big-decimal-for-floats=true` is the correct property name in Spring Boot 3.5 | BigDecimal section | Medium — property names occasionally change; verify during implementation |
| A3 | The idempotency catch pattern (catch `DataIntegrityViolationException`, query stored response) in the code example | Code Examples | Low — pattern is well-established; implementation details may vary |
| A4 | Walking skeleton scope (Wave 0 contents) | Walking Skeleton Pattern | Low — scope is derived from the phase goal; planner can adjust wave boundaries |
| A5 | `postgres:17-alpine` image works with Testcontainers and the project's Postgres feature set | Code Examples | Low — Postgres 17 is stable; test with explicit version if needed |

---

## Open Questions

1. **Spring Boot version: stick with 3.4.x (EOL) or upgrade to 3.5.x?**
   - What we know: D-02 specifies 3.4.x; 3.4 OSS support ended 2025-12-31; 3.5.3 is current OSS release
   - What's unclear: Whether the project has a specific reason to pin to 3.4 (e.g., known client compatibility requirement)
   - Recommendation: Use 3.5.3. Surface this to the user during planning. If 3.4 is required for a documented reason, it works — just acknowledge it's commercial-support-only.

2. **Package naming: `io.certacota.engine.*` or something else?**
   - What we know: CLAUDE.md notes package naming is Claude's discretion
   - Recommendation: Use `io.certacota.engine.core`, `io.certacota.engine.spring`, `io.certacota.engine.service` — mirrors the module names, consistent with the project's existing namespace

3. **Idempotency key scope: global per key, or scoped per operation?**
   - What we know: FUND-01 says "caller-supplied idempotency key" with a UNIQUE constraint; D-05 says account creation carries an idempotency key
   - What's unclear: Should the same key be reusable for different operation types (e.g., idempotency key "abc" for account creation AND for a future debit)?
   - Recommendation: Scope the UNIQUE constraint to `(idempotency_key, operation)` composite — allows the same key string to be used for different operation types without collision. This is the pattern shown in the DDL above.

4. **`engine-core` entity dependency — JPA annotations without Spring?**
   - What we know: `engine-core` must have zero Spring dependencies (D-05); JPA annotations are in `jakarta.persistence` (not Spring)
   - What's unclear: Whether `engine-core` should include `jakarta.persistence-api` as `compileOnly` or `implementation`
   - Recommendation: `compileOnly` — `engine-spring` brings the full JPA runtime (Hibernate) via `spring-boot-starter-data-jpa`. The entity annotations compile against the API jar; the runtime Hibernate implementation is provided by the dependent module.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | Cucumber 7.22.1 + JUnit 5 Platform + Testcontainers 1.21.3 |
| Config file | `junit-platform.properties` (optional; can use `@ConfigurationParameter` on suite runner instead) |
| Quick run command | `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i` |
| Full suite command | `./gradlew :engine-service:test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| ACCT-01 | POST /api/v1/accounts creates account with initial balance; balance is returned | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| ACCT-02 | DELETE /api/v1/accounts/{id} closes account; rejected if streaming active | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| ACCT-03 | GET /api/v1/accounts/{id} returns committed balance | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| FUND-01 | POST with same idempotency key twice returns identical response; one audit log entry | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| FUND-02 | Account creation writes audit log entry visible immediately in Postgres | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| FUND-03 | GET /actuator/health returns UP; GET /actuator/prometheus returns 200 | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |
| BAL-01 | Same as ACCT-03 | Cucumber integration | (covered above) | Wave 0 gap |
| BAL-03 | Operation below floor returns 422/400; balance unchanged; no audit entry created | Cucumber integration | `./gradlew :engine-service:test` | Wave 0 gap |

### Cucumber Scenarios for 5 Phase 1 Success Criteria

**Feature: account-lifecycle.feature (Success Criterion 1)**
```gherkin
Feature: Account lifecycle
  Scenario: Create retrieve and close an account
    Given no account with id "acct-001" exists
    When I create an account with id "acct-001" and initial balance 100.00
    Then the account "acct-001" exists with committed balance 100.00
    When I close account "acct-001"
    Then account "acct-001" has status CLOSED

  Scenario: Close account with no active streams succeeds
    Given account "acct-002" exists and is ACTIVE with no active streaming transactions
    When I close account "acct-002"
    Then the response status is 200

  Scenario: Close account with active streaming transactions is rejected
    Given account "acct-003" exists with an active streaming transaction
    When I close account "acct-003"
    Then the response status is 409
    And the error message mentions active streaming transactions
```

**Feature: idempotency.feature (Success Criterion 2)**
```gherkin
Feature: Idempotency enforcement
  Scenario: Submitting the same write twice with same idempotency key returns same result
    Given I have idempotency key "idem-abc-123"
    When I create an account with idempotency key "idem-abc-123" and balance 50.00
    And I create an account again with idempotency key "idem-abc-123" and balance 50.00
    Then both responses are identical
    And there is exactly 1 audit log entry for account creation

  Scenario: UNIQUE constraint enforces idempotency without application pre-check
    Given the database has a UNIQUE constraint on (idempotency_key, operation)
    When two concurrent requests arrive with idempotency key "concurrent-key"
    Then exactly one account creation succeeds
    And exactly one audit log entry exists
```

**Feature: audit-log.feature (Success Criterion 3)**
```gherkin
Feature: Audit log immutability
  Scenario: Every balance change is immediately visible in the audit log
    When I create an account with id "audit-001" and initial balance 200.00
    Then the balance_audit_log table contains exactly 1 entry for account "audit-001"
    And the entry has operation "ACCOUNT_CREATED" and balance_after 200.00

  Scenario: Audit log entries cannot be modified
    Given an audit log entry exists for account "audit-001"
    Then no UPDATE or DELETE statement can affect the balance_audit_log table
```

**Feature: balance-floor.feature (Success Criterion 4)**
```gherkin
Feature: Balance floor enforcement
  Scenario: Operation taking balance below global floor is rejected before write
    Given the global balance floor is 0
    And account "floor-001" has balance 10.00
    When I attempt to debit 15.00 from account "floor-001"
    Then the response status is 422
    And the balance of account "floor-001" remains 10.00
    And no audit log entry was created for that attempt

  Scenario: Per-account floor override takes precedence over global
    Given the global balance floor is 0
    And account "floor-002" has a balance floor of 50.00 and balance 60.00
    When I attempt to debit 15.00 from account "floor-002"
    Then the response status is 422
    And the balance of account "floor-002" remains 60.00
```

**Feature: observability.feature (Success Criterion 5)**
```gherkin
Feature: Actuator and metrics endpoints
  Scenario: Health endpoint returns UP against live Postgres
    Given the application is started with a live Postgres instance via Testcontainers
    When I GET /actuator/health
    Then the response status is 200
    And the response body contains status UP

  Scenario: Prometheus metrics endpoint is reachable
    Given the application is started
    When I GET /actuator/prometheus
    Then the response status is 200
    And the response content type contains text/plain
```

### Sampling Rate
- **Per task commit:** `./gradlew :engine-service:test --tests "*.CucumberTestRunner" -i`
- **Per wave merge:** `./gradlew test` (all three modules)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `engine-service/src/test/java/.../CucumberTestRunner.java` — suite entry point
- [ ] `engine-service/src/test/java/.../CucumberSpringConfiguration.java` — `@CucumberContextConfiguration`
- [ ] `engine-service/src/test/java/.../TestcontainersConfiguration.java` — `@ServiceConnection` config
- [ ] `engine-service/src/test/java/.../steps/` — step definition files (5, one per feature)
- [ ] `engine-service/src/test/resources/features/` — 5 feature files (skeletons with `@Pending` scenarios)
- [ ] `engine-service/src/main/resources/db/migration/` — V1, V2, V3 SQL files
- [ ] `engine-service/src/main/resources/application.yml` — Actuator exposure, Flyway, JPA config
- [ ] `engine-core/build.gradle`, `engine-spring/build.gradle`, `engine-service/build.gradle` — Gradle module files
- [ ] `settings.gradle`, root `build.gradle` — multi-module root

---

## Security Domain

> No explicit `security_enforcement: false` in config.json — section included.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No — engine trusts caller-supplied IDs per project constraint | N/A (platform's responsibility) |
| V3 Session Management | No | N/A |
| V4 Access Control | No — not in Phase 1 scope | N/A |
| V5 Input Validation | Yes — account ID format, BigDecimal bounds, required fields | Spring Validation (`@Valid`, `@NotNull`, `@Positive`) |
| V6 Cryptography | No — no sensitive data stored encrypted | N/A |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Negative balance below floor via race condition | Tampering | DB-level check + `@Transactional`; floor enforcement inside the transaction, not before it |
| Idempotency key collision (deliberate or accidental) | Spoofing | UNIQUE constraint scoped to `(idempotency_key, operation)` — same key for different ops is allowed |
| Audit log tampering | Tampering | Append-only table; revoke DELETE/UPDATE grants from the application DB user via Flyway migration |
| Integer overflow in NUMERIC(38,18) | Tampering | Postgres enforces precision; `BigDecimal` comparisons handle boundary conditions correctly |
| BigDecimal → Double deserialization | Tampering | Configure Jackson `use-big-decimal-for-floats=true`; never accept `double` in request DTOs |

`[ASSUMED: DB-level row-level security or GRANT REVOKE for audit log — verify whether to include in Flyway migrations or deployment ops]`

---

## Sources

### Primary (HIGH confidence)
- Context7 `/spring-projects/spring-boot` — multi-module Gradle BOM, Flyway autoconfigure, Actuator exposure, Testcontainers `@ServiceConnection`, autoconfigure registration
- Context7 `/cucumber/cucumber-jvm` — `@CucumberContextConfiguration`, JUnit Platform Suite runner, `@Suite`/`@IncludeEngines`, glue path configuration
- Context7 `/websites/cucumber_io` — `cucumber-spring` dependency
- Context7 `/websites/java_testcontainers` — `PostgreSQLContainer` setup
- Maven Central API — Spring Boot 3.5.3 (published 2026-05), Testcontainers 1.21.3, cucumber-bom 7.22.1

### Secondary (MEDIUM confidence)
- [spring.io/guides/gs/multi-module](https://spring.io/guides/gs/multi-module/) — multi-module Gradle Groovy DSL file structure, `include` in settings.gradle, `apply false` pattern for library modules
- [Integration Tests with Testcontainers and Spring Boot 3.1+](https://medium.com/@aleksanderkolata/integration-tests-with-testcontainers-and-spring-boot-3-1-39103ff95bd7) — `@ServiceConnection` `@TestConfiguration` pattern (cross-verified against Context7 official docs)
- [How to Map a JSON Column with JPA 3 and Hibernate 6](https://dev.to/antozanini/how-to-map-a-json-column-in-spring-boot-with-jpa-3-and-hibernate-6-5gd5) — `@JdbcTypeCode(SqlTypes.JSON)` for JSONB (cross-verified against Hibernate 6 docs)
- [endoflife.date/spring-boot](https://endoflife.date/spring-boot) — Spring Boot 3.4 EOL confirmed 2025-12-31; 3.5 active OSS support

### Tertiary (LOW confidence)
- WebSearch results on idempotency key table design — general pattern confirmed across multiple sources; specific column naming is Claude's discretion

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions verified against Maven Central; docs verified via Context7
- Architecture: HIGH — module separation follows official Spring Boot multi-module Gradle guide
- Pitfalls: HIGH — all pitfalls are verified framework-specific gotchas (Boot plugin on libraries, Flyway 10 split, Cucumber context annotation, etc.)
- BigDecimal mapping: HIGH — Hibernate 6 NUMERIC mapping documented; Jackson property name MEDIUM

**Research date:** 2026-05-13
**Valid until:** 2026-06-13 (stable libraries; Spring Boot patch releases won't break this)
