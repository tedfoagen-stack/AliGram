package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic JVM unit tests for {@link AliGramPeriodicVerify}.
 *
 * <p>Pure-Java only: the sweep and the roaming check are fakes, the timer
 * is a fake {@link AliGramHiddenFolders.Scheduler}. Covers the interval
 * decision, first-run delay, sweep-on-fire, roaming pause (skip sweep but
 * still reschedule), idempotent start (no overlapping runs), stop, and
 * the media-level dedup rule (live ref ⇒ already preserved or in flight
 * ⇒ never re-queued). The real sweep's per-message skip calls the tested
 * {@link AliGramPeriodicVerify#needsVerification(boolean)} predicate.
 */
public class AliGramPeriodicVerifyTest {

    private static final int ACC = 0;

    /** Fake scheduler: records posts/cancels, fires on demand (fake clock). */
    private static final class FakeScheduler implements AliGramHiddenFolders.Scheduler {
        private static final class Task {
            Runnable runnable;
            long delayMs;
            long fireAt;
        }

        private final List<Task> pending = new ArrayList<>();
        private final List<Long> postedDelays = new ArrayList<>();
        private int cancelCalls = 0;
        private long now = 0;

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            Task t = new Task();
            t.runnable = runnable;
            t.delayMs = delayMs;
            t.fireAt = now + delayMs;
            pending.add(t);
            postedDelays.add(delayMs);
        }

        @Override
        public void cancel(Runnable runnable) {
            cancelCalls++;
            pending.removeIf(t -> t.runnable == runnable);
        }

        void advance(long ms) {
            now += ms;
            List<Task> due = new ArrayList<>();
            for (Task t : pending) {
                if (t.fireAt <= now) {
                    due.add(t);
                }
            }
            pending.removeAll(due);
            for (Task t : due) {
                t.runnable.run();
            }
        }

        int pendingCount() {
            return pending.size();
        }

        int cancelCalls() {
            return cancelCalls;
        }

        List<Long> postedDelays() {
            return new ArrayList<>(postedDelays);
        }
    }

    private FakeScheduler scheduler;
    private int sweepCalls;
    private boolean roaming;

    @Before
    public void setUp() {
        AliGramPeriodicVerify.resetForTest();
        scheduler = new FakeScheduler();
        AliGramPeriodicVerify.setSchedulerForTest(scheduler);
        sweepCalls = 0;
        roaming = false;
    }

    @After
    public void tearDown() {
        AliGramPeriodicVerify.resetForTest();
    }

    private void start() {
        AliGramPeriodicVerify.start(ACC,
                account -> sweepCalls++,
                account -> roaming);
    }

    // --- Interval constants ---

    @Test
    public void intervalIsSixtyMinutes() {
        assertEquals(60L * 60_000L, AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS,
                AliGramPeriodicVerify.PERIODIC_VERIFY_INITIAL_DELAY_MS);
    }

    @Test
    public void shouldRunDueWhenNeverRun() {
        assertTrue(AliGramPeriodicVerify.shouldRunDue(0, 1_000L));
        assertTrue(AliGramPeriodicVerify.shouldRunDue(-1, 1_000L));
    }

    @Test
    public void shouldRunDueOnlyAfterFullInterval() {
        long last = 10_000L;
        assertFalse(AliGramPeriodicVerify.shouldRunDue(last, last + 59L * 60_000L));
        assertFalse(AliGramPeriodicVerify.shouldRunDue(last, last + AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS - 1));
        assertTrue(AliGramPeriodicVerify.shouldRunDue(last, last + AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS));
        assertTrue(AliGramPeriodicVerify.shouldRunDue(last, last + 2 * AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS));
    }

    // --- Scheduling ---

    @Test
    public void startSchedulesFirstRunOneIntervalOut() {
        start();
        assertTrue(AliGramPeriodicVerify.isStarted(ACC));
        assertEquals(1, scheduler.pendingCount());
        assertEquals(1, scheduler.postedDelays().size());
        assertEquals(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS,
                (long) scheduler.postedDelays().get(0));
        assertEquals(0, sweepCalls);
    }

    @Test
    public void fireRunsSweepOnceAndReschedules() {
        start();
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(1, sweepCalls);
        assertEquals(1, scheduler.pendingCount());
        assertEquals(2, scheduler.postedDelays().size());
        assertEquals(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS,
                (long) scheduler.postedDelays().get(1));
    }

    @Test
    public void fireRunsSweepEveryInterval() {
        start();
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(3, sweepCalls);
    }

    @Test
    public void doubleStartSchedulesOnlyOnce() {
        start();
        start();
        assertEquals(1, scheduler.pendingCount());
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(1, sweepCalls);
        assertEquals(1, scheduler.pendingCount());
    }

    @Test
    public void stopCancelsPendingRun() {
        start();
        AliGramPeriodicVerify.stop(ACC);
        assertFalse(AliGramPeriodicVerify.isStarted(ACC));
        assertEquals(0, scheduler.pendingCount());
        assertEquals(1, scheduler.cancelCalls());
        scheduler.advance(2 * AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(0, sweepCalls);
    }

    @Test
    public void stopWithoutStartIsNoop() {
        AliGramPeriodicVerify.stop(ACC);
        assertEquals(0, scheduler.cancelCalls());
    }

    // --- Roaming: pause, not fail ---

    @Test
    public void roamingSkipsSweepButStillReschedules() {
        roaming = true;
        start();
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(0, sweepCalls);
        assertEquals(1, scheduler.pendingCount());
        // Next round, back on Wi-Fi: the sweep runs again.
        roaming = false;
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(1, sweepCalls);
    }

    @Test
    public void roamingProbeFailurePausesButStillReschedules() {
        AliGramPeriodicVerify.start(ACC,
                account -> sweepCalls++,
                account -> {
                    throw new RuntimeException("probe failed");
                });
        scheduler.advance(AliGramPeriodicVerify.PERIODIC_VERIFY_INTERVAL_MS);
        assertEquals(0, sweepCalls);
        assertEquals(1, scheduler.pendingCount());
    }

    // --- Media-level dedup rule (the sweep calls this per message) ---

    @Test
    public void needsVerificationOnlyWithoutLiveRef() {
        // No live ref: message is missing → verify (re-drive through ingest).
        assertTrue(AliGramPeriodicVerify.needsVerification(false));
        // Live ref exists: already preserved-complete OR already in flight
        // (a LIVE ref is created at ingest, state ENQUEUED) → never re-queue.
        assertFalse(AliGramPeriodicVerify.needsVerification(true));
    }
}
