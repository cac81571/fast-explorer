package com.fastexplorer.model;

public record TaskProgress(
        String phase,
        int processed,
        int total,
        long startedAtMs
) {
    public TaskProgress {
        processed = Math.max(0, processed);
    }

    public static TaskProgress of(String phase, int processed, int total, long startedAtMs) {
        return new TaskProgress(phase, processed, total, startedAtMs);
    }

    public boolean hasTotal() {
        return total > 0;
    }

    public int percent() {
        if (!hasTotal() || processed <= 0) {
            return -1;
        }
        return (int) Math.min(100, (processed * 100L) / total);
    }

    public String formatCount() {
        if (hasTotal()) {
            return String.format("%,d / %,d", processed, total);
        }
        if (processed <= 0) {
            return "0 件";
        }
        return String.format("%,d 件", processed);
    }

    public String formatStatus() {
        StringBuilder sb = new StringBuilder(phase);
        sb.append("… ").append(formatCount());
        int pct = percent();
        if (pct >= 0) {
            sb.append(" (").append(pct).append("%)");
        }
        return sb.toString();
    }
}
