package com.fastexplorer.util;

import com.fastexplorer.model.TaskProgress;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 進捗コールバックを時間・件数で間引く。最初と最後は必ず通知する。
 */
public final class ProgressThrottle {

    private final Consumer<TaskProgress> onProgress;
    private final String phase;
    private final long startedAtMs;
    private final long minIntervalMs;
    private final int minCountStep;
    private final AtomicLong lastReportAt = new AtomicLong();
    private volatile int lastReportedProcessed = -1;
    private volatile int total;

    public ProgressThrottle(
            Consumer<TaskProgress> onProgress,
            String phase,
            int total,
            long startedAtMs,
            long minIntervalMs,
            int minCountStep
    ) {
        this.onProgress = onProgress;
        this.phase = phase;
        this.total = total;
        this.startedAtMs = startedAtMs;
        this.minIntervalMs = Math.max(50, minIntervalMs);
        this.minCountStep = Math.max(1, minCountStep);
    }

    public static ProgressThrottle of(
            Consumer<TaskProgress> onProgress,
            String phase,
            int total,
            long startedAtMs
    ) {
        return new ProgressThrottle(onProgress, phase, total, startedAtMs, 200, 1);
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public void report(int processed) {
        report(processed, false);
    }

    public void reportForce(int processed) {
        report(processed, true);
    }

    private void report(int processed, boolean force) {
        if (onProgress == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int previous = lastReportedProcessed;
        boolean countedEnough = previous < 0 || processed - previous >= minCountStep;
        boolean timedOut = now - lastReportAt.get() >= minIntervalMs;
        boolean finished = total > 0 && processed >= total;
        if (!force && !finished && previous >= 0 && !(countedEnough && timedOut)) {
            return;
        }
        lastReportedProcessed = processed;
        lastReportAt.set(now);
        onProgress.accept(TaskProgress.of(phase, processed, total, startedAtMs));
    }
}
