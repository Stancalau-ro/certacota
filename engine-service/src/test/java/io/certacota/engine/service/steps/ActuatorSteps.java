package io.certacota.engine.service.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class ActuatorSteps {

    @Autowired
    private SharedContext sharedContext;

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate;

    public ActuatorSteps() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Given("the application is started with a live Postgres instance via Testcontainers")
    public void applicationStartedWithLivePostgres() {
        // Satisfied by @SpringBootTest context with TestcontainersConfiguration — no action needed
        log.info("Application started with Testcontainers Postgres on port {}", port);
    }

    @Given("the application is running")
    public void applicationIsRunning() {
        // Satisfied by @SpringBootTest context — no action needed
        log.info("Application is running on port {}", port);
    }

    @When("^I request GET /actuator/health$")
    public void requestGetActuatorHealth() {
        String url = "http://localhost:" + port + "/actuator/health";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        sharedContext.setLastResponse(response);
        log.info("Actuator health response: {} {}", response.getStatusCode(), response.getBody());
    }

    @When("^I request GET /actuator/prometheus$")
    public void requestGetActuatorPrometheus() {
        String url = "http://localhost:" + port + "/actuator/prometheus";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        sharedContext.setLastResponse(response);
        log.info("Actuator prometheus status: {}", response.getStatusCode());
    }

    @Then("the response body contains {string}")
    public void responseBodyContains(String expected) {
        assertThat(sharedContext.getLastResponse().getBody()).contains(expected);
    }

    @Then("the response content type contains text/plain")
    public void responseContentTypeContainsTextPlain() {
        assertThat(sharedContext.getLastResponse().getHeaders().getContentType()).isNotNull();
        assertThat(sharedContext.getLastResponse().getHeaders().getContentType().toString()).contains("text/plain");
    }
}
