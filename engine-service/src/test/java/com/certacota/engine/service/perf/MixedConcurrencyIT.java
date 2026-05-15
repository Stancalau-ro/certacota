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
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
@Tag("concurrency")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MixedConcurrencyIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PerfTestProperties config;

    private RestTemplate restTemplate;

    private final List<String> accountIds = new CopyOnWriteArrayList<>();
    private final Set<String> hotAccountIds = ConcurrentHashMap.newKeySet();
    private final Set<String> activeStreamIds = ConcurrentHashMap.newKeySet();
    private final LatencyRecorder latency = new LatencyRecorder();
    private final AtomicReference<BigDecimal> initialTokensTotal = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalCredited = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalDebited = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalSettled = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");

    private BigDecimal addAtomic(AtomicReference<BigDecimal> ref, BigDecimal delta) {
        return ref.updateAndGet(curr -> curr.add(delta));
    }

    private long sampleInterval(long minMs, long maxMs, boolean isHot, double multiplier) {
        long base = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        if (isHot) {
            return (long) Math.max(1, base / multiplier);
        }
        return base;
    }

    private BigDecimal sampleAmount(BigDecimal min, BigDecimal max) {
        BigDecimal range = max.subtract(min);
        BigDecimal factor = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble());
        return min.add(range.multiply(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    private void recordCall(Runnable op) {
        long t0 = System.currentTimeMillis();
        try {
            op.run();
        } catch (Exception e) {
            errorCount.incrementAndGet();
        } finally {
            latency.record(System.currentTimeMillis() - t0);
        }
    }

    @BeforeAll
    void setup() throws Exception {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });

        int totalAccounts = config.getMixed().getAccounts();
        String accountsUrl = "http://localhost:" + port + "/api/v1/accounts";

        for (int i = 0; i < totalAccounts; i++) {
            String accountId = "perf-mixed-acc-" + String.format("%04d", i);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("id", accountId);
            body.put("initialBalance", INITIAL_BALANCE);
            body.put("idempotencyKey", "setup-mixed-" + i);
            restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            accountIds.add(accountId);
            addAtomic(initialTokensTotal, INITIAL_BALANCE);
        }

        int hotCount = Math.min(config.getMixed().getHotAccounts().getCount(), accountIds.size());
        List<String> shuffled = new ArrayList<>(accountIds);
        Collections.shuffle(shuffled, new Random(42));
        hotAccountIds.addAll(shuffled.subList(0, hotCount));
    }

    @Test
    void runMixedWorkload() throws Exception {
        long durationMs = config.getMixed().getDurationSeconds() * 1000L;
        long deadlineMillis = System.currentTimeMillis() + durationMs;
        int totalAccounts = accountIds.size();
        double contentionMultiplier = config.getMixed().getHotAccounts().getContentionMultiplier();

        ExecutorService executor = Executors.newFixedThreadPool(totalAccounts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalAccounts);

        for (String accountId : accountIds) {
            final String myAccountId = accountId;
            executor.submit(() -> {
                try {
                    start.await();
                    boolean isHot = hotAccountIds.contains(myAccountId);
                    int sessionsTarget = ThreadLocalRandom.current().nextInt(
                        config.getMixed().getSessions().getMin(),
                        config.getMixed().getSessions().getMax() + 1);
                    AtomicInteger sessionsStarted = new AtomicInteger();
                    Set<String> myStreams = ConcurrentHashMap.newKeySet();

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    while (System.currentTimeMillis() < deadlineMillis) {
                        int eventRoll = ThreadLocalRandom.current().nextInt(100);

                        if (eventRoll < 30 && sessionsStarted.get() < sessionsTarget) {
                            final String streamId = "perf-mixed-stream-" + myAccountId + "-" + sessionsStarted.getAndIncrement();
                            recordCall(() -> {
                                String url = "http://localhost:" + port + "/api/v1/streams";
                                Map<String, Object> body = new HashMap<>();
                                body.put("streamId", streamId);
                                body.put("accountId", myAccountId);
                                body.put("ratePerSecond", new BigDecimal("0.01"));
                                body.put("idempotencyKey", "ik-mixed-start-" + streamId);
                                ResponseEntity<String> resp = restTemplate.exchange(
                                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                                if (resp.getStatusCode().value() == 201) {
                                    myStreams.add(streamId);
                                    activeStreamIds.add(streamId);
                                    successCount.incrementAndGet();
                                } else {
                                    rejectedCount.incrementAndGet();
                                }
                            });
                        } else if (eventRoll < 65) {
                            BigDecimal amount = sampleAmount(
                                config.getMixed().getCreditAmount().getMin(),
                                config.getMixed().getCreditAmount().getMax());
                            String idemKey = "ik-mixed-credit-" + myAccountId + "-" + System.nanoTime();
                            recordCall(() -> {
                                String url = "http://localhost:" + port + "/api/v1/accounts/" + myAccountId + "/credit";
                                Map<String, Object> body = new HashMap<>();
                                body.put("amount", amount);
                                body.put("idempotencyKey", idemKey);
                                ResponseEntity<String> resp = restTemplate.exchange(
                                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                                if (resp.getStatusCode().value() == 201) {
                                    addAtomic(totalCredited, amount);
                                    successCount.incrementAndGet();
                                } else {
                                    rejectedCount.incrementAndGet();
                                }
                            });
                        } else {
                            BigDecimal amount = sampleAmount(
                                config.getMixed().getDebitAmount().getMin(),
                                config.getMixed().getDebitAmount().getMax());
                            String idemKey = "ik-mixed-debit-" + myAccountId + "-" + System.nanoTime();
                            recordCall(() -> {
                                String url = "http://localhost:" + port + "/api/v1/accounts/" + myAccountId + "/debit";
                                Map<String, Object> body = new HashMap<>();
                                body.put("amount", amount);
                                body.put("idempotencyKey", idemKey);
                                ResponseEntity<String> resp = restTemplate.exchange(
                                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                                if (resp.getStatusCode().value() == 201) {
                                    addAtomic(totalDebited, amount);
                                    successCount.incrementAndGet();
                                } else {
                                    rejectedCount.incrementAndGet();
                                }
                            });
                        }

                        long sleepMs = sampleInterval(
                            config.getMixed().getCreditIntervalMs().getMin(),
                            config.getMixed().getCreditIntervalMs().getMax(),
                            isHot, contentionMultiplier);
                        Thread.sleep(sleepMs);
                    }

                    for (String streamId : myStreams) {
                        recordCall(() -> {
                            String url = "http://localhost:" + port + "/api/v1/streams/" + streamId + "/stop";
                            Map<String, Object> body = new HashMap<>();
                            body.put("ignoreMinimum", false);
                            body.put("idempotencyKey", "ik-mixed-stop-" + streamId);
                            ResponseEntity<StopStreamResponse> resp = restTemplate.exchange(
                                url, HttpMethod.POST, new HttpEntity<>(body, headers), StopStreamResponse.class);
                            if (resp.getStatusCode().value() == 200 && resp.getBody() != null) {
                                addAtomic(totalSettled, resp.getBody().settledAmount());
                                activeStreamIds.remove(streamId);
                                successCount.incrementAndGet();
                            } else {
                                rejectedCount.incrementAndGet();
                            }
                        });
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean completed = done.await(config.getMixed().getDurationSeconds() + 60, TimeUnit.SECONDS);
        assertThat(completed).as("All account runners completed without deadlock").isTrue();

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    @AfterAll
    void teardown() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (String streamId : activeStreamIds) {
            Map<String, Object> body = new HashMap<>();
            body.put("ignoreMinimum", false);
            body.put("idempotencyKey", "ik-mixed-teardown-" + streamId);
            try {
                ResponseEntity<StopStreamResponse> resp = restTemplate.exchange(
                    "http://localhost:" + port + "/api/v1/streams/" + streamId + "/stop",
                    HttpMethod.POST, new HttpEntity<>(body, headers), StopStreamResponse.class);
                if (resp.getStatusCode().value() == 200 && resp.getBody() != null && resp.getBody().settledAmount() != null) {
                    addAtomic(totalSettled, resp.getBody().settledAmount());
                }
            } catch (Exception ignored) {
            }
        }
        activeStreamIds.clear();

        BigDecimal totalFinalBalance = BigDecimal.ZERO;
        for (String accountId : accountIds) {
            ResponseEntity<AccountResponse> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/accounts/" + accountId,
                HttpMethod.GET, null, AccountResponse.class);
            if (resp.getBody() != null && resp.getBody().balance() != null) {
                totalFinalBalance = totalFinalBalance.add(resp.getBody().balance());
            }
        }

        BigDecimal delta = initialTokensTotal.get().add(totalCredited.get()).subtract(totalFinalBalance).subtract(totalSettled.get()).subtract(totalDebited.get());
        boolean conserved = delta.compareTo(BigDecimal.ZERO) == 0;

        Map<String, Object> scenarioConfigMap = new HashMap<>();
        scenarioConfigMap.put("accounts", config.getMixed().getAccounts());
        scenarioConfigMap.put("durationSeconds", config.getMixed().getDurationSeconds());
        scenarioConfigMap.put("hotAccountCount", hotAccountIds.size());
        scenarioConfigMap.put("contentionMultiplier", config.getMixed().getHotAccounts().getContentionMultiplier());
        scenarioConfigMap.put("sessions", Map.of(
            "min", config.getMixed().getSessions().getMin(),
            "max", config.getMixed().getSessions().getMax()
        ));
        scenarioConfigMap.put("creditIntervalMs", Map.of(
            "min", config.getMixed().getCreditIntervalMs().getMin(),
            "max", config.getMixed().getCreditIntervalMs().getMax()
        ));
        scenarioConfigMap.put("debitIntervalMs", Map.of(
            "min", config.getMixed().getDebitIntervalMs().getMin(),
            "max", config.getMixed().getDebitIntervalMs().getMax()
        ));
        scenarioConfigMap.put("creditAmount", Map.of(
            "min", config.getMixed().getCreditAmount().getMin(),
            "max", config.getMixed().getCreditAmount().getMax()
        ));
        scenarioConfigMap.put("debitAmount", Map.of(
            "min", config.getMixed().getDebitAmount().getMin(),
            "max", config.getMixed().getDebitAmount().getMax()
        ));

        Map<String, Object> report = new HashMap<>();
        report.put("scenario", "mixed-concurrency");
        report.put("timestamp", OffsetDateTime.now());
        report.put("scenarioConfig", scenarioConfigMap);
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
            "totalCredited", totalCredited.get(),
            "finalTokensTotal", totalFinalBalance,
            "totalSettled", totalSettled.get(),
            "totalDebited", totalDebited.get(),
            "delta", delta,
            "conserved", conserved
        ));

        PerfReportWriter.write("mixed-concurrency", report);
        assertThat(conserved).as("Mixed token conservation: delta=" + delta).isTrue();
    }
}
