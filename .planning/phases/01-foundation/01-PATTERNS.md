# Phase 1: Foundation - Pattern Map

**Mapped:** 2026-05-13
**Files analyzed:** 21
**Analogs found:** 0 / 21 (greenfield — all patterns are external references from RESEARCH.md)

> This is a greenfield project. No local codebase exists. "Closest analog" means the
> authoritative external reference (official Spring Boot guide, verified library docs,
> or code example from RESEARCH.md) that the executor MUST copy directly.

---

## File Classification

| New File | Role | Data Flow | Authoritative Reference | Match Quality |
|----------|------|-----------|------------------------|---------------|
| `settings.gradle` | config | — | Spring multi-module Gradle guide (RESEARCH Pattern 1) | canonical |
| `build.gradle` (root) | config | — | Spring multi-module Gradle guide (RESEARCH Pattern 1) | canonical |
| `engine-core/build.gradle` | config | — | Spring dependency-management guide (RESEARCH Pattern 1) | canonical |
| `engine-spring/build.gradle` | config | — | Spring dependency-management guide (RESEARCH Pattern 1) | canonical |
| `engine-service/build.gradle` | config | — | Spring Boot plugin guide + RESEARCH Pattern 1 | canonical |
| `engine-core/.../Account.java` | model | CRUD | RESEARCH Code Examples — JPA Entity section | canonical |
| `engine-core/.../BalanceAuditLog.java` | model | CRUD | RESEARCH DDL V3 + Account.java pattern | role-match |
| `engine-core/.../IdempotencyKey.java` | model | CRUD | RESEARCH DDL V2 + Account.java pattern | role-match |
| `engine-core/.../AccountRepository.java` | repository | CRUD | Spring Data JPA `JpaRepository` | canonical |
| `engine-core/.../BalanceAuditLogRepository.java` | repository | CRUD | Spring Data JPA `JpaRepository` | canonical |
| `engine-core/.../IdempotencyKeyRepository.java` | repository | CRUD | Spring Data JPA `JpaRepository` | canonical |
| `engine-core/.../AccountService.java` | service | request-response | RESEARCH Architecture Diagram | canonical |
| `engine-core/.../exception/*.java` (4 files) | utility | — | Spring Boot exception hierarchy convention | canonical |
| `engine-spring/.../TokenEngineAutoConfiguration.java` | config | — | RESEARCH Pattern 6 — `@AutoConfiguration` | canonical |
| `engine-spring/.../TokenEngineProperties.java` | config | — | Spring `@ConfigurationProperties` | canonical |
| `engine-spring/.../AccountServiceImpl.java` | service | request-response | RESEARCH Code Examples — idempotency pattern | canonical |
| `engine-service/.../EngineServiceApplication.java` | config | — | Spring Boot `@SpringBootApplication` | canonical |
| `engine-service/.../AccountController.java` | controller | request-response | Spring `@RestController` thin-delegator convention | canonical |
| `engine-service/src/main/resources/application.yml` | config | — | RESEARCH Code Examples — Actuator + Prometheus config | canonical |
| `engine-service/src/main/resources/db/migration/V1__create_accounts.sql` | migration | — | RESEARCH Code Examples — DDL V1 | canonical |
| `engine-service/src/main/resources/db/migration/V2__create_idempotency_keys.sql` | migration | — | RESEARCH Code Examples — DDL V2 | canonical |
| `engine-service/src/main/resources/db/migration/V3__create_balance_audit_log.sql` | migration | — | RESEARCH Code Examples — DDL V3 | canonical |
| `engine-service/src/test/.../CucumberTestRunner.java` | test | — | RESEARCH Pattern 3 — `@Suite` runner | canonical |
| `engine-service/src/test/.../CucumberSpringConfiguration.java` | test | — | RESEARCH Pattern 4 — `@CucumberContextConfiguration` | canonical |
| `engine-service/src/test/.../TestcontainersConfiguration.java` | test | — | RESEARCH Pattern 2 — `@ServiceConnection` | canonical |
| `engine-service/src/test/.../steps/*.java` (5 files) | test | request-response | cucumber-spring step definition pattern | canonical |
| `engine-service/src/test/resources/features/*.feature` (5 files) | test | — | RESEARCH Validation Architecture — Cucumber scenarios | canonical |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | config | — | RESEARCH Pattern 6 | canonical |

---

## Pattern Assignments

### `settings.gradle` (root config)

**Reference:** Spring Boot multi-module Gradle guide — https://spring.io/guides/gs/multi-module/

**Copy this exactly** (RESEARCH.md Pattern 1, lines 247-254):
```groovy
rootProject.name = 'token-engine'
include 'engine-core'
include 'engine-spring'
include 'engine-service'
```

**Constraints:**
- D-01: Groovy DSL only — no `settings.gradle.kts`
- D-05: All three modules declared here

---

### `build.gradle` (root, shared subproject config)

**Reference:** Gradle multi-project builds docs — https://docs.gradle.org/current/userguide/multi_project_builds.html

**Copy this exactly** (RESEARCH.md Pattern 1, lines 257-279):
```groovy
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

**Constraints:**
- D-01: Groovy DSL
- D-03: Java 21 toolchain
- Do NOT apply `org.springframework.boot` here — Boot plugin is `engine-service` only

---

### `engine-core/build.gradle` (library module, no Boot plugin)

**Reference:** Spring dependency-management plugin docs (RESEARCH.md Pattern 1, lines 282-308)

**Copy this exactly:**
```groovy
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

**Critical constraints:**
- NO `id 'org.springframework.boot'` — applying it creates a fat JAR that breaks the dependency graph (RESEARCH Pitfall 1)
- `jakarta.persistence-api` is `compileOnly` — Hibernate runtime is provided by `engine-spring` via `spring-boot-starter-data-jpa` (RESEARCH Open Question 4)
- BOM version is `3.5.3` not `3.4.x` — Spring Boot 3.4 OSS support ended 2025-12-31 (RESEARCH Summary / State of the Art)

---

### `engine-spring/build.gradle` (autoconfigure library module)

**Reference:** RESEARCH.md Pattern 1, lines 311-338

**Copy this exactly:**
```groovy
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

**Critical constraints:**
- NO Boot plugin — library only
- `spring-boot-autoconfigure-processor` in `annotationProcessor` generates `spring-autoconfigure-metadata.properties` at compile time (required for Spring Boot startup optimization)

---

### `engine-service/build.gradle` (runnable service — only module with Boot plugin)

**Reference:** RESEARCH.md Pattern 1, lines 341-377

**Copy this exactly:**
```groovy
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

**Critical constraints:**
- Both `flyway-core` AND `flyway-database-postgresql` are required — Flyway 10+ split Postgres support into a separate module (RESEARCH Pitfall 2)
- Cucumber BOM declared as `platform(...)` — ensures `cucumber-java`, `cucumber-spring`, `cucumber-junit-platform-engine` stay version-aligned (RESEARCH Supporting libs)
- `spring-boot-starter-data-jpa` is repeated here intentionally — `engine-service` needs the JPA autoconfigure for its own test context and Flyway integration

---

### `engine-core/.../Account.java` (JPA entity, domain model)

**Package:** `io.certacota.engine.core.domain`

**Reference:** RESEARCH.md Code Examples — JPA Entity section, lines 639-675

**Copy this pattern exactly:**
```java
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

    @Column(name = "balance_floor", precision = 38, scale = 18)
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

**Also create:** `AccountStatus.java` enum in the same package with values `ACTIVE`, `CLOSED`.

**Critical constraints:**
- D-07: `id` is a caller-supplied `String` — no `@GeneratedValue`, no UUID generation
- D-10: `BigDecimal` with `precision = 38, scale = 18` on all amount columns
- D-11: `balanceFloor` is nullable — `null` means "use global property"
- D-08: `metadata` is `Map<String, Object>` mapped with `@JdbcTypeCode(SqlTypes.JSON)` — no custom type needed in Hibernate 6 (RESEARCH Don't Hand-Roll)
- Use `@Getter` + `@Builder` + `@NoArgsConstructor` + `@AllArgsConstructor` (Lombok) — matches CLAUDE.md convention; no `@Setter` (entity mutation goes through builder or explicit setter methods)
- Import: `org.hibernate.annotations.JdbcTypeCode` and `org.hibernate.type.SqlTypes`

---

### `engine-core/.../BalanceAuditLog.java` (append-only audit log entity)

**Package:** `io.certacota.engine.core.domain`

**Reference:** RESEARCH.md DDL V3 (lines 622-636) + Account.java pattern above

**Pattern to follow:**
```java
@Entity
@Table(name = "balance_audit_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Column(name = "operation", nullable = false, updatable = false, length = 50)
    private String operation;

    @Column(name = "amount", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 38, scale = 18)
    private BigDecimal balanceAfter;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "recorded_at", updatable = false)
    private OffsetDateTime recordedAt;
}
```

**Critical constraints:**
- All columns are `updatable = false` — this table is append-only; service layer never calls UPDATE (FUND-02)
- `@GeneratedValue(strategy = GenerationType.IDENTITY)` maps to `BIGSERIAL` in Postgres DDL
- No FK relationship to `IdempotencyKey` entity — audit log is independent (RESEARCH DDL V3 comment)
- `operation` values: `"ACCOUNT_CREATED"` for Phase 1; more added in Phase 2+

---

### `engine-core/.../IdempotencyKey.java` (idempotency enforcement entity)

**Package:** `io.certacota.engine.core.domain`

**Reference:** RESEARCH.md DDL V2 (lines 608-619) + Account.java pattern

**Pattern to follow:**
```java
@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_idempotency_key",
        columnNames = {"idempotency_key", "operation"}
    )
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "operation", nullable = false, updatable = false, length = 50)
    private String operation;

    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
```

**Critical constraints:**
- UNIQUE constraint is `(idempotency_key, operation)` composite — same key string can be used for different operation types (RESEARCH Open Question 3)
- `responseBody` is `TEXT` (no length limit) — stores serialized JSON of the original response
- The UNIQUE constraint in the `@Table` annotation mirrors the DDL V2 constraint — Hibernate `ddl-auto: validate` will check this

---

### `engine-core/.../AccountRepository.java` (Spring Data repository interface)

**Package:** `io.certacota.engine.core.repository`

**Reference:** Spring Data JPA `JpaRepository` docs

**Pattern:**
```java
public interface AccountRepository extends JpaRepository<Account, String> {
    // No custom queries needed in Phase 1 — JpaRepository provides findById, save, deleteById
}
```

**Constraints:**
- Resides in `engine-core` — Spring Data's `JpaRepository` is in `jakarta.persistence` / `spring-data-jpa`; this module has `jakarta.persistence-api` as `compileOnly` and the Spring Data dependency comes transitively from `engine-spring`
- Phase 1 needs only `findById`, `save`, `existsById` — all provided by `JpaRepository`

---

### `engine-core/.../BalanceAuditLogRepository.java` (audit log repository)

**Package:** `io.certacota.engine.core.repository`

**Pattern:**
```java
public interface BalanceAuditLogRepository extends JpaRepository<BalanceAuditLog, Long> {
    List<BalanceAuditLog> findByAccountId(String accountId);
}
```

**Constraints:**
- `findByAccountId` is needed by Cucumber step definitions to assert audit log entries exist (RESEARCH Validation Architecture — audit-log.feature scenario)
- INSERT-only from service layer — repository itself has no restriction, but `AccountServiceImpl` must never call `delete` or execute updates on this table

---

### `engine-core/.../IdempotencyKeyRepository.java` (idempotency key repository)

**Package:** `io.certacota.engine.core.repository`

**Pattern:**
```java
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByIdempotencyKeyAndOperation(String idempotencyKey, String operation);
}
```

**Constraints:**
- `findByIdempotencyKeyAndOperation` is used in the `DataIntegrityViolationException` catch block to retrieve the stored response for replay (RESEARCH Code Examples — idempotency pattern, lines 729-735)

---

### `engine-core/.../AccountService.java` (service interface)

**Package:** `io.certacota.engine.core.service`

**Reference:** RESEARCH.md Architecture Diagram — interface only in `engine-core`

**Pattern:**
```java
public interface AccountService {
    AccountResponse createAccount(CreateAccountRequest request);
    AccountResponse getAccount(String accountId);
    AccountResponse closeAccount(String accountId);
}
```

**Constraints:**
- D-05: Interface only in `engine-core` — implementation (`AccountServiceImpl`) lives in `engine-spring`
- DTOs (`CreateAccountRequest`, `AccountResponse`) also live in `engine-core` alongside the service interface
- No Spring annotations in this interface — `engine-core` has zero Spring dependencies

---

### `engine-core/.../exception/*.java` (4 domain exception classes)

**Package:** `io.certacota.engine.core.exception`

**Reference:** CLAUDE.md — custom exception hierarchy; RESEARCH Pitfall 6 notes

**Files to create and their HTTP mapping (used by `engine-service` global exception handler):**

| Class | Extends | HTTP Status | Trigger |
|-------|---------|-------------|---------|
| `AccountNotFoundException` | `RuntimeException` | 404 | Account ID not found in DB |
| `BalanceFloorViolationException` | `RuntimeException` | 422 | Resulting balance < effective floor |
| `DuplicateIdempotencyKeyException` | `RuntimeException` | — | Re-thrown from `DataIntegrityViolationException` catch; caller gets the cached response, not this exception |
| `AccountClosedException` | `RuntimeException` | 409 | Close called on already-CLOSED account, or any operation on CLOSED account |

**Pattern (copy for each):**
```java
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
```

**Constraints:**
- CLAUDE.md: No generic `RuntimeException` thrown from services — always a typed domain exception
- No Spring annotations — these are in `engine-core`

---

### `engine-spring/.../TokenEngineAutoConfiguration.java` (Spring Boot autoconfigure)

**Package:** `io.certacota.engine.spring.autoconfigure`

**Reference:** RESEARCH.md Pattern 6, lines 474-494

**Copy this pattern:**
```java
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

**Constraints:**
- `@AutoConfiguration` (not `@Configuration`) — Spring Boot 3.x standard (RESEARCH State of the Art)
- `@ConditionalOnMissingBean` on the `AccountService` bean — allows embedding app to override with its own impl
- The file `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` in `engine-spring/src/main/resources/` must contain the fully-qualified class name: `io.certacota.engine.spring.autoconfigure.TokenEngineAutoConfiguration`

---

### `engine-spring/.../TokenEngineProperties.java` (config properties binding)

**Package:** `io.certacota.engine.spring.config`

**Reference:** Spring `@ConfigurationProperties` docs; D-11

**Pattern:**
```java
@ConfigurationProperties(prefix = "token-engine")
@Getter
@Setter
public class TokenEngineProperties {

    private BigDecimal balanceFloor = BigDecimal.ZERO;
}
```

**Constraints:**
- D-11: Property name is `token-engine.balance-floor`; default is `0`
- Lombok `@Getter` + `@Setter` required — Spring Boot config binding calls setters
- `BigDecimal.ZERO` as default means accounts with no per-account floor enforce a floor of 0

---

### `engine-spring/.../AccountServiceImpl.java` (service implementation)

**Package:** `io.certacota.engine.spring.service`

**Reference:** RESEARCH.md Code Examples — idempotency pattern (lines 699-736) + floor enforcement pattern (lines 682-692)

**Core structure to follow:**
```java
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final BalanceAuditLogRepository auditLogRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TokenEngineProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public AccountResponse createAccount(CreateAccountRequest request) {
        try {
            // 1. Write idempotency key (DB UNIQUE constraint enforces idempotency)
            idempotencyKeyRepository.saveAndFlush(IdempotencyKey.builder()
                .idempotencyKey(request.idempotencyKey())
                .operation("ACCOUNT_CREATE")
                .responseBody("")  // placeholder — updated after account save
                .createdAt(OffsetDateTime.now())
                .build());

            // 2. Enforce floor on initial balance
            BigDecimal initialBalance = request.initialBalance() != null
                ? request.initialBalance() : BigDecimal.ZERO;
            enforceBalanceFloor(null, initialBalance);

            // 3. Create and persist account
            Account account = accountRepository.save(Account.builder()
                .id(request.id())
                .status(AccountStatus.ACTIVE)
                .balance(initialBalance)
                .balanceFloor(request.balanceFloor())
                .metadata(request.metadata())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());

            // 4. Audit log inside same @Transactional scope
            auditLogRepository.save(BalanceAuditLog.builder()
                .accountId(account.getId())
                .operation("ACCOUNT_CREATED")
                .amount(initialBalance)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(initialBalance)
                .idempotencyKey(request.idempotencyKey())
                .recordedAt(OffsetDateTime.now())
                .build());

            AccountResponse response = AccountResponse.from(account);

            // 5. Store serialized response for idempotency replay
            storeIdempotencyResponse(request.idempotencyKey(), "ACCOUNT_CREATE", response);

            return response;

        } catch (DataIntegrityViolationException ex) {
            return idempotencyKeyRepository
                .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "ACCOUNT_CREATE")
                .map(ik -> deserialize(ik.getResponseBody(), AccountResponse.class))
                .orElseThrow(() -> new IllegalStateException(
                    "Constraint violation but no cached response found", ex));
        }
    }

    private void enforceBalanceFloor(Account account, BigDecimal resultingBalance) {
        BigDecimal effectiveFloor = (account != null && account.getBalanceFloor() != null)
            ? account.getBalanceFloor()
            : properties.getBalanceFloor();
        if (resultingBalance.compareTo(effectiveFloor) < 0) {
            throw new BalanceFloorViolationException(
                "Balance " + resultingBalance + " is below floor " + effectiveFloor);
        }
    }
}
```

**Critical constraints:**
- `@Transactional` on the class/method — NOT on the controller (RESEARCH Pitfall 6)
- Audit INSERT and balance UPDATE share one transaction — if either fails, both roll back (FUND-02 / RESEARCH Architecture)
- Use `compareTo` not `equals` for `BigDecimal` floor check (RESEARCH Pitfall 7)
- `saveAndFlush` for the idempotency key so the constraint violation fires before proceeding — otherwise the exception may surface at commit time, making the catch block harder to handle
- Catch `DataIntegrityViolationException` (Spring), NOT `ConstraintViolationException` (JPA) — Spring wraps JPA exceptions (RESEARCH Anti-Patterns)
- `@RequiredArgsConstructor` + constructor injection — CLAUDE.md convention; no field injection

---

### `engine-service/.../EngineServiceApplication.java` (main class)

**Package:** `io.certacota.engine.service`

**Reference:** Spring Boot `@SpringBootApplication` docs

**Pattern:**
```java
@SpringBootApplication
public class EngineServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngineServiceApplication.class, args);
    }
}
```

**Constraints:**
- D-05: This is the only `@SpringBootApplication` in the entire project
- No component scan configuration needed — default scan covers `io.certacota.engine.service.*`; autoconfigure in `engine-spring` registers via `AutoConfiguration.imports`

---

### `engine-service/.../AccountController.java` (REST controller)

**Package:** `io.certacota.engine.service.controller`

**Reference:** Spring `@RestController` thin-delegator pattern; CLAUDE.md — controllers are thin

**Pattern:**
```java
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(
            @Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }

    @DeleteMapping("/{accountId}")
    public AccountResponse closeAccount(@PathVariable String accountId) {
        return accountService.closeAccount(accountId);
    }
}
```

**Also create:** `GlobalExceptionHandler.java` in `engine-service/.../controller/` (or `advice/`):
```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(AccountNotFoundException ex) {
        log.warn("Account not found: {}", ex.getMessage());
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(BalanceFloorViolationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleFloorViolation(BalanceFloorViolationException ex) {
        log.warn("Balance floor violation: {}", ex.getMessage());
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(AccountClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleClosed(AccountClosedException ex) {
        log.warn("Account closed: {}", ex.getMessage());
        return Map.of("error", ex.getMessage());
    }
}
```

**Critical constraints:**
- CLAUDE.md: Controllers are thin — delegate all logic to service immediately; zero business logic
- No `@Transactional` on controllers (RESEARCH Pitfall 6)
- `@Valid` on request body — triggers Bean Validation (Spring Boot Validation starter in `engine-service`)
- REST path prefix `/api/v1/accounts` — Claude's discretion per CONTEXT.md

---

### `engine-service/src/main/resources/application.yml`

**Reference:** RESEARCH.md Code Examples — Actuator + Prometheus config (lines 740-763)

**Copy this exactly:**
```yaml
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
      ddl-auto: validate
    show-sql: false
  flyway:
    locations: classpath:db/migration
  jackson:
    deserialization:
      use-big-decimal-for-floats: true

token-engine:
  balance-floor: 0
```

**Constraints:**
- `ddl-auto: validate` — Flyway manages schema; JPA only validates alignment (D-04)
- `use-big-decimal-for-floats: true` — prevents `10.5` from being deserialized as `Double` (RESEARCH Pitfall 7 / BigDecimal section)
- `token-engine.balance-floor: 0` — sets default global floor (D-11)
- No `spring.datasource.url` hardcoded — `@ServiceConnection` in tests overrides datasource; production gets it from environment variables

**Also create:** `application-test.yml` (empty or minimal — `@ServiceConnection` handles the Postgres URL):
```yaml
# Test datasource injected by @ServiceConnection — no overrides needed here
spring:
  flyway:
    locations: classpath:db/migration
```

---

### `V1__create_accounts.sql` (Flyway migration)

**Reference:** RESEARCH.md Code Examples — DDL V1 (lines 591-604)

**Copy this exactly:**
```sql
CREATE TABLE accounts (
    id              VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    balance         NUMERIC(38,18)  NOT NULL DEFAULT 0,
    balance_floor   NUMERIC(38,18),
    metadata        JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);
```

**Constraints:**
- D-10: `NUMERIC(38,18)` for all amounts
- D-11: `balance_floor` is nullable
- D-08: `metadata` is `JSONB`

---

### `V2__create_idempotency_keys.sql` (Flyway migration)

**Reference:** RESEARCH.md Code Examples — DDL V2 (lines 607-619)

**Copy this exactly:**
```sql
CREATE TABLE idempotency_keys (
    id              BIGSERIAL       PRIMARY KEY,
    idempotency_key VARCHAR(255)    NOT NULL,
    operation       VARCHAR(50)     NOT NULL,
    response_body   TEXT            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key, operation)
);
```

**Constraints:**
- FUND-01: The `UNIQUE (idempotency_key, operation)` constraint is the idempotency enforcer — no application pre-check
- `response_body TEXT` — stores serialized JSON with no length limit

---

### `V3__create_balance_audit_log.sql` (Flyway migration)

**Reference:** RESEARCH.md Code Examples — DDL V3 (lines 621-636)

**Copy this exactly:**
```sql
CREATE TABLE balance_audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    account_id      VARCHAR(255)    NOT NULL,
    operation       VARCHAR(50)     NOT NULL,
    amount          NUMERIC(38,18)  NOT NULL,
    balance_before  NUMERIC(38,18)  NOT NULL,
    balance_after   NUMERIC(38,18)  NOT NULL,
    idempotency_key VARCHAR(255),
    recorded_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);
```

**Constraints:**
- FUND-02: Append-only — no UPDATE or DELETE grants for the application user (can enforce via REVOKE in a separate migration or deployment script)
- FK to `accounts(id)` ensures referential integrity

---

### `TestcontainersConfiguration.java` (test infrastructure)

**Package:** `io.certacota.engine.service` (test sources)

**Reference:** RESEARCH.md Pattern 2 (lines 387-400) + Code Examples (lines 770-779)

**Copy this exactly:**
```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }
}
```

**Constraints:**
- `@ServiceConnection` auto-configures `spring.datasource.*` properties — do NOT add `spring.datasource.url` to `application-test.yml` (RESEARCH Pitfall 5)
- `proxyBeanMethods = false` is required for `@TestConfiguration` used with Testcontainers
- `postgres:17-alpine` — lean image; version-pinned for reproducibility (RESEARCH Assumption A5)

---

### `CucumberTestRunner.java` (JUnit 5 suite entry point)

**Package:** `io.certacota.engine.service` (test sources)

**Reference:** RESEARCH.md Pattern 3 (lines 407-419)

**Copy this exactly:**
```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "io.certacota.engine.service.steps")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,html:build/reports/cucumber.html")
public class CucumberTestRunner {
}
```

**Imports to include:**
```java
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
```

**Constraints:**
- D-13: Replaces `@RunWith(Cucumber.class)` — the JUnit 4 runner is deprecated (RESEARCH State of the Art)
- No `@SpringBootTest` here — that goes only on `CucumberSpringConfiguration`
- Glue path must match the `steps` package where step definitions live

---

### `CucumberSpringConfiguration.java` (Cucumber Spring context config)

**Package:** `io.certacota.engine.service` (test sources)

**Reference:** RESEARCH.md Pattern 4 (lines 427-440)

**Copy this exactly:**
```java
@CucumberContextConfiguration
@SpringBootTest(
    classes = EngineServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(TestcontainersConfiguration.class)
public class CucumberSpringConfiguration {
}
```

**Critical constraints:**
- EXACTLY ONE class in the glue path carries `@CucumberContextConfiguration` — adding it to any step definition class causes `CucumberContextConfigurationException` (RESEARCH Pitfall 4)
- Step definition classes (`AccountSteps`, etc.) use `@Autowired` only — no `@SpringBootTest` on them
- `RANDOM_PORT` — required for HTTP calls in step definitions; port injected via `@LocalServerPort`

---

### `steps/AccountSteps.java` and sibling step files (5 total)

**Package:** `io.certacota.engine.service.steps` (test sources)

**Reference:** RESEARCH.md Validation Architecture — Cucumber scenarios (lines 912-999)

**Pattern for all step definition classes:**
```java
@Slf4j
public class AccountSteps {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceAuditLogRepository auditLogRepository;

    @LocalServerPort
    private int port;

    private RestTemplate restTemplate = new RestTemplate();
    private ResponseEntity<?> lastResponse;

    @Given("no account with id {string} exists")
    public void noAccountExists(String accountId) {
        accountRepository.deleteById(accountId);
    }

    @When("I create an account with id {string} and initial balance {bigdecimal}")
    public void createAccount(String accountId, BigDecimal balance) {
        // ... HTTP POST to /api/v1/accounts
    }

    @Then("the account {string} exists with committed balance {bigdecimal}")
    public void accountExistsWithBalance(String accountId, BigDecimal expectedBalance) {
        // ... HTTP GET + assert
    }
}
```

**Files to create (one per feature):**
- `AccountSteps.java` — covers `account-lifecycle.feature`
- `IdempotencySteps.java` — covers `idempotency.feature`
- `AuditLogSteps.java` — covers `audit-log.feature`
- `BalanceFloorSteps.java` — covers `balance-floor.feature`
- `ActuatorSteps.java` — covers `observability.feature`

**Critical constraints:**
- NO `@CucumberContextConfiguration` or `@SpringBootTest` on step classes
- Use `@Autowired` for Spring beans injected from the shared context
- `@LocalServerPort` injects the random port selected at test startup
- Step definitions call the REST API via HTTP (not MockMvc) because `WebEnvironment.RANDOM_PORT` starts a real embedded Tomcat

---

### Feature files (5 total, `engine-service/src/test/resources/features/`)

**Reference:** RESEARCH.md Validation Architecture — Cucumber scenarios (lines 912-999)

**Files to create with their scenarios pre-written in RESEARCH.md:**
- `account-lifecycle.feature` — 3 scenarios (create/retrieve/close) — copy from RESEARCH lines 915-934
- `idempotency.feature` — 2 scenarios (same key twice, UNIQUE constraint) — copy from RESEARCH lines 937-950
- `audit-log.feature` — 2 scenarios (entry visible, cannot modify) — copy from RESEARCH lines 953-966
- `balance-floor.feature` — 2 scenarios (global floor, per-account override) — copy from RESEARCH lines 969-983
- `observability.feature` — 2 scenarios (health UP, Prometheus reachable) — copy from RESEARCH lines 986-999

**Constraints:**
- D-13: Gherkin feature files in `src/test/resources/features/`; step definitions in `io.certacota.engine.service.steps`
- D-14: These feature files cover all 5 Phase 1 success criteria

---

## Shared Patterns

### BigDecimal Everywhere (apply to ALL amount fields)

**Source:** RESEARCH.md — BigDecimal / NUMERIC(38,18) Mapping Details (lines 803-824)
**Apply to:** `Account.java`, `BalanceAuditLog.java`, `IdempotencyKey.java` (amount fields), all DTO fields

```java
@Column(name = "balance", nullable = false, precision = 38, scale = 18)
private BigDecimal balance;
```

```java
// Floor check — always compareTo, never equals
if (resultingBalance.compareTo(effectiveFloor) < 0) { ... }
```

```yaml
# application.yml
spring:
  jackson:
    deserialization:
      use-big-decimal-for-floats: true
```

### Lombok Constructor Injection (apply to ALL service and controller classes)

**Source:** CLAUDE.md — Constructor injection via `@RequiredArgsConstructor`, never field injection
**Apply to:** `AccountServiceImpl.java`, `AccountController.java`, `GlobalExceptionHandler.java`

```java
@RequiredArgsConstructor  // generates constructor for all final fields
public class AccountServiceImpl implements AccountService {
    private final AccountRepository accountRepository;
    // ...
}
```

### `@Transactional` Placement (apply to service implementation, NOT controllers)

**Source:** RESEARCH.md Pitfall 6; CLAUDE.md — `@Transactional` on service write methods, never on controllers
**Apply to:** `AccountServiceImpl.java` (class-level or method-level)

```java
@Service
@Transactional  // class-level: all public methods participate in a transaction
public class AccountServiceImpl implements AccountService { ... }
```

For read-only methods, add `@Transactional(readOnly = true)` to the method to optimize.

### SLF4J Logging (apply to ALL service and controller classes)

**Source:** CLAUDE.md — always use `@Slf4j` Lombok annotation; SLF4J parameterized logging
**Apply to:** `AccountServiceImpl.java`, `AccountController.java`, `GlobalExceptionHandler.java`, step definition files

```java
@Slf4j
public class AccountServiceImpl {
    log.info("Creating account: {}", request.id());
    log.warn("Floor violation for account {}: {}", accountId, ex.getMessage());
    log.error("Unexpected error in createAccount", ex);
}
```

### Idempotency Catch Pattern (apply to ALL write operations in `AccountServiceImpl`)

**Source:** RESEARCH.md Anti-Patterns + Code Examples (lines 699-736)
**Apply to:** `createAccount`, and all future Phase 2+ write operations

```java
try {
    idempotencyKeyRepository.saveAndFlush(/* ... */);
    // ... business logic ...
    return response;
} catch (DataIntegrityViolationException ex) {
    return idempotencyKeyRepository
        .findByIdempotencyKeyAndOperation(key, operation)
        .map(ik -> deserialize(ik.getResponseBody(), ResponseType.class))
        .orElseThrow(() -> new IllegalStateException("Constraint violation but no cached response", ex));
}
```

---

## No Analog Found

This section is not applicable — the project is greenfield. All patterns come from external references documented in RESEARCH.md. No local analog search was performed because no source files exist.

---

## Metadata

**Analog search scope:** N/A — greenfield project
**Files scanned:** 0 local source files (CONTEXT.md and RESEARCH.md read for pattern extraction)
**External references used:** 6 (Spring Boot multi-module guide, Spring Data JPA, Cucumber JVM docs, Testcontainers docs, Flyway docs, Hibernate 6 JSONB mapping)
**Pattern extraction date:** 2026-05-13
