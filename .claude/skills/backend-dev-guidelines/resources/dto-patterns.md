# DTO Patterns - Data Transfer Objects

Complete guide to creating and using DTOs in Spring Boot applications.

## Table of Contents

- [DTO Overview](#dto-overview)
- [Java Records for DTOs](#java-records-for-dtos)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Entity Mapping](#entity-mapping)
- [Nested DTOs](#nested-dtos)

---

## DTO Overview

### Why Use DTOs?

**Problems without DTOs:**
- ❌ Exposing internal entity structure
- ❌ Lazy loading issues in responses
- ❌ Circular reference problems
- ❌ Over-fetching data
- ❌ Security risks (exposing passwords, etc.)

**Benefits of DTOs:**
- ✅ Clean API contract
- ✅ Version control of API structure
- ✅ Security (hide sensitive fields)
- ✅ Performance (select only needed fields)
- ✅ Validation decoupled from entities

### DTO Directory Structure

```
dto/
├── request/
│   ├── CreateUserRequest.java
│   ├── UpdateUserRequest.java
│   └── UserSearchCriteria.java
└── response/
    ├── UserResponse.java
    ├── UserDetailResponse.java
    └── UserSummaryResponse.java
```

---

## Java Records for DTOs

### Basic Record DTO

```java
public record UserResponse(
    Long id,
    String email,
    String name,
    UserStatus status,
    LocalDateTime createdAt
) {}
```

**Benefits:**
- Immutable by default
- Auto-generated: constructor, getters, equals, hashCode, toString
- Compact syntax
- Pattern matching support

### Records with Validation

```java
public record CreateUserRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100)
    String name,

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password
) {}
```

### Records with Default Values

```java
public record CreatePostRequest(
    @NotBlank String title,
    @NotBlank String content,
    PostStatus status,
    List<String> tags
) {
    public CreatePostRequest {
        status = status != null ? status : PostStatus.DRAFT;
        tags = tags != null ? tags : List.of();
    }
}
```

---

## Request DTOs

### Create Request

```java
public record CreateUserRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
    String name,

    @NotBlank(message = "Password is required")
    @Pattern(
        regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$",
        message = "Password must contain uppercase, lowercase, and digit"
    )
    String password,

    @NotNull(message = "Department is required")
    Long departmentId,

    Set<String> roles
) {
    public CreateUserRequest {
        roles = roles != null ? roles : Set.of("USER");
    }
}
```

### Update Request (Partial Updates)

```java
public record UpdateUserRequest(
    @Size(min = 2, max = 100)
    String name,

    Long departmentId,

    Set<String> roles,

    UserStatus status
) {}
```

### Search/Filter Request

```java
public record UserSearchCriteria(
    String name,
    String email,
    UserStatus status,
    Long departmentId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    LocalDate createdAfter,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    LocalDate createdBefore
) {}
```

### Bulk Operations

```java
public record BulkUserRequest(
    @NotEmpty(message = "User IDs are required")
    @Size(max = 100, message = "Maximum 100 users per request")
    List<@NotNull Long> userIds,

    @NotNull(message = "Action is required")
    BulkAction action
) {}

public enum BulkAction {
    ACTIVATE, DEACTIVATE, DELETE
}
```

---

## Response DTOs

### Standard Response

```java
public record UserResponse(
    Long id,
    String email,
    String name,
    UserStatus status,
    DepartmentSummary department,
    Set<String> roles,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
```

### Summary Response (Lists)

```java
public record UserSummaryResponse(
    Long id,
    String name,
    String email,
    UserStatus status
) {}
```

### Detail Response (Single Item)

```java
public record UserDetailResponse(
    Long id,
    String email,
    String name,
    UserStatus status,
    DepartmentResponse department,
    Set<RoleResponse> roles,
    AddressResponse address,
    List<PostSummaryResponse> recentPosts,
    UserStatistics statistics,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime lastLoginAt
) {}
```

### Error Response

```java
public record ErrorResponse(
    String message,
    String code,
    Map<String, String> errors,
    LocalDateTime timestamp,
    String path
) {
    public static ErrorResponse of(String message, String code) {
        return new ErrorResponse(message, code, Map.of(), LocalDateTime.now(), null);
    }

    public static ErrorResponse withValidationErrors(Map<String, String> errors) {
        return new ErrorResponse(
            "Validation failed",
            "VALIDATION_ERROR",
            errors,
            LocalDateTime.now(),
            null
        );
    }
}
```

---

## Entity Mapping

### Manual Mapping

```java
@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getName(),
            user.getStatus(),
            toDepartmentSummary(user.getDepartment()),
            user.getRoles(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }

    public User toEntity(CreateUserRequest request) {
        return User.builder()
            .email(request.email())
            .name(request.name())
            .status(UserStatus.ACTIVE)
            .roles(request.roles())
            .build();
    }

    public void updateEntity(User user, UpdateUserRequest request) {
        if (request.name() != null) {
            user.setName(request.name());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.roles() != null) {
            user.setRoles(request.roles());
        }
    }

    private DepartmentSummary toDepartmentSummary(Department dept) {
        if (dept == null) return null;
        return new DepartmentSummary(dept.getId(), dept.getName());
    }
}
```

### MapStruct Mapping

```java
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {

    UserResponse toResponse(User user);

    UserSummaryResponse toSummary(User user);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "status", constant = "ACTIVE")
    User toEntity(CreateUserRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(@MappingTarget User user, UpdateUserRequest request);

    List<UserResponse> toResponseList(List<User> users);

    default Page<UserResponse> toResponsePage(Page<User> page) {
        return page.map(this::toResponse);
    }
}
```

### MapStruct with Nested Objects

```java
@Mapper(componentModel = "spring", uses = {DepartmentMapper.class, RoleMapper.class})
public interface UserMapper {

    @Mapping(source = "department", target = "department")
    @Mapping(source = "roles", target = "roles")
    UserDetailResponse toDetailResponse(User user);
}

@Mapper(componentModel = "spring")
public interface DepartmentMapper {

    DepartmentSummary toSummary(Department department);

    DepartmentResponse toResponse(Department department);
}
```

---

## Nested DTOs

### Embedded Objects

```java
public record UserResponse(
    Long id,
    String email,
    String name,
    AddressResponse address,
    DepartmentSummary department
) {}

public record AddressResponse(
    String street,
    String city,
    String state,
    String zipCode,
    String country
) {}

public record DepartmentSummary(
    Long id,
    String name
) {}
```

### Collections

```java
public record OrderResponse(
    Long id,
    String orderNumber,
    OrderStatus status,
    CustomerSummary customer,
    List<OrderItemResponse> items,
    MoneyResponse total,
    LocalDateTime createdAt
) {}

public record OrderItemResponse(
    Long id,
    ProductSummary product,
    int quantity,
    MoneyResponse unitPrice,
    MoneyResponse subtotal
) {}

public record MoneyResponse(
    BigDecimal amount,
    String currency
) {}
```

### Self-Referencing (Trees)

```java
public record CategoryResponse(
    Long id,
    String name,
    String slug,
    CategoryResponse parent,
    List<CategoryResponse> children
) {}

@Component
public class CategoryMapper {

    public CategoryResponse toResponse(Category category) {
        return toResponse(category, 2);
    }

    private CategoryResponse toResponse(Category category, int depth) {
        if (category == null || depth < 0) return null;

        return new CategoryResponse(
            category.getId(),
            category.getName(),
            category.getSlug(),
            depth > 0 ? toResponse(category.getParent(), depth - 1) : null,
            depth > 0 ? category.getChildren().stream()
                .map(c -> toResponse(c, depth - 1))
                .toList() : List.of()
        );
    }
}
```

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [controllers-and-endpoints.md](controllers-and-endpoints.md) - Controllers using DTOs
- [services-and-repositories.md](services-and-repositories.md) - Services using DTOs
