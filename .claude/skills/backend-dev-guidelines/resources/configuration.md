# Configuration - Spring Boot Properties

Complete guide to configuration management in Spring Boot applications.

## Table of Contents

- [ConfigurationProperties](#configurationproperties)
- [Application YAML](#application-yaml)
- [Profiles](#profiles)
- [Validation](#validation)

---

## ConfigurationProperties

### Record-Based Properties

```java
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
    @NotBlank String name,
    @NotBlank String version,
    JwtConfig jwt,
    CorsConfig cors,
    DatabaseConfig database
) {
    public record JwtConfig(
        @NotBlank String secret,
        @NotNull Duration accessTokenExpiration,
        @NotNull Duration refreshTokenExpiration
    ) {}

    public record CorsConfig(
        List<String> allowedOrigins,
        List<String> allowedMethods,
        boolean allowCredentials
    ) {}

    public record DatabaseConfig(
        int poolSize,
        Duration connectionTimeout
    ) {}
}
```

### Enable ConfigurationProperties

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### Usage

```java
@Service
@RequiredArgsConstructor
public class JwtService {

    private final AppProperties appProperties;

    public String generateToken(UserDetails user) {
        return Jwts.builder()
            .expiration(Date.from(Instant.now()
                .plus(appProperties.jwt().accessTokenExpiration())))
            .signWith(getKey(appProperties.jwt().secret()))
            .compact();
    }
}
```

---

## Application YAML

### application.yml

```yaml
spring:
  application:
    name: my-service

  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    hikari:
      maximum-pool-size: 10
      connection-timeout: 30000

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
        default_batch_fetch_size: 100

  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080
  servlet:
    context-path: /api

app:
  name: My Service
  version: 1.0.0
  jwt:
    secret: ${JWT_SECRET:your-256-bit-secret-key-minimum-32-characters}
    access-token-expiration: 15m
    refresh-token-expiration: 7d
  cors:
    allowed-origins:
      - http://localhost:3000
    allowed-methods:
      - GET
      - POST
      - PUT
      - DELETE
    allow-credentials: true

logging:
  level:
    root: INFO
    com.company.app: DEBUG
    org.hibernate.SQL: DEBUG

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: when_authorized
```

---

## Profiles

### application-dev.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb_dev
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true

logging:
  level:
    com.company.app: DEBUG
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```

### application-prod.yml

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    hikari:
      maximum-pool-size: 20
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

logging:
  level:
    root: WARN
    com.company.app: INFO
```

### application-test.yml

```yaml
spring:
  datasource:
    url: jdbc:tc:postgresql:15:///testdb
  jpa:
    hibernate:
      ddl-auto: create-drop
  flyway:
    enabled: false

app:
  jwt:
    secret: test-secret-key-for-testing-purposes-only-min-32-chars
```

---

## Validation

### Validated Properties

```java
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
    @NotBlank(message = "App name is required")
    String name,

    @NotNull(message = "JWT config is required")
    @Valid
    JwtConfig jwt
) {
    public record JwtConfig(
        @NotBlank(message = "JWT secret is required")
        @Size(min = 32, message = "JWT secret must be at least 32 characters")
        String secret,

        @NotNull(message = "Access token expiration is required")
        Duration accessTokenExpiration
    ) {}
}
```

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [security-guide.md](security-guide.md) - Security configuration
- [testing-guide.md](testing-guide.md) - Test configuration
