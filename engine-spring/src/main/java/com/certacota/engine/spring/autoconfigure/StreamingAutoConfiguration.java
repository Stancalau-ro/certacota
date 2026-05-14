package com.certacota.engine.spring.autoconfigure;

import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.redis.RedisStreamRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
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
    public StreamRegistry streamRegistry(RedisTemplate<String, String> redisTemplate) {
        return new RedisStreamRegistry(redisTemplate);
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
        for (String node : properties.getRedis().getSentinelNodes().split(",")) {
            String[] parts = node.trim().split(":");
            sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
