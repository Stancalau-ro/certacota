package io.certacota.engine.service.steps;

import io.certacota.engine.core.repository.BalanceAuditLogRepository;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class IdempotencySteps {

    @Autowired
    private BalanceAuditLogRepository auditLogRepository;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;
    private String currentIdempotencyKey;
    private ResponseEntity<String> firstResponse;
    private ResponseEntity<String> secondResponse;
    private String idempotencyAccountId;

    public IdempotencySteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Given("I have idempotency key {string}")
    public void iHaveIdempotencyKey(String key) {
        currentIdempotencyKey = key;
        idempotencyAccountId = "idem-acct-" + key.replaceAll("[^a-zA-Z0-9]", "").substring(0, Math.min(8, key.replaceAll("[^a-zA-Z0-9]", "").length()));
    }

    @When("I create an account with idempotency key {string} and balance {bigdecimal}")
    public void createAccountWithIdempotencyKey(String key, BigDecimal balance) {
        String url = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "id", idempotencyAccountId,
            "initialBalance", balance,
            "idempotencyKey", key
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        firstResponse = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        log.info("First create response: {} {}", firstResponse.getStatusCode(), firstResponse.getBody());
    }

    @And("I create an account again with idempotency key {string} and balance {bigdecimal}")
    public void createAccountAgainWithIdempotencyKey(String key, BigDecimal balance) {
        String url = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "id", idempotencyAccountId,
            "initialBalance", balance,
            "idempotencyKey", key
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        secondResponse = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        log.info("Second create response: {} {}", secondResponse.getStatusCode(), secondResponse.getBody());
    }

    @Then("both responses are identical")
    public void bothResponsesAreIdentical() {
        assertThat(firstResponse.getBody()).isEqualTo(secondResponse.getBody());
    }

    @And("there is exactly 1 audit log entry for account creation")
    public void thereIsExactlyOneAuditLogEntry() {
        List<?> entries = auditLogRepository.findByAccountId(idempotencyAccountId);
        assertThat(entries).hasSize(1);
    }
}
