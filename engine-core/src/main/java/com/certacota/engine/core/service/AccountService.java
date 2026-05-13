package com.certacota.engine.core.service;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.CreateAccountRequest;

public interface AccountService {
    AccountResponse createAccount(CreateAccountRequest request);
    AccountResponse getAccount(String accountId);
    AccountResponse closeAccount(String accountId);
}
