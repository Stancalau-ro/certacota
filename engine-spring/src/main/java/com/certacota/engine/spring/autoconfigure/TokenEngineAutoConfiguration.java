package com.certacota.engine.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.DiscreteTransactionRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.AccountService;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.TransactionService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.service.AccountServiceImpl;
import com.certacota.engine.spring.service.TransactionServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(TokenEngineProperties.class)
public class TokenEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AccountService accountService(
            AccountRepository accountRepository,
            BalanceAuditLogRepository auditLogRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            StreamingTransactionRepository streamingTransactionRepository,
            StreamRegistry streamRegistry,
            TokenEngineProperties properties,
            ObjectMapper objectMapper) {
        return new AccountServiceImpl(
            accountRepository, auditLogRepository, idempotencyKeyRepository,
            streamingTransactionRepository, streamRegistry, properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionService transactionService(
            AccountRepository accountRepository,
            DiscreteTransactionRepository discreteTransactionRepository,
            BalanceAuditLogRepository auditLogRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            StreamingTransactionRepository streamingTransactionRepository,
            StreamRegistry streamRegistry,
            TokenEngineProperties properties,
            ObjectMapper objectMapper,
            TagCommittedTotalsRepository tagCommittedTotalsRepository) {
        return new TransactionServiceImpl(
            accountRepository, discreteTransactionRepository, auditLogRepository,
            idempotencyKeyRepository, streamingTransactionRepository, streamRegistry, properties, objectMapper,
            tagCommittedTotalsRepository);
    }
}
