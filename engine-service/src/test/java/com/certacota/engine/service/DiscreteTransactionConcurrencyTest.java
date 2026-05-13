package com.certacota.engine.service;

import com.certacota.engine.core.repository.AccountRepository;
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
class DiscreteTransactionConcurrencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accountRepository;

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
    void concurrentDebitsDoNotDoubleSpend() throws InterruptedException {
        accountRepository.deleteById("concurrent-debit-001");

        String setupUrl = "http://localhost:" + port + "/api/v1/accounts";
        HttpHeaders setupHeaders = new HttpHeaders();
        setupHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> setupBody = Map.of(
            "id", "concurrent-debit-001",
            "initialBalance", new BigDecimal("200.00"),
            "idempotencyKey", "setup-concurrent"
        );
        restTemplate.exchange(setupUrl, HttpMethod.POST, new HttpEntity<>(setupBody, setupHeaders), String.class);

        int n = 20;
        BigDecimal amount = BigDecimal.TEN;
        ExecutorService executor = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        String txnUrl = "http://localhost:" + port + "/api/v1/transactions";

        for (int i = 0; i < n; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    Map<String, Object> body = new HashMap<>();
                    body.put("accountId", "concurrent-debit-001");
                    body.put("type", "DEBIT");
                    body.put("amount", amount);
                    body.put("idempotencyKey", "concurrent-key-" + index);
                    ResponseEntity<String> response = restTemplate.exchange(
                        txnUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                    if (response.getStatusCode().value() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get() + failCount.get()).isEqualTo(n);
        assertThat(successCount.get()).isGreaterThan(0);

        BigDecimal finalBalance = accountRepository.findById("concurrent-debit-001")
            .orElseThrow(() -> new AssertionError("Account not found after concurrent test"))
            .getBalance();
        BigDecimal expectedBalance = new BigDecimal("200.00").subtract(amount.multiply(BigDecimal.valueOf(successCount.get())));
        assertThat(finalBalance.compareTo(expectedBalance)).isEqualTo(0);
        assertThat(failCount.get()).isGreaterThanOrEqualTo(0);
    }
}
