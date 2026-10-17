package io.github.adkimsm.up366.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds one plausible "normal student" timeline for a task the automation fast-forwarded, so the
 * times written back to the page and submitted to the server look like real listening work instead
 * of a burst of accelerated timestamps. Pure arithmetic, no Android types, so the layout rules are
 * unit-testable.
 *
 * <p>Layout rules: every section occupies its audio duration times the page's play count, each
 * question then gets a random thinking slot, sections are separated by random gaps, and the whole
 * plan ends shortly before the real submit moment (the caller passes an anchor). A manual target
 * total stretches the thinking slots; it never shrinks below the audio that has to fit.
 */
final class TimelinePlanner implements SubmitTimeRewriter.TimeSource {
    static final long DEFAULT_AUDIO_MS = 25_000L;
    static final long MIN_THINK_MS = 8_000L;
    static final long MAX_THINK_MS = 25_000L;
    static final long MIN_GAP_MS = 5_000L;
    static final long MAX_GAP_MS = 15_000L;
    static final int DEFAULT_PLAY_COUNT = 2;
    private static final long UNKNOWN_END_OFFSET_MS = 10_000L;
    private static final long UNKNOWN_STEP_MS = 7_000L;

    /** One listening section: its audio duration (0 = unknown), play count and question order. */
    static final class Section {
        final long audioMs;
        final int playCount;
        final List<String> questionIds;

        Section(long audioMs, int playCount, List<String> questionIds) {
            this.audioMs = Math.max(0L, audioMs);
            this.playCount = playCount <= 0 ? DEFAULT_PLAY_COUNT : playCount;
            this.questionIds = questionIds == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(questionIds));
        }
    }

    private final Map<String, Long> answers = new LinkedHashMap<>();
    private final long startMs;
    private final long endMs;
    private final long totalMs;
    private int unknownCount;

    private TimelinePlanner(long startMs, long endMs, long totalMs) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.totalMs = totalMs;
    }

    /**
     * Lays out the timeline backwards from {@code anchorEndMs}. {@code targetTotalMs} of 0 means the
     * auto-computed total; a positive target is honored unless it cannot fit the audio.
     */
    static TimelinePlanner build(List<Section> sections, long anchorEndMs, long targetTotalMs,
                                 Random random) {
        Random rng = random == null ? new Random() : random;
        List<Section> safe = new ArrayList<>();
        if (sections != null) {
            for (Section section : sections) {
                if (section != null) safe.add(section);
            }
        }

        long audioSum = 0L;
        int questionCount = 0;
        for (Section section : safe) {
            audioSum += effectiveAudioMs(section) * section.playCount;
            questionCount += section.questionIds.size();
        }
        int gapCount = Math.max(0, safe.size() - 1);
        long[] think = new long[questionCount];
        long[] gaps = new long[gapCount];
        long autoTotal = audioSum;
        for (int i = 0; i < think.length; i++) {
            think[i] = between(rng, MIN_THINK_MS, MAX_THINK_MS);
            autoTotal += think[i];
        }
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = between(rng, MIN_GAP_MS, MAX_GAP_MS);
            autoTotal += gaps[i];
        }
        long minimumTotal = audioSum + questionCount * MIN_THINK_MS + gapCount * MIN_GAP_MS;
        long total = targetTotalMs > 0L ? Math.max(targetTotalMs, minimumTotal) : autoTotal;
        long extra = total - autoTotal;
        if (extra > 0L && questionCount > 0) {
            // Stretch the thinking slots so a longer target still looks like a real session.
            long per = extra / questionCount;
            long rest = extra % questionCount;
            for (int i = 0; i < questionCount; i++) {
                think[i] += per + (i < rest ? 1L : 0L);
            }
        }
        long actual = audioSum + sum(think) + sum(gaps)
                + (questionCount == 0 ? Math.max(0L, extra) : 0L);

        TimelinePlanner plan = new TimelinePlanner(anchorEndMs - actual, anchorEndMs, actual);
        long cursor = plan.startMs;
        int questionIndex = 0;
        for (int s = 0; s < safe.size(); s++) {
            Section section = safe.get(s);
            cursor += effectiveAudioMs(section) * section.playCount;
            for (String questionId : section.questionIds) {
                cursor += think[questionIndex++];
                if (questionId != null && !questionId.isEmpty()) {
                    plan.answers.put(questionId, cursor);
                }
            }
            if (s < safe.size() - 1) cursor += gaps[s];
        }
        return plan;
    }

    /** Planned answer time for a question; ids the plan did not know get a late deterministic slot. */
    @Override
    public long timestampFor(String questionId) {
        if (questionId != null) {
            Long known = answers.get(questionId);
            if (known != null) return known;
        }
        long fallback = endMs - UNKNOWN_END_OFFSET_MS - unknownCount++ * UNKNOWN_STEP_MS;
        return Math.max(startMs, fallback);
    }

    /** Seconds of listening/answering elapsed at that question's planned answer time. */
    @Override
    public long secondsFor(String questionId) {
        return Math.max(1L, (timestampFor(questionId) - startMs) / 1000L);
    }

    @Override
    public long startMs() {
        return startMs;
    }

    @Override
    public long endMs() {
        return endMs;
    }

    long totalMs() {
        return totalMs;
    }

    @Override
    public long totalSeconds() {
        return totalMs / 1000L;
    }

    int questionCount() {
        return answers.size();
    }

    private static long effectiveAudioMs(Section section) {
        return section.audioMs > 0L ? section.audioMs : DEFAULT_AUDIO_MS;
    }

    private static long between(Random rng, long min, long max) {
        if (max <= min) return min;
        return min + (long) rng.nextInt((int) Math.min(Integer.MAX_VALUE, max - min + 1));
    }

    private static long sum(long[] values) {
        long total = 0L;
        for (long value : values) total += value;
        return total;
    }
}
