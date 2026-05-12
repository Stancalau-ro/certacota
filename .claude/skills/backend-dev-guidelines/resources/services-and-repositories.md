# Services and Repositories - Business Logic Layer

Complete guide to organizing business logic with services and data access with Spring Data JPA repositories.

## Table of Contents

- [Service Layer Overview](#service-layer-overview)
- [Spring Dependency Injection](#spring-dependency-injection)
- [Transaction Management](#transaction-management)
- [Repository Pattern](#repository-pattern)
- [Service Design Principles](#service-design-principles)
- [Caching Strategies](#caching-strategies)

---

## Service Layer Overview

### Purpose of Services

**Services contain business logic** - the 'what' and 'why' of your application:

```
Controller asks: "Create this user"
Service answers: "Let me validate, create, and return the result"
Repository executes: "Here's the data you requested"
```

**Services are responsible for:**
- ✅ Business rules enforcement
- ✅ Orchestrating multiple repositories
- ✅ Transaction management
- ✅ Complex calculations
- ✅ External service integration
- ✅ DTO ↔ Entity mapping

**Services should NOT:**
- ❌ Know about HTTP (`HttpServletRequest`, `ResponseEntity`)
- ❌ Handle controller-specific concerns
- ❌ Manage security context directly (use `@PreAuthorize`)

---

## Spring Dependency Injection

### Constructor Injection with Lombok

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        log.info("Creating user with email: {}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already exists");
        }

        User user = User.builder()
            .email(request.email())
            .name(request.name())
            .password(passwordEncoder.encode(request.password()))
            .status(UserStatus.ACTIVE)
            .build();

        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }
}
```

**Key Points:**
- `@RequiredArgsConstructor` generates constructor for `final` fields
- Dependencies are injected via constructor (testable)
- `@Slf4j` adds logging capability
- `@Transactional` manages database transactions

### Service Interface Pattern

```java
public interface UserService {
    UserResponse create(CreateUserRequest request);
    UserResponse findById(Long id);
    Page<UserResponse> findAll(Pageable pageable);
    UserResponse update(Long id, UpdateUserRequest request);
    void delete(Long id);
}
```

**Benefits:**
- Clear contract
- Easy to mock in tests
- Allows multiple implementations

---

## Transaction Management

### `@Transactional` Basics

```java
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;

    @Override
    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        Order order = createOrder(request);

        inventoryService.reserveItems(order.getItems());

        paymentService.processPayment(order);

        return orderMapper.toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return orderRepository.findById(id)
            .map(orderMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }
}
```

### Transaction Best Practices

```java
@Transactional
public void updateWithRollback(Long id, UpdateRequest request) {
    try {
        performUpdate(id, request);
        externalService.notify(id);
    } catch (ExternalServiceException e) {
        log.error("External service failed, but transaction will commit", e);
    }
}

@Transactional(rollbackFor = Exception.class)
public void updateWithFullRollback(Long id, UpdateRequest request) {
    performUpdate(id, request);
    externalService.notify(id);
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void auditLog(String action, Long entityId) {
    auditRepository.save(new AuditLog(action, entityId));
}
```

**Transaction Annotations:**

| Annotation | Use Case |
|------------|----------|
| `@Transactional` | Write operations |
| `@Transactional(readOnly = true)` | Read-only queries (performance) |
| `@Transactional(rollbackFor = Exception.class)` | Rollback on checked exceptions |
| `@Transactional(propagation = REQUIRES_NEW)` | Independent transaction |

---

## Repository Pattern

### Spring Data JPA Repositories

```java
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByStatus(UserStatus status);

    @Query("SELECT u FROM User u WHERE u.department.id = :deptId")
    List<User> findByDepartmentId(@Param("deptId") Long departmentId);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.id = :id")
    Optional<User> findByIdWithRoles(@Param("id") Long id);

    @Modifying
    @Query("UPDATE User u SET u.status = :status WHERE u.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") UserStatus status);

    Page<User> findByStatusAndCreatedAtAfter(
        UserStatus status,
        LocalDateTime createdAt,
        Pageable pageable
    );
}
```

### Query Methods Naming Convention

| Method Name | Generated Query |
|-------------|-----------------|
| `findByEmail` | `WHERE email = ?` |
| `findByStatusAndRole` | `WHERE status = ? AND role = ?` |
| `findByNameContaining` | `WHERE name LIKE %?%` |
| `findByCreatedAtBetween` | `WHERE created_at BETWEEN ? AND ?` |
| `findByAgeGreaterThan` | `WHERE age > ?` |
| `countByStatus` | `SELECT COUNT(*) WHERE status = ?` |
| `existsByEmail` | `SELECT 1 WHERE email = ? LIMIT 1` |
| `deleteByStatus` | `DELETE WHERE status = ?` |

### Avoiding N+1 Queries

```java
public interface PostRepository extends JpaRepository<Post, Long> {

    @EntityGraph(attributePaths = {"author", "comments"})
    Optional<Post> findWithDetailsById(Long id);

    @Query("""
        SELECT p FROM Post p
        LEFT JOIN FETCH p.author
        LEFT JOIN FETCH p.comments c
        LEFT JOIN FETCH c.author
        WHERE p.id = :id
        """)
    Optional<Post> findByIdWithAllDetails(@Param("id") Long id);

    @Query("""
        SELECT DISTINCT p FROM Post p
        LEFT JOIN FETCH p.tags
        WHERE p.status = :status
        """)
    List<Post> findByStatusWithTags(@Param("status") PostStatus status);
}
```

---

## Service Design Principles

### 1. Single Responsibility

```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
}

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
}

@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {
    private final AuditLogRepository auditLogRepository;
}
```

### 2. Clear Method Names

```java
public interface UserService {
    UserResponse create(CreateUserRequest request);
    UserResponse findById(Long id);
    Page<UserResponse> search(UserSearchCriteria criteria, Pageable pageable);
    void activate(Long id);
    void deactivate(Long id);
    void changePassword(Long id, ChangePasswordRequest request);
}
```

### 3. Proper Exception Handling

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
            throw new ConflictException("User with email already exists: " + request.email());
        }

        User user = userMapper.toEntity(request);
        return userMapper.toResponse(userRepository.save(user));
    }
}
```

### 4. Service Orchestration

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final OrderMapper orderMapper;

    @Override
    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        log.info("Placing order for user: {}", request.userId());

        Order order = orderMapper.toEntity(request);

        inventoryService.validateAndReserve(order.getItems());

        PaymentResult payment = paymentService.process(order.getTotalAmount());
        order.setPaymentId(payment.transactionId());

        Order saved = orderRepository.save(order);

        notificationService.sendOrderConfirmation(saved);

        return orderMapper.toResponse(saved);
    }
}
```

---

## Caching Strategies

### Spring Cache Abstraction

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    @Cacheable(value = "users", key = "#id")
    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        log.info("Fetching user from database: {}", id);
        return userRepository.findById(id)
            .map(userMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Override
    @CacheEvict(value = "users", key = "#id")
    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));

        userMapper.updateEntity(user, request);
        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @CacheEvict(value = "users", key = "#id")
    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User", id);
        }
        userRepository.deleteById(id);
    }

    @CacheEvict(value = "users", allEntries = true)
    public void clearCache() {
        log.info("Clearing user cache");
    }
}
```

### Cache Configuration

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .recordStats());
        return cacheManager;
    }
}
```

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [controllers-and-endpoints.md](controllers-and-endpoints.md) - Controllers that use services
- [jpa-patterns.md](jpa-patterns.md) - JPA entity patterns
- [testing-guide.md](testing-guide.md) - Testing services
