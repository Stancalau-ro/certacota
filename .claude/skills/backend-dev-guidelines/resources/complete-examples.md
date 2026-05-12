# Complete Examples - Full Implementation Guide

End-to-end examples for common Spring Boot patterns.

## Table of Contents

- [CRUD Implementation](#crud-implementation)
- [Project Setup](#project-setup)

---

## CRUD Implementation

### Entity

```java
@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    private PostStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

### DTOs

```java
public record CreatePostRequest(
    @NotBlank String title,
    @NotBlank String content
) {}

public record UpdatePostRequest(
    String title,
    String content,
    PostStatus status
) {}

public record PostResponse(
    Long id,
    String title,
    String content,
    PostStatus status,
    AuthorSummary author,
    LocalDateTime createdAt
) {}

public record AuthorSummary(Long id, String name) {}
```

### Repository

```java
public interface PostRepository extends JpaRepository<Post, Long> {

    @EntityGraph(attributePaths = {"author"})
    Optional<Post> findWithAuthorById(Long id);

    Page<Post> findByStatus(PostStatus status, Pageable pageable);

    @Query("SELECT p FROM Post p WHERE p.author.id = :authorId")
    List<Post> findByAuthorId(@Param("authorId") Long authorId);
}
```

### Service

```java
public interface PostService {
    PostResponse create(CreatePostRequest request, Long authorId);
    PostResponse findById(Long id);
    Page<PostResponse> findAll(Pageable pageable);
    PostResponse update(Long id, UpdatePostRequest request);
    void delete(Long id);
}

@Service
@RequiredArgsConstructor
@Slf4j
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final PostMapper postMapper;

    @Override
    @Transactional
    public PostResponse create(CreatePostRequest request, Long authorId) {
        User author = userRepository.findById(authorId)
            .orElseThrow(() -> new ResourceNotFoundException("User", authorId));

        Post post = Post.builder()
            .title(request.title())
            .content(request.content())
            .status(PostStatus.DRAFT)
            .author(author)
            .build();

        return postMapper.toResponse(postRepository.save(post));
    }

    @Override
    @Transactional(readOnly = true)
    public PostResponse findById(Long id) {
        return postRepository.findWithAuthorById(id)
            .map(postMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Post", id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PostResponse> findAll(Pageable pageable) {
        return postRepository.findAll(pageable).map(postMapper::toResponse);
    }

    @Override
    @Transactional
    public PostResponse update(Long id, UpdatePostRequest request) {
        Post post = postRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Post", id));

        if (request.title() != null) post.setTitle(request.title());
        if (request.content() != null) post.setContent(request.content());
        if (request.status() != null) post.setStatus(request.status());

        return postMapper.toResponse(postRepository.save(post));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!postRepository.existsById(id)) {
            throw new ResourceNotFoundException("Post", id);
        }
        postRepository.deleteById(id);
    }
}
```

### Controller

```java
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
@Tag(name = "Posts")
public class PostController {

    private final PostService postService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public PostResponse create(
            @Valid @RequestBody CreatePostRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Long authorId = Long.parseLong(jwt.getSubject());
        return postService.create(request, authorId);
    }

    @GetMapping("/{id}")
    public PostResponse findById(@PathVariable Long id) {
        return postService.findById(id);
    }

    @GetMapping
    public Page<PostResponse> findAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC)
            Pageable pageable) {
        return postService.findAll(pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@postSecurity.isAuthor(#id) or hasRole('ADMIN')")
    public PostResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request) {
        return postService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@postSecurity.isAuthor(#id) or hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        postService.delete(id);
    }
}
```

### Mapper

```java
@Component
public class PostMapper {

    public PostResponse toResponse(Post post) {
        return new PostResponse(
            post.getId(),
            post.getTitle(),
            post.getContent(),
            post.getStatus(),
            toAuthorSummary(post.getAuthor()),
            post.getCreatedAt()
        );
    }

    private AuthorSummary toAuthorSummary(User author) {
        if (author == null) return null;
        return new AuthorSummary(author.getId(), author.getName());
    }
}
```

---

## Project Setup

### pom.xml Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>

    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

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
</dependencies>
```

---

**Related Files:**
- [SKILL.md](../SKILL.md) - Main guide
- [architecture-overview.md](architecture-overview.md) - Architecture
- [testing-guide.md](testing-guide.md) - Testing examples
