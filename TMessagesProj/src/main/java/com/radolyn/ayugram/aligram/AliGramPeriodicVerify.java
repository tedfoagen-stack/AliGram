package com.radolyn.ayugram.aligram;

import org.telegram.messenger.AndroidUtilities;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic auto-verification that media in preserve-enabled channels is
 * actually complete — so that opening an enabled channel finds everything
 * already downloaded, without the user having to open it first.
 *
 * <h2>Design: scheduler + reuse, no new download machinery</h2>
 * The completeness scan IS the existing missed-message sweep
 * ({@link AliGramPreservation#sweepMissedMessages(int)}): per opted-in
 * dialog it re-drives the most recent
 * {@link AliGramEligibility#SWEEP_MAX_MESSAGES_PER_DIALOG} messages inside
 * the {@link AliGramEligibility#SWEEP_RECENT_WINDOW_SECONDS} window that
 * have no LIVE ref. This class adds only the timer around it: every
 * {@link #PERIODIC_VERIFY_INTERVAL_MS} it runs the sweep again. All
 * dominance rules live in the sweep/ingest path and are unchanged —
 * roaming pauses (never fails), Ali's caps still block, retry-until-
 * success still applies.
 *
 * <h2>Scheduling choice (why a self-rescheduling UI-thread runnable)</h2>
 * <ul>
 *   <li>No WorkManager in this codebase (zero usages) — adding the
 *       dependency for one hourly check would be a disproportionate
 *       Gradle change for a small update.</li>
 *   <li>{@code AlarmManager.setInexactRepeating} exists in
 *       {@code ApplicationLoader} but only to restart the push
 *       {@code NotificationsService} every 10 minutes; waking the device
 *       hourly just to check downloads would cost battery for no benefit
 *       — a killed app gets its sweep at the next startup anyway
 *       (startup reconcile), so there is nothing an alarm would add.</li>
 *   <li>So this follows the AliGram idiom already used for the hidden-
 *       folder auto-lock refresh: a self-rescheduling
 *       {@code AndroidUtilities.runOnUIThread(runnable, delay)} chain
 *       (see {@code AliGramHiddenFolders} default scheduler and
 *       {@code DialogsActivity.aliGramScheduleAutoLockRefresh}). No wake
 *       locks, no alarms, no new permissions; the chain simply stops
 *       when the process dies and restarts (first run one interval after
 *       start) from {@code AliGramPreservation.ensureInitialized}.</li>
 * </ul>
 *
 * <h2>Deduplication</h2>
 * Two layers, both by construction rather than by bookkeeping:
 * <ul>
 *   <li>Run level: a single self-rescheduling runnable per account —
 *       {@link #start} is idempotent, so runs can never overlap or
 *       double-queue.</li>
 *   <li>Media level: the sweep skips any message that already has a LIVE
 *       ref ({@code AliGramPreservation.sweepMissedIngest}), and a LIVE
 *       ref exists from ingest (state ENQUEUED) until a terminal
 *       transition — so it covers both preserved-complete AND
 *       in-flight/download media. The rule is pinned as the pure
 *       {@link #needsVerification(boolean)} predicate, which the sweep
 *       calls for its skip decision.</li>
 * </ul>
 *
 * <h2>Testability</h2>
 * The sweep and the roaming check arrive as interfaces, so JVM tests
 * inject fakes; the timer goes through the {@link AliGramHiddenFolders.Scheduler}
 * interface (production: {@code AndroidUtilities} handler). Nothing in the
 * tested paths calls Android except the default scheduler, which tests
 * replace via {@link #setSchedulerForTest}.
 */
public final class AliGramPeriodicVerify {

    private AliGramPeriodicVerify() {
    }

    /** How often the completeness sweep re-runs: 60 minutes. */
    public static final long PERIODIC_VERIFY_INTERVAL_MS = 3_600_000L;

    /** Delay before the first periodic run after start (== the interval). */
    public static final long PERIODIC_VERIFY_INITIAL_DELAY_MS = PERIODIC_VERIFY_INTERVAL_MS;

    /** Runs the completeness sweep for one account (production: the missed-message sweep). */
    public interface SweepRunner {
        void runSweep(int account);
    }

    /** Roaming probe (production: {@code AliGramPreservation.isRoaming}). */
    public interface RoamingCheck {
        boolean isRoaming(int account);
    }

    private static final class VerifyState {
        boolean started;
        Runnable task;
    }

    private static final Map<Integer, VerifyState> STATES = new ConcurrentHashMap<>();

    private static final AliGramHiddenFolders.Scheduler DEFAULT_SCHEDULER =
            new AliGramHiddenFolders.Scheduler() {
                @Override
                public void postDelayed(Runnable runnable, long delayMs) {
                    AndroidUtilities.runOnUIThread(runnable, delayMs);
                }

                @Override
                public void cancel(Runnable runnable) {
                    AndroidUtilities.cancelRunOnUIThread(runnable);
                }
            };

    private static volatile AliGramHiddenFolders.Scheduler scheduler = DEFAULT_SCHEDULER;

    /** Test hook: replace the scheduler (null restores the default). */
    public static void setSchedulerForTest(AliGramHiddenFolders.Scheduler testScheduler) {
        scheduler = testScheduler != null ? testScheduler : DEFAULT_SCHEDULER;
    }

    /** Test hook: clear all per-account state and restore the default scheduler. */
    public static void resetForTest() {
        STATES.clear();
        scheduler = DEFAULT_SCHEDULER;
    }

    /** True iff the periodic verifier is started for this account. */
    public static boolean isStarted(int account) {
        VerifyState st = STATES.get(account);
        return st != null && st.started;
    }

    /**
     * The media-level dedup rule the completeness scan applies per message:
     * a message needs (re-)verification only when it has NO live ref. A
     * live ref exists from ingest (ENQUEUED) through download/copy until a
     * terminal state, so {@code hasLiveRef == true} covers both
     * already-preserved-complete media and media already in flight — neither
     * is ever re-queued.
     */
    public static boolean needsVerification(boolean hasLiveRef) {
        return !hasLiveRef;
    }

    /**
     * Pure interval decision: a periodic run is due when no run has ever
     * happened ({@code lastRunMs <= 0}) or at least one full interval has
     * elapsed since the last run.
     */
    public static boolean shouldRunDue(long lastRunMs, long nowMs) {
        if (lastRunMs <= 0) {
            return true;
        }
        return nowMs - lastRunMs >= PERIODIC_VERIFY_INTERVAL_MS;
    }

    /**
     * Starts the periodic verifier for one account. Idempotent: a second
     * call while already started schedules nothing new (no overlapping
     * runs, no double sweep). The first run fires one interval after
     * start; every run reschedules the next one interval out. Production
     * caller: {@code AliGramPreservation.ensureInitialized}, passing the
     * missed-message sweep and the roaming probe.
     */
    public static void start(int account, SweepRunner sweep, RoamingCheck roaming) {
        if (sweep == null || roaming == null) {
            return;
        }
        VerifyState st = STATES.computeIfAbsent(account, k -> new VerifyState());
        synchronized (st) {
            if (st.started) {
                return;
            }
            st.started = true;
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    // PAUSE, not fail: roaming skips this round's sweep,
                    // the next round still happens on schedule.
                    try {
                        if (!roaming.isRoaming(account)) {
                            sweep.runSweep(account);
                        }
                    } catch (Throwable ignored) {
                        // The sweep itself logs its own failures and never
                        // throws; a roaming-probe failure is treated as a
                        // pause. Either way the next run still reschedules.
                    }
                    synchronized (st) {
                        if (st.started) {
                            scheduler.postDelayed(this, PERIODIC_VERIFY_INTERVAL_MS);
                        }
                    }
                }
            };
            st.task = task;
            scheduler.postDelayed(task, PERIODIC_VERIFY_INITIAL_DELAY_MS);
        }
    }

    /** Stops the periodic verifier for one account; cancels the pending run. */
    public static void stop(int account) {
        VerifyState st = STATES.get(account);
        if (st == null) {
            return;
        }
        synchronized (st) {
            st.started = false;
            if (st.task != null) {
                scheduler.cancel(st.task);
                st.task = null;
            }
        }
    }
}
