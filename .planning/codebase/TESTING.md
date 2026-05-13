# Testing Patterns

**Analysis Date:** 2026-05-13

## Overview

This is a reference library, not a runnable application. It contains two distinct testing contexts:

1. **TypeScript Hooks** — minimal test harness via npm script in `.claude/hooks/`
2. **Spring Boot Target Projects** — comprehensive testing patterns documented in `.claude/skills/backend-dev-guidelines/resources/testing-guide.md`

---

## TypeScript Hooks Testing

### Framework

**Runner:** None formal — single manual test script

**Run Commands:**
```bash
# Run hooks test (executes skill-activation-prompt.ts with test-input.json)
cd .claude/hooks && npm test

# Validate TypeScript without running
cd .claude/hooks && npm run check
# equivalent to: npx tsc --noEmit

# Run a hook manually against stdin
echo '{"session_id":"test","transcript_path":"","cwd":"/project","prompt":"add spring boot controller"}' | npx tsx .claude/hooks/skill-activation-prompt.ts
```

**Package script** (`.claude/hooks/package.json`):
```json
"test": "tsx skill-activation-prompt.ts < test-input.json"
```

### Test File Organization

- No separate test files — hooks are validated via direct execution with piped input
- `test-input.json` is the fixture (not committed, created manually)
- TypeScript type checking (`tsc --noEmit`) serves as the primary static verification

### Hook Testing Pattern

All hooks read JSON from stdin and write to stdout. The test pattern is:
```bash
echo '<json-input>' | npx tsx .claude/hooks/<hook-name>.ts
```

Hooks exit with code `0` on success (including no-op scenarios) and `1` only on unrecoverable errors in critical hooks.

---

## Spring Boot Testing (Documented in Skills)

The `backend-dev-guidelines` skill at `.claude/skills/backend-dev-guidelines/resources/testing-guide.md` defines the authoritative testing patterns for Spring Boot target projects. These patterns are prescribed for all projects that integrate this library.

### Framework

**Runner:** JUnit 5 (`@ExtendWith(MockitoExtension.class)`)

**Assertion Library:** AssertJ (`assertThat(...)`)

**Mocking:** Mockito (`@Mock`, `@InjectMocks`, `when(...).thenReturn(...)`)

**Integration:** TestContainers (`@Testcontainers`, `@Container`)

**Controller Testing:** MockMvc (`@WebMvcTest`, `@MockBean`)

**Repository Testing:** Spring Data Test (`@DataJpaTest`, `TestEntityManager`)

**E2E:** Cucumber + Gherkin (`@Suite`, `@SelectClasspathResource`)

### Test File Organization

```
src/test/java/com/company/app/
├── integration/         # Integration tests with TestContainers
│   ├── BaseIntegrationTest.java   # Abstract base with container setup
│   └── UserServiceIntegrationTest.java
├── unit/               # Unit tests with Mockito
│   └── service/
│       └── UserServiceImplTest.java
└── e2e/                # Cucumber E2E tests
    ├── CucumberSpringConfiguration.java
    ├── CucumberTestRunner.java
    └── steps/
        └── UserSteps.java

src/test/resources/
└── features/           # Cucumber feature files (Gherkin)
    └── user-management.feature
```

**Naming:**
- Unit tests: `{ClassName}Test.java` — `UserServiceImplTest.java`
- Integration tests: `{ClassName}IntegrationTest.java` — `UserServiceIntegrationTest.java`
- Controller tests: `{ClassName}Test.java` with `@WebMvcTest` — `UserControllerTest.java`
- Repository tests: `{ClassName}Test.java` with `@DataJpaTest` — `UserRepositoryTest.java`

### Test Method Naming Convention

```java
// Pattern: methodName_ShouldExpectedBehavior_WhenCondition
void create_ShouldCreateUser_WhenEmailIsUnique() { ... }
void create_ShouldThrowConflictException_WhenEmailExists() { ... }
void findById_ShouldReturnUser_WhenUserExists() { ... }
void findById_ShouldThrowResourceNotFoundException_WhenUserNotFound() { ... }
```

### Unit Test Structure

```java
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void create_ShouldCreateUser_WhenEmailIsUnique() {
        // Arrange
        CreateUserRequest request = new CreateUserRequest("test@example.com", "Test User", "password123");
        User user = User.builder().id(1L).email(request.email()).build();
        UserResponse expectedResponse = new UserResponse(1L, "test@example.com", "Test User");

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(expectedResponse);

        // Act
        UserResponse result = userService.create(request);

        // Assert
        assertThat(result).isEqualTo(expectedResponse);
        verify(userRepository).existsByEmail(request.email());
        verify(userRepository).save(any(User.class));
    }
}
```

### Integration Test Structure

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}

class UserServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void create_ShouldPersistUser() {
        // ... test against real DB via TestContainers
    }
}
```

### Controller Test Structure

```java
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser
    void create_ShouldReturn201_WhenValidRequest() throws Exception {
        when(userService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    void create_ShouldReturn401_WhenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
```

### Repository Test Structure

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        // ...
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByEmail_ShouldReturnUser_WhenEmailExists() {
        // Use entityManager.persistAndFlush() to set up data
        // Use repository method to query
        // Assert with AssertJ
    }
}
```

## Mocking

**Framework:** Mockito

**Annotation-based (unit tests):**
```java
@Mock
private UserRepository userRepository;

@InjectMocks
private UserServiceImpl userService;
```

**Spring-managed (controller tests):**
```java
@MockBean
private UserService userService;
```

**Patterns:**
```java
// Stub return value
when(userRepository.findById(userId)).thenReturn(Optional.of(user));

// Stub exception
when(userRepository.findById(999L)).thenThrow(new ResourceNotFoundException(...));

// Verify interaction
verify(userRepository).save(any(User.class));
verify(userRepository, never()).save(any());
```

**What to Mock:**
- Repository dependencies in service unit tests
- Service dependencies in controller tests via `@MockBean`
- External clients (password encoders, mappers)

**What NOT to Mock:**
- The system under test
- Database in integration tests (use real DB via TestContainers)
- Spring Security in controller tests (use `@WithMockUser` instead)

## Fixtures and Test Data

**Pattern:** Builder-based in-line construction

```java
User user = User.builder()
    .email("test@example.com")
    .name("Test User")
    .password("encoded")
    .status(UserStatus.ACTIVE)
    .build();
```

**For integration tests:** `@BeforeEach` with `repository.deleteAll()` to isolate each test.

**For repository tests:** `entityManager.persistAndFlush(entity)` to persist test data before querying.

**Location:** No shared fixture files — test data constructed inline per test method.

## E2E Testing with Cucumber

**Pattern:** BDD with Gherkin feature files

```gherkin
Feature: User Management
  Scenario: Create a new user successfully
    When I create a user with:
      | email            | name     |
      | john@example.com | John Doe |
    Then the response status should be 201
```

**Setup:**
- Feature files in `src/test/resources/features/`
- Step definitions in `src/test/java/.../e2e/steps/`
- `CucumberTestRunner.java` with `@Suite` + `@SelectClasspathResource("features")`

## Test Types

**Unit Tests:**
- Scope: Single class in isolation
- Annotations: `@ExtendWith(MockitoExtension.class)`
- Dependencies: Mocked with Mockito
- Speed: Fast — no Spring context
- Location: `src/test/java/.../unit/`

**Integration Tests:**
- Scope: Multiple Spring layers with real database
- Annotations: `@SpringBootTest`, `@Testcontainers`, `@ActiveProfiles("test")`
- Dependencies: Real DB via TestContainers (PostgreSQL, Redis, LocalStack)
- Speed: Medium — Docker containers
- Location: `src/test/java/.../integration/`

**Controller Tests:**
- Scope: REST layer only
- Annotations: `@WebMvcTest`, `@Import(SecurityConfig.class)`
- Dependencies: Service mocked via `@MockBean`
- Speed: Medium — partial Spring context

**Repository Tests:**
- Scope: JPA repository only
- Annotations: `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@Testcontainers`
- Speed: Medium — minimal Spring context with real DB

**E2E Tests:**
- Scope: Full application stack
- Framework: Cucumber 7.x + JUnit Platform
- Speed: Slow — all real dependencies

## Coverage

**Requirements:** None enforced in this reference library

**Target (from skill guidelines):** Follow the test pyramid — many unit tests, some integration tests, few E2E tests

---

*Testing analysis: 2026-05-13*
