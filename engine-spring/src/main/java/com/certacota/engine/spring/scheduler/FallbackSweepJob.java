package com.certacota.engine.spring.scheduler;

import com.certacota.engine.core.domain.StreamingTransaction;
import com.certacota.engine.core.repository.AccountRepository;
import com.certacota.engine.core.repository.StreamingTransactionRepository;
import com.certacota.engine.core.service.StreamingService;
import com.certacota.engine.spring.config.TokenEngineProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static net.javacrumbs.shedlock.core.LockAssert.assertLocked;

@Component
@RequiredArgsConstructor
@Slf4j
public class FallbackSweepJob {

    private final StreamingTransactionRepository streamingTransactionRepository;
    private final StreamingService streamingService;
    private final AccountRepository accountRepository;
    private final TokenEngineProperties properties;

    @Scheduled(fixedDelayString = "${token-engine.streaming.fallback-sweep-seconds:300}000")
    @SchedulerLock(name = "streaming_fallback_sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional(readOnly = true)
    public void runFallbackSweep() {
        assertLocked();

        List<StreamingTransaction> activeStreams = streamingTransactionRepository.findByStatus(StreamingTransaction.ACTIVE);
        log.info("Fallback sweep: checking {} active streams", activeStreams.size());

        AtomicInteger terminated = new AtomicInteger(0);

        for (StreamingTransaction txn : activeStreams) {
            try {
                long elapsedMillis = System.currentTimeMillis() - txn.getStartedAt().toInstant().toEpochMilli();
                BigDecimal elapsedSeconds = BigDecimal.valueOf(elapsedMillis)
                    .divide(BigDecimal.valueOf(1000L), 18, RoundingMode.DOWN);
                BigDecimal projected = txn.getRatePerSecond().multiply(elapsedSeconds)
                    .setScale(18, RoundingMode.DOWN);

                // Load account once to get both floor and balance with consistent state (WR-02)
                var accountOpt = accountRepository.findById(txn.getAccountId());

                BigDecimal effectiveFloor = accountOpt
                    .map(account -> account.getBalanceFloor() != null
                        ? account.getBalanceFloor()
                        : properties.getBalanceFloor())
                    .orElse(properties.getBalanceFloor());

                BigDecimal estimatedRemainingBalance = accountOpt
                    .map(account -> account.getBalance().subtract(projected))
                    .orElse(BigDecimal.ZERO);

                if (estimatedRemainingBalance.compareTo(effectiveFloor) <= 0) {
                    log.info("Fallback sweep: auto-terminating exhausted stream {}", txn.getStreamId());
                    try {
                        streamingService.autoTerminate(txn.getStreamId());
                        terminated.incrementAndGet();
                    } catch (Exception e) {
                        log.warn("Fallback sweep: failed to auto-terminate stream {}: {}", txn.getStreamId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Fallback sweep: error processing stream {}: {}", txn.getStreamId(), e.getMessage());
            }
        }

        log.info("Fallback sweep complete: swept {} active streams, terminated {}", activeStreams.size(), terminated.get());
    }
}
