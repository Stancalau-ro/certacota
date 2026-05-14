package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.TagCommittedTotals;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface TagCommittedTotalsRepository extends JpaRepository<TagCommittedTotals, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TagCommittedTotals t WHERE t.tag = :tag")
    Optional<TagCommittedTotals> findWithLock(@Param("tag") String tag);

    int deleteByLastActivityAtBefore(OffsetDateTime cutoff);
}
