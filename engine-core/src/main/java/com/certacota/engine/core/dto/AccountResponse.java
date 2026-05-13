package com.certacota.engine.core.dto;

import com.certacota.engine.core.domain.Account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record AccountResponse(
    String id,
    String status,
    BigDecimal balance,
    BigDecimal balanceFloor,
    Map<String, Object> metadata,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
            account.getId(),
            account.getStatus().name(),
            account.getBalance(),
            account.getBalanceFloor(),
            account.getMetadata(),
            account.getCreatedAt(),
            account.getUpdatedAt()
        );
    }
}
