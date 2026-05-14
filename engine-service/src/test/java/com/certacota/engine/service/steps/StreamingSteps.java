package com.certacota.engine.service.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.service.StreamingService;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class StreamingSteps {

    @Autowired
    private StreamingTransactionRepository streamingTransactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private StreamingService streamingService;

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StreamingSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @When("I start a stream {string} on account {string} at rate {bigdecimal} with idempotency key {string}")
    public void startStream(String streamId, String accountId, BigDecimal rate, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/streams";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("accountId", accountId);
        body.put("ratePerSecond", rate);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Start stream response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I start a stream {string} on account {string} at rate {bigdecimal} with minimum amount {bigdecimal} and idempotency key {string}")
    public void startStreamWithMinimumAmount(String streamId, String accountId, BigDecimal rate, BigDecimal minimumAmount, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/streams";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("accountId", accountId);
        body.put("ratePerSecond", rate);
        body.put("minimumAmount", minimumAmount);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Start stream with minimum response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I start a stream {string} on account {string} at rate {bigdecimal} with increment {bigdecimal} and idempotency key {string}")
    public void startStreamWithIncrement(String streamId, String accountId, BigDecimal rate, BigDecimal increment, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/streams";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("accountId", accountId);
        body.put("ratePerSecond", rate);
        body.put("increment", increment);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Start stream with increment response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I stop stream {string}")
    public void stopStream(String streamId) {
        String url = "http://localhost:" + port + "/api/v1/streams/" + streamId + "/stop";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Stop stream response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I stop stream {string} with ignoreMinimum true")
    public void stopStreamWithIgnoreMinimum(String streamId) {
        String url = "http://localhost:" + port + "/api/v1/streams/" + streamId + "/stop";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("ignoreMinimum", true);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Stop stream with ignoreMinimum response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I get estimated balance for account {string}")
    public void getEstimatedBalance(String accountId) {
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId + "/estimated-balance";
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        sharedContext.setLastResponse(response);
        log.info("Estimated balance response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("the auto-termination scheduler processes stream {string}")
    public void autoTerminationSchedulerProcessesStream(String streamId) {
        streamingService.autoTerminate(streamId);
    }

    @Given("account {string} is closed")
    public void accountIsClosed(String accountId) {
        String url = "http://localhost:" + port + "/api/v1/accounts/" + accountId;
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
        log.info("Close account response: {} {}", response.getStatusCode(), response.getBody());
    }

    @Then("the stream response has stream id {string}")
    public void streamResponseHasStreamId(String streamId) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.get("streamId").asText()).isEqualTo(streamId);
    }

    @Then("the stream response has account id {string}")
    public void streamResponseHasAccountId(String accountId) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.get("accountId").asText()).isEqualTo(accountId);
    }

    @Then("the stream response has no minimum amount")
    public void streamResponseHasNoMinimumAmount() throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        JsonNode minimumAmount = json.get("minimumAmount");
        assertThat(minimumAmount == null || minimumAmount.isNull()).isTrue();
    }

    @Then("the stream response has increment {bigdecimal}")
    public void streamResponseHasIncrement(BigDecimal increment) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal actual = json.get("increment").decimalValue();
        assertThat(actual.compareTo(increment)).isEqualTo(0);
    }

    @Then("the stop response has reason {string}")
    public void stopResponseHasReason(String reason) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.get("reason").asText()).isEqualTo(reason);
    }

    @Then("the stop response has settled amount greater than or equal to {bigdecimal}")
    public void stopResponseHasSettledAmountGreaterThanOrEqualTo(BigDecimal amount) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal settled = json.get("settledAmount").decimalValue();
        assertThat(settled.compareTo(amount)).isGreaterThanOrEqualTo(0);
    }

    @Then("the stop response has settled amount {bigdecimal}")
    public void stopResponseHasSettledAmount(BigDecimal amount) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal settled = json.get("settledAmount").decimalValue();
        assertThat(settled.compareTo(amount)).isEqualTo(0);
    }

    @Then("the stop response has settled amount less than {bigdecimal}")
    public void stopResponseHasSettledAmountLessThan(BigDecimal amount) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal settled = json.get("settledAmount").decimalValue();
        assertThat(settled.compareTo(amount)).isLessThan(0);
    }

    @Then("the stop response settled amount is a multiple of {bigdecimal}")
    public void stopResponseSettledAmountIsMultipleOf(BigDecimal divisor) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal settled = json.get("settledAmount").decimalValue();
        if (settled.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        BigDecimal remainder = settled.remainder(divisor);
        assertThat(remainder.compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    @Then("stream {string} has status {string} in the database")
    public void streamHasStatusInDatabase(String streamId, String status) {
        streamingTransactionRepository.findByStreamId(streamId).ifPresentOrElse(
            txn -> assertThat(txn.getStatus()).isEqualTo(status),
            () -> { throw new AssertionError("Stream " + streamId + " not found in database"); }
        );
    }

    @Then("stream {string} has reason {string} in the database")
    public void streamHasReasonInDatabase(String streamId, String reason) {
        streamingTransactionRepository.findByStreamId(streamId).ifPresentOrElse(
            txn -> assertThat(txn.getReason()).isEqualTo(reason),
            () -> { throw new AssertionError("Stream " + streamId + " not found in database"); }
        );
    }

    @Then("stream {string} has settled amount less than {bigdecimal} in the database")
    public void streamHasSettledAmountLessThanInDatabase(String streamId, BigDecimal amount) {
        streamingTransactionRepository.findByStreamId(streamId).ifPresentOrElse(
            txn -> assertThat(txn.getSettledAmount().compareTo(amount)).isLessThan(0),
            () -> { throw new AssertionError("Stream " + streamId + " not found in database"); }
        );
    }

    @Then("account {string} has committed balance less than {bigdecimal}")
    public void accountHasCommittedBalanceLessThan(String accountId, BigDecimal amount) {
        accountRepository.findById(accountId).ifPresentOrElse(
            account -> assertThat(account.getBalance().compareTo(amount)).isLessThan(0),
            () -> { throw new AssertionError("Account " + accountId + " not found"); }
        );
    }

    @Then("the estimation response has committed balance {bigdecimal}")
    public void estimationResponseHasCommittedBalance(BigDecimal amount) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal committed = json.get("committedBalance").decimalValue();
        assertThat(committed.compareTo(amount)).isEqualTo(0);
    }

    @Then("the estimation response has estimated balance less than committed balance")
    public void estimationResponseHasEstimatedBalanceLessThanCommitted() throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal estimated = json.get("estimatedBalance").decimalValue();
        BigDecimal committed = json.get("committedBalance").decimalValue();
        assertThat(estimated.compareTo(committed)).isLessThan(0);
    }

    @Then("the estimation response has estimated balance equal to committed balance")
    public void estimationResponseHasEstimatedBalanceEqualToCommitted() throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal estimated = json.get("estimatedBalance").decimalValue();
        BigDecimal committed = json.get("committedBalance").decimalValue();
        assertThat(estimated.compareTo(committed)).isEqualTo(0);
    }

    @Then("the estimation response has estimatedAt populated")
    public void estimationResponseHasEstimatedAtPopulated() throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.has("estimatedAt")).isTrue();
        assertThat(json.get("estimatedAt").isNull()).isFalse();
    }

    @Then("the estimation response has estimatedDrainAt populated")
    public void estimationResponseHasEstimatedDrainAtPopulated() throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.has("estimatedDrainAt")).isTrue();
        assertThat(json.get("estimatedDrainAt").isNull()).isFalse();
    }

    @Then("the estimation response has estimatedDrainAt null")
    public void estimationResponseHasEstimatedDrainAtNull() throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        JsonNode drainAt = json.get("estimatedDrainAt");
        assertThat(drainAt == null || drainAt.isNull()).isTrue();
    }

    @When("I start a stream {string} on account {string} at rate {bigdecimal} with tags {string} and idempotency key {string}")
    public void startStreamWithTags(String streamId, String accountId, BigDecimal rate, String tagsCommaDelimited, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/streams";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("accountId", accountId);
        body.put("ratePerSecond", rate);
        body.put("idempotencyKey", idempotencyKey);
        List<String> tags = Arrays.stream(tagsCommaDelimited.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .toList();
        body.put("tags", tags);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Start stream with tags response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I start a stream {string} on account {string} at rate {bigdecimal} with rake rate {bigdecimal} to account {string} platform account {string} and idempotency key {string}")
    public void startStreamWithRake(String streamId, String accountId, BigDecimal rate, BigDecimal rakeRate, String toAccountId, String platformAccountId, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/streams";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("accountId", accountId);
        body.put("ratePerSecond", rate);
        body.put("rakeRate", rakeRate);
        body.put("toAccountId", toAccountId);
        body.put("platformAccountId", platformAccountId);
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Start stream with rake response: {} {}", response.getStatusCode(), response.getBody());
    }

    @Then("the start response has tags {string}")
    public void startResponseHasTags(String expectedTagsCsv) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        JsonNode tagsNode = json.get("tags");
        assertThat(tagsNode).isNotNull();
        List<String> expectedTags = Arrays.stream(expectedTagsCsv.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .sorted()
            .toList();
        List<String> actualTags = new java.util.ArrayList<>();
        tagsNode.forEach(t -> actualTags.add(t.asText()));
        actualTags.sort(String::compareTo);
        assertThat(actualTags).isEqualTo(expectedTags);
    }

    @When("I post a CREDIT of {bigdecimal} to account {string} with tags {string} and idempotency key {string}")
    public void postCreditWithTags(BigDecimal amount, String accountId, String tagsCommaDelimited, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/transactions/credit";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("amount", amount);
        body.put("idempotencyKey", idempotencyKey);
        List<String> tags = Arrays.stream(tagsCommaDelimited.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .toList();
        body.put("tags", tags);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Post credit with tags response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} with tags {string} and idempotency key {string}")
    public void postDebitWithTags(BigDecimal amount, String accountId, String tagsCommaDelimited, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/transactions/debit";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("amount", amount);
        body.put("idempotencyKey", idempotencyKey);
        List<String> tags = Arrays.stream(tagsCommaDelimited.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .toList();
        body.put("tags", tags);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Post debit with tags response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I post a DEBIT of {bigdecimal} from account {string} to account {string} with tags {string} and idempotency key {string}")
    public void postTransferWithTags(BigDecimal amount, String fromAccountId, String toAccountId, String tagsCommaDelimited, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/transactions/transfer";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("fromAccountId", fromAccountId);
        body.put("toAccountId", toAccountId);
        body.put("amount", amount);
        body.put("idempotencyKey", idempotencyKey);
        List<String> tags = Arrays.stream(tagsCommaDelimited.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .toList();
        body.put("tags", tags);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("Post transfer with tags response: {} {}", response.getStatusCode(), response.getBody());
    }
}
