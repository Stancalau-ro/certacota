package io.certacota.engine.core.repository;

import io.certacota.engine.core.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {
}
