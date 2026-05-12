---
name: route-tester
description: Test REST endpoints in Spring Boot applications using MockMvc, TestRestTemplate, and TestContainers. Use this skill when testing API endpoints, validating controller functionality, or writing integration tests.
---

# Spring Boot Endpoint Testing

## Purpose

This skill provides patterns for testing REST endpoints in Spring Boot applications using MockMvc for unit tests and TestContainers for integration tests.

## When to Use This Skill

- Testing new API endpoints
- Validating controller functionality after changes
- Writing integration tests with real database
- Testing authentication and authorization
- Verifying request/response data

## Testing Methods

### Method 1: MockMvc (Unit Tests) - RECOMMENDED FOR CONTROLLERS

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
            "test@example.com", "Test User", "password123");
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
    void findById_ShouldReturn404_WhenNotFound() throws Exception {
        when(userService.findById(999L))
            .thenThrow(new ResourceNotFoundException("User", 999L));

        mockMvc.perform(get("/api/users/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
```

### Method 2: TestContainers (Integration Tests)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class UserControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void fullCrudFlow() {
        CreateUserRequest createRequest = new CreateUserRequest(
            "test@example.com", "Test User", "password123");

        ResponseEntity<UserResponse> createResponse = restTemplate.postForEntity(
            "/api/users", createRequest, UserResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody().email()).isEqualTo("test@example.com");

        Long userId = createResponse.getBody().id();

        ResponseEntity<UserResponse> getResponse = restTemplate.getForEntity(
            "/api/users/" + userId, UserResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().email()).isEqualTo("test@example.com");
    }
}
```

### Method 3: MockMvc with Full Context

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserControllerFullTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminEndpoint_ShouldSucceed_WhenAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void adminEndpoint_ShouldFail_WhenNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isForbidden());
    }
}
```

## Common Test Patterns

### Test Validation Errors

```java
@Test
@WithMockUser
void create_ShouldReturn400_WhenInvalidEmail() throws Exception {
    CreateUserRequest request = new CreateUserRequest(
        "invalid-email", "Test User", "password123");

    mockMvc.perform(post("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists());
}
```

### Test Pagination

```java
@Test
@WithMockUser
void findAll_ShouldReturnPaginatedResults() throws Exception {
    mockMvc.perform(get("/api/users")
            .param("page", "0")
            .param("size", "10")
            .param("sort", "createdAt,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.totalElements").isNumber())
        .andExpect(jsonPath("$.totalPages").isNumber());
}
```

### Test Authentication Required

```java
@Test
void create_ShouldReturn401_WhenNotAuthenticated() throws Exception {
    mockMvc.perform(post("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnauthorized());
}
```

### Test with JWT Token

```java
@Test
void endpoint_ShouldSucceed_WithValidJwt() throws Exception {
    String jwt = createTestJwt("user@test.com", List.of("ROLE_USER"));

    mockMvc.perform(get("/api/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
        .andExpect(status().isOk());
}

private String createTestJwt(String subject, List<String> roles) {
    return Jwts.builder()
        .subject(subject)
        .claim("roles", roles)
        .issuedAt(new Date())
        .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
        .signWith(Keys.hmacShaKeyFor("test-secret-key-minimum-32-characters".getBytes()))
        .compact();
}
```

## Testing Checklist

Before testing an endpoint:

- [ ] Identify the HTTP method (GET, POST, PUT, DELETE)
- [ ] Determine authentication requirements
- [ ] Prepare request body (if POST/PUT)
- [ ] Set up mock dependencies or test data
- [ ] Run the test
- [ ] Verify response status
- [ ] Verify response body
- [ ] Check database changes (integration tests)

## Debugging Failed Tests

### 401 Unauthorized

- Add `@WithMockUser` annotation
- Check security configuration
- Verify JWT token if using Bearer auth

### 403 Forbidden

- Add appropriate roles: `@WithMockUser(roles = "ADMIN")`
- Check `@PreAuthorize` annotations

### 404 Not Found

- Verify URL path
- Check `@RequestMapping` annotations
- Ensure test data exists (integration tests)

### 400 Bad Request

- Check validation constraints
- Verify request body format
- Review DTO annotations

## Test Dependencies (pom.xml)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

## Related Skills

- **backend-dev-guidelines** - Controller patterns being tested
- **error-tracking** - Exception handling verification
