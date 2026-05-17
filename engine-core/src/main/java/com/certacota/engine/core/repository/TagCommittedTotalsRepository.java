package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.TagCommittedTotals;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface TagCommittedTotalsRepository extends JpaRepository<TagCommittedTotals, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TagCommittedTotals t WHERE t.tag = :tag")
    Optional<TagCommittedTotals> findWithLock(@Param("tag") String tag);

    // Atomic upsert: inserts a new row or increments existing totals in one statement.
    // Eliminates the phantom-row INSERT race that occurs when two concurrent REQUIRES_NEW
    // transactions both see no row for the same tag and both attempt INSERT.
    @Modifying
    @Query(value = """
        INSERT INTO tag_committed_totals (tag, total_debited, total_credited_recipient, last_activity_at)
        VALUES (:tag, :debit, :credit, CURRENT_TIMESTAMP)
        ON CONFLICT (tag) DO UPDATE SET
            total_debited = tag_committed_totals.total_debited + EXCLUDED.total_debited,
            total_credited_recipient = tag_committed_totals.total_credited_recipient + EXCLUDED.total_credited_recipient,
            last_activity_at = CURRENT_TIMESTAMP
        """, nativeQuery = true)
    void upsertTotals(@Param("tag") String tag, @Param("debit") BigDecimal debit, @Param("credit") BigDecimal credit);

    int deleteByLastActivityAtBefore(OffsetDateTime cutoff);
}
