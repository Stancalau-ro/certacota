package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.EstimatedBalanceResponse;
import com.certacota.engine.core.service.StreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class EstimationController {

    private final StreamingService streamingService;

    @GetMapping("/{accountId}/estimated-balance")
    public EstimatedBalanceResponse estimateBalance(@PathVariable String accountId) {
        log.info("Getting estimated balance for account {}", accountId);
        return streamingService.estimateBalance(accountId);
    }
}
