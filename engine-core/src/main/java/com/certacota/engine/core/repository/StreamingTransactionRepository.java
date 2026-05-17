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

    // Scalar query — returns status string, not a managed entity, so Hibernate never serves
    // a stale L1-cached result. Use this after a REQUIRES_NEW stopStream commit to verify
    // the actual DB status without the outer transaction's first-level cache interfering.
    @Query("SELECT t.status FROM StreamingTransaction t WHERE t.streamId = :streamId")
    Optional<String> findStatusByStreamId(@Param("streamId") String streamId);
}
