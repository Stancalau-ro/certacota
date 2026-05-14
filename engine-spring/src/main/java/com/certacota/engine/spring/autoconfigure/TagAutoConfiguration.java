package com.certacota.engine.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.StreamingService;
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
            ObjectMapper objectMapper,
            StreamingTransactionRepository streamingTransactionRepository) {
        return new TagServiceImpl(tagCommittedTotalsRepository, streamRegistry,
            idempotencyKeyRepository, streamingService, objectMapper, streamingTransactionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public TagTtlCleanupJob tagTtlCleanupJob(JdbcTemplate jdbcTemplate, TokenEngineProperties properties) {
        return new TagTtlCleanupJob(jdbcTemplate, properties);
    }
}
