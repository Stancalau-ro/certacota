package com.certacota.engine.service.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class BalanceFloorSteps {

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;

    public BalanceFloorSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Given("the global balance floor is {bigdecimal}")
    public void theGlobalBalanceFloorIs(BigDecimal floor) {
        // Global floor is configured via application.yml token-engine.balance-floor=0
        // This step is informational only — no action needed
        log.info("Global balance floor is: {}", floor);
    }

    @When("I attempt to create an account with id {string} and initialBalance {bigdecimal}")
    public void attemptToCreateAccountWithNegativeBalance(String accountId, BigDecimal initialBalance) {
        String url = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "id", accountId,
            "initialBalance", initialBalance,
            "idempotencyKey", "floor-test-" + accountId
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Attempt create floor test response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I attempt to create an account with id {string} and initialBalance {bigdecimal} and balanceFloor {bigdecimal}")
    public void attemptToCreateAccountWithBalanceFloor(String accountId, BigDecimal initialBalance, BigDecimal balanceFloor) {
        String url = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("id", accountId);
        body.put("initialBalance", initialBalance);
        body.put("balanceFloor", balanceFloor);
        body.put("idempotencyKey", "floor-test-" + accountId);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Attempt create per-account floor test response: {} {}", response.getStatusCode(), response.getBody());
    }
}
