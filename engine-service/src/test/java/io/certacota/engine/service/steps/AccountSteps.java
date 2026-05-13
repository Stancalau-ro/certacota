package io.certacota.engine.service.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.certacota.engine.core.repository.AccountRepository;
import io.certacota.engine.core.repository.BalanceAuditLogRepository;
import io.cucumber.java.PendingException;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class AccountSteps {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceAuditLogRepository auditLogRepository;

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;

    public AccountSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Given("no account with id {string} exists")
    public void noAccountExists(String accountId) {
        accountRepository.deleteById(accountId);
    }

    @When("I create an account with id {string} and initial balance {bigdecimal}")
    public void createAccount(String accountId, BigDecimal balance) {
        String url = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
            "id", accountId,
            "initialBalance", balance,
            "idempotencyKey", "auto-" + accountId
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Create account response: {} {}", response.getStatusCode(), response.getBody());
    }

    @Then("the account {string} exists with committed balance {bigdecimal}")
    public void accountExistsWithBalance(String accountId, BigDecimal expectedBalance) throws Exception {
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId;
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(response.getBody());
        BigDecimal actualBalance = json.get("balance").decimalValue();
        assertThat(actualBalance.compareTo(expectedBalance)).isEqualTo(0);
    }

    @When("I close account {string}")
    public void closeAccount(String accountId) {
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId;
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
        sharedContext.setLastResponse(response);
        log.info("Close account response: {} {}", response.getStatusCode(), response.getBody());
    }

    @Then("account {string} has status CLOSED")
    public void accountHasStatusClosed(String accountId) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.get("status").asText()).isEqualTo("CLOSED");
    }

    @Then("the response status is {int}")
    public void responseStatusIs(int expectedStatus) {
        assertThat(sharedContext.getLastResponse().getStatusCode().value()).isEqualTo(expectedStatus);
    }

    @Given("account {string} exists with an active streaming transaction")
    public void accountExistsWithActiveStreamingTransaction(String accountId) {
        throw new PendingException("Stream registry not implemented until Phase 3");
    }

    @And("the error message mentions active streaming transactions")
    public void errorMessageMentionsActiveStreamingTransactions() {
        throw new PendingException("Stream registry not implemented until Phase 3");
    }
}
