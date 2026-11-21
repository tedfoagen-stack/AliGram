package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Deterministic JVM unit tests for the O-01 (Phase 3.2) preservation-dialog
 * wording decision in {@link AliGramEligibility#resolvePreservationDialogState}.
 *
 * <p>Scope: the pure decision only — which body text and positive button the
 * dialog shows for a given (opted-in, activation-state) pair. The Android
 * dialog itself ({@code ChatActivity.showAliGramPreservationDialogInternal},
 * string resources, button wiring) is NOT covered here and must be verified
 * by code review: the mapping from {@code PreservationDialogState} to
 * string resources lives there, and these tests pin the decision it is built
 * on so a future change to the branching fails loudly instead of silently
 * re-introducing the contradiction (ON text shown while PENDING).
 */
public class AliGramPreservationDialogTest {

    @Test
    public void enabledPendingShowsPendingOnly() {
        assertEquals(
                AliGramEligibility.PreservationDialogState.PENDING,
                AliGramEligibility.resolvePreservationDialogState(
                        true, AliGramEligibility.ActivationState.PENDING));
    }

    @Test
    public void enabledStampedShowsEnabled() {
        assertEquals(
                AliGramEligibility.PreservationDialogState.ENABLED,
                AliGramEligibility.resolvePreservationDialogState(
                        true, AliGramEligibility.ActivationState.STAMPED));
    }

    @Test
    public void disabledPendingShowsDisabled() {
        // Opt-out wins: a disabled chat never shows the pending notice,
        // even if a stale PENDING record is still on disk.
        assertEquals(
                AliGramEligibility.PreservationDialogState.DISABLED,
                AliGramEligibility.resolvePreservationDialogState(
                        false, AliGramEligibility.ActivationState.PENDING));
    }

    @Test
    public void disabledStampedShowsDisabled() {
        // Opt-out wins over a stale STAMPED record as well.
        assertEquals(
                AliGramEligibility.PreservationDialogState.DISABLED,
                AliGramEligibility.resolvePreservationDialogState(
                        false, AliGramEligibility.ActivationState.STAMPED));
    }

    @Test
    public void enabledNullStateTreatedAsPending() {
        // Defensive: without a proven stamp the ON text must never show.
        assertEquals(
                AliGramEligibility.PreservationDialogState.PENDING,
                AliGramEligibility.resolvePreservationDialogState(true, null));
    }
}
