package io.github.adkimsm.up366.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SpeedProfileTest {

    @Test
    public void levelsExposeIncreasingSpeed() {
        SpeedProfile off = SpeedProfile.of(0);
        SpeedProfile normal = SpeedProfile.of(1);
        SpeedProfile fast = SpeedProfile.of(2);
        SpeedProfile max = SpeedProfile.of(3);

        assertFalse(off.acceleratesPlayback());
        assertFalse(off.hooksPage());
        assertTrue(normal.acceleratesPlayback());
        assertTrue(normal.hooksPage());
        assertTrue(Double.parseDouble(max.rate) > Double.parseDouble(fast.rate));
        assertTrue(max.clock > fast.clock);
        assertTrue(max.timerMaxMs < fast.timerMaxMs);
        assertTrue(max.stopRetryMs <= fast.stopRetryMs);
    }

    @Test
    public void indexHelpersClampAndCycle() {
        assertEquals(SpeedProfile.DEFAULT_INDEX, SpeedProfile.clampIndex(-5));
        assertEquals(SpeedProfile.DEFAULT_INDEX, SpeedProfile.clampIndex(99));
        assertEquals(0, SpeedProfile.nextIndex(SpeedProfile.LEVELS.length - 1));
        assertEquals(SpeedProfile.DEFAULT_INDEX, SpeedProfile.of(99).clock == 0 ? -1 : SpeedProfile.DEFAULT_INDEX);
    }

    @Test
    public void offProfileProducesADisabledHookScript() {
        String script = AutomationScripts.pageHooksScript(SpeedProfile.of(0));
        assertTrue(script.contains("disabled"));
        assertFalse(script.contains("window.setTimeout="));
    }
}
