package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.BalanceAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BalanceAuditLogRepository extends JpaRepository<BalanceAuditLog, Long> {
    List<BalanceAuditLog> findByAccountId(String accountId);
}
