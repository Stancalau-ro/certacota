package com.certacota.engine.spring.redis;

import com.certacota.engine.core.domain.StreamState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {RedisAutoConfiguration.class, RedisStreamRegistry.class})
@Testcontainers
class RedisStreamRegistryTagIT {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private RedisStreamRegistry redisStreamRegistry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private StreamState buildState(String streamId, List<String> tags) {
        return new StreamState(
            streamId, "test-account",
            new BigDecimal("1.0"),
            System.currentTimeMillis(),
            System.nanoTime(),
            true,
            null, null,
            tags,
            null, null, null
        );
    }

    @Test
    void register_sadd_tagStreams() {
        StreamState state = buildState("stream-001", List.of("alpha", "beta"));
        redisStreamRegistry.register(state);

        Long alphaSize = redisTemplate.opsForSet().size("tag-streams:alpha");
        Long betaSize = redisTemplate.opsForSet().size("tag-streams:beta");
        assertThat(alphaSize).isEqualTo(1);
        assertThat(betaSize).isEqualTo(1);
        assertThat(redisTemplate.opsForSet().isMember("tag-streams:alpha", "stream-001")).isTrue();
        assertThat(redisTemplate.opsForSet().isMember("tag-streams:beta", "stream-001")).isTrue();
    }

    @Test
    void remove_srem_tagStreams() {
        StreamState state = buildState("stream-002", List.of("alpha", "beta"));
        redisStreamRegistry.register(state);

        redisStreamRegistry.remove("stream-002", "test-account", List.of("alpha", "beta"));

        assertThat(redisTemplate.opsForSet().isMember("tag-streams:alpha", "stream-002")).isFalse();
        assertThat(redisTemplate.opsForSet().isMember("tag-streams:beta", "stream-002")).isFalse();
        assertThat(redisTemplate.opsForHash().entries("stream:stream-002")).isEmpty();
    }

    @Test
    void register_storesTagsAsCommaSeparatedHashField() {
        StreamState state = buildState("stream-003", List.of("alpha", "beta"));
        redisStreamRegistry.register(state);

        Object tagsField = redisTemplate.opsForHash().get("stream:stream-003", "tags");
        assertThat(tagsField).isNotNull();
        assertThat(tagsField.toString()).contains("alpha");
        assertThat(tagsField.toString()).contains("beta");
    }

    @Test
    void getStreamsByTag_returnsStreamState() {
        StreamState state = buildState("stream-004", List.of("alpha"));
        redisStreamRegistry.register(state);

        List<StreamState> found = redisStreamRegistry.getStreamsByTag("alpha");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).streamId()).isEqualTo("stream-004");
        assertThat(found.get(0).tags()).contains("alpha");
    }
}
