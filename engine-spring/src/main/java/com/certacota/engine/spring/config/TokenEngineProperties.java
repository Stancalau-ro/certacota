package com.certacota.engine.spring.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "token-engine")
@Getter
@Setter
public class TokenEngineProperties {

    private BigDecimal balanceFloor = BigDecimal.ZERO;

    private RakeProperties rake = new RakeProperties();

    @Getter
    @Setter
    @Slf4j
    public static class RakeProperties {

        private boolean enabled = false;
        private String metadataKey = "transaction_type";
        private Map<String, String> rates = new HashMap<>();
        private String platformAccountId;

        public BigDecimal getRateFor(Map<String, Object> metadata) {
            if (!enabled || metadata == null) {
                return BigDecimal.ZERO;
            }
            Object keyValue = metadata.get(metadataKey);
            if (keyValue == null) {
                return BigDecimal.ZERO;
            }
            String rateStr = rates.get(keyValue.toString());
            if (rateStr == null) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(rateStr);
            } catch (NumberFormatException e) {
                log.warn("Misconfigured rake rate '{}' for key '{}'; treating as zero", rateStr, keyValue);
                return BigDecimal.ZERO;
            }
        }
    }
}
