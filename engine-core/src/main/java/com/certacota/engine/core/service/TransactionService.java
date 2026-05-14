package com.certacota.engine.core.service;

import com.certacota.engine.core.dto.CreditRequest;
import com.certacota.engine.core.dto.DebitRequest;
import com.certacota.engine.core.dto.PostTransactionResponse;
import com.certacota.engine.core.dto.PostTransferRequest;

public interface TransactionService {
    PostTransactionResponse credit(String accountId, CreditRequest request);
    PostTransactionResponse debit(String accountId, DebitRequest request);
    PostTransactionResponse transfer(PostTransferRequest request);
}
