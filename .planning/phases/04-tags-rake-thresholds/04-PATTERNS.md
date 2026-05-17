# Phase 4: Tags and Rake on Streaming - Pattern Map

**Mapped:** 2026-05-14
**Files analyzed:** 24 new/modified files
**Analogs found:** 23 / 24

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `engine-core/.../domain/MetadataConverter.java` | utility | transform | `engine-core/.../domain/StreamState.java` (fromRedis/fromDbRow transform pattern) | role-match |
| `engine-core/.../domain/DiscreteTransaction.java` | model | CRUD | itself (retrofit — remove `@JdbcTypeCode`) | exact |
| `engine-core/.../domain/StreamingTransaction.java` | model | CRUD | `engine-core/.../domain/DiscreteTransaction.java` | exact |
| `engine-core/.../domain/StreamState.java` | model | transform | itself (extend record with new fields) | exact |
| `engine-core/.../domain/TagCommittedTotals.java` | model | CRUD | `engine-core/.../domain/DiscreteTransaction.java` | exact |
| `engine-core/.../dto/StartStreamRequest.java` | dto | request-response | itself (extend record) | exact |
| `engine-core/.../dto/CreditRequest.java` | dto | request-response | `engine-core/.../dto/StartStreamRequest.java` | exact |
| `engine-core/.../dto/DebitRequest.java` | dto | request-response | `engine-core/.../dto/StartStreamRequest.java` | exact |
| `engine-core/.../dto/TagAggregateResponse.java` | dto | request-response | `engine-core/.../dto/StartStreamResponse.java` (record DTO) | role-match |
| `engine-core/.../dto/EndByTagResponse.java` | dto | request-response | `engine-core/.../dto/StopStreamResponse.java` | role-match |
| `engine-core/.../repository/TagCommittedTotalsRepository.java` | repository | CRUD | `engine-core/.../repository/AccountRepository.java` | exact |
| `engine-core/.../service/StreamRegistry.java` | port-interface | request-response | itself (extend interface) | exact |
| `engine-core/.../service/TagService.java` | port-interface | request-response | `engine-core/.../service/StreamRegistry.java` | role-match |
| `engine-spring/.../redis/RedisStreamRegistry.java` | service | event-driven | itself (extend) | exact |
| `engine-spring/.../service/StreamingServiceImpl.java` | service | CRUD | itself (extend `stopStream()` and `startStream()`) | exact |
| `engine-spring/.../service/TransactionServiceImpl.java` | service | CRUD | itself (extend `credit()` / `debit()` with tag writes) | exact |
| `engine-spring/.../service/TagServiceImpl.java` | service | CRUD | `engine-spring/.../service/StreamingServiceImpl.java` | exact |
| `engine-spring/.../scheduler/TagTtlCleanupJob.java` | scheduler | batch | `engine-spring/.../scheduler/AuditArchivalJob.java` | exact |
| `engine-spring/.../config/TokenEngineProperties.java` | config | — | itself (extend with `TagProperties` inner class) | exact |
| `engine-spring/.../autoconfigure/TagAutoConfiguration.java` | config | — | `engine-spring/.../autoconfigure/StreamingAutoConfiguration.java` | exact |
| `engine-service/.../controller/TagController.java` | controller | request-response | `engine-service/.../controller/StreamController.java` | exact |
| `engine-service/.../resources/db/migration/V10__*.sql` | migration | — | `V7__create_streaming_transactions.sql` | role-match |
| `engine-service/.../resources/db/migration/V11__*.sql` | migration | — | `V7__create_streaming_transactions.sql` | role-match |
| `engine-service/.../resources/db/migration/V12__*.sql` | migration | — | `V7__create_streaming_transactions.sql` | role-match |
| `engine-service/.../test/resources/features/streaming-tags.feature` | test | request-response | `engine-service/.../features/streaming-stop.feature` | exact |
| `engine-service/.../test/resources/features/streaming-rake.feature` | test | request-response | `engine-service/.../features/discrete-rake.feature` | exact |
| `engine-service/.../test/resources/features/discrete-tags.feature` | test | request-response | `engine-service/.../features/discrete-rake.feature` | exact |
| `engine-service/.../test/resources/features/end-by-tag.feature` | test | request-response | `engine-service/.../features/streaming-stop.feature` | exact |
| `engine-service/.../test/java/.../steps/TagSteps.java` | test | request-response | `engine-service/.../steps/StreamingSteps.java` | exact |

---

## Pattern Assignments

### `engine-core/.../domain/MetadataConverter.java` (utility, transform)

**Analog:** `engine-core/.../domain/StreamState.java` — `fromRedis()` field-parsing pattern; `TransactionServiceImpl` Jackson ObjectMapper usage

**Imports pattern** (`TransactionServiceImpl.java` lines 1-3, `DiscreteTransaction.java` lines 1-17):
```java
package com.certacota.engine.core.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
```

**Core converter pattern** (from RESEARCH.md §Pattern 1):
```java
@Converter
public class MetadataConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize metadata to JSON", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize metadata from JSON string", e);
        }
    }
}
```

Note: `MAPPER` is `static final` for thread safety across concurrent JPA provider calls (Pitfall 4 in RESEARCH.md). No Spring `@Autowired` — converters are not Spring-managed when used via `@Convert`.

---

### `engine-core/.../domain/DiscreteTransaction.java` (model, CRUD — retrofit)

**Analog:** itself — remove lines 15-16 (`@JdbcTypeCode`, `@Column(columnDefinition = "jsonb")`) and replace with portable annotations.

**Current state** (`DiscreteTransaction.java` lines 15-16, 44-46):
```java
// REMOVE these imports:
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// REMOVE these annotations and replace:
// Before:
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "metadata", columnDefinition = "jsonb")
private Map<String, Object> metadata;

// After:
@Convert(converter = MetadataConverter.class)
@Column(name = "metadata")
private Map<String, Object> metadata;
```

Add import: `import jakarta.persistence.Convert;`

The `@Builder`, `@Getter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Entity`, `@Table` annotation block (lines 21-28) is unchanged. The tag extension requires adding an `@ElementCollection`:

```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "discrete_transaction_tags",
    joinColumns = @JoinColumn(name = "transaction_id"))
@Column(name = "tag")
private List<String> tags;
```

---

### `engine-core/.../domain/StreamingTransaction.java` (model, CRUD — extend)

**Analog:** `engine-core/.../domain/DiscreteTransaction.java` (lines 1-53) — identical entity annotation pattern.

**Existing entity header pattern** (`StreamingTransaction.java` lines 1-28):
```java
@Entity
@Table(name = "streaming_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamingTransaction {
    // existing fields unchanged
```

**New columns to add** (after `idempotencyKey` field, line 64):
```java
@Column(name = "to_account_id")
private String toAccountId;

@Column(name = "rake_rate", precision = 38, scale = 18)
private BigDecimal rakeRate;

@Column(name = "platform_account_id")
private String platformAccountId;

@Column(name = "to_account_amount", precision = 38, scale = 18)
private BigDecimal toAccountAmount;

@Column(name = "rake_amount", precision = 38, scale = 18)
private BigDecimal rakeAmount;

@Convert(converter = MetadataConverter.class)
@Column(name = "metadata")
private Map<String, Object> metadata;

@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "stream_tags",
    joinColumns = @JoinColumn(name = "stream_id", referencedColumnName = "stream_id"))
@Column(name = "tag")
private List<String> tags;
```

Note: `@JoinColumn(referencedColumnName = "stream_id")` is required because the FK references the `stream_id` VARCHAR column (not `id`), matching the `uq_stream_id` unique constraint established in V7.

---

### `engine-core/.../domain/StreamState.java` (model, transform — extend)

**Analog:** itself — `StreamState.java` lines 1-53 (full file read in this session).

**Record extension** (add 4 new fields after `increment`, line 15):
```java
public record StreamState(
    String streamId,
    String accountId,
    BigDecimal ratePerSecond,
    long startedAtEpochMillis,
    long startedAtNano,
    boolean startedAtNanoFromCurrentJvm,
    BigDecimal minimumAmount,
    BigDecimal increment,
    // NEW Phase 4 fields:
    List<String> tags,
    String toAccountId,
    BigDecimal rakeRate,
    String platformAccountId
) { ... }
```

**`fromRedis()` extension** — add after `increment` parsing (lines 22-27):
```java
String tagsStr = (String) fields.getOrDefault("tags", "");
List<String> tags = tagsStr.isEmpty()
    ? Collections.emptyList()
    : List.of(tagsStr.split(","));

String toAccountId = (String) fields.get("toAccountId");
String rakeRateStr = (String) fields.get("rakeRate");
BigDecimal rakeRate = (rakeRateStr == null || rakeRateStr.isEmpty()) ? null : new BigDecimal(rakeRateStr);
String platformAccountId = (String) fields.get("platformAccountId");
```

**`fromDbRow()` extension** — add from `StreamingTransaction` entity's new fields:
```java
public static StreamState fromDbRow(StreamingTransaction txn) {
    return new StreamState(
        txn.getStreamId(), txn.getAccountId(), txn.getRatePerSecond(),
        txn.getStartedAt().toInstant().toEpochMilli(), 0L, false,
        txn.getMinimumAmount(), txn.getIncrement(),
        // NEW:
        txn.getTags() != null ? txn.getTags() : Collections.emptyList(),
        txn.getToAccountId(), txn.getRakeRate(), txn.getPlatformAccountId()
    );
}
```

---

### `engine-core/.../domain/TagCommittedTotals.java` (model, CRUD — new)

**Analog:** `engine-core/.../domain/DiscreteTransaction.java` lines 1-53 — entity annotation block pattern.

**Imports pattern** (copy `DiscreteTransaction` imports, replacing enum/type-specific ones):
```java
package com.certacota.engine.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
```

**Entity pattern** (modelled on `DiscreteTransaction.java` lines 21-53):
```java
@Entity
@Table(name = "tag_committed_totals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagCommittedTotals {

    @Id
    @Column(name = "tag", nullable = false, updatable = false)
    private String tag;

    @Column(name = "total_debited", nullable = false, precision = 38, scale = 18)
    private BigDecimal totalDebited;

    @Column(name = "total_credited_recipient", nullable = false, precision = 38, scale = 18)
    private BigDecimal totalCreditedRecipient;

    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;

    public static TagCommittedTotals zero(String tag) {
        return TagCommittedTotals.builder()
            .tag(tag).totalDebited(BigDecimal.ZERO)
            .totalCreditedRecipient(BigDecimal.ZERO)
            .lastActivityAt(OffsetDateTime.now()).build();
    }

    public void addDebit(BigDecimal amount) {
        this.totalDebited = this.totalDebited.add(amount);
        this.lastActivityAt = OffsetDateTime.now();
    }

    public void addCreditedRecipient(BigDecimal amount) {
        this.totalCreditedRecipient = this.totalCreditedRecipient.add(amount);
        this.lastActivityAt = OffsetDateTime.now();
    }
}
```

Note: `@Setter` is needed (unlike `DiscreteTransaction`) because `addDebit`/`addCreditedRecipient` mutate the entity inside the same transaction. `totalRaked` is NOT stored — derived at query time as `totalDebited - totalCreditedRecipient`.

---

### `engine-core/.../dto/StartStreamRequest.java` (dto, request-response — extend)

**Analog:** itself (`StartStreamRequest.java` lines 1-17).

**Current record** (lines 9-17):
```java
public record StartStreamRequest(
    @NotBlank String streamId,
    @NotBlank String accountId,
    @NotNull @Positive BigDecimal ratePerSecond,
    @NotBlank String idempotencyKey,
    @Positive BigDecimal minimumAmount,
    @Positive BigDecimal increment
) { }
```

**Extension — add new optional fields**:
```java
public record StartStreamRequest(
    @NotBlank String streamId,
    @NotBlank String accountId,
    @NotNull @Positive BigDecimal ratePerSecond,
    @NotBlank String idempotencyKey,
    @Positive BigDecimal minimumAmount,
    @Positive BigDecimal increment,
    // NEW Phase 4 fields:
    @Size(max = 50) List<@NotBlank @Size(max = 255) String> tags,
    String toAccountId,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal rakeRate,
    String platformAccountId
) { }
```

`tags` defaults to empty list in service layer when null (field is optional — no `@NotNull`). `rakeRate`, `toAccountId`, `platformAccountId` are all optional; when all three are absent the stream is non-rake.

---

### `engine-core/.../dto/CreditRequest.java` and `DebitRequest.java` (dto, request-response — extend)

**Analog:** `engine-core/.../dto/StartStreamRequest.java` — record DTO pattern with jakarta validation annotations.

Add `tags` field only (no rake fields on discrete credit/debit):
```java
@Size(max = 50) List<@NotBlank @Size(max = 255) String> tags
```

---

### `engine-core/.../dto/TagAggregateResponse.java` (dto, request-response — new)

**Analog:** `engine-core/.../dto/StopStreamResponse.java` — response record pattern with static factory.

**Pattern** (modelled on `StopStreamResponse` factory pattern):
```java
package com.certacota.engine.core.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TagAggregateResponse(
    CommittedSide committed,
    InFlightSide inFlight,
    OffsetDateTime estimatedAt
) {
    public record CommittedSide(
        BigDecimal totalDebited,
        BigDecimal totalCreditedRecipient,
        BigDecimal totalRaked
    ) { }

    public record InFlightSide(
        BigDecimal inFlightDebit,
        BigDecimal inFlightCreditedRecipient,
        BigDecimal inFlightRaked
    ) { }
}
```

`totalRaked` and `inFlightRaked` are derived (`totalDebited - totalCreditedRecipient`) — computed in the service before building the response, never stored.

---

### `engine-core/.../dto/EndByTagResponse.java` (dto, request-response — new)

**Analog:** `engine-core/.../dto/StopStreamResponse.java`.

```java
public record EndByTagResponse(
    int settledCount,
    int skippedCount,
    List<SettledStream> settledStreams
) {
    public record SettledStream(String streamId, BigDecimal settledAmount) { }
}
```

---

### `engine-core/.../repository/TagCommittedTotalsRepository.java` (repository, CRUD — new)

**Analog:** `engine-core/.../repository/AccountRepository.java` (lines 1-17) — `@Lock(PESSIMISTIC_WRITE)` + `@Query` pattern.

**Full pattern** (`AccountRepository.java` lines 1-17):
```java
package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.TagCommittedTotals;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface TagCommittedTotalsRepository extends JpaRepository<TagCommittedTotals, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TagCommittedTotals t WHERE t.tag = :tag")
    Optional<TagCommittedTotals> findWithLock(@Param("tag") String tag);

    int deleteByLastActivityAtBefore(OffsetDateTime cutoff);
}
```

---

### `engine-core/.../service/StreamRegistry.java` (port-interface — extend)

**Analog:** itself (`StreamRegistry.java` lines 1-19).

**New methods to add**:
```java
List<StreamState> getStreamsByTag(String tag);

void removeTag(String streamId, String tag);
```

---

### `engine-core/.../service/TagService.java` (port-interface — new)

**Analog:** `engine-core/.../service/StreamRegistry.java` — port interface pattern (no annotations, pure Java interface).

```java
package com.certacota.engine.core.service;

import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;

public interface TagService {

    TagAggregateResponse aggregate(String tag);

    EndByTagResponse endByTag(String tag, String idempotencyKey, String reason);
}
```

---

### `engine-spring/.../redis/RedisStreamRegistry.java` (service, event-driven — extend)

**Analog:** itself (lines 1-104 — full file read in this session).

**New constant** (after `ACCOUNT_STREAMS_PREFIX` line 27):
```java
private static final String TAG_STREAMS_PREFIX = "tag-streams:";
```

**`register()` extension** (after `redisTemplate.opsForSet().add(accountStreamsKey, ...)` at line 46):
```java
// Phase 4: store tags as comma-separated string in hash
fields.put("tags", state.tags() != null ? String.join(",", state.tags()) : "");
fields.put("toAccountId", state.toAccountId() != null ? state.toAccountId() : "");
fields.put("rakeRate", state.rakeRate() != null ? state.rakeRate().toPlainString() : "");
fields.put("platformAccountId", state.platformAccountId() != null ? state.platformAccountId() : "");

// SADD to tag-streams:{tag} for each tag
if (state.tags() != null) {
    for (String tag : state.tags()) {
        redisTemplate.opsForSet().add(TAG_STREAMS_PREFIX + tag, state.streamId());
    }
}
```

**`remove()` extension** (after `redisTemplate.opsForSet().remove(ACCOUNT_STREAMS_PREFIX ...)` at line 69):
```java
// Phase 4: SREM from tag-streams:{tag} for each tag
if (tags != null) {
    for (String tag : tags) {
        redisTemplate.opsForSet().remove(TAG_STREAMS_PREFIX + tag, streamId);
    }
}
```

Note: `remove()` signature must change to accept `List<String> tags` parameter (or read tags from the hash before deleting it). Reading from the hash before deletion is the safe pattern since the caller (the `AFTER_COMMIT` event listener) fires after the Postgres commit and the hash is still present.

**New `getStreamsByTag()` method** (copy `getActiveStreams()` pattern at lines 76-92):
```java
@Override
public List<StreamState> getStreamsByTag(String tag) {
    try {
        Set<String> streamIds = redisTemplate.opsForSet().members(TAG_STREAMS_PREFIX + tag);
        if (streamIds == null || streamIds.isEmpty()) {
            return Collections.emptyList();
        }
        return streamIds.stream()
            .map(id -> {
                Map<Object, Object> fields = redisTemplate.opsForHash().entries(STREAM_KEY_PREFIX + id);
                return fields.isEmpty() ? null : StreamState.fromRedis(id, fields);
            })
            .filter(Objects::nonNull)
            .toList();
    } catch (RedisConnectionFailureException e) {
        throw new RedisUnavailableException("Redis unavailable during getStreamsByTag for tag=" + tag + ": " + e.getMessage());
    }
}
```

---

### `engine-spring/.../service/StreamingServiceImpl.java` (service, CRUD — extend)

**Analog:** itself (lines 1-353 — full file read in this session).

**`startStream()` extension** — extend `StreamState` construction (lines 151-161) and `StreamingTransaction.builder()` (lines 130-139):

After `StreamingTransaction.builder()` block, add:
```java
// Phase 4: persist tags to stream_tags join table (via @ElementCollection on entity)
// and write rake fields to streaming_transactions row
StreamingTransaction.builder()
    ...
    .tags(request.tags() != null ? request.tags() : Collections.emptyList())
    .toAccountId(request.toAccountId())
    .rakeRate(request.rakeRate())
    .platformAccountId(request.platformAccountId())
    .build();
```

After `StreamState state = new StreamState(...)` (lines 151-161), extend with new fields:
```java
StreamState state = new StreamState(
    request.streamId(), request.accountId(), request.ratePerSecond(),
    System.currentTimeMillis(), System.nanoTime(), true,
    request.minimumAmount(), request.increment(),
    // Phase 4:
    request.tags() != null ? request.tags() : Collections.emptyList(),
    request.toAccountId(), request.rakeRate(), request.platformAccountId()
);
```

**`stopStream()` rake extension** — after `Account account = accountRepository.findWithLock(...)` (line 189), add:

```java
// Phase 4: acquire to-account and platform-account locks (lock order: from → to → platform)
// D-11: consistent with TransactionServiceImpl.transfer() lock ordering
BigDecimal rakeRate = state.rakeRate() != null ? state.rakeRate() : BigDecimal.ZERO;

Account toAccount = null;
Account platformAccount = null;
if (state.toAccountId() != null) {
    toAccount = accountRepository.findWithLock(state.toAccountId())
        .orElseThrow(() -> new AccountNotFoundException(state.toAccountId()));
}

// compute clampedAmount first (existing logic unchanged)
// ...

BigDecimal rakeAmount = state.rakeRate() != null
    ? clampedAmount.multiply(state.rakeRate()).setScale(18, RoundingMode.DOWN)
    : BigDecimal.ZERO;
BigDecimal toAccountAmount = clampedAmount.subtract(rakeAmount);

if (state.platformAccountId() != null && rakeAmount.compareTo(BigDecimal.ZERO) > 0) {
    platformAccount = accountRepository.findWithLock(state.platformAccountId())
        .orElseThrow(() -> new AccountNotFoundException(state.platformAccountId()));
}

// Phase 4: acquire tag row locks — alphabetical order to prevent deadlock (D-08)
List<String> sortedTags = state.tags().stream().sorted().toList();
Map<String, TagCommittedTotals> tagTotalsMap = new LinkedHashMap<>();
for (String tag : sortedTags) {
    TagCommittedTotals totals = tagCommittedTotalsRepository.findWithLock(tag)
        .orElseGet(() -> TagCommittedTotals.zero(tag));
    tagTotalsMap.put(tag, totals);
}
```

After balance mutations:
```java
// Phase 4: credit to-account and platform-account
if (toAccount != null) {
    BigDecimal toBalanceBefore = toAccount.getBalance();
    toAccount.credit(toAccountAmount);
    accountRepository.save(toAccount);
    // audit log entry for to-account credit
}
if (platformAccount != null) {
    BigDecimal platformBalanceBefore = platformAccount.getBalance();
    platformAccount.credit(rakeAmount);
    accountRepository.save(platformAccount);
    // audit log entry for platform credit
}

// Phase 4: update tag_committed_totals (inside same @Transactional)
for (String tag : sortedTags) {
    TagCommittedTotals totals = tagTotalsMap.get(tag);
    totals.addDebit(clampedAmount);
    totals.addCreditedRecipient(toAccountAmount);
    tagCommittedTotalsRepository.save(totals);
}
```

**`onStreamSettled()` AFTER_COMMIT extension** (lines 243-247) — `remove()` call must pass tags list:
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onStreamSettled(StreamSettledEvent event) {
    autoTerminationScheduler.cancel(event.streamId());
    streamRegistry.remove(event.streamId(), event.accountId(), event.tags());
}
```

The `StreamSettledEvent` must be extended to carry `tags` list so the AFTER_COMMIT handler can SREM from `tag-streams:{tag}` Sets.

---

### `engine-spring/.../service/TransactionServiceImpl.java` (service, CRUD — extend)

**Analog:** itself (lines 1-346 — full file read in this session).

**`credit()` extension** — after `discreteTransactionRepository.save(txn)` (line 85-92), add tag writes:
```java
// Phase 4: write tags to discrete_transaction_tags (via @ElementCollection)
// and update tag_committed_totals (D-08 lock ordering: account lock already held)
if (request.tags() != null && !request.tags().isEmpty()) {
    List<String> sortedTags = request.tags().stream().sorted().toList();
    for (String tag : sortedTags) {
        TagCommittedTotals totals = tagCommittedTotalsRepository.findWithLock(tag)
            .orElseGet(() -> TagCommittedTotals.zero(tag));
        totals.addCreditedRecipient(request.amount()); // CREDIT → total_credited_recipient
        tagCommittedTotalsRepository.save(totals);
    }
}
```

Note: the `@ElementCollection` on `DiscreteTransaction.tags` handles writing `discrete_transaction_tags` rows automatically when the entity is saved with a non-empty tags list.

**`debit()` extension** — same pattern but calls `totals.addDebit(request.amount())` instead.

**`transfer()` extension** — same pattern; calls `totals.addDebit(request.amount())` for the from-account side and `totals.addCreditedRecipient(toAccountAmount)` for the to-account side (consistent with TAG-06 gross flow tracking).

---

### `engine-spring/.../service/TagServiceImpl.java` (service, CRUD — new)

**Analog:** `engine-spring/.../service/StreamingServiceImpl.java` — `@Service`, `@Transactional`, `@RequiredArgsConstructor`, `@Slf4j` class-level annotations; idempotency-after-lock pattern; `@Transactional` on write methods.

**Imports pattern** (modelled on `StreamingServiceImpl.java` lines 1-46):
```java
package com.certacota.engine.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.TagCommittedTotals;
import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
```

**`aggregate()` method** — `@Transactional(readOnly = true)`, copies `estimateBalance()` pattern (`StreamingServiceImpl` lines 258-290):
```java
@Override
@Transactional(readOnly = true)
public TagAggregateResponse aggregate(String tag) {
    TagCommittedTotals committed = tagCommittedTotalsRepository.findById(tag).orElse(null);

    List<StreamState> activeTagStreams = streamRegistry.getStreamsByTag(tag);
    BigDecimal inFlightDebit = BigDecimal.ZERO;
    BigDecimal inFlightCreditedRecipient = BigDecimal.ZERO;

    for (StreamState s : activeTagStreams) {
        BigDecimal projection = StreamSettlementCalculator.computeProjection(s);
        inFlightDebit = inFlightDebit.add(projection);
        BigDecimal rakeRate = s.rakeRate() != null ? s.rakeRate() : BigDecimal.ZERO;
        BigDecimal inFlightRake = projection.multiply(rakeRate).setScale(18, RoundingMode.DOWN);
        inFlightCreditedRecipient = inFlightCreditedRecipient.add(projection.subtract(inFlightRake));
    }
    // build and return TagAggregateResponse
}
```

**`endByTag()` method** — `@Transactional`; idempotency key stored with `BULK_END_BY_TAG` operation (same `storeIdempotencyKey` pattern from `TransactionServiceImpl` lines 326-337):
```java
@Override
@Transactional
public EndByTagResponse endByTag(String tag, String idempotencyKey, String reason) {
    log.info("End-by-tag for tag={}, idempotencyKey={}", tag, idempotencyKey);
    // 1. Check idempotency (BULK_END_BY_TAG) — same pattern as StreamingServiceImpl.startStream() lines 73-78
    // 2. Store pending idempotency key FIRST (same pattern as startStream() lines 81-87)
    // 3. SMEMBERS tag-streams:{tag} → Set<streamId>
    // 4. For each streamId: check status, skip if SETTLED/ERROR, else settle via streamingService.stopStream()
    // 5. Publish StreamSettledEvent per settled stream (Pitfall 2: one event per stream)
    // 6. Update pending idempotency key with response
    String effectiveReason = reason != null ? reason : "end_by_tag";
    List<StreamState> candidates = streamRegistry.getStreamsByTag(tag);
    // ... settlement loop ...
}
```

---

### `engine-spring/.../scheduler/TagTtlCleanupJob.java` (scheduler, batch — new)

**Analog:** `engine-spring/.../scheduler/AuditArchivalJob.java` (lines 1-52 — full file read in this session).

**Full pattern copy** (`AuditArchivalJob.java` lines 1-52):
```java
package com.certacota.engine.spring.scheduler;

import com.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static net.javacrumbs.shedlock.core.LockAssert.assertLocked;

@Component
@RequiredArgsConstructor
@Slf4j
public class TagTtlCleanupJob {

    private final JdbcTemplate jdbcTemplate;
    private final TokenEngineProperties properties;

    @Scheduled(cron = "${token-engine.tags.cleanup-cron:0 0 3 * * *}")
    @SchedulerLock(
        name = "tag_ttl_cleanup_job",
        lockAtMostFor = "PT1H",
        lockAtLeastFor = "PT1M"
    )
    @Transactional
    public void runCleanup() {
        assertLocked();
        int deleted = jdbcTemplate.update(
            "DELETE FROM tag_committed_totals WHERE last_activity_at < NOW() - (? * INTERVAL '1 hour')",
            properties.getTags().getTtlHours());
        log.info("Tag TTL cleanup: deleted {} stale tag_committed_totals rows", deleted);
    }
}
```

Key differences from `AuditArchivalJob`: single step (no archive step — tag totals are ephemeral), uses `properties.getTags().getTtlHours()` from the new `TagProperties` inner class.

---

### `engine-spring/.../config/TokenEngineProperties.java` (config — extend)

**Analog:** itself (lines 1-42 — full file read in this session).

**`TagProperties` inner class** — copy `AuditProperties` pattern (lines 28-35):
```java
private TagProperties tags = new TagProperties();

@Getter
@Setter
public static class TagProperties {
    private int ttlHours = 24;
    private String cleanupCron = "0 0 3 * * *";
}
```

---

### `engine-spring/.../autoconfigure/TagAutoConfiguration.java` (config — new)

**Analog:** `engine-spring/.../autoconfigure/StreamingAutoConfiguration.java` (lines 1-115 — full file read in this session).

**Imports and class-level annotations** (`StreamingAutoConfiguration.java` lines 1-41):
```java
package com.certacota.engine.spring.autoconfigure;

import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.TagService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.scheduler.TagTtlCleanupJob;
import com.certacota.engine.spring.service.TagServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@EnableConfigurationProperties(TokenEngineProperties.class)
public class TagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TagService tagService(
            TagCommittedTotalsRepository tagCommittedTotalsRepository,
            StreamRegistry streamRegistry,
            IdempotencyKeyRepository idempotencyKeyRepository,
            StreamingService streamingService,
            ObjectMapper objectMapper) {
        return new TagServiceImpl(...);
    }

    @Bean
    @ConditionalOnMissingBean
    public TagTtlCleanupJob tagTtlCleanupJob(JdbcTemplate jdbcTemplate, TokenEngineProperties properties) {
        return new TagTtlCleanupJob(jdbcTemplate, properties);
    }
}
```

Register in `AutoConfiguration.imports` file alongside `StreamingAutoConfiguration`.

---

### `engine-service/.../controller/TagController.java` (controller, request-response — new)

**Analog:** `engine-service/.../controller/StreamController.java` (lines 1-43 — full file read in this session).

**Imports and class-level pattern** (`StreamController.java` lines 1-23):
```java
package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;
import com.certacota.engine.core.service.TagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
@Slf4j
public class TagController {

    private final TagService tagService;

    @GetMapping("/{tag}/aggregate")
    @ResponseStatus(HttpStatus.OK)
    public TagAggregateResponse aggregate(@PathVariable String tag) {
        log.info("Tag aggregate request for tag={}", tag);
        return tagService.aggregate(tag);
    }

    @PostMapping("/{tag}/end")
    @ResponseStatus(HttpStatus.OK)
    public EndByTagResponse endByTag(
            @PathVariable String tag,
            @Valid @RequestBody EndByTagRequest request) {
        log.info("End-by-tag request for tag={}, idempotencyKey={}", tag, request.idempotencyKey());
        return tagService.endByTag(tag, request.idempotencyKey(), request.reason());
    }
}
```

`EndByTagRequest` is a small local record (or a `engine-core` DTO):
```java
public record EndByTagRequest(
    @NotBlank String idempotencyKey,
    String reason
) { }
```

Controllers are thin delegates — no business logic, consistent with `StreamController` pattern.

---

### Flyway Migrations V10, V11, V12 (migration — new)

**Analog:** `engine-service/src/main/resources/db/migration/V7__create_streaming_transactions.sql` — file naming convention `V{N}__{description}.sql`.

Current highest migration: **V9** (`V9__create_audit_archive.sql`). Phase 4 uses V10, V11, V12.

Migration content from RESEARCH.md §Code Examples (verified against D-14/D-15/D-16 decisions):

**V10__add_metadata_portability.sql:**
```sql
ALTER TABLE discrete_transactions
    ALTER COLUMN metadata TYPE TEXT USING metadata::text;

ALTER TABLE streaming_transactions
    ADD COLUMN metadata TEXT;
```

**V11__create_tag_tables.sql:** (full DDL in RESEARCH.md lines 578-604)

**V12__add_streaming_rake_fields.sql:** (full DDL in RESEARCH.md lines 609-623)

---

### Cucumber Feature Files (test — new)

**Analog:** `engine-service/src/test/resources/features/streaming-stop.feature` (for streaming tests) and `discrete-rake.feature` (for rake/tag tests).

**Feature file pattern** (`streaming-stop.feature` lines 1-32, `discrete-rake.feature` lines 1-35):
```gherkin
Feature: [Feature name]

  Scenario: [Happy-path description]
    Given no account with id "[unique-id]" exists
    And an account "[id]" exists with balance [N].00
    [setup steps]
    When [action step]
    Then the response status is [code]
    And [assertion]

  Scenario: [Error case]
    Given ...
    When ...
    Then the response status is 4xx
```

Pattern notes:
- Each scenario uses unique account/stream IDs to avoid cross-scenario state leakage
- `Given no account with id "..." exists` step is mandatory at scenario start (cleanup pattern)
- `And the response status is N` always follows a `When` step
- BDD-style: business behavior described, not implementation

---

### `engine-service/.../steps/TagSteps.java` (test, request-response — new)

**Analog:** `engine-service/.../steps/StreamingSteps.java` (lines 1-80 read in this session — full class available).

**Class-level pattern** (`StreamingSteps.java` lines 1-59):
```java
@Slf4j
public class TagSteps {

    @Autowired
    private TagCommittedTotalsRepository tagCommittedTotalsRepository;

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TagSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) { return false; }
        });
    }
```

**Step method pattern** (`StreamingSteps.java` lines 61-75) — `@When`, `@Then`, `@And` with natural-language step sentences, `RestTemplate.exchange()` for HTTP calls, `sharedContext.setLastResponse()` for response storage, `assertThat()` for assertions.

---

## Shared Patterns

### Lock Acquisition (Pessimistic Write)
**Source:** `engine-core/.../repository/AccountRepository.java` lines 13-16
**Apply to:** `TagCommittedTotalsRepository.findWithLock()`, every settlement path in `StreamingServiceImpl` and `TagServiceImpl`
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findWithLock(@Param("id") String id);
```

### Lock Ordering (Deadlock Prevention)
**Source:** `engine-spring/.../service/TransactionServiceImpl.java` lines 225-233
**Apply to:** `StreamingServiceImpl.stopStream()` (rake extension), `TagServiceImpl.endByTag()`
```java
// Lock order: from → to → platform (consistent ordering prevents deadlock)
Account toAccount = accountRepository.findWithLock(request.toAccountId())...;
Account platformAccount = null;
if (request.platformAccountId() != null && rakeAmount.compareTo(BigDecimal.ZERO) > 0) {
    platformAccount = accountRepository.findWithLock(request.platformAccountId())...;
}
// Then tag locks alphabetically — D-08
```

### Rake Arithmetic
**Source:** `engine-spring/.../service/TransactionServiceImpl.java` lines 221-223
**Apply to:** `StreamingServiceImpl.stopStream()` rake extension, `TagServiceImpl` in-flight projection
```java
BigDecimal rakeRate = request.rakeRate() != null ? request.rakeRate() : BigDecimal.ZERO;
BigDecimal rakeAmount = request.amount().multiply(rakeRate).setScale(18, RoundingMode.DOWN);
BigDecimal toAccountAmount = request.amount().subtract(rakeAmount);
```

### Idempotency After Lock
**Source:** `engine-spring/.../service/TransactionServiceImpl.java` lines 68-74
**Apply to:** `TagServiceImpl.endByTag()` (uses `BULK_END_BY_TAG` operation key)
```java
var existing = idempotencyKeyRepository
    .findByIdempotencyKeyAndOperation(request.idempotencyKey(), "DISCRETE_CREDIT");
if (existing.isPresent()) {
    log.info("Returning cached idempotent response for key: {}", request.idempotencyKey());
    return deserialize(existing.get().getResponseBody(), PostTransactionResponse.class);
}
```

### Pending-First Idempotency (for write operations that need pre-write guard)
**Source:** `engine-spring/.../service/StreamingServiceImpl.java` lines 81-87
**Apply to:** `TagServiceImpl.endByTag()`
```java
IdempotencyKey pendingKey = idempotencyKeyRepository.save(IdempotencyKey.builder()
    .idempotencyKey(request.idempotencyKey())
    .operation("STREAM_START")
    .responseBody("pending")
    .createdAt(OffsetDateTime.now())
    .build());
```

### AFTER_COMMIT Event Pattern (Redis cleanup after Postgres commit)
**Source:** `engine-spring/.../service/StreamingServiceImpl.java` lines 238, 243-247
**Apply to:** `StreamingServiceImpl.stopStream()` rake extension — publish `StreamSettledEvent` with tags included; `TagServiceImpl.endByTag()` — one `StreamSettledEvent` per settled stream
```java
eventPublisher.publishEvent(new StreamSettledEvent(streamId, state.accountId()));
// ...
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onStreamSettled(StreamSettledEvent event) {
    autoTerminationScheduler.cancel(event.streamId());
    streamRegistry.remove(event.streamId(), event.accountId());
}
```

### ShedLock-Guarded Scheduled Job
**Source:** `engine-spring/.../scheduler/AuditArchivalJob.java` lines 22-31
**Apply to:** `TagTtlCleanupJob.runCleanup()`
```java
@Scheduled(cron = "${token-engine.audit.cron:0 0 2 * * *}")
@SchedulerLock(
    name = "audit_archival_job",
    lockAtMostFor = "${token-engine.audit.lock-at-most-hours:PT2H}",
    lockAtLeastFor = "${token-engine.audit.lock-at-least-minutes:PT1M}"
)
@Transactional
public void runArchival() {
    assertLocked();
    // ...
}
```

### Bean Registration in AutoConfiguration
**Source:** `engine-spring/.../autoconfigure/StreamingAutoConfiguration.java` lines 44-48
**Apply to:** `TagAutoConfiguration` bean registrations
```java
@Bean
@ConditionalOnMissingBean
public StreamRegistry streamRegistry(StringRedisTemplate stringRedisTemplate) {
    return new RedisStreamRegistry(stringRedisTemplate);
}
```

### Entity Builder Pattern
**Source:** `engine-core/.../domain/DiscreteTransaction.java` lines 21-28
**Apply to:** `TagCommittedTotals.java`
```java
@Entity
@Table(name = "discrete_transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscreteTransaction {
```

### Redis SMEMBERS → Stream List Pattern
**Source:** `engine-spring/.../redis/RedisStreamRegistry.java` lines 76-92
**Apply to:** `RedisStreamRegistry.getStreamsByTag()` (identical structure, different key prefix)
```java
Set<String> streamIds = redisTemplate.opsForSet().members(ACCOUNT_STREAMS_PREFIX + accountId);
if (streamIds == null || streamIds.isEmpty()) { return Collections.emptyList(); }
return streamIds.stream()
    .map(id -> {
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(STREAM_KEY_PREFIX + id);
        return fields.isEmpty() ? null : StreamState.fromRedis(id, fields);
    })
    .filter(Objects::nonNull)
    .toList();
```

---

## No Analog Found

All files have analogs in the codebase. No greenfield patterns required.

---

## Metadata

**Analog search scope:** `engine-core/`, `engine-spring/`, `engine-service/` — all three modules
**Files read for analog extraction:** 14 source files (full reads) + 2 feature files + 1 steps file
**Pattern extraction date:** 2026-05-14

**Critical coupling notes:**
- `StreamSettledEvent` must be extended with `List<String> tags` before `onStreamSettled()` can SREM from `tag-streams:{tag}` Sets — this is a breaking change to an existing class
- `StreamState` record extension is a breaking change (all call sites constructing `StreamState` directly must add the new fields) — `fromDbRow()` and `fromRedis()` are the two construction sites; compiler enforces completeness
- V10 migration (`ALTER COLUMN metadata TYPE TEXT`) and `DiscreteTransaction.java` annotation removal are coupled — they must ship in the same commit (Pitfall 5 in RESEARCH.md)
- `compileOnly 'org.hibernate.orm:hibernate-core'` in `engine-core/build.gradle` may be droppable after removing `@JdbcTypeCode`/`@SqlTypes` — verify after retrofit (RESEARCH.md A3)
