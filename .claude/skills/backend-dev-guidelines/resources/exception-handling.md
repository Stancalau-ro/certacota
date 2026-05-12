# Exception Handling - Global Error Management

Complete guide to exception handling in Spring Boot applications.

## Table of Contents

- [Global Exception Handler](#global-exception-handler)
- [Custom Exceptions](#custom-exceptions)
- [Error Response Format](#error-response-format)
- [Validation Errors](#validation-errors)

---

## Global Exception Handler

### @ControllerAdvice

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ErrorResponse.of(ex.getMessage(), "NOT_FOUND", getPath(request));
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(ConflictException ex, WebRequest request) {
        log.warn("Conflict: {}", ex.getMessage());
        return ErrorResponse.of(ex.getMessage(), "CONFLICT", getPath(request));
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(BadRequestException ex, WebRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        return ErrorResponse.of(ex.getMessage(), "BAD_REQUEST", getPath(request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.put(error.getField(), error.getDefaultMessage()));

        log.warn("Validation failed: {}", errors);
        return ErrorResponse.withValidationErrors(errors, getPath(request));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String field = violation.getPropertyPath().toString();
            errors.put(field, violation.getMessage());
        });

        return ErrorResponse.withValidationErrors(errors, getPath(request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        log.warn("Access denied: {}", ex.getMessage());
        return ErrorResponse.of("Access denied", "FORBIDDEN", getPath(request));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex, WebRequest request) {
        log.error("Unexpected error", ex);
        return ErrorResponse.of("An unexpected error occurred", "INTERNAL_ERROR", getPath(request));
    }

    private String getPath(WebRequest request) {
        return request.getDescription(false).replace("uri=", "");
    }
}
```

---

## Custom Exceptions

### Base Exception

```java
public abstract class BaseException extends RuntimeException {
    private final String code;

    protected BaseException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

### Common Exceptions

```java
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException(String resource, Long id) {
        super(String.format("%s not found with id: %d", resource, id), "NOT_FOUND");
    }

    public ResourceNotFoundException(String resource, String field, String value) {
        super(String.format("%s not found with %s: %s", resource, field, value), "NOT_FOUND");
    }
}

@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends BaseException {

    public ConflictException(String message) {
        super(message, "CONFLICT");
    }
}

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends BaseException {

    public BadRequestException(String message) {
        super(message, "BAD_REQUEST");
    }
}

@ResponseStatus(HttpStatus.FORBIDDEN)
public class ForbiddenException extends BaseException {

    public ForbiddenException(String message) {
        super(message, "FORBIDDEN");
    }
}

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UnauthorizedException extends BaseException {

    public UnauthorizedException(String message) {
        super(message, "UNAUTHORIZED");
    }
}
```

---

## Error Response Format

```java
public record ErrorResponse(
    String message,
    String code,
    Map<String, String> errors,
    LocalDateTime timestamp,
    String path
) {
    public static ErrorResponse of(String message, String code, String path) {
        return new ErrorResponse(message, code, Map.of(), LocalDateTime.now(), path);
    }

    public static ErrorResponse withValidationErrors(Map<String, String> errors, String path) {
        return new ErrorResponse(
            "Validation failed",
            "VALIDATION_ERROR",
            errors,
            LocalDateTime.now(),
            path
        );
    }
}
```

### Example Responses

**404 Not Found:**
```json
{
  "message": "User not found with id: 123",
  "code": "NOT_FOUND",
  "errors": {},
  "timestamp": "2024-01-15T10:30:00",
  "path": "/api/users/123"
}
```

**400 Validation Error:**
```json
{
  "message": "Validation failed",
  "code": "VALIDATION_ERROR",
  "errors": {
    "email": "Invalid email format",
    "name": "Name is required"
  },
  "timestamp": "2024-01-15T10:30:00",
  "path": "/api/users"
}
```

---

## Validation Errors

### Usage in Service

```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    @Override
    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return userRepository.findById(id)
            .map(userMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already registered: " + request.email());
        }
        // ... create user
    }
}
```

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [controllers-and-endpoints.md](controllers-and-endpoints.md) - Controllers
- [services-and-repositories.md](services-and-repositories.md) - Services