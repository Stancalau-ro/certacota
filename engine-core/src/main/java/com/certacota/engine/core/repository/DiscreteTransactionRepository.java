package com.certacota.engine.core.repository;

import com.certacota.engine.core.domain.DiscreteTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscreteTransactionRepository extends JpaRepository<DiscreteTransaction, Long> {

    List<DiscreteTransaction> findByAccountId(String accountId);

    Optional<DiscreteTransaction> findByIdempotencyKey(String idempotencyKey);
}
