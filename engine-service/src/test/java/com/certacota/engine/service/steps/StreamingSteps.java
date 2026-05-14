package com.certacota.engine.service.steps;

import com.certacota.engine.core.repository.StreamingTransactionRepository;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.cucumber.java.PendingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Slf4j
public class StreamingSteps {

    @Autowired
    private StreamingTransactionRepository streamingTransactionRepository;

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;

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
        throw new PendingException();
    }

    @When("I start a stream {string} on account {string} at rate {bigdecimal} with minimum amount {bigdecimal} and idempotency key {string}")
    public void startStreamWithMinimumAmount(String streamId, String accountId, BigDecimal rate, BigDecimal minimumAmount, String idempotencyKey) {
        throw new PendingException();
    }

    @When("I start a stream {string} on account {string} at rate {bigdecimal} with increment {bigdecimal} and idempotency key {string}")
    public void startStreamWithIncrement(String streamId, String accountId, BigDecimal rate, BigDecimal increment, String idempotencyKey) {
        throw new PendingException();
    }

    @When("I stop stream {string}")
    public void stopStream(String streamId) {
        throw new PendingException();
    }

    @When("I stop stream {string} with ignoreMinimum true")
    public void stopStreamWithIgnoreMinimum(String streamId) {
        throw new PendingException();
    }

    @When("I get estimated balance for account {string}")
    public void getEstimatedBalance(String accountId) {
        throw new PendingException();
    }

    @When("the auto-termination scheduler processes stream {string}")
    public void autoTerminationSchedulerProcessesStream(String streamId) {
        throw new PendingException();
    }

    @Given("account {string} is closed")
    public void accountIsClosed(String accountId) {
        throw new PendingException();
    }

    @Then("the stream response has stream id {string}")
    public void streamResponseHasStreamId(String streamId) {
        throw new PendingException();
    }

    @Then("the stream response has account id {string}")
    public void streamResponseHasAccountId(String accountId) {
        throw new PendingException();
    }

    @Then("the stream response has no minimum amount")
    public void streamResponseHasNoMinimumAmount() {
        throw new PendingException();
    }

    @Then("the stream response has increment {bigdecimal}")
    public void streamResponseHasIncrement(BigDecimal increment) {
        throw new PendingException();
    }

    @Then("the stop response has reason {string}")
    public void stopResponseHasReason(String reason) {
        throw new PendingException();
    }

    @Then("the stop response has settled amount greater than or equal to {bigdecimal}")
    public void stopResponseHasSettledAmountGreaterThanOrEqualTo(BigDecimal amount) {
        throw new PendingException();
    }

    @Then("the stop response has settled amount {bigdecimal}")
    public void stopResponseHasSettledAmount(BigDecimal amount) {
        throw new PendingException();
    }

    @Then("the stop response has settled amount less than {bigdecimal}")
    public void stopResponseHasSettledAmountLessThan(BigDecimal amount) {
        throw new PendingException();
    }

    @Then("the stop response settled amount is a multiple of {bigdecimal}")
    public void stopResponseSettledAmountIsMultipleOf(BigDecimal divisor) {
        throw new PendingException();
    }

    @Then("stream {string} has status {string} in the database")
    public void streamHasStatusInDatabase(String streamId, String status) {
        throw new PendingException();
    }

    @Then("stream {string} has reason {string} in the database")
    public void streamHasReasonInDatabase(String streamId, String reason) {
        throw new PendingException();
    }

    @Then("stream {string} has settled amount less than {bigdecimal} in the database")
    public void streamHasSettledAmountLessThanInDatabase(String streamId, BigDecimal amount) {
        throw new PendingException();
    }

    @Then("account {string} has committed balance less than {bigdecimal}")
    public void accountHasCommittedBalanceLessThan(String accountId, BigDecimal amount) {
        throw new PendingException();
    }

    @Then("the estimation response has committed balance {bigdecimal}")
    public void estimationResponseHasCommittedBalance(BigDecimal amount) {
        throw new PendingException();
    }

    @Then("the estimation response has estimated balance less than committed balance")
    public void estimationResponseHasEstimatedBalanceLessThanCommitted() {
        throw new PendingException();
    }

    @Then("the estimation response has estimated balance equal to committed balance")
    public void estimationResponseHasEstimatedBalanceEqualToCommitted() {
        throw new PendingException();
    }

    @Then("the estimation response has estimatedAt populated")
    public void estimationResponseHasEstimatedAtPopulated() {
        throw new PendingException();
    }

    @Then("the estimation response has estimatedDrainAt populated")
    public void estimationResponseHasEstimatedDrainAtPopulated() {
        throw new PendingException();
    }

    @Then("the estimation response has estimatedDrainAt null")
    public void estimationResponseHasEstimatedDrainAtNull() {
        throw new PendingException();
    }
}
