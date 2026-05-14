package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.StreamingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StreamingTransactionRepository extends JpaRepository<StreamingTransaction, Long> {

    Optional<StreamingTransaction> findByStreamId(String streamId);

    List<StreamingTransaction> findByAccountIdAndStatus(String accountId, String status);

    List<StreamingTransaction> findByStatus(String status);

    boolean existsByAccountIdAndStatus(String accountId, String status);
}
