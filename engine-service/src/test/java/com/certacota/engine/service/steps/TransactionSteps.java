package com.certacota.engine.service.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.DiscreteTransaction;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.DiscreteTransactionRepository;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class TransactionSteps {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DiscreteTransactionRepository discreteTransactionRepository;

    @Autowired
    private BalanceAuditLogRepository auditLogRepository;

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TransactionSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Given("an account {string} exists with balance {bigdecimal}")
    public void anAccountExistsWithBalance(String accountId, BigDecimal balance) {
        String url = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "id", accountId,
            "initialBalance", balance,
            "idempotencyKey", "setup-" + accountId
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        log.info("Setup account {} response: {} {}", accountId, response.getStatusCode(), response.getBody());
    }

    @When("I post a CREDIT of {bigdecimal} to account {string} with idempotency key {string}")
    public void postCredit(BigDecimal amount, String accountId, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId + "/credit";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "amount", amount,
            "idempotencyKey", idempotencyKey
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("CREDIT response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} with idempotency key {string}")
    public void postDebit(BigDecimal amount, String accountId, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId + "/debit";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "amount", amount,
            "idempotencyKey", idempotencyKey
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("DEBIT response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a CREDIT of {bigdecimal} to account {string} with metadata {string} and idempotency key {string}")
    public void postCreditWithMetadata(BigDecimal amount, String accountId, String metadataJson, String idempotencyKey) throws Exception {
        Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId + "/credit";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("metadata", metadata);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("CREDIT with metadata response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} with metadata {string} and idempotency key {string}")
    public void postDebitWithMetadata(BigDecimal amount, String accountId, String metadataJson, String idempotencyKey) throws Exception {
        Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId + "/debit";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("metadata", metadata);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("DEBIT with metadata response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} to account {string} with idempotency key {string}")
    public void postTransfer(BigDecimal amount, String fromAccountId, String toAccountId, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", fromAccountId);
        body.put("toAccountId", toAccountId);
        body.put("amount", amount);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Transfer response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} to account {string} with metadata {string} and idempotency key {string}")
    public void postTransferWithMetadata(BigDecimal amount, String fromAccountId, String toAccountId, String metadataJson, String idempotencyKey) throws Exception {
        Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", fromAccountId);
        body.put("toAccountId", toAccountId);
        body.put("amount", amount);
        body.put("metadata", metadata);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Transfer with metadata response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} to account {string} with rake rate {bigdecimal} platform account {string} and idempotency key {string}")
    public void postTransferWithRake(BigDecimal amount, String fromAccountId, String toAccountId, BigDecimal rakeRate, String platformAccountId, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", fromAccountId);
        body.put("toAccountId", toAccountId);
        body.put("amount", amount);
        body.put("rakeRate", rakeRate);
        body.put("platformAccountId", platformAccountId);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Transfer with rake response: {} {}", response.getStatusCode(), response.getBody());
    }

    @Then("the transaction response has balanceAfter {bigdecimal}")
    public void transactionResponseHasBalanceAfter(BigDecimal expectedBalance) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal actualBalance = json.get("balanceAfter").decimalValue();
        assertThat(actualBalance.compareTo(expectedBalance)).isEqualTo(0);
    }

    @Then("the transaction response metadata contains key {string} with value {string}")
    public void transactionResponseMetadataContainsKey(String key, String expectedValue) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        JsonNode metadata = json.get("metadata");
        assertThat(metadata).isNotNull();
        assertThat(metadata.get(key)).isNotNull();
        assertThat(metadata.get(key).asText()).isEqualTo(expectedValue);
    }

    @Then("the audit log for account {string} has an entry with transaction metadata key {string} value {string}")
    public void auditLogHasEntryWithTransactionMetadata(String accountId, String metadataKey, String expectedValue) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        Long transactionId = json.get("transactionId").longValue();

        Optional<DiscreteTransaction> txnOpt = discreteTransactionRepository.findById(transactionId);
        assertThat(txnOpt).isPresent();
        DiscreteTransaction txn = txnOpt.get();
        assertThat(txn.getMetadata()).isNotNull();
        assertThat(txn.getMetadata().get(metadataKey)).isNotNull();
        assertThat(txn.getMetadata().get(metadataKey).toString()).isEqualTo(expectedValue);

        List<?> auditEntries = auditLogRepository.findByAccountId(accountId);
        boolean hasMatchingEntry = auditEntries.stream()
            .anyMatch(entry -> {
                com.certacota.engine.core.domain.BalanceAuditLog log =
                    (com.certacota.engine.core.domain.BalanceAuditLog) entry;
                return transactionId.equals(log.getTransactionId());
            });
        assertThat(hasMatchingEntry)
            .as("Audit log for account %s should have an entry linked to transaction %d", accountId, transactionId)
            .isTrue();
    }
}
