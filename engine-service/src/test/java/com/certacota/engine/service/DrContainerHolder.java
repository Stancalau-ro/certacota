package com.certacota.engine.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class DrContainerHolder {

    private static final PostgreSQLContainer<?> postgres;
    private static final GenericContainer<?> redis;

    static {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
        redis.start();

        // @DynamicPropertySource is not discovered in imported @TestConfiguration classes;
        // use System.setProperty so Redis coordinates are available before context refresh.
        System.setProperty("spring.data.redis.host", redis.getHost());
        System.setProperty("spring.data.redis.port", String.valueOf(redis.getMappedPort(6379)));
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return postgres;
    }

    public static PostgreSQLContainer<?> getPostgresContainer() {
        return postgres;
    }

    public static GenericContainer<?> getRedisContainer() {
        return redis;
    }
}
