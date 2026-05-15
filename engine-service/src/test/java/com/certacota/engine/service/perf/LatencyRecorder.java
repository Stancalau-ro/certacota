package com.certacota.engine.service.perf;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LatencyRecorder {

    private final ConcurrentLinkedQueue<Long> latenciesMs = new ConcurrentLinkedQueue<>();

    public void record(long elapsedMs) {
        latenciesMs.add(Math.max(0, elapsedMs));
    }

    public Snapshot snapshot() {
        Long[] boxed = latenciesMs.toArray(new Long[0]);
        if (boxed.length == 0) {
            return new Snapshot(0, 0, 0, 0, 0);
        }
        long[] arr = new long[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            arr[i] = boxed[i];
        }
        Arrays.sort(arr);
        long p50 = arr[(int) Math.min(arr.length - 1, Math.floor(arr.length * 0.50))];
        long p95 = arr[(int) Math.min(arr.length - 1, Math.floor(arr.length * 0.95))];
        long p99 = arr[(int) Math.min(arr.length - 1, Math.floor(arr.length * 0.99))];
        long max = arr[arr.length - 1];
        return new Snapshot(p50, p95, p99, max, arr.length);
    }

    public int size() {
        return latenciesMs.size();
    }

    public record Snapshot(long p50Ms, long p95Ms, long p99Ms, long maxMs, long count) {}
}
