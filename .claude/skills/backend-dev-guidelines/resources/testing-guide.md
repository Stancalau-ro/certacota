# Testing Guide - Spring Boot Testing with TestContainers

Complete guide to testing Spring Boot applications with JUnit 5, Mockito, and TestContainers.

## Table of Contents

- [Testing Strategy](#testing-strategy)
- [Unit Testing](#unit-testing)
- [Integration Testing with TestContainers](#integration-testing-with-testcontainers)
- [Controller Testing](#controller-testing)
- [Repository Testing](#repository-testing)
- [E2E Testing with Cucumber](#e2e-testing-with-cucumber)
- [Test Data Management](#test-data-management)

---

## Testing Strategy

### Test Pyramid

```
        /\
       /  \      E2E Tests (few)
      /----\
     /      \    Integration Tests (some)
    /--------\
   /          \  Unit Tests (many)
  /------------\
```

### Test Types

| Type | Scope | Speed | Dependencies |
|------|-------|-------|--------------|
| Unit | Single class | Fast | Mocked |
| Integration | Multiple layers | Medium | Real DB (TestContainers) |
| Controller | REST API | Medium | MockMvc |
| E2E | Full application | Slow | All real |

---

## Unit Testing

### Service Unit Test

```java
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void create_ShouldCreateUser_WhenEmailIsUnique() {
        CreateUserRequest request = new CreateUserRequest(
            "test@example.com",
            "Test User",
            "password123"
        );

        User user = User.builder()
            .id(1L)
            .email(request.email())
            .name(request.name())
            .build();

        UserResponse expectedResponse = new UserResponse(1L, "test@example.com", "Test User");

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(expectedResponse);

        UserResponse result = userService.create(request);

        assertThat(result).isEqualTo(expectedResponse);
        verify(userRepository).existsByEmail(request.email());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void create_ShouldThrowConflictException_WhenEmailExists() {
        CreateUserRequest request = new CreateUserRequest(
            "existing@example.com",
            "Test User",
            "password123"
        );

        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    void findById_ShouldReturnUser_WhenUserExists() {
        Long userId = 1L;
        User user = User.builder().id(userId).email("test@example.com").build();
        UserResponse expectedResponse = new UserResponse(userId, "test@example.com", "Test");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userMapper.toResponse(user)).thenReturn(expectedResponse);

        UserResponse result = userService.findById(userId);

        assertThat(result).isEqualTo(expectedResponse);
    }

    @Test
    void findById_ShouldThrowResourceNotFoundException_WhenUserNotFound() {
        Long userId = 999L;

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(userId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("User")
            .hasMessageContaining("999");
    }
}
```

---

## Integration Testing with TestContainers

### Base Test Configuration

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
```

### Service Integration Test

```java
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
        CreateUserRequest request = new CreateUserRequest(
            "integration@test.com",
            "Integration Test",
            "password123"
        );

        UserResponse response = userService.create(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.email()).isEqualTo(request.email());

        Optional<User> savedUser = userRepository.findByEmail(request.email());
        assertThat(savedUser).isPresent();
        assertThat(savedUser.get().getName()).isEqualTo(request.name());
    }

    @Test
    void findById_ShouldReturnPersistedUser() {
        User user = userRepository.save(User.builder()
            .email("find@test.com")
            .name("Find Test")
            .password("encoded")
            .status(UserStatus.ACTIVE)
            .build());

        UserResponse response = userService.findById(user.getId());

        assertThat(response.email()).isEqualTo(user.getEmail());
        assertThat(response.name()).isEqualTo(user.getName());
    }
}
```

### Multiple Containers

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:2.0"))
        .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.SQS);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("aws.s3.endpoint", () -> localstack.getEndpointOverride(S3));
        registry.add("aws.sqs.endpoint", () -> localstack.getEndpointOverride(SQS));
    }
}
```

---

## Controller Testing

### MockMvc Tests

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
        CreateUserRequest request = new CreateUserRequest(
            "test@example.com",
            "Test User",
            "password123"
        );
        UserResponse response = new UserResponse(1L, "test@example.com", "Test User");

        when(userService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    @WithMockUser
    void create_ShouldReturn400_WhenInvalidEmail() throws Exception {
        CreateUserRequest request = new CreateUserRequest(
            "invalid-email",
            "Test User",
            "password123"
        );

        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @WithMockUser
    void findById_ShouldReturn200_WhenUserExists() throws Exception {
        UserResponse response = new UserResponse(1L, "test@example.com", "Test User");

        when(userService.findById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/users/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    @WithMockUser
    void findById_ShouldReturn404_WhenUserNotFound() throws Exception {
        when(userService.findById(999L))
            .thenThrow(new ResourceNotFoundException("User", 999L));

        mockMvc.perform(get("/api/users/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("User not found with id: 999"));
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

### Full Integration Controller Test

```java
class UserControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void fullCrudFlow() throws Exception {
        CreateUserRequest createRequest = new CreateUserRequest(
            "crud@test.com",
            "CRUD Test",
            "password123"
        );
        String createResponse = mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        Long userId = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(get("/api/users/" + userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("crud@test.com"));

        UpdateUserRequest updateRequest = new UpdateUserRequest("Updated Name");
        mockMvc.perform(put("/api/users/" + userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated Name"));

        mockMvc.perform(delete("/api/users/" + userId))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/" + userId))
            .andExpect(status().isNotFound());
    }
}
```

---

## Repository Testing

### DataJpaTest

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
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByEmail_ShouldReturnUser_WhenEmailExists() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test User")
            .password("encoded")
            .status(UserStatus.ACTIVE)
            .build();
        entityManager.persistAndFlush(user);

        Optional<User> found = userRepository.findByEmail("test@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test User");
    }

    @Test
    void existsByEmail_ShouldReturnTrue_WhenEmailExists() {
        User user = User.builder()
            .email("exists@example.com")
            .name("Exists Test")
            .password("encoded")
            .status(UserStatus.ACTIVE)
            .build();
        entityManager.persistAndFlush(user);

        boolean exists = userRepository.existsByEmail("exists@example.com");

        assertThat(exists).isTrue();
    }

    @Test
    void findByStatus_ShouldReturnOnlyActiveUsers() {
        entityManager.persist(User.builder()
            .email("active@test.com").name("Active").password("p").status(UserStatus.ACTIVE).build());
        entityManager.persist(User.builder()
            .email("inactive@test.com").name("Inactive").password("p").status(UserStatus.INACTIVE).build());
        entityManager.flush();

        List<User> activeUsers = userRepository.findByStatus(UserStatus.ACTIVE);

        assertThat(activeUsers).hasSize(1);
        assertThat(activeUsers.get(0).getEmail()).isEqualTo("active@test.com");
    }
}
```

---

## E2E Testing with Cucumber

Behavior-Driven Development (BDD) with Cucumber provides human-readable test scenarios using Gherkin syntax.

### Dependencies (pom.xml)

```xml
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-java</artifactId>
    <version>7.15.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-spring</artifactId>
    <version>7.15.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-junit-platform-engine</artifactId>
    <version>7.15.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-suite</artifactId>
    <scope>test</scope>
</dependency>
```

### Directory Structure

```
src/test/
├── java/
│   └── com/company/app/
│       └── e2e/
│           ├── CucumberSpringConfiguration.java
│           ├── CucumberTestRunner.java
│           └── steps/
│               ├── UserSteps.java
│               └── CommonSteps.java
└── resources/
    └── features/
        ├── user-management.feature
        └── authentication.feature
```

### Feature File (Gherkin)

```gherkin
# src/test/resources/features/user-management.feature
Feature: User Management
  As an administrator
  I want to manage users
  So that I can control access to the system

  Background:
    Given the database is clean
    And I am authenticated as an admin

  Scenario: Create a new user successfully
    When I create a user with:
      | email            | name       |
      | john@example.com | John Doe   |
    Then the response status should be 201
    And the response should contain:
      | field | value            |
      | email | john@example.com |
      | name  | John Doe         |
    And the user "john@example.com" should exist in the database

  Scenario: Fail to create user with existing email
    Given a user exists with email "existing@example.com"
    When I create a user with:
      | email               | name     |
      | existing@example.com| Jane Doe |
    Then the response status should be 409
    And the response should contain error "Email already exists"

  Scenario Outline: Validate user creation input
    When I create a user with:
      | email   | name   |
      | <email> | <name> |
    Then the response status should be 400
    And the response should contain validation error for "<field>"

    Examples:
      | email           | name | field |
      | invalid-email   | John | email |
      | john@example.com|      | name  |
      |                 | John | email |

  Scenario: Get user by ID
    Given a user exists with:
      | email           | name     |
      | test@example.com| Test User|
    When I get the user by their ID
    Then the response status should be 200
    And the response should contain:
      | field | value            |
      | email | test@example.com |

  Scenario: Delete a user
    Given a user exists with email "delete@example.com"
    When I delete the user "delete@example.com"
    Then the response status should be 204
    And the user "delete@example.com" should not exist in the database
```

### Cucumber Spring Configuration

```java
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public class CucumberSpringConfiguration {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

### Test Runner

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.company.app.e2e")
public class CucumberTestRunner {
}
```

### Step Definitions

```java
public class UserSteps {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    private ResponseEntity<String> response;
    private String authToken;
    private Long lastCreatedUserId;

    @Given("the database is clean")
    public void theDatabaseIsClean() {
        userRepository.deleteAll();
    }

    @Given("I am authenticated as an admin")
    public void iAmAuthenticatedAsAdmin() {
        authToken = obtainAdminToken();
    }

    @Given("a user exists with email {string}")
    public void aUserExistsWithEmail(String email) {
        User user = User.builder()
            .email(email)
            .name("Test User")
            .password("encoded")
            .status(UserStatus.ACTIVE)
            .build();
        userRepository.save(user);
    }

    @Given("a user exists with:")
    public void aUserExistsWith(DataTable dataTable) {
        Map<String, String> data = dataTable.asMaps().get(0);
        User user = User.builder()
            .email(data.get("email"))
            .name(data.get("name"))
            .password("encoded")
            .status(UserStatus.ACTIVE)
            .build();
        User saved = userRepository.save(user);
        lastCreatedUserId = saved.getId();
    }

    @When("I create a user with:")
    public void iCreateAUserWith(DataTable dataTable) {
        Map<String, String> data = dataTable.asMaps().get(0);
        CreateUserRequest request = new CreateUserRequest(
            data.get("email"),
            data.get("name"),
            "password123"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(authToken);

        HttpEntity<CreateUserRequest> entity = new HttpEntity<>(request, headers);
        response = restTemplate.exchange("/api/users", HttpMethod.POST, entity, String.class);
    }

    @When("I get the user by their ID")
    public void iGetTheUserByTheirId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);

        HttpEntity<?> entity = new HttpEntity<>(headers);
        response = restTemplate.exchange(
            "/api/users/" + lastCreatedUserId,
            HttpMethod.GET,
            entity,
            String.class
        );
    }

    @When("I delete the user {string}")
    public void iDeleteTheUser(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);

        HttpEntity<?> entity = new HttpEntity<>(headers);
        response = restTemplate.exchange(
            "/api/users/" + user.getId(),
            HttpMethod.DELETE,
            entity,
            String.class
        );
    }

    @Then("the response status should be {int}")
    public void theResponseStatusShouldBe(int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
    }

    @Then("the response should contain:")
    public void theResponseShouldContain(DataTable dataTable) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(response.getBody());

        for (Map<String, String> row : dataTable.asMaps()) {
            String field = row.get("field");
            String expectedValue = row.get("value");
            assertThat(json.get(field).asText()).isEqualTo(expectedValue);
        }
    }

    @Then("the response should contain error {string}")
    public void theResponseShouldContainError(String errorMessage) throws Exception {
        assertThat(response.getBody()).contains(errorMessage);
    }

    @Then("the response should contain validation error for {string}")
    public void theResponseShouldContainValidationErrorFor(String field) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(response.getBody());
        assertThat(json.get("errors").has(field)).isTrue();
    }

    @Then("the user {string} should exist in the database")
    public void theUserShouldExistInTheDatabase(String email) {
        assertThat(userRepository.existsByEmail(email)).isTrue();
    }

    @Then("the user {string} should not exist in the database")
    public void theUserShouldNotExistInTheDatabase(String email) {
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    private String obtainAdminToken() {
        return "test-admin-token";
    }
}
```

### Authentication Feature

```gherkin
# src/test/resources/features/authentication.feature
Feature: Authentication
  As a user
  I want to authenticate
  So that I can access protected resources

  Scenario: Login with valid credentials
    Given a user exists with:
      | email            | password    |
      | user@example.com | password123 |
    When I login with email "user@example.com" and password "password123"
    Then the response status should be 200
    And I should receive a valid JWT token

  Scenario: Login with invalid password
    Given a user exists with:
      | email            | password    |
      | user@example.com | password123 |
    When I login with email "user@example.com" and password "wrongpassword"
    Then the response status should be 401

  Scenario: Access protected endpoint without token
    When I access "/api/users/me" without authentication
    Then the response status should be 401

  Scenario: Access protected endpoint with valid token
    Given I am authenticated as "user@example.com"
    When I access "/api/users/me"
    Then the response status should be 200
    And the response should contain my user details
```

### Running Cucumber Tests

```bash
# Run all Cucumber tests
./mvnw test -Dtest=CucumberTestRunner

# Run specific feature
./mvnw test -Dtest=CucumberTestRunner -Dcucumber.filter.tags="@user-management"

# Generate HTML report
./mvnw test -Dtest=CucumberTestRunner -Dcucumber.plugin="html:target/cucumber-report.html"
```

### Best Practices

| Practice | Description |
|----------|-------------|
| **Keep scenarios independent** | Each scenario should set up its own data |
| **Use Background wisely** | Common setup that applies to all scenarios |
| **Avoid UI language** | Focus on business behavior, not clicks |
| **Use Scenario Outline** | For testing multiple inputs with same behavior |
| **Tag scenarios** | `@smoke`, `@regression`, `@slow` for filtering |
| **Reuse step definitions** | Create common steps for authentication, cleanup |

### Tags for Test Organization

```gherkin
@user-management
Feature: User Management

  @smoke @critical
  Scenario: Create a new user successfully
    ...

  @regression
  Scenario: Validate email format
    ...

  @slow @integration
  Scenario: Bulk user import
    ...
```

Run by tags:
```bash
./mvnw test -Dcucumber.filter.tags="@smoke"
./mvnw test -Dcucumber.filter.tags="@regression and not @slow"
```

---

## Test Data Management

### Test Fixtures

```java
public class TestFixtures {

    public static User createUser() {
        return User.builder()
            .email("test@example.com")
            .name("Test User")
            .password("encodedPassword")
            .status(UserStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();
    }

    public static User createUser(String email) {
        return User.builder()
            .email(email)
            .name("Test User")
            .password("encodedPassword")
            .status(UserStatus.ACTIVE)
            .build();
    }

    public static CreateUserRequest createUserRequest() {
        return new CreateUserRequest(
            "test@example.com",
            "Test User",
            "password123"
        );
    }
}
```

### Test Data Builder Pattern

```java
public class UserTestBuilder {
    private String email = "default@test.com";
    private String name = "Default Name";
    private String password = "password";
    private UserStatus status = UserStatus.ACTIVE;

    public static UserTestBuilder aUser() {
        return new UserTestBuilder();
    }

    public UserTestBuilder withEmail(String email) {
        this.email = email;
        return this;
    }

    public UserTestBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public UserTestBuilder inactive() {
        this.status = UserStatus.INACTIVE;
        return this;
    }

    public User build() {
        return User.builder()
            .email(email)
            .name(name)
            .password(password)
            .status(status)
            .build();
    }
}

@Test
void example() {
    User user = UserTestBuilder.aUser()
        .withEmail("custom@test.com")
        .inactive()
        .build();
}
```

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [services-and-repositories.md](services-and-repositories.md) - Services to test
- [complete-examples.md](complete-examples.md) - Full examples
