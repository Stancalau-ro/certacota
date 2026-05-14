package com.certacota.engine.spring.scheduler;

import com.certacota.engine.core.service.StreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoTerminationScheduler implements ApplicationListener<ApplicationReadyEvent> {

    private static final String EXHAUSTION_QUEUE = "stream-exhaustion-queue";

    private final RedissonClient redissonClient;
    private final StreamingService streamingService;

    private RBlockingDeque<String> destinationQueue;
    private RDelayedQueue<String> delayedQueue;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        destinationQueue = redissonClient.getBlockingDeque(EXHAUSTION_QUEUE);
        delayedQueue = redissonClient.getDelayedQueue(destinationQueue);
        startConsumerThread();
        log.info("AutoTerminationScheduler initialized with queue: {}", EXHAUSTION_QUEUE);
    }

    public void enqueue(String streamId, long delayMillis) {
        try {
            delayedQueue.offer(streamId, delayMillis, TimeUnit.MILLISECONDS);
            log.debug("Enqueued stream {} for auto-termination in {}ms", streamId, delayMillis);
        } catch (Exception e) {
            log.warn("Failed to enqueue stream {} for auto-termination — fallback sweep will handle: {}", streamId, e.getMessage());
        }
    }

    public void cancel(String streamId) {
        // Always cancel before any re-enqueue to maintain ordering invariant
        try {
            delayedQueue.remove(streamId);
            log.debug("Cancelled auto-termination entry for stream {}", streamId);
        } catch (Exception e) {
            log.warn("Failed to cancel auto-termination entry for stream {}: {}", streamId, e.getMessage());
        }
    }

    public static long calculateExhaustionDelayMillis(BigDecimal estimatedBalance, BigDecimal effectiveFloor, BigDecimal ratePerSecond) {
        if (ratePerSecond.compareTo(BigDecimal.ZERO) <= 0) {
            return Long.MAX_VALUE;
        }
        BigDecimal timeToExhaustionSeconds = estimatedBalance.subtract(effectiveFloor)
            .divide(ratePerSecond, 3, RoundingMode.DOWN)
            .multiply(BigDecimal.valueOf(1000));
        return timeToExhaustionSeconds.max(BigDecimal.ZERO).longValue();
    }

    private void startConsumerThread() {
        Thread.ofVirtual().name("auto-termination-consumer").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String streamId = destinationQueue.take();
                    streamingService.autoTerminate(streamId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("Auto-termination consumer error for stream — will retry via fallback sweep: {}", e.getMessage());
                }
            }
        });
    }
}
