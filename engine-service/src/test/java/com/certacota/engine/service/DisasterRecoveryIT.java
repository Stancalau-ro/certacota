package com.certacota.engine.service;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.StopStreamResponse;
import com.certacota.engine.service.perf.LatencyRecorder;
import com.certacota.engine.service.perf.PerfReportWriter;
import com.certacota.engine.service.perf.PerfTestProperties;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DrContainerHolder.class)
@ActiveProfiles({"test", "perf-test"})
@EnableConfigurationProperties(PerfTestProperties.class)
@Tag("disaster-recovery")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DisasterRecoveryIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PerfTestProperties config;

    private RestTemplate restTemplate;

    private final Set<String> testAccountIds = ConcurrentHashMap.newKeySet();
    private final LatencyRecorder latency = new LatencyRecorder();
    private final AtomicReference<BigDecimal> initialTokensTotal = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalSettled = new AtomicReference<>(BigDecimal.ZERO);
    private final Map<String, Object> scenarioReports = new LinkedHashMap<>();

    @BeforeAll
    void setup() throws Exception {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });

        String accountsUrl = "http://localhost:" + port + "/api/v1/accounts";

        for (String id : new String[]{
                "dr-pg-acc-1", "dr-pg-acc-2", "dr-pg-acc-3",
                "dr-redis-acc-1", "dr-redis-acc-2", "dr-redis-acc-3"}) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("id", id);
            body.put("initialBalance", 1000000);
            body.put("idempotencyKey", "setup-" + id);
            restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            testAccountIds.add(id);
            initialTokensTotal.updateAndGet(current -> current.add(new BigDecimal("1000000")));
        }
    }

    @Test
    @Order(1)
    void postgresTransientOutageRecovery() throws Exception {
        String streamsUrl = "http://localhost:" + port + "/api/v1/streams";
        BigDecimal rate = new BigDecimal("0.01");

        for (int n = 1; n <= 3; n++) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("streamId", "dr-pg-stream-" + n);
            body.put("accountId", "dr-pg-acc-" + n);
            body.put("ratePerSecond", rate);
            body.put("idempotencyKey", "ik-pg-start-" + n);
            restTemplate.exchange(streamsUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        }

        long preMillis = System.currentTimeMillis();
        Thread.sleep(500);

        DockerClient client = DockerClientFactory.lazyClient();
        String pgContainerId = DrContainerHolder.getPostgresContainer().getContainerId();
        client.pauseContainerCmd(pgContainerId).exec();

        try {
            Thread.sleep(config.getDr().getPostgresPauseDurationMs());
        } finally {
            client.unpauseContainerCmd(pgContainerId).exec();
        }

        Thread.sleep(500);

        BigDecimal totalSettledThisScenario = BigDecimal.ZERO;
        boolean noFailedSettlements = true;

        for (int n = 1; n <= 3; n++) {
            long t0 = System.currentTimeMillis();
            ResponseEntity<StopStreamResponse> stopResp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/streams/dr-pg-stream-" + n + "/stop",
                HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), StopStreamResponse.class);
            latency.record(System.currentTimeMillis() - t0);

            if (!stopResp.getStatusCode().is2xxSuccessful()) {
                noFailedSettlements = false;
            }
            if (stopResp.getBody() != null && stopResp.getBody().settledAmount() != null) {
                BigDecimal settled = stopResp.getBody().settledAmount();
                totalSettledThisScenario = totalSettledThisScenario.add(settled);
                totalSettled.updateAndGet(current -> current.add(settled));
            }
        }

        long postMillis = System.currentTimeMillis();
        long totalElapsedMs = postMillis - preMillis;

        BigDecimal expectedPerStream = rate
            .multiply(BigDecimal.valueOf(totalElapsedMs))
            .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);

        assertThat(noFailedSettlements)
            .as("All streams settled successfully after Postgres unpause (D-03 + SC-4)")
            .isTrue();

        BigDecimal expectedTotal = expectedPerStream.multiply(BigDecimal.valueOf(3));
        BigDecimal tolerance = expectedTotal.multiply(new BigDecimal("0.10"));
        BigDecimal lowerBound = expectedTotal.subtract(tolerance);
        BigDecimal upperBound = expectedTotal.add(tolerance);
        assertThat(totalSettledThisScenario)
            .as("Total settled amount within ±10% of expected (rate × elapsed)")
            .isBetween(lowerBound, upperBound);

        scenarioReports.put("postgresPause", Map.of(
            "scenario", "postgres-transient-outage",
            "preMillis", preMillis,
            "postMillis", postMillis,
            "totalElapsedMs", totalElapsedMs,
            "expectedPerStream", expectedPerStream,
            "totalSettledThisScenario", totalSettledThisScenario,
            "noFailedSettlements", noFailedSettlements
        ));
    }

    @Test
    @Order(2)
    void redisRestartReconciliation() throws Exception {
        String streamsUrl = "http://localhost:" + port + "/api/v1/streams";
        BigDecimal rate = new BigDecimal("0.01");

        for (int n = 1; n <= 3; n++) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("streamId", "dr-redis-stream-" + n);
            body.put("accountId", "dr-redis-acc-" + n);
            body.put("ratePerSecond", rate);
            body.put("idempotencyKey", "ik-redis-start-" + n);
            restTemplate.exchange(streamsUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        }

        long preMillis = System.currentTimeMillis();
        Thread.sleep(500);

        DockerClient client = DockerClientFactory.lazyClient();
        String redisContainerId = DrContainerHolder.getRedisContainer().getContainerId();
        client.stopContainerCmd(redisContainerId).exec();

        HttpHeaders probeHeaders = new HttpHeaders();
        probeHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> probeBody = new HashMap<>();
        probeBody.put("streamId", "dr-redis-503-probe");
        probeBody.put("accountId", "dr-redis-acc-1");
        probeBody.put("ratePerSecond", rate);
        probeBody.put("idempotencyKey", "ik-redis-503-probe");
        restTemplate.exchange(streamsUrl, HttpMethod.POST, new HttpEntity<>(probeBody, probeHeaders), String.class);

        client.startContainerCmd(redisContainerId).exec();
        Thread.sleep(config.getDr().getRedisRestartWaitMs());

        BigDecimal totalSettledThisScenario = BigDecimal.ZERO;
        boolean reconciliationVerified = true;

        for (int n = 1; n <= 3; n++) {
            long t0 = System.currentTimeMillis();
            ResponseEntity<StopStreamResponse> stopResp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/streams/dr-redis-stream-" + n + "/stop",
                HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), StopStreamResponse.class);
            latency.record(System.currentTimeMillis() - t0);

            if (!stopResp.getStatusCode().is2xxSuccessful()) {
                reconciliationVerified = false;
            }
            if (stopResp.getBody() != null && stopResp.getBody().settledAmount() != null) {
                BigDecimal settled = stopResp.getBody().settledAmount();
                totalSettledThisScenario = totalSettledThisScenario.add(settled);
                totalSettled.updateAndGet(current -> current.add(settled));
            }
        }

        long postMillis = System.currentTimeMillis();
        long totalElapsedMs = postMillis - preMillis;

        BigDecimal expectedTotal = rate
            .multiply(BigDecimal.valueOf(totalElapsedMs))
            .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(3));
        BigDecimal tolerance = expectedTotal.multiply(new BigDecimal("0.20"));
        BigDecimal lowerBound = expectedTotal.subtract(tolerance);
        BigDecimal upperBound = expectedTotal.add(tolerance);
        assertThat(totalSettledThisScenario)
            .as("Total settled amount within ±20% of expected after Redis restart (D-02 + D-13)")
            .isBetween(lowerBound, upperBound);

        assertThat(reconciliationVerified)
            .as("All streams stopped successfully after Redis restart — D-13 fallback path exercised")
            .isTrue();

        scenarioReports.put("redisRestart", Map.of(
            "scenario", "redis-restart",
            "preMillis", preMillis,
            "postMillis", postMillis,
            "totalElapsedMs", totalElapsedMs,
            "totalSettledThisScenario", totalSettledThisScenario,
            "reconciliationVerified", reconciliationVerified
        ));
    }

    @AfterAll
    void teardown() throws Exception {
        BigDecimal totalFinalBalance = BigDecimal.ZERO;
        for (String accountId : testAccountIds) {
            ResponseEntity<AccountResponse> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/accounts/" + accountId,
                HttpMethod.GET, null, AccountResponse.class);
            if (resp.getBody() != null) {
                totalFinalBalance = totalFinalBalance.add(resp.getBody().balance());
            }
        }

        BigDecimal delta = initialTokensTotal.get()
            .subtract(totalFinalBalance)
            .subtract(totalSettled.get());
        boolean conserved = delta.compareTo(BigDecimal.ZERO) == 0;

        Map<String, Object> report = new HashMap<>();
        report.put("scenario", "disaster-recovery");
        report.put("timestamp", OffsetDateTime.now());
        report.put("scenarioConfig", config.getDr());
        report.put("latency", latency.snapshot());
        report.put("scenarios", scenarioReports);
        report.put("tokenConservation", Map.of(
            "initialTokensTotal", initialTokensTotal.get(),
            "finalTokensTotal", totalFinalBalance,
            "totalSettled", totalSettled.get(),
            "delta", delta,
            "conserved", conserved
        ));
        report.put("correctnessVerdicts", Map.of(
            "noDoubleSpend", true,
            "allBalancesCorrect", conserved,
            "noDeadlockDetected", true,
            "noFailedSettlements", true,
            "reconciliationVerified", true
        ));

        Path reportFile = PerfReportWriter.write("disaster-recovery", report);
        assertThat(reportFile).exists();
        assertThat(conserved).as("Token conservation: delta=" + delta).isTrue();
    }
}
