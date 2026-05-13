package io.certacota.engine.core.service;

import io.certacota.engine.core.dto.AccountResponse;
import io.certacota.engine.core.dto.CreateAccountRequest;

public interface AccountService {
    AccountResponse createAccount(CreateAccountRequest request);
    AccountResponse getAccount(String accountId);
    AccountResponse closeAccount(String accountId);
}
