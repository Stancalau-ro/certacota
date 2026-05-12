# Controllers and Endpoints - REST API Layer

Complete guide to building REST controllers in Spring Boot applications.

## Table of Contents

- [Controller Basics](#controller-basics)
- [Request Mapping](#request-mapping)
- [Request Parameters](#request-parameters)
- [Response Handling](#response-handling)
- [Input Validation](#input-validation)
- [Pagination and Sorting](#pagination-and-sorting)

---

## Controller Basics

### Standard Controller Structure

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
@Tag(name = "Users", description = "User management endpoints")
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new user")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID")
    public UserResponse findById(@PathVariable Long id) {
        return userService.findById(id);
    }

    @GetMapping
    @Operation(summary = "Get all users with pagination")
    public Page<UserResponse> findAll(Pageable pageable) {
        return userService.findAll(pageable);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user")
    public UserResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete user")
    public void delete(@PathVariable Long id) {
        userService.delete(id);
    }
}
```

### Controller Annotations

| Annotation | Purpose |
|------------|---------|
| `@RestController` | Combines `@Controller` + `@ResponseBody` |
| `@RequestMapping` | Base path for all endpoints |
| `@RequiredArgsConstructor` | Lombok constructor injection |
| `@Validated` | Enable method-level validation |
| `@Tag` | OpenAPI documentation group |

---

## Request Mapping

### HTTP Method Annotations

```java
@GetMapping("/{id}")              // GET /api/users/{id}
@PostMapping                      // POST /api/users
@PutMapping("/{id}")              // PUT /api/users/{id}
@PatchMapping("/{id}")            // PATCH /api/users/{id}
@DeleteMapping("/{id}")           // DELETE /api/users/{id}
```

### Path Patterns

```java
@GetMapping("/{id}")
public UserResponse findById(@PathVariable Long id) {}

@GetMapping("/{userId}/posts/{postId}")
public PostResponse findPost(
    @PathVariable Long userId,
    @PathVariable Long postId) {}

@GetMapping("/search/{keyword}")
public List<UserResponse> search(@PathVariable String keyword) {}

@GetMapping("/status/{status}")
public List<UserResponse> findByStatus(
    @PathVariable UserStatus status) {}
```

### Content Type

```java
@PostMapping(
    consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE)
public UserResponse create(@RequestBody CreateUserRequest request) {}

@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public FileResponse upload(@RequestParam MultipartFile file) {}
```

---

## Request Parameters

### Path Variables

```java
@GetMapping("/{id}")
public UserResponse findById(@PathVariable Long id) {}

@GetMapping("/{id}")
public UserResponse findById(@PathVariable("id") Long userId) {}
```

### Query Parameters

```java
@GetMapping
public List<UserResponse> search(
    @RequestParam(required = false) String name,
    @RequestParam(defaultValue = "ACTIVE") UserStatus status,
    @RequestParam(name = "dept") Long departmentId) {}

@GetMapping("/filter")
public List<UserResponse> filter(
    @RequestParam List<Long> ids) {}
```

### Request Body

```java
@PostMapping
public UserResponse create(@Valid @RequestBody CreateUserRequest request) {}

@PatchMapping("/{id}")
public UserResponse patch(
    @PathVariable Long id,
    @RequestBody Map<String, Object> updates) {}
```

### Headers

```java
@GetMapping
public UserResponse get(
    @RequestHeader("X-Correlation-Id") String correlationId,
    @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {}
```

### Current User (Spring Security)

```java
@GetMapping("/me")
public UserResponse getCurrentUser(@AuthenticationPrincipal UserDetails user) {}

@GetMapping("/me")
public UserResponse getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
    String userId = jwt.getSubject();
    return userService.findById(userId);
}
```

---

## Response Handling

### ResponseEntity

```java
@PostMapping
public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
    UserResponse user = userService.create(request);
    URI location = ServletUriComponentsBuilder
        .fromCurrentRequest()
        .path("/{id}")
        .buildAndExpand(user.id())
        .toUri();
    return ResponseEntity.created(location).body(user);
}

@GetMapping("/{id}")
public ResponseEntity<UserResponse> findById(@PathVariable Long id) {
    return ResponseEntity.ok(userService.findById(id));
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) {
    userService.delete(id);
    return ResponseEntity.noContent().build();
}
```

### Status Code Annotations

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public UserResponse create(@RequestBody CreateUserRequest request) {}

@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable Long id) {}
```

### Common Response Patterns

```java
@GetMapping("/{id}")
public UserResponse findById(@PathVariable Long id) {
    return userService.findById(id);
}

@GetMapping
public List<UserResponse> findAll() {
    return userService.findAll();
}

@GetMapping
public Page<UserResponse> findAll(Pageable pageable) {
    return userService.findAll(pageable);
}

@GetMapping("/count")
public Map<String, Long> count() {
    return Map.of("count", userService.count());
}
```

---

## Input Validation

### Bean Validation Annotations

```java
public record CreateUserRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255)
    String email,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name,

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(regexp = ".*[A-Z].*", message = "Password must contain uppercase")
    String password,

    @NotNull(message = "Age is required")
    @Min(value = 18, message = "Must be at least 18")
    @Max(value = 120, message = "Invalid age")
    Integer age,

    @NotEmpty(message = "Roles are required")
    @Size(min = 1, max = 5, message = "Must have 1-5 roles")
    List<@NotBlank String> roles
) {}
```

### Controller Validation

```java
@PostMapping
public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
    return userService.create(request);
}

@GetMapping
public List<UserResponse> search(
    @RequestParam @NotBlank @Size(min = 2) String query) {}
```

### Custom Validation

```java
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueEmailValidator.class)
public @interface UniqueEmail {
    String message() default "Email already exists";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class UniqueEmailValidator implements ConstraintValidator<UniqueEmail, String> {

    private final UserRepository userRepository;

    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        return email == null || !userRepository.existsByEmail(email);
    }
}
```

---

## Pagination and Sorting

### Basic Pagination

```java
@GetMapping
public Page<UserResponse> findAll(Pageable pageable) {
    return userService.findAll(pageable);
}

@GetMapping
public Page<UserResponse> findAll(
    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
    Pageable pageable) {
    return userService.findAll(pageable);
}
```

### Custom Page Response

```java
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean first,
    boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.isFirst(),
            page.isLast()
        );
    }
}

@GetMapping
public PageResponse<UserResponse> findAll(Pageable pageable) {
    return PageResponse.from(userService.findAll(pageable));
}
```

### Search with Filters

```java
public record UserSearchCriteria(
    String name,
    String email,
    UserStatus status,
    LocalDate createdAfter,
    LocalDate createdBefore
) {}

@GetMapping("/search")
public Page<UserResponse> search(
    @Valid UserSearchCriteria criteria,
    Pageable pageable) {
    return userService.search(criteria, pageable);
}
```

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [services-and-repositories.md](services-and-repositories.md) - Services called by controllers
- [exception-handling.md](exception-handling.md) - Error handling
- [dto-patterns.md](dto-patterns.md) - Request/Response DTOs
