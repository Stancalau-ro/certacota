# Architecture Overview - Spring Boot Applications

Complete guide to the layered architecture pattern used in Spring Boot applications.

## Table of Contents

- [Layered Architecture Pattern](#layered-architecture-pattern)
- [Request Lifecycle](#request-lifecycle)
- [Directory Structure Rationale](#directory-structure-rationale)
- [Module Organization](#module-organization)
- [Separation of Concerns](#separation-of-concerns)

---

## Layered Architecture Pattern

### The Three Layers

```
┌─────────────────────────────────────┐
│         HTTP Request                │
└───────────────┬─────────────────────┘
                ↓
┌─────────────────────────────────────┐
│  Layer 1: CONTROLLERS               │
│  - REST endpoints                   │
│  - Input validation                 │
│  - Call services                    │
│  - Format responses                 │
│  - NO business logic                │
└───────────────┬─────────────────────┘
                ↓
┌─────────────────────────────────────┐
│  Layer 2: SERVICES                  │
│  - Business logic                   │
│  - Transaction management           │
│  - Orchestration                    │
│  - No HTTP knowledge                │
└───────────────┬─────────────────────┘
                ↓
┌─────────────────────────────────────┐
│  Layer 3: REPOSITORIES              │
│  - Data access abstraction          │
│  - JPA/Hibernate operations         │
│  - Query optimization               │
└───────────────┬─────────────────────┘
                ↓
┌─────────────────────────────────────┐
│         Database (PostgreSQL/MySQL) │
└─────────────────────────────────────┘
```

### Why This Architecture?

**Testability:**
- Each layer can be tested independently
- Easy to mock dependencies with `@MockBean`
- Clear test boundaries

**Maintainability:**
- Changes isolated to specific layers
- Business logic separate from HTTP concerns
- Easy to locate bugs

**Reusability:**
- Services can be used by controllers, scheduled tasks, event listeners
- Repositories hide database implementation
- Business logic not tied to HTTP

**Scalability:**
- Easy to add new endpoints
- Clear patterns to follow
- Consistent structure

---

## Request Lifecycle

### Complete Flow Example

```java
1. HTTP POST /api/users
   ↓
2. Spring DispatcherServlet routes to UserController
   ↓
3. Filter chain executes:
   - SecurityFilterChain (authentication)
   - RequestLoggingFilter (optional)
   ↓
4. Controller method handles request:
   @PostMapping
   public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request)
   ↓
5. Controller calls service:
   - Validation already done via @Valid
   - Call userService.create(request)
   - Return ResponseEntity
   ↓
6. Service executes business logic:
   - Check business rules
   - Call userRepository.save(entity)
   - Return mapped DTO
   ↓
7. Repository performs database operation:
   - JPA/Hibernate generates SQL
   - Transaction committed
   - Return saved entity
   ↓
8. Response flows back:
   Repository → Service → Controller → Client
```

### Spring Security Filter Chain

**Critical:** Filters execute in registration order

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(Customizer.withDefaults()))
            .build();
    }
}
```

---

## Directory Structure Rationale

### Standard Spring Boot Structure

```
src/main/java/com/company/app/
├── config/              # Configuration classes
│   ├── SecurityConfig.java
│   ├── JpaConfig.java
│   └── AppProperties.java
├── controller/          # REST controllers
│   └── UserController.java
├── service/             # Business logic
│   ├── UserService.java
│   └── impl/
│       └── UserServiceImpl.java
├── repository/          # Spring Data JPA repositories
│   └── UserRepository.java
├── entity/              # JPA entities
│   └── User.java
├── dto/                 # Data Transfer Objects
│   ├── request/
│   │   └── CreateUserRequest.java
│   └── response/
│       └── UserResponse.java
├── mapper/              # Entity ↔ DTO mappers
│   └── UserMapper.java
├── exception/           # Custom exceptions
│   ├── ResourceNotFoundException.java
│   └── GlobalExceptionHandler.java
├── security/            # Security components
│   └── JwtTokenProvider.java
└── Application.java     # Main entry point
```

### Controllers Directory

**Purpose:** Handle HTTP request/response concerns

**Contents:**
- `{Feature}Controller.java` - Feature-specific controllers

**Naming:** PascalCase + Controller

**Responsibilities:**
- Define REST endpoints with `@RestController`
- Parse request parameters (`@PathVariable`, `@RequestParam`, `@RequestBody`)
- Validate input with `@Valid`
- Call appropriate service methods
- Return `ResponseEntity<T>`

### Services Directory

**Purpose:** Business logic and orchestration

**Contents:**
- `{Feature}Service.java` - Service interface
- `impl/{Feature}ServiceImpl.java` - Implementation

**Naming:** PascalCase + Service

**Responsibilities:**
- Implement business rules
- Orchestrate multiple repositories
- Transaction management (`@Transactional`)
- Business validations
- No HTTP knowledge (never use `HttpServletRequest`)

### Repositories Directory

**Purpose:** Data access abstraction

**Contents:**
- `{Entity}Repository.java` - Spring Data JPA interface

**Naming:** PascalCase + Repository

**Responsibilities:**
- Extend `JpaRepository<Entity, ID>`
- Custom query methods
- Query optimization (`@EntityGraph`, `@Query`)

### Entity Directory

**Purpose:** JPA entity definitions

**Contents:**
- `{Entity}.java` - JPA entities with Lombok

**Pattern:**
```java
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
```

### DTO Directory

**Purpose:** Data Transfer Objects

**Contents:**
- `request/` - Incoming request DTOs
- `response/` - Outgoing response DTOs

**Pattern:** Use Java Records for immutability:
```java
public record CreateUserRequest(
    @NotBlank String email,
    @NotBlank String name
) {}
```

---

## Module Organization

### Feature-Based Organization (Large Applications)

For large features, organize by domain:

```
src/main/java/com/company/app/
├── user/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   └── dto/
├── order/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   └── dto/
└── common/
    ├── exception/
    ├── config/
    └── security/
```

**When to use:**
- Application has 10+ entities
- Clear bounded contexts exist
- Multiple teams work on different features

### Flat Organization (Standard Applications)

```
src/main/java/com/company/app/
├── controller/
├── service/
├── repository/
├── entity/
├── dto/
└── config/
```

**When to use:**
- Application has < 10 entities
- Single team
- Clear layer separation is sufficient

---

## Separation of Concerns

### What Goes Where

**Controllers Layer:**
- ✅ REST endpoint definitions
- ✅ Request validation (`@Valid`)
- ✅ Service calls
- ✅ Response formatting
- ❌ Business logic
- ❌ Database operations
- ❌ Transaction management

**Services Layer:**
- ✅ Business logic
- ✅ Business rules enforcement
- ✅ Orchestration (multiple repos)
- ✅ Transaction management (`@Transactional`)
- ✅ DTO ↔ Entity mapping
- ❌ HTTP concerns (Request/Response)
- ❌ Direct SQL (use repositories)

**Repositories Layer:**
- ✅ JPA operations
- ✅ Custom queries (`@Query`)
- ✅ Entity graph optimization
- ❌ Business logic
- ❌ HTTP concerns
- ❌ Transaction management (let service handle)

### Example: User Creation

**Controller:**
```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(userService.create(request));
    }
}
```

**Service:**
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already exists");
        }

        User user = userMapper.toEntity(request);
        User saved = userRepository.save(user);

        log.info("Created user with id: {}", saved.getId());
        return userMapper.toResponse(saved);
    }
}
```

**Repository:**
```java
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);
}
```

**Notice:** Each layer has clear, distinct responsibilities!

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [controllers-and-endpoints.md](controllers-and-endpoints.md) - Controller patterns
- [services-and-repositories.md](services-and-repositories.md) - Service patterns
