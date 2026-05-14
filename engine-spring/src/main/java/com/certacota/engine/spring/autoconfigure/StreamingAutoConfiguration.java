package com.certacota.engine.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.StreamingService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.redis.RedisStreamRegistry;
import com.certacota.engine.spring.scheduler.AuditArchivalJob;
import com.certacota.engine.spring.scheduler.AutoTerminationScheduler;
import com.certacota.engine.spring.scheduler.FallbackSweepJob;
import com.certacota.engine.spring.service.StreamingServiceImpl;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(TokenEngineProperties.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class StreamingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StreamRegistry streamRegistry(StringRedisTemplate stringRedisTemplate) {
        return new RedisStreamRegistry(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AutoTerminationScheduler autoTerminationScheduler(
            RedissonClient redissonClient,
            @Lazy StreamingService streamingService) {
        return new AutoTerminationScheduler(redissonClient, streamingService);
    }

    @Bean
    @ConditionalOnMissingBean
    public StreamingService streamingService(
            AccountRepository accountRepository,
            StreamingTransactionRepository streamingTransactionRepository,
            BalanceAuditLogRepository auditLogRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            StreamRegistry streamRegistry,
            TokenEngineProperties properties,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            TagCommittedTotalsRepository tagCommittedTotalsRepository) {
        return new StreamingServiceImpl(
            accountRepository, streamingTransactionRepository, auditLogRepository,
            idempotencyKeyRepository, streamRegistry, properties, objectMapper, eventPublisher,
            tagCommittedTotalsRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public FallbackSweepJob fallbackSweepJob(
            StreamingTransactionRepository streamingTransactionRepository,
            StreamingService streamingService,
            AccountRepository accountRepository,
            TokenEngineProperties properties) {
        return new FallbackSweepJob(streamingTransactionRepository, streamingService, accountRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditArchivalJob auditArchivalJob(JdbcTemplate jdbcTemplate, TokenEngineProperties properties) {
        return new AuditArchivalJob(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }

    @Bean
    @ConditionalOnProperty(name = "token-engine.redis.sentinel.master")
    public RedisConnectionFactory sentinelConnectionFactory(TokenEngineProperties properties) {
        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
            .master(properties.getRedis().getSentinelMaster());
        // WR-03: use lastIndexOf(':') to support IPv6 addresses and give a clear error on bad format
        for (String node : properties.getRedis().getSentinelNodes().split(",")) {
            String trimmed = node.trim();
            int colonIdx = trimmed.lastIndexOf(':');
            if (colonIdx < 1) {
                throw new IllegalStateException(
                    "Invalid sentinel node format (expected host:port): " + trimmed);
            }
            String host = trimmed.substring(0, colonIdx);
            int port = Integer.parseInt(trimmed.substring(colonIdx + 1));
            sentinelConfig.sentinel(host, port);
        }
        // WR-02: do not call afterPropertiesSet() manually; Spring manages the bean lifecycle
        return new LettuceConnectionFactory(sentinelConfig);
    }

}
