package com.certacota.engine.spring.autoconfigure;

import com.certacota.engine.core.repository.TagCommittedTotalsRepository;
import com.certacota.engine.core.service.StreamRegistry;
import com.certacota.engine.core.service.TagService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import com.certacota.engine.spring.service.TagServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(TokenEngineProperties.class)
public class TagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TagService tagService(
            TagCommittedTotalsRepository tagCommittedTotalsRepository,
            StreamRegistry streamRegistry) {
        return new TagServiceImpl(tagCommittedTotalsRepository, streamRegistry);
    }
}
