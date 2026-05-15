package com.certacota.engine.service.perf;

import com.certacota.engine.core.dto.AccountResponse;
import com.certacota.engine.service.TestcontainersConfiguration;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"test", "perf-test"})
@EnableConfigurationProperties(PerfTestProperties.class)
@Tag("performance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
class LargeDbStressIT {

    private static final int ACCOUNTS = Integer.parseInt(
        System.getProperty("certacota.perf.stress.accounts", "10000"));
    private static final int TXN_PER_ACCOUNT = Integer.parseInt(
        System.getProperty("certacota.perf.stress.transactions-per-account", "500000"));
    private static final String IMAGE_NAME = "certacota-perf-db-a" + ACCOUNTS + "-t" + TXN_PER_ACCOUNT;

    private static final PostgreSQLContainer<?> seededPostgres;

    static {
        try {
            ensureSeedImageExists(IMAGE_NAME, ACCOUNTS, TXN_PER_ACCOUNT);
            DockerImageName seededImage = DockerImageName.parse(IMAGE_NAME)
                .asCompatibleSubstituteFor("postgres");
            seededPostgres = new PostgreSQLContainer<>(seededImage)
                .withDatabaseName("certacota")
                .withUsername("certacota")
                .withPassword("certacota");
            seededPostgres.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to provision seeded postgres image " + IMAGE_NAME, e);
        }
    }

    @DynamicPropertySource
    static void registerSeededPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", seededPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", seededPostgres::getUsername);
        registry.add("spring.datasource.password", seededPostgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private PerfTestProperties config;

    @Autowired
    private DataSource dataSource;

    private RestTemplate restTemplate;

    private final LatencyRecorder readLatency = new LatencyRecorder();
    private final LatencyRecorder writeLatency = new LatencyRecorder();
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final AtomicReference<BigDecimal> totalDebited = new AtomicReference<>(BigDecimal.ZERO);

    private static void ensureSeedImageExists(String imageName, int accounts, int txnPerAccount)
        throws Exception {
        ProcessBuilder check = new ProcessBuilder("docker", "images", "-q", imageName);
        check.redirectErrorStream(true);
        Process checkProcess = check.start();
        String imageId = new String(checkProcess.getInputStream().readAllBytes()).trim();
        checkProcess.waitFor();

        if (!imageId.isEmpty()) {
            return;
        }

        Path dockerDir = Paths.get("engine-service/src/test/docker/perf-db").toAbsolutePath();
        ProcessBuilder build = new ProcessBuilder(
            "docker", "build",
            "--build-arg", "ACCOUNTS=" + accounts,
            "--build-arg", "TXN_PER_ACCOUNT=" + txnPerAccount,
            "-t", imageName,
            dockerDir.toString());
        build.redirectErrorStream(true);
        build.inheritIO();
        int exitCode = build.start().waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                "docker build failed for image: " + imageName + " (exit=" + exitCode + ")");
        }
    }

    @BeforeAll
    void setup() {
        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        log.info("Stress IT booted against seeded image {} (containerId={})",
            IMAGE_NAME, seededPostgres.getContainerId());
    }

    @Test
    void queryLatencyUnderLargeDataset() {
        int accountsToProbe = Math.min(100, ACCOUNTS);
        int writesToIssue = Math.min(50, ACCOUNTS);

        for (int i = 1; i <= accountsToProbe; i++) {
            String accountId = "stress-acc-" + String.format("%06d", i);
            long t0 = System.currentTimeMillis();
            ResponseEntity<AccountResponse> resp = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/accounts/" + accountId,
                AccountResponse.class);
            long elapsed = System.currentTimeMillis() - t0;
            readLatency.record(elapsed);
            if (resp.getStatusCode().value() == 200) {
                successCount.incrementAndGet();
            } else {
                rejectedCount.incrementAndGet();
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (int i = 1; i <= writesToIssue; i++) {
            String accountId = "stress-acc-" + String.format("%06d", i);
            Map<String, Object> body = new HashMap<>();
            body.put("amount", new BigDecimal("1"));
            body.put("idempotencyKey", "stress-debit-" + i);

            long t0 = System.currentTimeMillis();
            ResponseEntity<String> resp = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/accounts/" + accountId + "/debit",
                new HttpEntity<>(body, headers),
                String.class);
            long elapsed = System.currentTimeMillis() - t0;
            writeLatency.record(elapsed);

            if (resp.getStatusCode().value() == 201) {
                successCount.incrementAndGet();
                totalDebited.updateAndGet(c -> c.add(BigDecimal.ONE));
            } else {
                rejectedCount.incrementAndGet();
            }
        }

        long readP95 = readLatency.snapshot().p95Ms();
        long writeP95 = writeLatency.snapshot().p95Ms();
        long ceiling = config.getStress().getMaxQueryLatencyMs();

        assertThat(readP95).as("Read p95 under stress").isLessThanOrEqualTo(ceiling);
        assertThat(writeP95).as("Write p95 under stress").isLessThanOrEqualTo(ceiling * 2);
    }

    @AfterAll
    void teardown() throws Exception {
        BigDecimal initialTokensTotal;
        BigDecimal totalFinalBalance;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) AS cnt, SUM(balance) AS sum_balance" +
                 " FROM stress_accounts WHERE id LIKE 'stress-acc-%'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            int seededCount = rs.getInt("cnt");
            totalFinalBalance = rs.getBigDecimal("sum_balance");
            if (totalFinalBalance == null) {
                totalFinalBalance = BigDecimal.ZERO;
            }
            initialTokensTotal = new BigDecimal("1000000")
                .multiply(BigDecimal.valueOf(seededCount));
        }

        BigDecimal delta = initialTokensTotal.subtract(totalFinalBalance);
        boolean conserved = delta.compareTo(BigDecimal.ZERO) == 0;

        boolean latencyUnderCeiling = readLatency.snapshot().p95Ms()
            <= config.getStress().getMaxQueryLatencyMs();

        Map<String, Object> report = new HashMap<>();
        report.put("scenario", "large-db-stress");
        report.put("timestamp", OffsetDateTime.now());
        report.put("scenarioConfig", Map.of(
            "accounts", ACCOUNTS,
            "transactionsPerAccount", TXN_PER_ACCOUNT,
            "maxQueryLatencyMs", config.getStress().getMaxQueryLatencyMs(),
            "imageName", IMAGE_NAME
        ));
        report.put("readLatency", readLatency.snapshot());
        report.put("writeLatency", writeLatency.snapshot());
        report.put("operationCounts", Map.of(
            "success", successCount.get(),
            "rejected", rejectedCount.get(),
            "error", 0
        ));
        report.put("correctnessVerdicts", Map.of(
            "latencyUnderCeiling", latencyUnderCeiling,
            "allBalancesCorrect", conserved
        ));
        report.put("tokenConservation", Map.of(
            "initialTokensTotal", initialTokensTotal,
            "finalTokensTotal", totalFinalBalance,
            "totalSettled", BigDecimal.ZERO,
            "totalCredited", BigDecimal.ZERO,
            "totalDebited", totalDebited.get(),
            "delta", delta,
            "conserved", conserved
        ));

        Path reportFile = PerfReportWriter.write("large-db-stress", report);
        assertThat(reportFile).exists();
        assertThat(conserved).as("Token conservation on stress_accounts: delta=" + delta).isTrue();
    }
}
