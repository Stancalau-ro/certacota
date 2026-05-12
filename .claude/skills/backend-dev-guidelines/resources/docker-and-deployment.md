# Docker and Deployment Guide

Containerization patterns for Spring Boot applications using Docker Compose for local development and container packaging for deployment.

## Table of Contents

- [Local Development with Spring Docker Compose](#local-development-with-spring-docker-compose)
- [Docker Compose Configuration](#docker-compose-configuration)
- [Containerizing for Deployment](#containerizing-for-deployment)
- [Building Container Images](#building-container-images)
- [Production Best Practices](#production-best-practices)

---

## Local Development with Spring Docker Compose

Spring Boot 3.1+ includes Docker Compose support that automatically manages containers during development.

### Add Dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-docker-compose</artifactId>
    <scope>runtime</scope>
</dependency>
```

### How It Works

When you run your application:
1. Spring Boot detects `docker-compose.yml` in your project
2. Automatically runs `docker-compose up` before startup
3. Configures connection properties from container labels
4. Runs `docker-compose stop` on shutdown (configurable)

### Configuration (application.yml)

```yaml
spring:
  docker:
    compose:
      enabled: true
      file: docker-compose.yml
      lifecycle-management: start-and-stop
      start:
        command: up
      stop:
        command: stop
        timeout: 30s
      skip:
        in-tests: true
```

### Lifecycle Options

| Option | Behavior |
|--------|----------|
| `start-and-stop` | Start on app start, stop on app stop (default) |
| `start-only` | Start containers, leave running on stop |
| `none` | Disable automatic management |

---

## Docker Compose Configuration

### Basic Setup (docker-compose.yml)

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    container_name: app-postgres
    environment:
      POSTGRES_DB: appdb
      POSTGRES_USER: appuser
      POSTGRES_PASSWORD: apppass
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U appuser -d appdb"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: app-redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres-data:
  redis-data:
```

### With Service Labels for Auto-Configuration

Spring Boot reads labels to auto-configure connections:

```yaml
services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: appdb
      POSTGRES_USER: appuser
      POSTGRES_PASSWORD: apppass
    ports:
      - "5432:5432"
    labels:
      org.springframework.boot.service-connection: postgres

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    labels:
      org.springframework.boot.service-connection: redis
```

### Full Development Stack

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    container_name: app-postgres
    environment:
      POSTGRES_DB: appdb
      POSTGRES_USER: appuser
      POSTGRES_PASSWORD: apppass
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./docker/init-scripts:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U appuser -d appdb"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: app-redis
    command: redis-server --appendonly yes
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

  mailhog:
    image: mailhog/mailhog:latest
    container_name: app-mailhog
    ports:
      - "1025:1025"
      - "8025:8025"

  localstack:
    image: localstack/localstack:3.0
    container_name: app-localstack
    environment:
      SERVICES: s3,sqs,sns
      DEBUG: 0
    ports:
      - "4566:4566"
    volumes:
      - localstack-data:/var/lib/localstack
      - ./docker/localstack-init:/etc/localstack/init/ready.d

  keycloak:
    image: quay.io/keycloak/keycloak:23.0
    container_name: app-keycloak
    command: start-dev --import-realm
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "8080:8080"
    volumes:
      - ./docker/keycloak/realm.json:/opt/keycloak/data/import/realm.json

volumes:
  postgres-data:
  redis-data:
  localstack-data:
```

### Development Profile Configuration

```yaml
# application-dev.yml
spring:
  docker:
    compose:
      enabled: true
      lifecycle-management: start-and-stop

  datasource:
    url: jdbc:postgresql://localhost:5432/appdb
    username: appuser
    password: apppass

  data:
    redis:
      host: localhost
      port: 6379

  mail:
    host: localhost
    port: 1025
```

---

## Containerizing for Deployment

### Multi-Stage Dockerfile (Recommended)

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

COPY src src

RUN ./mvnw package -DskipTests -B

RUN java -Djarmode=layertools -jar target/*.jar extract

# Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

### Simple Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

COPY target/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### With JVM Tuning for Containers

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

---

## Building Container Images

### Option 1: Spring Boot Maven Plugin (Buildpacks)

No Dockerfile needed - uses Cloud Native Buildpacks:

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <image>
            <name>${project.artifactId}:${project.version}</name>
            <builder>paketobuildpacks/builder:base</builder>
            <env>
                <BP_JVM_VERSION>21</BP_JVM_VERSION>
            </env>
        </image>
    </configuration>
</plugin>
```

Build command:
```bash
./mvnw spring-boot:build-image
```

### Option 2: Jib (No Docker Daemon Required)

```xml
<plugin>
    <groupId>com.google.cloud.tools</groupId>
    <artifactId>jib-maven-plugin</artifactId>
    <version>3.4.0</version>
    <configuration>
        <from>
            <image>eclipse-temurin:21-jre-alpine</image>
        </from>
        <to>
            <image>registry.example.com/${project.artifactId}</image>
            <tags>
                <tag>${project.version}</tag>
                <tag>latest</tag>
            </tags>
        </to>
        <container>
            <jvmFlags>
                <jvmFlag>-XX:+UseContainerSupport</jvmFlag>
                <jvmFlag>-XX:MaxRAMPercentage=75.0</jvmFlag>
            </jvmFlags>
            <ports>
                <port>8080</port>
            </ports>
            <creationTime>USE_CURRENT_TIMESTAMP</creationTime>
        </container>
    </configuration>
</plugin>
```

Build commands:
```bash
# Build to local Docker daemon
./mvnw jib:dockerBuild

# Build and push to registry (no Docker daemon needed)
./mvnw jib:build

# Build to local tar file
./mvnw jib:buildTar
```

### Option 3: Docker Build

```bash
# Build with Dockerfile
docker build -t myapp:latest .

# Build with specific Dockerfile
docker build -f Dockerfile.prod -t myapp:prod .

# Multi-platform build
docker buildx build --platform linux/amd64,linux/arm64 -t myapp:latest .
```

---

## Production Best Practices

### Docker Compose for Production

```yaml
# docker-compose.prod.yml
version: '3.8'

services:
  app:
    image: registry.example.com/myapp:${VERSION:-latest}
    container_name: myapp
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/appdb
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      JAVA_OPTS: >-
        -XX:+UseContainerSupport
        -XX:MaxRAMPercentage=75.0
        -Xlog:gc*:file=/var/log/gc.log
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '0.5'
          memory: 512M
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    restart: unless-stopped
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  postgres:
    image: postgres:15-alpine
    container_name: myapp-postgres
    environment:
      POSTGRES_DB: appdb
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d appdb"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  postgres-data:
```

### Application Configuration for Containers

```yaml
# application-prod.yml
spring:
  docker:
    compose:
      enabled: false

server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true

logging:
  pattern:
    console: '{"timestamp":"%d{ISO8601}","level":"%p","thread":"%t","logger":"%c","message":"%m"}%n'
```

### Kubernetes Probes Configuration

```yaml
# application.yml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
```

Kubernetes deployment uses:
- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`

### Security Checklist

| Practice | Implementation |
|----------|----------------|
| Non-root user | `USER appuser` in Dockerfile |
| Read-only filesystem | Mount volumes for writable paths |
| No secrets in image | Use environment variables or secrets |
| Minimal base image | Use `-alpine` or distroless |
| Scan for vulnerabilities | `docker scan` or Trivy |
| Pin versions | Use specific tags, not `latest` |

### .dockerignore

```
target/
!target/*.jar
.git
.gitignore
.mvn/wrapper/maven-wrapper.jar
*.md
docker-compose*.yml
Dockerfile*
.env*
```

---

## Quick Reference

### Commands

```bash
# Local development
docker-compose up -d              # Start services
docker-compose down               # Stop services
docker-compose logs -f postgres   # View logs

# Build image
./mvnw spring-boot:build-image    # Buildpacks
./mvnw jib:dockerBuild            # Jib
docker build -t myapp .           # Dockerfile

# Run container
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/db \
  myapp:latest

# Push to registry
docker tag myapp:latest registry.example.com/myapp:v1.0.0
docker push registry.example.com/myapp:v1.0.0
```

### Environment Variables

| Variable | Purpose |
|----------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile |
| `SPRING_DATASOURCE_URL` | Database connection URL |
| `JAVA_OPTS` | JVM options |
| `SERVER_PORT` | Application port (default 8080) |

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [configuration.md](configuration.md) - Configuration patterns
- [testing-guide.md](testing-guide.md) - TestContainers for testing