---
name: backend-dev-guidelines
description: Comprehensive backend development guide for Spring Boot 3.x applications. Use when creating controllers, services, repositories, DTOs, or working with REST APIs, JPA/Hibernate database access, Spring Security, Bean Validation, configuration, dependency injection, or async patterns. Covers layered architecture (controllers → services → repositories), exception handling, Lombok patterns, testing with TestContainers, and enterprise patterns.
---

# Backend Development Guidelines - Spring Boot 3.x

## Purpose

Establish consistency and best practices across Spring Boot backend applications using modern Java/Spring patterns with Lombok, Spring Security, and TestContainers.

## When to Use This Skill

Automatically activates when working on:
- Creating or modifying REST controllers and endpoints
- Building services and repositories
- Implementing security (authentication, authorization)
- Database operations with JPA/Hibernate
- Exception handling and error responses
- Input validation with Bean Validation
- Configuration and properties management
- Backend testing and refactoring

---

## Quick Start

### New Feature Checklist

- [ ] **Controller**: REST controller with proper annotations
- [ ] **Service**: Business logic with `@Service` and `@Transactional`
- [ ] **Repository**: Spring Data JPA repository
- [ ] **DTO**: Request/Response DTOs with validation
- [ ] **Entity**: JPA entity with Lombok
- [ ] **Mapper**: Entity ↔ DTO mapping
- [ ] **Exception**: Custom exceptions with `@ControllerAdvice`
- [ ] **Tests**: Unit + Integration tests with TestContainers
- [ ] **Config**: Use `@ConfigurationProperties`

### New Application Checklist

- [ ] Project structure (see [architecture-overview.md](resources/architecture-overview.md))
- [ ] Spring Security configuration
- [ ] Global exception handler
- [ ] Configuration properties
- [ ] Logging configuration
- [ ] Testing framework with TestContainers
- [ ] OpenAPI/Swagger documentation

---

## Architecture Overview

### Layered Architecture

```
HTTP Request
    ↓
Controllers (REST endpoints)
    ↓
Services (business logic)
    ↓
Repositories (data access)
    ↓
Database (JPA/Hibernate)
```

**Key Principle:** Each layer has ONE responsibility.

See [architecture-overview.md](resources/architecture-overview.md) for complete details.

---

## Directory Structure

```
src/main/java/com/company/app/
├── config/              # Configuration classes
├── controller/          # REST controllers
├── service/             # Business logic
│   └── impl/            # Service implementations
├── repository/          # Spring Data JPA repositories
├── entity/              # JPA entities
├── dto/                 # Data Transfer Objects
│   ├── request/         # Request DTOs
│   └── response/        # Response DTOs
├── mapper/              # Entity ↔ DTO mappers
├── exception/           # Custom exceptions
├── security/            # Security configuration
└── util/                # Utility classes

src/main/resources/
├── application.yml      # Main configuration
├── application-dev.yml  # Development profile
├── application-prod.yml # Production profile
└── db/migration/        # Flyway/Liquibase migrations

src/test/java/
├── integration/         # Integration tests with TestContainers
└── unit/                # Unit tests
```

**Naming Conventions:**
- Controllers: `PascalCase + Controller` - `UserController.java`
- Services: `PascalCase + Service` - `UserService.java`
- Repositories: `PascalCase + Repository` - `UserRepository.java`
- DTOs: `PascalCase + Request/Response` - `CreateUserRequest.java`
- Entities: `PascalCase` - `User.java`

---

## Core Principles (7 Key Rules)

### 1. Controllers Only Handle HTTP

```java
// ❌ NEVER: Business logic in controllers
@PostMapping
public ResponseEntity<?> create(@RequestBody CreateUserRequest request) {
    // 200 lines of business logic...
}

// ✅ ALWAYS: Delegate to service
@PostMapping
public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(userService.create(request));
}
```

### 2. Use Lombok Annotations

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

    private String email;
    private String name;
}
```

### 3. Validate All Input with Bean Validation

```java
public record CreateUserRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100)
    String name
) {}
```

### 4. Use DTOs, Never Expose Entities

```java
// ❌ NEVER: Expose entity directly
@GetMapping("/{id}")
public User getUser(@PathVariable Long id) {
    return userRepository.findById(id).orElseThrow();
}

// ✅ ALWAYS: Use DTOs
@GetMapping("/{id}")
public UserResponse getUser(@PathVariable Long id) {
    return userService.findById(id);
}
```

### 5. Use `@ConfigurationProperties`, Never Direct `@Value`

```java
// ❌ NEVER
@Value("${app.jwt.secret}")
private String jwtSecret;

// ✅ ALWAYS
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(
    @NotBlank String secret,
    Duration expiration
) {}
```

### 6. Use Spring Data Repository Pattern

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.status = :status")
    List<User> findByStatus(@Param("status") UserStatus status);
}
```

### 7. Comprehensive Testing Required

```java
@SpringBootTest
@Testcontainers
class UserServiceIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Test
    void shouldCreateUser() {
        // Test implementation
    }
}
```

---

## Common Imports

```java
// Spring Web
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

// Validation
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

// JPA
import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// Security
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

// Lombok
import lombok.*;
import lombok.extern.slf4j.Slf4j;

// Transactions
import org.springframework.transaction.annotation.Transactional;
```

---

## Quick Reference

### HTTP Status Codes

| Code | Use Case | Spring Method |
|------|----------|---------------|
| 200 | Success | `ResponseEntity.ok()` |
| 201 | Created | `ResponseEntity.status(HttpStatus.CREATED)` |
| 204 | No Content | `ResponseEntity.noContent()` |
| 400 | Bad Request | Throw `BadRequestException` |
| 401 | Unauthorized | Spring Security handles |
| 403 | Forbidden | `@PreAuthorize` or throw `AccessDeniedException` |
| 404 | Not Found | Throw `ResourceNotFoundException` |
| 409 | Conflict | Throw `ConflictException` |
| 500 | Server Error | Throw `InternalServerException` |

### Common Annotations

| Annotation | Purpose |
|------------|---------|
| `@RestController` | REST controller class |
| `@RequestMapping` | Base path for controller |
| `@Service` | Service layer bean |
| `@Repository` | Data access layer bean |
| `@Transactional` | Transaction management |
| `@Valid` | Enable validation |
| `@PreAuthorize` | Method-level security |
| `@Slf4j` | Lombok logging |

---

## Anti-Patterns to Avoid

❌ Business logic in controllers
❌ Exposing JPA entities in API responses
❌ Direct `@Value` for configuration
❌ Missing input validation
❌ Catching generic `Exception`
❌ Using `System.out.println` instead of logging
❌ Missing `@Transactional` on service methods
❌ N+1 query problems (use `@EntityGraph` or `JOIN FETCH`)

---

## Navigation Guide

| Need to... | Read this |
|------------|-----------|
| Understand architecture | [architecture-overview.md](resources/architecture-overview.md) |
| Create controllers | [controllers-and-endpoints.md](resources/controllers-and-endpoints.md) |
| Organize business logic | [services-and-repositories.md](resources/services-and-repositories.md) |
| Create DTOs | [dto-patterns.md](resources/dto-patterns.md) |
| Configure security | [security-guide.md](resources/security-guide.md) |
| Handle exceptions | [exception-handling.md](resources/exception-handling.md) |
| Database access | [jpa-patterns.md](resources/jpa-patterns.md) |
| Manage configuration | [configuration.md](resources/configuration.md) |
| Write tests | [testing-guide.md](resources/testing-guide.md) |
| Dockerize application | [docker-and-deployment.md](resources/docker-and-deployment.md) |
| See complete examples | [complete-examples.md](resources/complete-examples.md) |

---

## Resource Files

### [architecture-overview.md](resources/architecture-overview.md)
Layered architecture, request lifecycle, separation of concerns

### [controllers-and-endpoints.md](resources/controllers-and-endpoints.md)
REST controllers, request mapping, response handling

### [services-and-repositories.md](resources/services-and-repositories.md)
Service patterns, repository pattern, transactions

### [dto-patterns.md](resources/dto-patterns.md)
Request/Response DTOs, Java Records, MapStruct mapping

### [security-guide.md](resources/security-guide.md)
Spring Security, JWT authentication, method security

### [exception-handling.md](resources/exception-handling.md)
Global exception handler, custom exceptions, error responses

### [jpa-patterns.md](resources/jpa-patterns.md)
JPA entities, relationships, query optimization

### [configuration.md](resources/configuration.md)
Configuration properties, profiles, externalized config

### [testing-guide.md](resources/testing-guide.md)
Unit tests, integration tests with TestContainers, E2E with Cucumber/Gherkin

### [docker-and-deployment.md](resources/docker-and-deployment.md)
Docker Compose for local dev, containerization, deployment patterns

### [complete-examples.md](resources/complete-examples.md)
Full examples, CRUD implementation guide

---

## Related Skills

- **error-tracking** - Centralized logging and monitoring patterns
- **skill-developer** - Meta-skill for creating and managing skills

---

**Skill Status**: COMPLETE ✅
**Line Count**: < 500 ✅
**Progressive Disclosure**: 11 resource files ✅
