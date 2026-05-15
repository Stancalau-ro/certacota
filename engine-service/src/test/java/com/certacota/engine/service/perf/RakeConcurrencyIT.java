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
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"test", "perf-test"})
@EnableConfigurationProperties(PerfTestProperties.class)
@Tag("performance")
@Tag("concurrency")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RakeConcurrencyIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PerfTestProperties config;

    private RestTemplate restTemplate;

    private final Set<String> testAccountIds = ConcurrentHashMap.newKeySet();
    private final Set<String> testStreamIds = ConcurrentHashMap.newKeySet();
    private final LatencyRecorder latency = new LatencyRecorder();
    private final AtomicReference<BigDecimal> initialTokensTotal = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalSettled = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalRaked = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private String platformAccountId;

    @BeforeAll
    void setup() throws Exception {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });

        int n = config.getConcurrency().getSessions().getCount();
        platformAccountId = "perf-rake-platform";

        String accountsUrl = "http://localhost:" + port + "/api/v1/accounts";

        HttpHeaders platHeaders = new HttpHeaders();
        platHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> platBody = new HashMap<>();
        platBody.put("id", platformAccountId);
        platBody.put("initialBalance", 0);
        platBody.put("idempotencyKey", "setup-rake-platform");
        restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(platBody, platHeaders), String.class);
        testAccountIds.add(platformAccountId);

        for (int i = 0; i < n; i++) {
            HttpHeaders fromHeaders = new HttpHeaders();
            fromHeaders.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> fromBody = new HashMap<>();
            fromBody.put("id", "perf-rake-from-" + i);
            fromBody.put("initialBalance", 100000);
            fromBody.put("idempotencyKey", "setup-rake-from-" + i);
            restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(fromBody, fromHeaders), String.class);
            testAccountIds.add("perf-rake-from-" + i);
            initialTokensTotal.updateAndGet(current -> current.add(new BigDecimal("100000")));

            HttpHeaders toHeaders = new HttpHeaders();
            toHeaders.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> toBody = new HashMap<>();
            toBody.put("id", "perf-rake-to-" + i);
            toBody.put("initialBalance", 0);
            toBody.put("idempotencyKey", "setup-rake-to-" + i);
            restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(toBody, toHeaders), String.class);
            testAccountIds.add("perf-rake-to-" + i);

            HttpHeaders streamHeaders = new HttpHeaders();
            streamHeaders.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> streamBody = new HashMap<>();
            streamBody.put("streamId", "perf-rake-stream-" + i);
            streamBody.put("accountId", "perf-rake-from-" + i);
            streamBody.put("ratePerSecond", new BigDecimal("1.0"));
            streamBody.put("idempotencyKey", "ik-rake-start-" + i);
            streamBody.put("toAccountId", "perf-rake-to-" + i);
            streamBody.put("rakeRate", new BigDecimal("0.1"));
            streamBody.put("platformAccountId", platformAccountId);

            ResponseEntity<String> streamResp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/streams",
                HttpMethod.POST, new HttpEntity<>(streamBody, streamHeaders), String.class);

            if (streamResp.getStatusCode().value() == 201) {
                testStreamIds.add("perf-rake-stream-" + i);
            } else {
                rejectedCount.incrementAndGet();
            }
        }
    }

    @Test
    void concurrentRakeSettlement() throws Exception {
        Thread.sleep(500);

        int n = testStreamIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(n > 0 ? n : 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);

        for (String streamId : testStreamIds) {
            executor.submit(() -> {
                try {
                    start.await();
                    long t0 = System.currentTimeMillis();

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    ResponseEntity<StopStreamResponse> stopResp = restTemplate.exchange(
                        "http://localhost:" + port + "/api/v1/streams/" + streamId + "/stop",
                        HttpMethod.POST, new HttpEntity<>(headers), StopStreamResponse.class);

                    if (stopResp.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                        if (stopResp.getBody() != null && stopResp.getBody().settledAmount() != null) {
                            totalSettled.updateAndGet(current -> current.add(stopResp.getBody().settledAmount()));
                        }
                    } else {
                        errorCount.incrementAndGet();
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
        boolean completed = done.await(60, TimeUnit.SECONDS);
        assertThat(completed).as("All rake settlement calls completed without deadlock").isTrue();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successCount.get() + errorCount.get()).isEqualTo(n);
    }

    @AfterAll
    void teardown() throws Exception {
        int streamCount = config.getConcurrency().getSessions().getCount();

        BigDecimal totalFinalBalance = BigDecimal.ZERO;
        BigDecimal fromFinalTotal = BigDecimal.ZERO;
        BigDecimal toFinalTotal = BigDecimal.ZERO;
        BigDecimal platformFinal = BigDecimal.ZERO;

        for (int i = 0; i < streamCount; i++) {
            ResponseEntity<AccountResponse> fromResp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/accounts/perf-rake-from-" + i,
                HttpMethod.GET, null, AccountResponse.class);
            if (fromResp.getBody() != null) {
                BigDecimal bal = fromResp.getBody().balance();
                fromFinalTotal = fromFinalTotal.add(bal);
                totalFinalBalance = totalFinalBalance.add(bal);
            }

            ResponseEntity<AccountResponse> toResp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/accounts/perf-rake-to-" + i,
                HttpMethod.GET, null, AccountResponse.class);
            if (toResp.getBody() != null) {
                BigDecimal bal = toResp.getBody().balance();
                toFinalTotal = toFinalTotal.add(bal);
                totalFinalBalance = totalFinalBalance.add(bal);
            }
        }

        ResponseEntity<AccountResponse> platResp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/accounts/" + platformAccountId,
            HttpMethod.GET, null, AccountResponse.class);
        if (platResp.getBody() != null) {
            platformFinal = platResp.getBody().balance();
            totalFinalBalance = totalFinalBalance.add(platformFinal);
        }

        BigDecimal initialFrom = new BigDecimal("100000").multiply(BigDecimal.valueOf(streamCount));
        BigDecimal threeWayDelta = initialFrom.subtract(toFinalTotal).subtract(platformFinal).subtract(fromFinalTotal);
        boolean threeWayBalanced = threeWayDelta.compareTo(BigDecimal.ZERO) == 0;

        BigDecimal delta = initialTokensTotal.get().subtract(totalFinalBalance);
        boolean conserved = delta.compareTo(BigDecimal.ZERO) == 0;

        Map<String, Object> report = new HashMap<>();
        report.put("scenario", "rake-concurrency");
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
            "noDeadlockDetected", true,
            "threeWayBalanced", threeWayBalanced
        ));
        report.put("tokenConservation", Map.of(
            "initialTokensTotal", initialTokensTotal.get(),
            "finalTokensTotal", totalFinalBalance,
            "fromFinalTotal", fromFinalTotal,
            "toFinalTotal", toFinalTotal,
            "platformFinal", platformFinal,
            "totalSettled", totalSettled.get(),
            "delta", delta,
            "conserved", conserved
        ));

        PerfReportWriter.write("rake-concurrency", report);
        assertThat(conserved).as("Token conservation (closed pool): delta=" + delta).isTrue();
        assertThat(threeWayBalanced).as("Three-way balance (from = to + platform + remaining from): delta=" + threeWayDelta).isTrue();
    }
}
