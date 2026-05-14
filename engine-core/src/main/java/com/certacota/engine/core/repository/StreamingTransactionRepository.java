package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.StreamingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StreamingTransactionRepository extends JpaRepository<StreamingTransaction, Long> {

    Optional<StreamingTransaction> findByStreamId(String streamId);

    List<StreamingTransaction> findByAccountIdAndStatus(String accountId, String status);

    List<StreamingTransaction> findByStatus(String status);

    boolean existsByAccountIdAndStatus(String accountId, String status);

    @Query("SELECT st.streamId FROM StreamingTransaction st JOIN st.tags t WHERE t = :tag AND st.status = 'ACTIVE'")
    List<String> findActiveStreamIdsByTag(@Param("tag") String tag);
}
