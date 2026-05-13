package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByIdempotencyKeyAndOperation(String idempotencyKey, String operation);
}
