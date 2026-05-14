package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.PostTransactionResponse;
import com.certacota.engine.core.dto.PostTransferRequest;
import com.certacota.engine.core.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostTransactionResponse transfer(@Valid @RequestBody PostTransferRequest request) {
        log.info("Posting transfer of {} from account: {} to account: {}", request.amount(), request.accountId(), request.toAccountId());
        return transactionService.transfer(request);
    }
}
