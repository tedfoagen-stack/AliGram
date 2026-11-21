package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Deterministic JVM unit tests for the Phase 3.1 R-7 stamping fix.
 *
 * <p>Scope: the pure decision functions in {@link AliGramEligibility} only.
 * The 30-second Connected-only fallback was REMOVED — these tests pin the
 * replacement rule (stamp only on Connected AND a server-originated round
 * in this process; otherwise stay PENDING) and its observable effect on
 * the ingest gate. The native evidence behind the rule (what Connected
 * does and does not guarantee, the callers that do and do not prove a
 * server round) is documented in
 * {@code ~/workspace/aligram/phase3-1-r7-note.md} — it is NOT proven by
 * these tests, and the runtime scenarios listed there remain unexecuted.
 *
 * <p>The design asymmetry ({@code edit_date >= activationTime} for edit
 * enrolment vs strict {@code date > activationTime} for ingest) is NOT
 * changed here and stays pinned by {@code AliGramEligibilityTest}.
 */
public class AliGramActivationTest {

    private static final int A = 1_700_000_000;

    // ------------------------------------------------------------------
    // mayStampActivation: the R-7 rule — both conditions, no fallback.
    // ------------------------------------------------------------------

    @Test
    public void mayStamp_connectedAndRound_stamps() {
        assertTrue(AliGramEligibility.mayStampActivation(true, true));
    }

    @Test
    public void mayStamp_connectedWithoutRound_doesNotStamp() {
        // This IS the removed fallback: Connected alone, 30 s or not,
        // must never stamp. A PENDING activation stays PENDING.
        assertFalse(AliGramEligibility.mayStampActivation(true, false));
    }

    @Test
    public void mayStamp_roundWithoutConnected_doesNotStamp() {
        // A round recorded earlier in this process but no live connection
        // now: do not stamp on a possibly stale timeDifference. The next
        // reconnect + round will stamp.
        assertFalse(AliGramEligibility.mayStampActivation(false, true));
    }

    @Test
    public void mayStamp_neither_doesNotStamp() {
        assertFalse(AliGramEligibility.mayStampActivation(false, false));
    }

    // ------------------------------------------------------------------
    // Server-time validity: A <= 0 is never accepted.
    // ------------------------------------------------------------------

    @Test
    public void stampableServerTime_requiresPositive() {
        assertFalse(AliGramEligibility.isValidActivationTime(0));
        assertFalse(AliGramEligibility.isValidActivationTime(-5));
        assertTrue(AliGramEligibility.isValidActivationTime(1));
        assertTrue(AliGramEligibility.isValidActivationTime(A));
    }

    // ------------------------------------------------------------------
    // Observable effect: the ingest gate stays closed while PENDING,
    // even for a message dated after the (unstamped) activation time.
    // ------------------------------------------------------------------

    private boolean ingestOk(int messageDate,
                             AliGramEligibility.ActivationState state, int activationTime) {
        return AliGramEligibility.canIngest(
                true, true, true, true, false,
                true, true, true, false,
                true, messageDate, activationTime, state, false);
    }

    @Test
    public void gateClosedWhilePending_evenAfterActivation() {
        // No stamp yet (A == 0 / PENDING): a brand-new message is NOT
        // enrolled. This is what "staying PENDING is safe" means.
        assertFalse(ingestOk(A + 60, AliGramEligibility.ActivationState.PENDING, 0));
        assertFalse(AliGramEligibility.isGateOpen(
                AliGramEligibility.ActivationState.PENDING, 0));
    }

    @Test
    public void gateOpensOnlyWhenStampedWithValidTime() {
        assertFalse(AliGramEligibility.isGateOpen(
                AliGramEligibility.ActivationState.STAMPED, 0));
        assertTrue(AliGramEligibility.isGateOpen(
                AliGramEligibility.ActivationState.STAMPED, A));
        assertTrue(ingestOk(A + 1, AliGramEligibility.ActivationState.STAMPED, A));
        assertFalse(ingestOk(A, AliGramEligibility.ActivationState.STAMPED, A));
    }

    // ------------------------------------------------------------------
    // SPEC-MODEL: what counts as a "server round" at the hook site.
    //
    // The hook lives at the end of MessagesController.processUpdateArray
    // and fires only when (fromGetDifference || date != 0). This table
    // mirrors the callers exactly; it is a tripwire for the rule, not
    // operational verification — whether a given device build really
    // delivers those parameters needs the runtime probe (T-B11).
    // ------------------------------------------------------------------

    /** Mirror of the hook gate in MessagesController.processUpdateArray. */
    private static boolean isServerRound(boolean fromGetDifference, int date) {
        return fromGetDifference || date != 0;
    }

    @Test
    public void serverRoundSignal_getDifferenceCounts() {
        // getDifference batches pass (true, 0).
        assertTrue(isServerRound(true, 0));
    }

    @Test
    public void serverRoundSignal_updatesWithServerDateCounts() {
        // processUpdates / Stories / ChatTheme pass (false, server date).
        assertTrue(isServerRound(false, 1_700_000_123));
    }

    @Test
    public void serverRoundSignal_localSynthesisDoesNotCount() {
        // Locally synthesised read-state / story / secret-chat batches:
        // (false, 0) — built from cache or a push payload, no server
        // round-trip, no clock proof. Must never set roundSeen.
        assertFalse(isServerRound(false, 0));
    }
}
