package io.github.adkimsm.up366.hook;

/**
 * Selectable speed profile for the listening fast-forward. Each level bundles the playback rate,
 * the page-clock multiplier, the timer-compression ceiling and the automation polling intervals, so
 * the trade-off between speed and page-timing safety can be chosen from the injected panel without
 * rebuilding. Kept free of Android types so the arithmetic is unit-testable.
 */
final class SpeedProfile {
    final String label;
    final String rate;
    final int clock;
    final int timerMaxMs;
    final int timerFloorMs;
    final long stopRetryMs;
    final long fastStepMs;
    final long slowStepMs;

    private SpeedProfile(String label, String rate, int clock, int timerMaxMs, int timerFloorMs,
                         long stopRetryMs, long fastStepMs, long slowStepMs) {
        this.label = label;
        this.rate = rate;
        this.clock = clock;
        this.timerMaxMs = timerMaxMs;
        this.timerFloorMs = timerFloorMs;
        this.stopRetryMs = stopRetryMs;
        this.fastStepMs = fastStepMs;
        this.slowStepMs = slowStepMs;
    }

    static final SpeedProfile[] LEVELS = {
            new SpeedProfile("关闭", "1.0", 1, 0, 0, 400L, 200L, 400L),
            new SpeedProfile("标准", "8.0", 20, 120, 30, 250L, 60L, 200L),
            new SpeedProfile("快速", "16.0", 40, 60, 15, 120L, 30L, 120L),
            new SpeedProfile("极限", "32.0", 80, 30, 10, 80L, 25L, 80L),
    };

    static final int DEFAULT_INDEX = 2;

    static SpeedProfile of(int index) {
        if (index < 0 || index >= LEVELS.length) return LEVELS[DEFAULT_INDEX];
        return LEVELS[index];
    }

    static int nextIndex(int index) {
        return (index + 1) % LEVELS.length;
    }

    static int clampIndex(int index) {
        if (index < 0 || index >= LEVELS.length) return DEFAULT_INDEX;
        return index;
    }

    boolean acceleratesPlayback() {
        return !"1.0".equals(rate);
    }

    boolean hooksPage() {
        return clock > 1 || timerMaxMs > 0;
    }

    String describe() {
        return label + "（rate=" + rate + ", clock=" + clock + "x"
                + (timerMaxMs > 0 ? ", timer<=" + timerMaxMs + "ms" : "") + "）";
    }
}
