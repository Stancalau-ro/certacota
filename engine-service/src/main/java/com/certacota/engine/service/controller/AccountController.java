package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.CreateAccountRequest;
import com.certacota.engine.core.dto.CreditRequest;
import com.certacota.engine.core.dto.DebitRequest;
import com.certacota.engine.core.dto.PostTransactionResponse;
import com.certacota.engine.core.service.AccountService;
import com.certacota.engine.core.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;
    private final TransactionService transactionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }

    @DeleteMapping("/{accountId}")
    public AccountResponse closeAccount(@PathVariable String accountId) {
        return accountService.closeAccount(accountId);
    }

    @PostMapping("/{accountId}/credit")
    @ResponseStatus(HttpStatus.CREATED)
    public PostTransactionResponse credit(@PathVariable String accountId, @Valid @RequestBody CreditRequest request) {
        log.info("Posting CREDIT of {} to account: {}", request.amount(), accountId);
        return transactionService.credit(accountId, request);
    }

    @PostMapping("/{accountId}/debit")
    @ResponseStatus(HttpStatus.CREATED)
    public PostTransactionResponse debit(@PathVariable String accountId, @Valid @RequestBody DebitRequest request) {
        log.info("Posting DEBIT of {} from account: {}", request.amount(), accountId);
        return transactionService.debit(accountId, request);
    }
}
