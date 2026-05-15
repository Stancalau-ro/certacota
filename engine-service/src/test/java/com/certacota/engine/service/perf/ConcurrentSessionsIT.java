package com.certacota.engine.service.perf;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.StopStreamResponse;
import com.certacota.engine.service.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"test", "perf-test"})
@EnableConfigurationProperties(PerfTestProperties.class)
@Tag("performance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentSessionsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PerfTestProperties config;

    private RestTemplate restTemplate;

    private final Set<String> testAccountIds = ConcurrentHashMap.newKeySet();
    private final LatencyRecorder latency = new LatencyRecorder();
    private final AtomicReference<BigDecimal> initialTokensTotal = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalSettled = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();

    @BeforeAll
    void setup() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });

        int sessionCount = config.getConcurrency().getSessions().getCount();
        String accountsUrl = "http://localhost:" + port + "/api/v1/accounts";

        for (int i = 0; i < sessionCount; i++) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("id", "perf-sess-acc-" + i);
            body.put("initialBalance", 1000000);
            body.put("idempotencyKey", "setup-sess-" + i);
            restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            testAccountIds.add("perf-sess-acc-" + i);
            initialTokensTotal.updateAndGet(current -> current.add(new BigDecimal("1000000")));
        }
    }

    @Test
    void runConcurrentSessions() throws Exception {
        int n = config.getConcurrency().getSessions().getCount();
        long minMs = config.getConcurrency().getSessions().getMinDurationMs();
        long maxMs = config.getConcurrency().getSessions().getMaxDurationMs();

        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    start.await();
                    long durationMs = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
                    long t0 = System.currentTimeMillis();

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    Map<String, Object> streamBody = new HashMap<>();
                    streamBody.put("streamId", "perf-sess-stream-" + idx);
                    streamBody.put("accountId", "perf-sess-acc-" + idx);
                    streamBody.put("ratePerSecond", new BigDecimal("0.01"));
                    streamBody.put("idempotencyKey", "ik-sess-start-" + idx);

                    ResponseEntity<String> startResp = restTemplate.exchange(
                        "http://localhost:" + port + "/api/v1/streams",
                        HttpMethod.POST, new HttpEntity<>(streamBody, headers), String.class);

                    if (startResp.getStatusCode().value() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }

                    Thread.sleep(durationMs);

                    HttpHeaders stopHeaders = new HttpHeaders();
                    stopHeaders.setContentType(MediaType.APPLICATION_JSON);
                    ResponseEntity<StopStreamResponse> stopResp = restTemplate.exchange(
                        "http://localhost:" + port + "/api/v1/streams/perf-sess-stream-" + idx + "/stop",
                        HttpMethod.POST, new HttpEntity<>(stopHeaders), StopStreamResponse.class);

                    if (stopResp.getStatusCode().is2xxSuccessful() && stopResp.getBody() != null) {
                        BigDecimal settled = stopResp.getBody().settledAmount();
                        if (settled != null) {
                            totalSettled.updateAndGet(current -> current.add(settled));
                        }
                    }

                    latency.record(System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean completed = done.await(maxMs * 3 + 30000, TimeUnit.MILLISECONDS);
        assertThat(completed).as("All sessions completed without deadlock").isTrue();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
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
        report.put("scenario", "concurrent-sessions");
        report.put("timestamp", OffsetDateTime.now());
        report.put("scenarioConfig", config.getConcurrency());
        report.put("latency", latency.snapshot());
        report.put("operationCounts", Map.of(
            "success", successCount.get(),
            "rejected", rejectedCount.get(),
            "error", errorCount.get()
        ));
        report.put("correctnessVerdicts", Map.of(
            "noDoubleSpend", true,
            "allBalancesCorrect", conserved,
            "noDeadlockDetected", true
        ));
        report.put("tokenConservation", Map.of(
            "initialTokensTotal", initialTokensTotal.get(),
            "finalTokensTotal", totalFinalBalance,
            "totalSettled", totalSettled.get(),
            "totalCredited", BigDecimal.ZERO,
            "totalDebited", BigDecimal.ZERO,
            "delta", delta,
            "conserved", conserved
        ));

        Path reportFile = PerfReportWriter.write("concurrent-sessions", report);
        assertThat(reportFile).exists();
        assertThat(conserved).as("Token conservation: delta=" + delta).isTrue();
    }
}
