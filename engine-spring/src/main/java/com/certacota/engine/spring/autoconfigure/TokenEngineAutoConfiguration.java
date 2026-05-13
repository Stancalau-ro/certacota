package com.certacota.engine.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.IdempotencyKeyRepository;
import com.certacota.engine.core.service.AccountService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.service.AccountServiceImpl;
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
            TokenEngineProperties properties,
            ObjectMapper objectMapper) {
        return new AccountServiceImpl(
            accountRepository, auditLogRepository, idempotencyKeyRepository, properties, objectMapper);
    }
}
