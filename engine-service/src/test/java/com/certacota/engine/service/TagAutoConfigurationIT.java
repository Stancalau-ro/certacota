package com.certacota.engine.service;

import com.certacota.engine.core.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TagAutoConfigurationIT {

    @Autowired
    private TagService tagService;

    @Test
    void tagServiceBeanLoads() {
        assertThat(tagService).isNotNull();
    }

    @Test
    void aggregateReturnsResponseForUnknownTag() {
        var response = tagService.aggregate("nonexistent-tag");
        assertThat(response).isNotNull();
        assertThat(response.committed()).isNotNull();
        assertThat(response.inFlight()).isNotNull();
        assertThat(response.estimatedAt()).isNotNull();
    }
}
