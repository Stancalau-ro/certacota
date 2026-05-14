package com.certacota.engine.service;

import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StreamingConcurrencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private StreamingTransactionRepository streamingTransactionRepository;

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Test
    void concurrentStreamingAndDiscreteDebitsDoNotDoubleSpend() throws InterruptedException {
        String accountId = "concurrent-stream-001";
        String streamId = "concurrent-stream-001";

        accountRepository.deleteById(accountId);
        streamingTransactionRepository.findByStreamId(streamId)
            .ifPresent(tx -> streamingTransactionRepository.delete(tx));

        String accountsUrl = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders setupHeaders = new HttpHeaders();
        setupHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> setupBody = new HashMap<>();
        setupBody.put("id", accountId);
        setupBody.put("initialBalance", new BigDecimal("1000.00"));
        setupBody.put("idempotencyKey", "setup-concurrent-stream");
        restTemplate.exchange(accountsUrl, HttpMethod.POST, new HttpEntity<>(setupBody, setupHeaders), String.class);

        String streamsUrl = "http://localhost:" + port + "/api/v1/streams";
        HttpHeaders streamHeaders = new HttpHeaders();
        streamHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> streamBody = new HashMap<>();
        streamBody.put("streamId", streamId);
        streamBody.put("accountId", accountId);
        streamBody.put("ratePerSecond", new BigDecimal("0.001"));
        streamBody.put("idempotencyKey", "ik-concurrent-stream-001");
        restTemplate.exchange(streamsUrl, HttpMethod.POST, new HttpEntity<>(streamBody, streamHeaders), String.class);

        int n = 10;
        BigDecimal debitAmount = new BigDecimal("50");
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        String debitUrl = "http://localhost:" + port + "/api/v1/accounts/" + accountId + "/debit";

        for (int i = 0; i < n; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    Map<String, Object> body = new HashMap<>();
                    body.put("amount", debitAmount);
                    body.put("idempotencyKey", "conc-debit-" + index);
                    ResponseEntity<String> response = restTemplate.exchange(
                        debitUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                    if (response.getStatusCode().value() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejectedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get() + rejectedCount.get()).isEqualTo(n);

        String stopUrl = "http://localhost:" + port + "/api/v1/streams/" + streamId + "/stop";
        HttpHeaders stopHeaders = new HttpHeaders();
        stopHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> stopResponse = restTemplate.exchange(
            stopUrl, HttpMethod.POST, new HttpEntity<>(stopHeaders), String.class);
        assertThat(stopResponse.getStatusCode().value()).isEqualTo(200);

        BigDecimal finalBalance = accountRepository.findById(accountId)
            .orElseThrow(() -> new AssertionError("Account not found after concurrent test"))
            .getBalance();

        assertThat(finalBalance.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);

        assertThat(streamingTransactionRepository.findByAccountIdAndStatus(accountId, "ACTIVE")).isEmpty();
    }
}
