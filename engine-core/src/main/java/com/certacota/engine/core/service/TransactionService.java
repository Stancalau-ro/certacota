package com.certacota.engine.core.service;

import com.certacota.engine.core.dto.PostTransactionRequest;
import com.certacota.engine.core.dto.PostTransactionResponse;

public interface TransactionService {
    PostTransactionResponse credit(PostTransactionRequest request);
    PostTransactionResponse debit(PostTransactionRequest request);
}
