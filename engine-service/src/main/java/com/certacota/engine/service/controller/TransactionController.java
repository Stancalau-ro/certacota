package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.CreditRequest;
import com.certacota.engine.core.dto.DebitRequest;
import com.certacota.engine.core.dto.PostTransactionResponse;
import com.certacota.engine.core.dto.PostTransferRequest;
import com.certacota.engine.core.service.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    @PostMapping("/credit")
    @ResponseStatus(HttpStatus.CREATED)
    public PostTransactionResponse credit(@Valid @RequestBody TaggedCreditRequest request) {
        log.info("Posting tagged CREDIT of {} to account: {}", request.amount(), request.accountId());
        return transactionService.credit(request.accountId(), new CreditRequest(
            request.amount(), request.idempotencyKey(), request.metadata(), request.tags()));
    }

    @PostMapping("/debit")
    @ResponseStatus(HttpStatus.CREATED)
    public PostTransactionResponse debit(@Valid @RequestBody TaggedDebitRequest request) {
        log.info("Posting tagged DEBIT of {} from account: {}", request.amount(), request.accountId());
        return transactionService.debit(request.accountId(), new DebitRequest(
            request.amount(), request.idempotencyKey(), request.metadata(), request.tags()));
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public PostTransactionResponse taggedTransfer(@Valid @RequestBody TaggedTransferRequest request) {
        log.info("Posting tagged transfer of {} from account: {} to account: {}", request.amount(), request.fromAccountId(), request.toAccountId());
        return transactionService.transfer(new PostTransferRequest(
            request.fromAccountId(), request.toAccountId(), request.amount(), request.idempotencyKey(),
            request.metadata(), request.rakeRate(), request.platformAccountId(), request.tags()));
    }

    public record TaggedCreditRequest(
        @NotBlank String accountId,
        @NotNull @Positive BigDecimal amount,
        @NotNull String idempotencyKey,
        Map<String, Object> metadata,
        @Size(max = 50) List<@NotBlank @Size(max = 255) String> tags
    ) { }

    public record TaggedDebitRequest(
        @NotBlank String accountId,
        @NotNull @Positive BigDecimal amount,
        @NotNull String idempotencyKey,
        Map<String, Object> metadata,
        @Size(max = 50) List<@NotBlank @Size(max = 255) String> tags
    ) { }

    public record TaggedTransferRequest(
        @NotBlank String fromAccountId,
        @NotBlank String toAccountId,
        @NotNull @Positive BigDecimal amount,
        @NotNull String idempotencyKey,
        Map<String, Object> metadata,
        BigDecimal rakeRate,
        String platformAccountId,
        @Size(max = 50) List<@NotBlank @Size(max = 255) String> tags
    ) { }
}
