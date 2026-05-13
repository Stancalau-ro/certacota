package io.certacota.engine.spring.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "token-engine")
@Getter
@Setter
public class TokenEngineProperties {

    private BigDecimal balanceFloor = BigDecimal.ZERO;
}
