---
name: error-tracking
description: Centralized logging, exception handling, and monitoring patterns for Spring Boot applications. Use this skill when adding error handling, creating exception handlers, implementing logging, or setting up monitoring with Spring Actuator.
---

# Spring Boot Error Tracking and Monitoring

## Purpose

This skill establishes patterns for centralized error handling, logging, and monitoring in Spring Boot applications.

## When to Use This Skill

- Adding error handling to any code
- Creating global exception handlers
- Implementing logging with SLF4J/Logback
- Setting up Spring Actuator monitoring
- Creating custom exceptions
- Adding health checks

## Core Patterns

### 1. Global Exception Handler

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.put(error.getField(), error.getDefaultMessage()));
        log.warn("Validation failed: {}", errors);
        return ErrorResponse.withValidationErrors(errors, getPath(request));
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

### 2. Custom Exceptions

```java
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, Long id) {
        super(String.format("%s not found with id: %d", resource, id));
    }
}

@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
```

### 3. Error Response Format

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
        return new ErrorResponse("Validation failed", "VALIDATION_ERROR", errors, LocalDateTime.now(), path);
    }
}
```

### 4. Logging with @Slf4j

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        log.info("Creating user with email: {}", request.email());

        try {
            User user = userMapper.toEntity(request);
            User saved = userRepository.save(user);
            log.info("Created user with id: {}", saved.getId());
            return userMapper.toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            log.error("Failed to create user - constraint violation", e);
            throw new ConflictException("Email already exists");
        }
    }
}
```

### 5. Service Error Handling

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Override
    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        log.info("Placing order for user: {}", request.userId());

        try {
            Order order = createOrder(request);
            inventoryService.reserve(order.getItems());
            paymentService.process(order);
            return orderMapper.toResponse(orderRepository.save(order));

        } catch (InsufficientInventoryException e) {
            log.warn("Insufficient inventory for order: {}", e.getMessage());
            throw new BadRequestException("Some items are out of stock");

        } catch (PaymentFailedException e) {
            log.error("Payment failed for order", e);
            inventoryService.release(order.getItems());
            throw new BadRequestException("Payment processing failed");
        }
    }
}
```

## Spring Actuator Monitoring

### Configuration

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true
  health:
    db:
      enabled: true
    diskspace:
      enabled: true
```

### Custom Health Indicator

```java
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(1)) {
                return Health.up().withDetail("database", "Available").build();
            }
        } catch (SQLException e) {
            return Health.down().withException(e).build();
        }
        return Health.down().build();
    }
}
```

## Logging Configuration

### logback-spring.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <property name="LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>

    <logger name="com.company.app" level="DEBUG"/>
    <logger name="org.hibernate.SQL" level="DEBUG"/>
</configuration>
```

## Best Practices

### DO:
- ✅ Use `@Slf4j` for logging (Lombok)
- ✅ Log at appropriate levels (error, warn, info, debug)
- ✅ Include context in log messages
- ✅ Use structured logging for production
- ✅ Implement global exception handler
- ✅ Create specific exception classes
- ✅ Return consistent error responses

### DON'T:
- ❌ Use `System.out.println`
- ❌ Log sensitive data (passwords, tokens)
- ❌ Catch and swallow exceptions silently
- ❌ Use generic `Exception` catch blocks
- ❌ Expose internal error details to clients

## Related Skills

- **backend-dev-guidelines** - Controller and service patterns
- **skill-developer** - Creating custom skills
