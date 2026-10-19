package io.github.adkimsm.up366.hook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Main-thread-confined FIFO queue of pending "one-click fill" rounds for one WebView. Every request
 * is accepted: a request that arrives while an earlier round is still queued shares that round (the
 * clicks are idempotent and the answer snapshot is taken from the same page state), and a request
 * that arrives while a round is already executing waits as the next round. The fast-forward step
 * loop and the standalone fill pump poll rounds from the same queue, so fill scripts and submit
 * scripts never race inside one page and no click is ever rejected as busy.
 */
final class FillQueue {
    /** Receives the fill script's JSON result exactly once per accepted request. */
    interface Sink {
        void onResult(String json);
    }

    /** One queued execution: the answers to click plus every request that shares the execution. */
    static final class Round {
        private final Map<String, String> answers;
        private final List<Sink> sinks = new ArrayList<>();

        Round(Map<String, String> answers) {
            this.answers = answers == null
                    ? Collections.emptyMap() : Collections.unmodifiableMap(answers);
        }

        Map<String, String> answers() {
            return answers;
        }

        void addSink(Sink sink) {
            if (sink != null) sinks.add(sink);
        }

        List<Sink> sinks() {
            return Collections.unmodifiableList(sinks);
        }

        int size() {
            return answers.size();
        }
    }

    private final Deque<Round> queued = new ArrayDeque<>();
    private Round executing;

    /** Accepts one fill request; never rejects and never drops a sink. */
    void submit(Map<String, String> answers, Sink sink) {
        if (sink == null) return;
        Round pending = queued.peekLast();
        if (pending == null) {
            pending = new Round(answers);
            queued.addLast(pending);
        }
        pending.addSink(sink);
    }

    /**
     * Takes the next queued round for execution, or {@code null} when the queue is empty or a
     * previous round is still executing. The caller must call {@link #finishRound()} when done.
     */
    Round poll() {
        if (executing != null) return null;
        Round next = queued.pollFirst();
        if (next != null) executing = next;
        return next;
    }

    /** Marks the executing round as done so the next queued round can be polled. */
    void finishRound() {
        executing = null;
    }

    /** True while a fill round is executing or queued; keeps the submit click back. */
    boolean pending() {
        return executing != null || !queued.isEmpty();
    }
}
