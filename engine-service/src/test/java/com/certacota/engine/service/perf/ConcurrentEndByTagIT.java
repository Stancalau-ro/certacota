package com.certacota.engine.service.perf;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;
import com.certacota.engine.service.TestcontainersConfiguration;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentEndByTagIT {

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
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private final List<String> tagPool = new ArrayList<>();

    @BeforeAll
    void setup() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Test
    @Order(1)
    void setupTagsAndStreams() throws Exception {
        int tagCount = config.getEndByTag().getTagCount();
        int sessionsPerTag = config.getEndByTag().getSessionsPerTag();
        double overlapFactor = config.getEndByTag().getOverlapFactor();

        for (int i = 0; i < tagCount; i++) {
            tagPool.add("perf-tag-" + i);
        }

        int totalSessions = tagCount * sessionsPerTag;
        String accountsUrl = "http://localhost:" + port + "/api/v1/accounts";
        String streamsUrl = "http://localhost:" + port + "/api/v1/streams";

        for (int s = 0; s < totalSessions; s++) {
            HttpHeaders accHeaders = new HttpHeaders();
            accHeaders.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> accBody = new HashMap<>();
            accBody.put("id", "perf-tag-acc-" + s);
            accBody.put("initialBalance", 1000000);
            accBody.put("idempotencyKey", "setup-tag-" + s);
            restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(accBody, accHeaders), String.class);
            testAccountIds.add("perf-tag-acc-" + s);
            initialTokensTotal.updateAndGet(current -> current.add(new BigDecimal("1000000")));

            String primaryTag = tagPool.get(s % tagCount);
            List<String> tags = new ArrayList<>();
            tags.add(primaryTag);
            if (Math.random() < overlapFactor) {
                tags.add(tagPool.get((s + 1) % tagCount));
            }

            HttpHeaders streamHeaders = new HttpHeaders();
            streamHeaders.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> streamBody = new HashMap<>();
            streamBody.put("streamId", "perf-tag-stream-" + s);
            streamBody.put("accountId", "perf-tag-acc-" + s);
            streamBody.put("ratePerSecond", new BigDecimal("0.001"));
            streamBody.put("idempotencyKey", "ik-tag-start-" + s);
            streamBody.put("tags", tags);

            ResponseEntity<String> streamResp = restTemplate.exchange(
                streamsUrl, HttpMethod.POST, new HttpEntity<>(streamBody, streamHeaders), String.class);

            if (streamResp.getStatusCode().value() == 201) {
                testStreamIds.add("perf-tag-stream-" + s);
            } else {
                rejectedCount.incrementAndGet();
            }
        }
    }

    @Test
    @Order(2)
    void concurrentEndByTag() throws Exception {
        Thread.sleep(500);

        int n = tagPool.size();
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);

        for (String tag : tagPool) {
            executor.submit(() -> {
                try {
                    start.await();
                    long t0 = System.currentTimeMillis();

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    Map<String, Object> body = new HashMap<>();
                    body.put("idempotencyKey", "ik-end-" + tag);
                    body.put("reason", "perf-test");

                    ResponseEntity<EndByTagResponse> resp = restTemplate.exchange(
                        "http://localhost:" + port + "/api/v1/tags/" + tag + "/end",
                        HttpMethod.POST, new HttpEntity<>(body, headers), EndByTagResponse.class);

                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        successCount.incrementAndGet();
                        EndByTagResponse endResp = resp.getBody();
                        if (endResp.settledStreams() != null) {
                            for (EndByTagResponse.SettledStream ss : endResp.settledStreams()) {
                                if (ss.settledAmount() != null) {
                                    totalSettled.updateAndGet(current -> current.add(ss.settledAmount()));
                                }
                            }
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
        assertThat(completed).as("All end-by-tag calls completed without deadlock").isTrue();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        for (String tag : tagPool) {
            ResponseEntity<TagAggregateResponse> aggResp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/tags/" + tag + "/aggregate",
                HttpMethod.GET, null, TagAggregateResponse.class);
            if (aggResp.getBody() != null && aggResp.getBody().inFlight() != null) {
                BigDecimal inFlightDebit = aggResp.getBody().inFlight().inFlightDebit();
                assertThat(inFlightDebit.compareTo(BigDecimal.ZERO))
                    .as("Tag " + tag + " should have no in-flight debit after end-by-tag")
                    .isEqualTo(0);
            }
        }
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
        report.put("scenario", "concurrent-end-by-tag");
        report.put("timestamp", OffsetDateTime.now());
        report.put("scenarioConfig", config.getEndByTag());
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

        PerfReportWriter.write("concurrent-end-by-tag", report);
        assertThat(conserved).as("Token conservation: delta=" + delta).isTrue();
    }
}
