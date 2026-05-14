package com.certacota.engine.spring.redis;

import com.certacota.engine.core.domain.StreamState;
import com.certacota.engine.core.exception.RedisUnavailableException;
import com.certacota.engine.core.service.StreamRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStreamRegistry implements StreamRegistry {

    private static final String STREAM_KEY_PREFIX = "stream:";
    private static final String ACCOUNT_STREAMS_PREFIX = "account-streams:";
    private static final long JVM_START_NANO = System.nanoTime();

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void register(StreamState state) {
        try {
            String streamKey = STREAM_KEY_PREFIX + state.streamId();
            String accountStreamsKey = ACCOUNT_STREAMS_PREFIX + state.accountId();

            Map<String, String> fields = new HashMap<>();
            fields.put("accountId", state.accountId());
            fields.put("ratePerSecond", state.ratePerSecond().toPlainString());
            fields.put("startedAtEpochMillis", String.valueOf(state.startedAtEpochMillis()));
            fields.put("startedAtNano", String.valueOf(state.startedAtNano()));
            fields.put("minimumAmount", state.minimumAmount() != null ? state.minimumAmount().toPlainString() : "");
            fields.put("increment", state.increment() != null ? state.increment().toPlainString() : "");
            fields.put("status", "ACTIVE");

            redisTemplate.opsForHash().putAll(streamKey, fields);
            redisTemplate.opsForSet().add(accountStreamsKey, state.streamId());
        } catch (RedisConnectionFailureException e) {
            throw new RedisUnavailableException("Redis unavailable during stream registration: " + e.getMessage());
        }
    }

    @Override
    public Optional<StreamState> get(String streamId) {
        try {
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(STREAM_KEY_PREFIX + streamId);
            if (fields.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(StreamState.fromRedis(streamId, fields));
        } catch (RedisConnectionFailureException e) {
            throw new RedisUnavailableException("Redis unavailable during stream lookup: " + e.getMessage());
        }
    }

    @Override
    public void remove(String streamId, String accountId) {
        try {
            redisTemplate.delete(STREAM_KEY_PREFIX + streamId);
            redisTemplate.opsForSet().remove(ACCOUNT_STREAMS_PREFIX + accountId, streamId);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable during stream removal for streamId={}; startup reconciliation will resync", streamId);
        }
    }

    @Override
    public List<StreamState> getActiveStreams(String accountId) {
        try {
            Set<String> streamIds = redisTemplate.opsForSet().members(ACCOUNT_STREAMS_PREFIX + accountId);
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
            log.warn("Redis unavailable during getActiveStreams for accountId={}; returning empty list", accountId);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean hasActiveStreams(String accountId) {
        try {
            Long size = redisTemplate.opsForSet().size(ACCOUNT_STREAMS_PREFIX + accountId);
            return size != null && size > 0;
        } catch (RedisConnectionFailureException e) {
            throw new RedisUnavailableException("Redis unavailable during hasActiveStreams check: " + e.getMessage());
        }
    }

    public static boolean startedAtNanoFromCurrentJvm(long storedNano) {
        return storedNano >= JVM_START_NANO;
    }
}
