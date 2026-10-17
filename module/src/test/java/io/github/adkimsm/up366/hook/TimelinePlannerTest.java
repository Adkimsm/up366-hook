package io.github.adkimsm.up366.hook;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class TimelinePlannerTest {

    private static final long ANCHOR = 1_700_000_000_000L;

    private static List<TimelinePlanner.Section> sections() {
        return Arrays.asList(
                new TimelinePlanner.Section(30_000L, 2, Arrays.asList("Q1", "Q2")),
                new TimelinePlanner.Section(20_000L, 2, Arrays.asList("Q3", "Q4", "Q5")));
    }

    @Test
    public void answersFollowTheAudioAndIncreaseMonotonically() {
        TimelinePlanner plan = TimelinePlanner.build(sections(), ANCHOR, 0L, new Random(42));

        long previous = 0L;
        for (String id : Arrays.asList("Q1", "Q2", "Q3", "Q4", "Q5")) {
            long value = plan.timestampFor(id);
            if (previous == 0L) {
                assertTrue("first answer must wait for the first clip twice",
                        value >= plan.startMs() + 60_000L);
            }
            assertTrue("answers must increase in order", value > previous);
            previous = value;
        }
        assertEquals(ANCHOR, plan.endMs());
        assertEquals(plan.timestampFor("Q1"), plan.timestampFor("Q1"));
        assertTrue(plan.timestampFor("Q3") >= plan.timestampFor("Q2") + 40_000L);
        assertTrue(plan.totalSeconds() >= 60L + 40L + 5L * 8L);
    }

    @Test
    public void targetTotalStretchesTheThinkingSlots() {
        TimelinePlanner plan = TimelinePlanner.build(sections(), ANCHOR, 1_800_000L, new Random(7));

        assertEquals(1_800L, plan.totalSeconds());
        assertEquals(ANCHOR, plan.endMs());
        assertTrue(plan.timestampFor("Q1") >= plan.startMs() + 60_000L);
        assertTrue(plan.timestampFor("Q5") <= plan.endMs());
    }

    @Test
    public void targetTotalNeverShrinksBelowTheAudio() {
        TimelinePlanner plan = TimelinePlanner.build(sections(), ANCHOR, 1L, new Random(3));

        long minimum = 60_000L + 40_000L + 5L * TimelinePlanner.MIN_THINK_MS
                + TimelinePlanner.MIN_GAP_MS;
        assertTrue(plan.totalMs() >= minimum);
    }

    @Test
    public void unknownQuestionsGetDistinctSlotsInsideTheWindow() {
        TimelinePlanner plan = TimelinePlanner.build(sections(), ANCHOR, 0L, new Random(11));

        long first = plan.timestampFor("UNKNOWN-1");
        long second = plan.timestampFor("UNKNOWN-2");

        assertNotEquals(first, second);
        assertTrue(first >= plan.startMs() && first <= plan.endMs());
        assertTrue(second >= plan.startMs() && second <= plan.endMs());
    }

    @Test
    public void missingAudioFallsBackToTheDefaultLength() {
        TimelinePlanner plan = TimelinePlanner.build(
                Collections.singletonList(new TimelinePlanner.Section(0L, 1,
                        Collections.singletonList("Q1"))),
                ANCHOR, 0L, new Random(5));

        assertTrue(plan.timestampFor("Q1") >= plan.startMs()
                + TimelinePlanner.DEFAULT_AUDIO_MS + TimelinePlanner.MIN_THINK_MS);
    }
}
