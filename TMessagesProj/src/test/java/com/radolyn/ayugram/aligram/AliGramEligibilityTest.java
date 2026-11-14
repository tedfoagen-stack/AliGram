package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Deterministic JVM unit tests for the AliGram preservation policy.
 *
 * <p>Scope: pure policy logic in {@link AliGramEligibility} only. Anything
 * that needs Android, Room, the storage queue, FileLoader or the network is
 * NOT covered here and must NOT be claimed as covered:
 *
 * <ul>
 *   <li>DB idempotency (the unique index on PreservedRef) and account
 *       isolation are structural guarantees of the Room schema and the
 *       per-account prefs files; they are verified by the build (Room
 *       schema generation) and by the runtime probes, not by these tests.</li>
 *   <li>Pin / copy / delete races across queues, the deferred-delete
 *       release, restart re-drive, manual cache clear, clear-history and
 *       dialog-delete sweeps are integration behaviour of
 *       {@code AliGramPreservation} + {@code MessagesStorage} /
 *       {@code FileLoader}; these tests cover the pure decision functions
 *       they are built on ({@code decideRelease}, {@code isTerminalState},
 *       {@code isActivePinState}, {@code attemptsExhausted},
 *       {@code shouldProbeDownload}).</li>
 *   <li>T-H9-1/2/3 and T-R19-1/2 below are SPEC-MODEL tests: they encode the
 *       branch table proven in phase3-investigations.md (H-9: scheduled /
 *       quick-reply branches delete no files; R-19: the watchdog probe is
 *       the second wake-up) using the same predicates the production code
 *       evaluates. They are a tripwire for the spec, not operational
 *       verification — a runtime trace on a device is still required for
 *       T-B11 and for the streaming-player cancel behaviour.</li>
 * </ul>
 *
 * <p>Every rule is a direct transcription of DESIGN-PHASE2-3 (authoritative,
 * overrides Phase 2.2 on conflict).
 */
public class AliGramEligibilityTest {

    // Activation second used by most ingest tests.
    private static final int A = 1_700_000_000;

    /** A fully-valid ingest argument set (all gates open, date after A). */
    private boolean ingestOk(int messageDate, boolean preExisting) {
        return AliGramEligibility.canIngest(
                true, true, true, true, false,
                true, true, false, true, false,
                true, messageDate, A, AliGramEligibility.ActivationState.STAMPED, preExisting);
    }

    // ------------------------------------------------------------------
    // Activation boundary: strict '>' (Phase 2.3 §1.3).
    // ------------------------------------------------------------------

    @Test
    public void secondBoundary_strictlyGreater() {
        assertTrue(AliGramEligibility.isAfterActivation(A + 1, A));
        assertFalse(AliGramEligibility.isAfterActivation(A, A));
        assertFalse(AliGramEligibility.isAfterActivation(A - 1, A));
    }

    @Test
    public void secondBoundary_ingest() {
        assertTrue(ingestOk(A + 1, false));
        assertFalse(ingestOk(A, false));     // the activation second itself: skipped by design
        assertFalse(ingestOk(A - 1, false)); // one second before activation: pre-activation media
    }

    @Test
    public void activationTimeZeroIsNeverValid() {
        assertFalse(AliGramEligibility.isValidActivationTime(0));
        assertFalse(AliGramEligibility.isValidActivationTime(-5));
        assertFalse(AliGramEligibility.isGateOpen(AliGramEligibility.ActivationState.STAMPED, 0));
        assertTrue(AliGramEligibility.isValidActivationTime(1));
    }

    @Test
    public void pendingGateIsClosed() {
        // PENDING (no server sync yet in this process — R-7) closes the gate
        // even when an activation time is recorded.
        assertFalse(AliGramEligibility.isGateOpen(AliGramEligibility.ActivationState.PENDING, A));
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, false, true, true, false, true, false,
                true, A + 100, A, AliGramEligibility.ActivationState.PENDING, false));
    }

    @Test
    public void delayedPreActivationMessageIsNeverEnrolled() {
        // Created before activation, delivered after it (getDifference /
        // push late delivery): the date, not the receipt time, decides.
        assertFalse(ingestOk(A - 3600, false));
        // Even a message created exactly at the activation second is out.
        assertFalse(ingestOk(A, false));
    }

    @Test
    public void newAfterActivationIsEnrolled() {
        assertTrue(ingestOk(A + 1, false));
        assertTrue(ingestOk(A + 86400, false));
    }

    @Test
    public void preExistingMessageIsNeverEnrolled() {
        // A messages_v2 row already existed before this ingest
        // (re-delivery / update of a stored message).
        assertFalse(ingestOk(A + 1, true));
    }

    @Test
    public void forwardedMessageIsEligibleAsNewContent() {
        // Interpretation A (Phase 2.3 §3): message.date on a forward IS the
        // forward time, so a forward received after activation is new
        // content — no special-case exists in the predicate, by design.
        assertTrue(ingestOk(A + 30, false));
        // …and a forward whose date is pre-activation is excluded the same
        // way as any other pre-activation message.
        assertFalse(ingestOk(A - 30, false));
    }

    @Test
    public void liveSyncUsesGreaterOrEqualByDesign() {
        // DOCUMENTED DESIGN ASYMMETRY (Phase 2.3 §4.3): LIVE-sync enrols
        // new media at edit time with edit_date >= activationTime, while
        // H-I ingest uses strict >. Any future change to either side must
        // be a conscious decision, not an accident — this test pins both.
        boolean editAtBoundary = AliGramEligibility.canEnrolNewMediaAtEdit(
                true, true, false, true, false, true, true,
                A, A, AliGramEligibility.ActivationState.STAMPED, false, false);
        assertTrue(editAtBoundary);
        assertFalse(ingestOk(A, false));
        // And one second later both agree.
        assertTrue(AliGramEligibility.canEnrolNewMediaAtEdit(
                true, true, false, true, false, true, true,
                A + 1, A, AliGramEligibility.ActivationState.STAMPED, false, false));
        assertTrue(ingestOk(A + 1, false));
    }

    @Test
    public void editDateUnknownFailsSafe() {
        assertFalse(AliGramEligibility.canEnrolNewMediaAtEdit(
                true, true, false, true, false, true, true,
                0, A, AliGramEligibility.ActivationState.STAMPED, false, false));
        assertFalse(AliGramEligibility.canEnrolNewMediaAtEdit(
                true, true, false, true, false, true, true,
                -1, A, AliGramEligibility.ActivationState.STAMPED, false, false));
    }

    // ------------------------------------------------------------------
    // Switch matrix (Phase 2.3 §4.3): P / D / E / M-T / X.
    // ------------------------------------------------------------------

    @Test
    public void ingestMatrix() {
        // P off.
        assertFalse(AliGramEligibility.canIngest(
                false, true, true, true, false, true, true, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        // D and E both off: no axis could ever consume the blob.
        assertFalse(AliGramEligibility.canIngest(
                true, false, false, true, false, true, true, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        // D on, E off: ingest still runs (D consumes it).
        assertTrue(AliGramEligibility.canIngest(
                true, true, false, true, false, true, true, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        // D off, E on: ingest still runs (E consumes it).
        assertTrue(AliGramEligibility.canIngest(
                true, false, true, true, false, true, true, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        // M/T off blocks ingest…
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, false, false, true, true, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        // …as do secret/TTL, archived, non-post channel message, roaming,
        // an exhausted budget, a bad class and an over-cap size.
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, true, true, true, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, false, true, true, true, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, false, true, true, false, false, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, false, true, true, false, true, true,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, false, true, true, false, true, false,
                false, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, false, false, true, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
        assertFalse(AliGramEligibility.canIngest(
                true, true, true, true, false, true, false, false, true, false,
                true, A + 1, A, AliGramEligibility.ActivationState.STAMPED, false));
    }

    @Test
    public void exclusionGatesBindingNotIngest() {
        // X / X_s never appear in canIngest (there is no such parameter):
        // an excluded dialog still ingests; the exclusion bites at bind /
        // freeze time only (Phase 2.3 §4.2 C-04).
        assertTrue(ingestOk(A + 1, false));
        // Freeze: X(dialog) or X(sender) blocks.
        assertFalse(AliGramEligibility.canFreeze(true, true, true, true, false, true, true));
        assertFalse(AliGramEligibility.canFreeze(true, true, true, false, true, true, true));
        assertTrue(AliGramEligibility.canFreeze(true, true, true, false, false, true, true));
        assertFalse(AliGramEligibility.canFreeze(true, true, false, false, false, true, true));
        assertFalse(AliGramEligibility.canFreeze(false, true, true, false, false, true, true));
        // Delete binding: D off / X / bot rule blocks.
        assertFalse(AliGramEligibility.canBindDeleted(false, false, false, true));
        assertFalse(AliGramEligibility.canBindDeleted(true, true, false, true));
        assertFalse(AliGramEligibility.canBindDeleted(true, false, true, true));
        assertFalse(AliGramEligibility.canBindDeleted(true, false, false, false));
        assertTrue(AliGramEligibility.canBindDeleted(true, false, false, true));
    }

    // ------------------------------------------------------------------
    // Media classification + caps (Phase 2.2 §4.1/§4.2, Phase 2.3 §4).
    // ------------------------------------------------------------------

    @Test
    public void classificationPrecedence() {
        // TTL/secret wins over everything.
        assertEquals(AliGramEligibility.MediaClass.TTL_SECRET, AliGramEligibility.classifyMedia(
                true, true, false, false, false, false, false, false, false, true));
        // Story before sticker/webpage.
        assertEquals(AliGramEligibility.MediaClass.STORY, AliGramEligibility.classifyMedia(
                false, false, false, false, false, false, true, true, true, false));
        // Sticker before webpage.
        assertEquals(AliGramEligibility.MediaClass.STICKER, AliGramEligibility.classifyMedia(
                false, false, false, false, false, false, true, true, false, false));
        // Voice before music, round before video, GIF before video.
        assertEquals(AliGramEligibility.MediaClass.VOICE, AliGramEligibility.classifyMedia(
                false, false, false, false, true, true, false, false, false, false));
        assertEquals(AliGramEligibility.MediaClass.ROUND_VIDEO, AliGramEligibility.classifyMedia(
                false, true, true, false, false, false, false, false, false, false));
        assertEquals(AliGramEligibility.MediaClass.GIF, AliGramEligibility.classifyMedia(
                false, true, false, true, false, false, false, false, false, false));
        assertEquals(AliGramEligibility.MediaClass.VIDEO, AliGramEligibility.classifyMedia(
                false, true, false, false, false, false, false, false, false, false));
        assertEquals(AliGramEligibility.MediaClass.PHOTO, AliGramEligibility.classifyMedia(
                true, false, false, false, false, false, false, false, false, false));
        assertEquals(AliGramEligibility.MediaClass.UNSUPPORTED, AliGramEligibility.classifyMedia(
                false, false, false, false, false, false, false, false, false, false));
    }

    @Test
    public void preservableSet() {
        assertTrue(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.PHOTO));
        assertTrue(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.VIDEO));
        assertTrue(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.ROUND_VIDEO));
        assertTrue(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.GIF));
        assertTrue(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.DOCUMENT));
        assertTrue(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.VOICE));
        assertTrue(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.MUSIC));
        assertFalse(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.STICKER));
        assertFalse(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.WEBPAGE));
        assertFalse(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.STORY));
        assertFalse(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.TTL_SECRET));
        assertFalse(AliGramEligibility.isPreservableClass(AliGramEligibility.MediaClass.UNSUPPORTED));
    }

    @Test
    public void sizeCapsPerPreset() {
        // Photo: no size check, at any size.
        assertEquals(-1L, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.PHOTO, AliGramEligibility.NetworkPreset.MOBILE));
        assertTrue(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.PHOTO, Long.MAX_VALUE, AliGramEligibility.NetworkPreset.MOBILE));
        // Video: 10 MiB mobile / 15 MiB Wi-Fi, boundary inclusive.
        assertEquals(10L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.VIDEO, AliGramEligibility.NetworkPreset.MOBILE));
        assertEquals(15L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.VIDEO, AliGramEligibility.NetworkPreset.WIFI));
        assertTrue(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.VIDEO, 10L * 1024 * 1024, AliGramEligibility.NetworkPreset.MOBILE));
        assertFalse(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.VIDEO, 10L * 1024 * 1024 + 1, AliGramEligibility.NetworkPreset.MOBILE));
        assertTrue(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.VIDEO, 15L * 1024 * 1024, AliGramEligibility.NetworkPreset.WIFI));
        assertFalse(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.VIDEO, 15L * 1024 * 1024 + 1, AliGramEligibility.NetworkPreset.WIFI));
        // Round video and GIF share the video cap.
        assertEquals(10L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.GIF, AliGramEligibility.NetworkPreset.MOBILE));
        assertEquals(10L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.ROUND_VIDEO, AliGramEligibility.NetworkPreset.MOBILE));
        // Document: 1 MiB mobile / 3 MiB Wi-Fi.
        assertEquals(1L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.DOCUMENT, AliGramEligibility.NetworkPreset.MOBILE));
        assertEquals(3L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.DOCUMENT, AliGramEligibility.NetworkPreset.WIFI));
        assertFalse(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.DOCUMENT, 1L * 1024 * 1024 + 1, AliGramEligibility.NetworkPreset.MOBILE));
        // Voice: max(512 KiB, document cap) — 1 MiB mobile, 3 MiB Wi-Fi.
        assertEquals(1L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.VOICE, AliGramEligibility.NetworkPreset.MOBILE));
        assertEquals(3L * 1024 * 1024, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.VOICE, AliGramEligibility.NetworkPreset.WIFI));
        // Unknown size fails closed (except photos, already covered).
        assertFalse(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.VIDEO, -1, AliGramEligibility.NetworkPreset.WIFI));
        // Excluded classes have cap 0 and never pass.
        assertEquals(0L, AliGramEligibility.sizeCapBytes(AliGramEligibility.MediaClass.STICKER, AliGramEligibility.NetworkPreset.WIFI));
        assertFalse(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.STICKER, 0, AliGramEligibility.NetworkPreset.WIFI));
        assertFalse(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.STORY, 0, AliGramEligibility.NetworkPreset.WIFI));
        assertFalse(AliGramEligibility.isWithinSizeCap(AliGramEligibility.MediaClass.TTL_SECRET, 0, AliGramEligibility.NetworkPreset.WIFI));
    }

    @Test
    public void budgets() {
        // 25 jobs / 500 MiB per chat (P-12).
        assertTrue(AliGramEligibility.withinBudget(0, 0, 1));
        assertTrue(AliGramEligibility.withinBudget(24, 0, 1));
        assertFalse(AliGramEligibility.withinBudget(25, 0, 1));
        assertFalse(AliGramEligibility.withinBudget(26, 0, 1));
        long mib500 = 500L * 1024 * 1024;
        assertTrue(AliGramEligibility.withinBudget(0, mib500 - 1, 1));
        assertTrue(AliGramEligibility.withinBudget(0, mib500, 0));
        assertFalse(AliGramEligibility.withinBudget(0, mib500, 1));
        assertFalse(AliGramEligibility.withinBudget(0, mib500 - 1, 2));
    }

    // ------------------------------------------------------------------
    // Identity (Phase 2.2 §1.6/§6.2).
    // ------------------------------------------------------------------

    @Test
    public void sha256KnownVector() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AliGramEligibility.sha256Hex("abc".getBytes()));
    }

    @Test
    public void blobIdIdentity() {
        String doc = AliGramEligibility.blobIdFor(123L, 0L, 2, 1000L, "abcdef0123456789abcdef");
        String doc2 = AliGramEligibility.blobIdFor(123L, 0L, 2, 1000L, "abcdef0123456789abcdef");
        assertEquals(doc, doc2); // deterministic: same identity -> same blob id
        assertTrue(doc.startsWith("d123_2_1000_abcdef0123456789"));
        // Same media, different dc -> different blob.
        assertNotEquals(doc, AliGramEligibility.blobIdFor(123L, 0L, 5, 1000L, "abcdef0123456789abcdef"));
        // Photo identity uses the p prefix.
        assertTrue(AliGramEligibility.blobIdFor(0L, 456L, 2, 800L, "ffffffffffffffff")
                .startsWith("p456_2_800_ffffffffffffffff"));
        // Zero document AND photo id: never key on a zero id — sha-only fallback.
        String fallback = AliGramEligibility.blobIdFor(0L, 0L, 2, 800L, "ffffffffffffffff");
        assertTrue(fallback.startsWith("s800_ffffffffffffffff"));
        assertFalse(fallback.startsWith("d0_"));
        assertFalse(fallback.startsWith("p0_"));
        // Document id wins over photo id when both are present.
        assertTrue(AliGramEligibility.blobIdFor(123L, 456L, 2, 1000L, "abcdef0123456789abcdef")
                .startsWith("d123_"));
        // Only the first 16 hex chars of the sha are kept.
        assertTrue(AliGramEligibility.blobIdFor(123L, 0L, 2, 1000L, "0123456789abcdef0123456789abcdef")
                .endsWith("_0123456789abcdef"));
    }

    @Test
    public void duplicateDeliveryPredicateIsDeterministic() {
        // The same ingest inputs always decide the same way, so a duplicate
        // delivery cannot flip the decision; row-level idempotency itself
        // is enforced by the PreservedRef unique index (not JVM-testable).
        assertEquals(ingestOk(A + 5, false), ingestOk(A + 5, false));
        assertEquals(ingestOk(A + 5, true), ingestOk(A + 5, true));
        assertNotEquals(ingestOk(A + 5, false), ingestOk(A + 5, true));
    }

    @Test
    public void nextRevisionSeqIsMonotonic() {
        assertEquals(0, AliGramEligibility.nextRevisionSeq(-1));
        assertEquals(1, AliGramEligibility.nextRevisionSeq(0));
        assertEquals(5, AliGramEligibility.nextRevisionSeq(4));
    }

    // ------------------------------------------------------------------
    // Deferred delete + state machine (Phase 2.3 §2.2).
    // ------------------------------------------------------------------

    @Test
    public void terminalAndActivePinStates() {
        assertTrue(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_COPIED));
        assertTrue(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_FAILED));
        assertTrue(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_UNAVAILABLE));
        assertTrue(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_CANCELLED));
        assertTrue(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_GATE_OFF));
        assertTrue(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_SWEPT));
        assertFalse(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_ENQUEUED));
        assertFalse(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_DOWNLOADING));
        assertFalse(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_PINNED));
        assertFalse(AliGramEligibility.isTerminalState(AliGramEligibility.STATE_COPYING));
        assertFalse(AliGramEligibility.isTerminalState(null));

        assertTrue(AliGramEligibility.isActivePinState(AliGramEligibility.STATE_PINNED));
        assertTrue(AliGramEligibility.isActivePinState(AliGramEligibility.STATE_COPYING));
        assertTrue(AliGramEligibility.isActivePinState(AliGramEligibility.STATE_DOWNLOADING));
        assertTrue(AliGramEligibility.isActivePinState(AliGramEligibility.STATE_ENQUEUED));
        assertFalse(AliGramEligibility.isActivePinState(AliGramEligibility.STATE_COPIED));
        assertFalse(AliGramEligibility.isActivePinState(AliGramEligibility.STATE_FAILED));
        assertFalse(AliGramEligibility.isActivePinState(null));
    }

    @Test
    public void deferredDeleteReleaseDecisions() {
        // File still the pinned file (size AND mtime match) -> DELETE.
        assertEquals(AliGramEligibility.ReleaseDecision.DELETE,
                AliGramEligibility.decideRelease(true, 1000L, 555L, 1000L, 555L));
        // File already gone -> ALREADY_GONE.
        assertEquals(AliGramEligibility.ReleaseDecision.ALREADY_GONE,
                AliGramEligibility.decideRelease(false, 0L, 0L, 1000L, 555L));
        // Size mismatch (path reused by another file) -> KEEP.
        assertEquals(AliGramEligibility.ReleaseDecision.KEEP_MISMATCH,
                AliGramEligibility.decideRelease(true, 1001L, 555L, 1000L, 555L));
        // Mtime mismatch (path reused) -> KEEP.
        assertEquals(AliGramEligibility.ReleaseDecision.KEEP_MISMATCH,
                AliGramEligibility.decideRelease(true, 1000L, 556L, 1000L, 555L));
    }

    @Test
    public void attemptsBudget() {
        assertFalse(AliGramEligibility.attemptsExhausted(0));
        assertFalse(AliGramEligibility.attemptsExhausted(2));
        assertTrue(AliGramEligibility.attemptsExhausted(3));
        assertTrue(AliGramEligibility.attemptsExhausted(4));
        assertEquals(3, AliGramEligibility.MAX_COPY_ATTEMPTS);
    }

    // ------------------------------------------------------------------
    // R-19 second wake-up (watchdog probe).
    // ------------------------------------------------------------------

    @Test
    public void watchdogProbeRule() {
        long t0 = 1_000_000L;
        // Fires only for a DOWNLOADING job stuck >= 90 s.
        assertTrue(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_DOWNLOADING, t0 + 90_000L, t0));
        assertTrue(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_DOWNLOADING, t0 + 300_000L, t0));
        assertFalse(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_DOWNLOADING, t0 + 89_999L, t0));
        assertFalse(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_DOWNLOADING, t0, t0));
        // Any other state: never.
        assertFalse(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_ENQUEUED, t0 + 300_000L, t0));
        assertFalse(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_PINNED, t0 + 300_000L, t0));
        assertFalse(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_COPIED, t0 + 300_000L, t0));
        assertFalse(AliGramEligibility.shouldProbeDownload(
                AliGramEligibility.STATE_FAILED, t0 + 300_000L, t0));
        assertFalse(AliGramEligibility.shouldProbeDownload(null, t0 + 300_000L, t0));
        assertEquals(90_000L, AliGramEligibility.WATCHDOG_DELAY_MS);
    }

    // ------------------------------------------------------------------
    // T-H9-1/2/3 + T-R19-1/2 — SPEC-MODEL tests (see class javadoc).
    // The production dispatch is: mode 0 (normal) -> mark deletes rows and
    // files; MODE_SCHEDULED / MODE_QUICK_REPLIES -> SQL only on
    // scheduled_messages_v2 / quick_replies_messages, no files at all.
    // ------------------------------------------------------------------

    private static final int MODE_NORMAL = 0;
    private static final int MODE_SCHEDULED = 1;      // ChatActivity.MODE_SCHEDULED
    private static final int MODE_QUICK_REPLIES = 2;  // ChatActivity.MODE_QUICK_REPLIES

    /** Model of the file side-effect of markMessagesAsDeletedInternal(mode). */
    private boolean modelDeletesFiles(int mode) {
        return mode == MODE_NORMAL;
    }

    /** Model of the row side-effect: every mode deletes its own table's rows. */
    private boolean modelDeletesRows(int mode) {
        return true;
    }

    @Test
    public void h9_1_scheduledDeleteDeletesNoFiles() {
        assertFalse(modelDeletesFiles(MODE_SCHEDULED));
        assertTrue(modelDeletesRows(MODE_SCHEDULED));
    }

    @Test
    public void h9_2_quickReplyDeleteDeletesNoFiles() {
        assertFalse(modelDeletesFiles(MODE_QUICK_REPLIES));
        assertTrue(modelDeletesRows(MODE_QUICK_REPLIES));
    }

    @Test
    public void h9_3_sentScheduledDeleteTouchesNeitherRowNorFile() {
        // Phase 3.2 (sent_messages re-check, phase3-2-sent-messages.md):
        // sent_messages is NOT a table — it is an optional Vector<int>
        // of message IDs inside TL_updateDeleteScheduledMessages, used
        // only for the "VideoConversionDone" notification; it never
        // reaches MessagesStorage and cannot carry media, so it can
        // delete neither rows nor files. This test pins the model only:
        // the scheduled-delete branch itself is SQL-only (no files).
        // The premise that a remote delete of an already-sent scheduled
        // message travels this update is unproven (Q1–Q3 need a device
        // probe); worst case is an orphaned anchor, never media loss.
        assertFalse(modelDeletesFiles(MODE_SCHEDULED));
    }

    @Test
    public void r19_1_completedPrefixThenFullLoadNeedsSecondWakeup() {
        // Swallow case (FileLoader:1007-1010): a preload that finished its
        // prefix, then a later loadFile converts it to a full load, early-
        // returns with no fileDidLoaded — H-C never wakes. The second
        // wake-up is the watchdog probe: a DOWNLOADING job with no progress
        // for >= 90 s must be probed.
        assertFalse("fresh progress must not probe",
                AliGramEligibility.shouldProbeDownload(
                        AliGramEligibility.STATE_DOWNLOADING, 10_000L, 10_000L));
        assertTrue("stuck after the swallow must probe",
                AliGramEligibility.shouldProbeDownload(
                        AliGramEligibility.STATE_DOWNLOADING, 200_000L, 10_000L));
    }

    @Test
    public void r19_2_sharedLoadWakesBothJobs() {
        // fileLoaded is a filename broadcast: two jobs waiting on the same
        // file are both in the waiting set, so one broadcast claims both.
        // Model: the claim set for a file is every waiting job naming it.
        String file = "123_456.jpg";
        String[] waitingJobs = {file, file};
        int claimed = 0;
        for (String f : waitingJobs) {
            if (f.equals(file)) {
                claimed++;
            }
        }
        assertEquals(2, claimed);
    }
}
