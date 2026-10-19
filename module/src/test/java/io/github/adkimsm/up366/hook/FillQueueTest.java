package io.github.adkimsm.up366.hook;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class FillQueueTest {
    @Test
    public void pollReturnsTheQueuedRoundAndPendingTracksExecution() {
        FillQueue queue = new FillQueue();
        queue.submit(answers("q1", "A"), json -> { });
        assertTrue(queue.pending());

        FillQueue.Round round = queue.poll();
        assertNotNull(round);
        assertEquals("A", round.answers().get("q1"));
        assertTrue(queue.pending());   // still in flight
        assertNull(queue.poll());      // a second round cannot start while one executes

        queue.finishRound();
        assertFalse(queue.pending());
        assertNull(queue.poll());
    }

    @Test
    public void requestsThatArriveBeforeExecutionShareOneRound() {
        FillQueue queue = new FillQueue();
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        queue.submit(answers("q1", "A"), first);
        queue.submit(answers("q1", "A"), second);

        FillQueue.Round round = queue.poll();
        assertNotNull(round);
        assertEquals(2, round.sinks().size());
        for (FillQueue.Sink sink : round.sinks()) sink.onResult("{\"filled\":1}");

        assertEquals(1, first.results.size());
        assertEquals(1, second.results.size());

        queue.finishRound();
        assertNull(queue.poll());      // the merged request never created a second round
        assertFalse(queue.pending());
    }

    @Test
    public void requestsThatArriveDuringExecutionWaitAsTheNextRound() {
        FillQueue queue = new FillQueue();
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        queue.submit(answers("q1", "A"), first);
        FillQueue.Round executing = queue.poll();
        assertNotNull(executing);

        queue.submit(answers("q2", "B"), second);
        assertTrue(queue.pending());
        assertNull(queue.poll());      // the executing round is still busy

        queue.finishRound();
        FillQueue.Round next = queue.poll();
        assertNotNull(next);
        assertEquals("B", next.answers().get("q2"));
        assertEquals(1, next.sinks().size());

        queue.finishRound();
        assertFalse(queue.pending());
    }

    @Test
    public void requestsDuringExecutionMergeIntoOneNextRound() {
        FillQueue queue = new FillQueue();
        queue.submit(answers("q1", "A"), json -> { });
        assertNotNull(queue.poll());

        queue.submit(answers("q2", "B"), json -> { });
        queue.submit(answers("q3", "C"), json -> { });
        queue.finishRound();

        FillQueue.Round next = queue.poll();
        assertNotNull(next);
        assertEquals(2, next.sinks().size());
        assertEquals("B", next.answers().get("q2"));   // the first waiting request wins the round
    }

    @Test
    public void nullSinksAndEmptySubmitsNeverQueueARound() {
        FillQueue queue = new FillQueue();
        queue.submit(answers("q1", "A"), null);
        assertFalse(queue.pending());
        assertNull(queue.poll());
    }

    private static Map<String, String> answers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static final class Recorder implements FillQueue.Sink {
        final List<String> results = new ArrayList<>();

        @Override
        public void onResult(String json) {
            results.add(json);
        }
    }
}
