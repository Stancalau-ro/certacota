package com.certacota.engine.service.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.domain.DiscreteTransaction;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.BalanceAuditLogRepository;
import com.certacota.engine.core.repository.DiscreteTransactionRepository;
import com.certacota.engine.spring.config.TokenEngineProperties;
import io.cucumber.java.Before;
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

    @Autowired
    private TokenEngineProperties tokenEngineProperties;

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

    @Before
    public void resetRakeProperties() {
        TokenEngineProperties.RakeProperties rake = tokenEngineProperties.getRake();
        rake.setEnabled(false);
        rake.setMetadataKey("transaction_type");
        rake.getRates().clear();
        rake.setPlatformAccountId(null);
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
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "accountId", accountId,
            "type", "CREDIT",
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
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "accountId", accountId,
            "type", "DEBIT",
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
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("type", "CREDIT");
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
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("type", "DEBIT");
        body.put("amount", amount);
        body.put("metadata", metadata);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("DEBIT with metadata response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} to account {string} with idempotency key {string}")
    public void postDebitWithToAccount(BigDecimal amount, String fromAccountId, String toAccountId, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", fromAccountId);
        body.put("toAccountId", toAccountId);
        body.put("type", "DEBIT");
        body.put("amount", amount);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("DEBIT with toAccount response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} to account {string} with metadata {string} and idempotency key {string}")
    public void postDebitWithToAccountAndMetadata(BigDecimal amount, String fromAccountId, String toAccountId, String metadataJson, String idempotencyKey) throws Exception {
        Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        String url = "http://localhost:" + port + "/api/v1/transactions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", fromAccountId);
        body.put("toAccountId", toAccountId);
        body.put("type", "DEBIT");
        body.put("amount", amount);
        body.put("metadata", metadata);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("DEBIT with toAccount response: {} {}", response.getStatusCode(), response.getBody());
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

    @Given("rake is configured with rate {bigdecimal} for transaction type {string} using metadata key {string} and platform account {string}")
    public void rakeIsConfigured(BigDecimal rate, String transactionType, String metadataKey, String platformAccountId) {
        TokenEngineProperties.RakeProperties rake = tokenEngineProperties.getRake();
        rake.setEnabled(true);
        rake.setMetadataKey(metadataKey);
        rake.getRates().put(transactionType, rate.toPlainString());
        rake.setPlatformAccountId(platformAccountId);
        log.info("Rake configured: rate={} for type={} via key={} platform={}", rate, transactionType, metadataKey, platformAccountId);
    }
}
