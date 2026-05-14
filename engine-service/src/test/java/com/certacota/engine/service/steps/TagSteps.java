package com.certacota.engine.service.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.And;
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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class TagSteps {

    // FORWARD REF: TagCommittedTotalsRepository created in plan 02 — kept as injection point
    // @Autowired
    // private TagCommittedTotalsRepository tagCommittedTotalsRepository;

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TagSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @When("I get tag aggregate for {string}")
    public void getTagAggregate(String tag) {
        String url = "http://localhost:" + port + "/api/v1/tags/" + tag + "/aggregate";
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        sharedContext.setLastResponse(response);
        log.info("Tag aggregate response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("I end streams by tag {string} with idempotency key {string}")
    public void endStreamsByTag(String tag, String idempotencyKey) {
        String url = "http://localhost:" + port + "/api/v1/tags/" + tag + "/end";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("idempotencyKey", idempotencyKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        sharedContext.setLastResponse(response);
        log.info("End-by-tag response: {} {}", response.getStatusCode(), response.getBody());
    }

    @Then("the tag aggregate in-flight debit is greater than or equal to {bigdecimal}")
    public void tagAggregateInFlightDebitIsGreaterThanOrEqualTo(BigDecimal amount) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal inFlightDebit = json.path("inFlight").path("inFlightDebit").decimalValue();
        assertThat(inFlightDebit.compareTo(amount)).isGreaterThanOrEqualTo(0);
    }

    @Then("the tag aggregate estimated at is populated")
    public void tagAggregateEstimatedAtIsPopulated() throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.has("estimatedAt")).isTrue();
        assertThat(json.get("estimatedAt").isNull()).isFalse();
    }

    @Then("the tag aggregate committed total debited is greater than or equal to {bigdecimal}")
    public void tagAggregateCommittedTotalDebitedIsGreaterThanOrEqualTo(BigDecimal amount) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        BigDecimal totalDebited = json.path("committed").path("totalDebited").decimalValue();
        assertThat(totalDebited.compareTo(amount)).isGreaterThanOrEqualTo(0);
    }

    @Then("tag {string} has committed total debited greater than or equal to {bigdecimal}")
    public void tagHasCommittedTotalDebitedGreaterThanOrEqualTo(String tag, BigDecimal amount) {
        // FORWARD REF: implementation in plan 02 when TagCommittedTotalsRepository is available
        // For now, this step is pending until plan 02 wires the repository
        throw new io.cucumber.java.PendingException("TagCommittedTotalsRepository not yet available — wired in plan 02");
    }

    @Then("tag {string} has committed total credited recipient greater than or equal to {bigdecimal}")
    public void tagHasCommittedTotalCreditedRecipientGreaterThanOrEqualTo(String tag, BigDecimal amount) {
        // FORWARD REF: implementation in plan 02 when TagCommittedTotalsRepository is available
        throw new io.cucumber.java.PendingException("TagCommittedTotalsRepository not yet available — wired in plan 02");
    }

    @Then("the end-by-tag response settled count is {int}")
    public void endByTagResponseSettledCountIs(int count) throws Exception {
        JsonNode json = objectMapper.readTree(sharedContext.getLastResponse().getBody());
        assertThat(json.get("settledCount").asInt()).isEqualTo(count);
    }

    @When("placeholder tag step")
    public void placeholderTagStep() {
        // placeholder to ensure class compiles standalone in Wave 0
    }
}
