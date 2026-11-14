package com.radolyn.ayugram.aligram;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * AliGram — pure, deterministic preservation policy logic.
 *
 * <p>This class has NO Android / Telegram dependencies so it can be unit-tested
 * on a plain JVM (see {@code AliGramEligibilityTest}). Every rule here is a
 * direct transcription of DESIGN-PHASE2-3 (which overrides Phase 2.2 on
 * conflict) and of phase3-investigations.md (H-9 / R-19 / R-A9):
 *
 * <ul>
 *   <li>Activation predicate is STRICT: {@code message.date > activationTime},
 *       never {@code >=} (Phase 2.3 §1.3). The single activation second is
 *       deliberately sacrificed: a genuinely new message created in that
 *       second is a known false negative, never a leak.</li>
 *   <li>Activation records are PENDING until STAMPED with a server time
 *       {@code > 0} proven by a sync event inside the current process
 *       (Phase 2.3 §1.5 + R-7: a restored {@code timeDifference} alone is
 *       never enough).</li>
 *   <li>Forwarded messages are eligible (interpretation A, Phase 2.3 §3):
 *       {@code message.date} on a forward is the forward time, so no
 *       special-case is needed — and none is implemented here.</li>
 *   <li>X (dialog/sender exclusion) gates binding/freezing only, never LIVE
 *       enrolment (Phase 2.3 §4.2 C-04).</li>
 *   <li>Terminal reasons use one vocabulary: {@code GATE_OFF} (gate named
 *       separately) for setting changes, {@code ANCHOR_LOST} for delete-event
 *       termination, never {@code OPT_OUT} vs {@code MASTER_OFF}.</li>
 * </ul>
 */
public final class AliGramEligibility {

    private AliGramEligibility() {
    }

    // ------------------------------------------------------------------
    // Budgets (Phase 2.2 §3.2 / P-12; numbers are the design's starting
    // point, not measured values).
    // ------------------------------------------------------------------
    public static final int MAX_PENDING_JOBS_PER_CHAT = 25;
    public static final long MAX_PENDING_BYTES_PER_CHAT = 500L * 1024L * 1024L;

    // Size caps (Phase 2.2 §4.1, preset strings in DownloadController).
    public static final long VIDEO_CAP_MOBILE = 10L * 1024L * 1024L;      // 10,485,760
    public static final long VIDEO_CAP_WIFI = 15L * 1024L * 1024L;        // 15,728,640
    public static final long DOCUMENT_CAP_MOBILE = 1L * 1024L * 1024L;    // 1,048,576
    public static final long DOCUMENT_CAP_WIFI = 3L * 1024L * 1024L;      // 3,145,728
    public static final long VOICE_FLOOR = 512L * 1024L;                  // 512 KiB
    public static final int MAX_COPY_ATTEMPTS = 3;
    /** Watchdog delay before the second wake-up (R-19 probe) fires. */
    public static final long WATCHDOG_DELAY_MS = 90_000L;

    public enum NetworkPreset {
        MOBILE, WIFI, ROAMING
    }

    public enum MediaClass {
        PHOTO, VIDEO, ROUND_VIDEO, GIF, DOCUMENT, VOICE, MUSIC,
        /** Downloadable but no file binding in v1 (mapMedia writes no mediaPath). */
        STICKER, WEBPAGE, STORY,
        /** Policy-excluded in v1 (D-08). */
        TTL_SECRET,
        UNSUPPORTED
    }

    public enum ActivationState {
        /** Flag on, no valid server timestamp yet — the H-I gate is closed. */
        PENDING,
        /** Valid server timestamp {@code > 0} recorded after a sync event. */
        STAMPED
    }

    /**
     * Which wording the preservation dialog shows (O-01, Phase 3.2).
     *
     * <p>The old dialog appended the "Preservation is ON…" text even while
     * {@link ActivationState#PENDING}, then added the pending notice — the
     * two sentences contradicted each other, and the positive button read
     * "Disable preservation" while nothing was being preserved yet.
     * Exactly one body text is shown now: the ON text only when STAMPED,
     * the pending notice alone while PENDING, the OFF text when opted out.
     */
    public enum PreservationDialogState {
        /** Opted out: show the OFF text, positive button enables. */
        DISABLED,
        /** Opted in but not yet stamped: show only the pending notice. */
        PENDING,
        /** Opted in and stamped: show the ON text, positive button disables. */
        ENABLED
    }

    /**
     * Pure dialog-state decision for
     * {@code ChatActivity.showAliGramPreservationDialogInternal}.
     *
     * <p>A {@code null} activation state is treated as PENDING (never show
     * the ON text without a proven stamp). The opt-out wins over the
     * activation state: a disabled chat is DISABLED even if a stale
     * STAMPED record is still on disk.
     */
    public static PreservationDialogState resolvePreservationDialogState(boolean enabled, ActivationState state) {
        if (!enabled) {
            return PreservationDialogState.DISABLED;
        }
        return state == ActivationState.STAMPED
                ? PreservationDialogState.ENABLED
                : PreservationDialogState.PENDING;
    }

    // ------------------------------------------------------------------
    // Activation boundary (Phase 2.3 §1).
    // ------------------------------------------------------------------

    /** Strict predicate: {@code date > activationTime}. Never {@code >=}. */
    public static boolean isAfterActivation(int messageDate, int activationTime) {
        return messageDate > activationTime;
    }

    /** A stamped activation time must be a real server second, never 0. */
    public static boolean isValidActivationTime(int activationTime) {
        return activationTime > 0;
    }

    /**
     * R-7 stamping decision (Phase 3.1 — the 30-second Connected-only
     * fallback was REMOVED because Connected alone cannot guarantee
     * trustworthy time). A PENDING activation may be stamped only when,
     * in this process, the connection is Connected AND at least one
     * server-originated round (getDifference / updates) has completed.
     * Either condition alone is insufficient: Connected can be set on the
     * first valid data before the handshake recomputes
     * {@code timeDifference}, and a completed update batch proves
     * nothing if it was synthesised locally. There is no fallback — a
     * PENDING activation simply stays PENDING (gate closed) until both
     * hold. See {@code ~/workspace/aligram/phase3-1-r7-note.md}.
     */
    public static boolean mayStampActivation(boolean connected, boolean serverRoundSeenInProcess) {
        return connected && serverRoundSeenInProcess;
    }

    /** The H-I gate is open only for a STAMPED record with a valid time. */
    public static boolean isGateOpen(ActivationState state, int activationTime) {
        return state == ActivationState.STAMPED && isValidActivationTime(activationTime);
    }

    // ------------------------------------------------------------------
    // Media classification (Phase 2.2 §4.2, corrected by Phase 2.3 §4).
    // Order matters: TTL/secret first, then story, sticker, webpage.
    // ------------------------------------------------------------------

    /**
     * Pure classification from boolean media facts. Callers pass the facts
     * they read off {@code TLRPC.Message} / {@code MessageObject} helpers.
     */
    public static MediaClass classifyMedia(boolean isPhoto, boolean isVideo, boolean isRoundVideo,
                                           boolean isGif, boolean isVoice, boolean isMusic,
                                           boolean isSticker, boolean isWebpage, boolean isStory,
                                           boolean isTtlOrSecret) {
        if (isTtlOrSecret) {
            return MediaClass.TTL_SECRET;
        }
        if (isStory) {
            return MediaClass.STORY;
        }
        if (isSticker) {
            return MediaClass.STICKER;
        }
        if (isWebpage) {
            return MediaClass.WEBPAGE;
        }
        if (isVoice) {
            return MediaClass.VOICE;
        }
        if (isMusic) {
            return MediaClass.MUSIC;
        }
        if (isRoundVideo) {
            return MediaClass.ROUND_VIDEO;
        }
        if (isGif) {
            return MediaClass.GIF;
        }
        if (isVideo) {
            return MediaClass.VIDEO;
        }
        if (isPhoto) {
            return MediaClass.PHOTO;
        }
        return MediaClass.UNSUPPORTED;
    }

    /** v1 preserved set: photos, video/round/GIF within caps, small documents, voice (deletes only). */
    public static boolean isPreservableClass(MediaClass mediaClass) {
        switch (mediaClass) {
            case PHOTO:
            case VIDEO:
            case ROUND_VIDEO:
            case GIF:
            case DOCUMENT:
            case VOICE:
            case MUSIC:
                return true;
            default:
                return false;
        }
    }

    /** Size cap in bytes for a class under a network preset. -1 = no size check (photo). */
    public static long sizeCapBytes(MediaClass mediaClass, NetworkPreset preset) {
        switch (mediaClass) {
            case PHOTO:
                return -1;
            case VIDEO:
            case ROUND_VIDEO:
            case GIF:
                return preset == NetworkPreset.WIFI ? VIDEO_CAP_WIFI : VIDEO_CAP_MOBILE;
            case VOICE: {
                long docCap = preset == NetworkPreset.WIFI ? DOCUMENT_CAP_WIFI : DOCUMENT_CAP_MOBILE;
                return Math.max(VOICE_FLOOR, docCap);
            }
            case DOCUMENT:
            case MUSIC:
                return preset == NetworkPreset.WIFI ? DOCUMENT_CAP_WIFI : DOCUMENT_CAP_MOBILE;
            default:
                return 0;
        }
    }

    /** {@code size} within cap; {@code size < 0} (unknown) fails closed except photos. */
    public static boolean isWithinSizeCap(MediaClass mediaClass, long size, NetworkPreset preset) {
        long cap = sizeCapBytes(mediaClass, preset);
        if (cap < 0) {
            return true; // photo: no size check
        }
        if (cap == 0) {
            return false;
        }
        return size >= 0 && size <= cap;
    }

    // ------------------------------------------------------------------
    // Ingest / freeze / binding predicates (Phase 2.3 §4.3).
    // ------------------------------------------------------------------

    /**
     * H-I ingest: {@code P ∧ (D ∨ E) ∧ M/T ∧ ¬secret/TTL/once ∧ media-class ∧
     * size-cap ∧ ¬archived ∧ (channel ⇒ post) ∧ ¬roaming ∧ budget ∧
     * activation-boundary}. X / X_s / bot do NOT gate ingest.
     *
     * @param preExistingRow true if a messages_v2 row existed before this ingest
     */
    public static boolean canIngest(boolean optedIn, boolean deleteMasterOn, boolean editMasterOn,
                                    boolean saveMediaOn, boolean isSecretTtlOnce,
                                    boolean mediaClassOk, boolean sizeOk, boolean isArchived,
                                    boolean isChannelPostOk, boolean isRoaming,
                                    boolean withinBudget, int messageDate, int activationTime,
                                    ActivationState activationState, boolean preExistingRow) {
        if (!optedIn) {
            return false;
        }
        if (!deleteMasterOn && !editMasterOn) {
            return false; // (D ∨ E): both off ⇒ no axis could ever consume the blob
        }
        if (!saveMediaOn) {
            return false;
        }
        if (isSecretTtlOnce) {
            return false;
        }
        if (!mediaClassOk || !sizeOk) {
            return false;
        }
        if (isArchived) {
            return false;
        }
        if (!isChannelPostOk) {
            return false;
        }
        if (isRoaming) {
            return false;
        }
        if (!withinBudget) {
            return false;
        }
        if (!isGateOpen(activationState, activationTime)) {
            return false;
        }
        if (!isAfterActivation(messageDate, activationTime)) {
            return false;
        }
        if (preExistingRow) {
            return false;
        }
        return true;
    }

    /**
     * H-E freeze: {@code legacy-revision-created ∧ P ∧ E ∧ ¬X(dialog) ∧
     * ¬X(sender) ∧ old-media-eligible ∧ old-media-is-preservable-file}.
     * Bot rules do NOT gate the freeze.
     */
    public static boolean canFreeze(boolean legacyRevisionCreated, boolean optedIn,
                                    boolean editMasterOn, boolean excludedDialog,
                                    boolean excludedSender, boolean oldMediaEligible,
                                    boolean oldMediaPreservableFile) {
        return legacyRevisionCreated && optedIn && editMasterOn
                && !excludedDialog && !excludedSender
                && oldMediaEligible && oldMediaPreservableFile;
    }

    /** H-D binding: {@code D ∧ ¬X(dialog) ∧ ¬X(sender) ∧ bot-ok}. P/M-T not re-checked (P-3). */
    public static boolean canBindDeleted(boolean deleteMasterOn, boolean excludedDialog,
                                         boolean excludedSender, boolean botOk) {
        return deleteMasterOn && !excludedDialog && !excludedSender && botOk;
    }

    /**
     * New-media enrolment at edit time (Phase 2.3 §4.3): like ingest, plus
     * {@code edit_date >= activationTime}; absent/0 edit_date ⇒ no enrolment
     * (fail-safe, log {@code EDIT_DATE_UNKNOWN}). Note the design writes
     * {@code >=} here (not the strict {@code >} of the H-I date rule); that
     * asymmetry is the design's own wording and is preserved verbatim.
     */
    public static boolean canEnrolNewMediaAtEdit(boolean optedIn, boolean deleteMasterOn,
                                                 boolean editMasterOn, boolean saveMediaOn,
                                                 boolean isSecretTtlOnce, boolean mediaClassOk,
                                                 boolean sizeOk, int editDate, int activationTime,
                                                 ActivationState activationState,
                                                 boolean restrictedEditDiscard, boolean sameMedia) {
        if (restrictedEditDiscard || sameMedia) {
            return false;
        }
        if (!optedIn || (!deleteMasterOn && !editMasterOn) || !saveMediaOn) {
            return false;
        }
        if (isSecretTtlOnce || !mediaClassOk || !sizeOk) {
            return false;
        }
        if (editDate <= 0) {
            return false; // EDIT_DATE_UNKNOWN fail-safe
        }
        if (!isGateOpen(activationState, activationTime)) {
            return false;
        }
        return editDate >= activationTime;
    }

    /** Per-chat budget: 25 pending jobs / 500 MiB per chat (P-12). */
    public static boolean withinBudget(int pendingJobs, long pendingBytes, long newSize) {
        if (pendingJobs >= MAX_PENDING_JOBS_PER_CHAT) {
            return false;
        }
        return pendingBytes + Math.max(0, newSize) <= MAX_PENDING_BYTES_PER_CHAT;
    }

    // ------------------------------------------------------------------
    // Identity (Phase 2.2 §1.6/§6.2, Phase 2.3 §2).
    // ------------------------------------------------------------------

    /**
     * Content-addressed blob id: {@code d<docId>_<dcId>_<size>_<sha16>} or
     * {@code p<photoId>_…}. A zero document/photo id falls back to sha-only
     * identity — never key a blob on a zero id.
     */
    public static String blobIdFor(long documentId, long photoId, int dcId, long size, String sha256Hex) {
        String shaPart = sha256Hex == null ? "n_sha" : sha256Hex.substring(0, Math.min(16, sha256Hex.length()));
        if (documentId != 0) {
            return String.format(Locale.US, "d%d_%d_%d_%s", documentId, dcId, size, shaPart);
        }
        if (photoId != 0) {
            return String.format(Locale.US, "p%d_%d_%d_%s", photoId, dcId, size, shaPart);
        }
        return String.format(Locale.US, "s%d_%s", size, shaPart);
    }

    /**
     * Phase 3.1 (D-05): account-scoped blob id. Blob files live in
     * per-account directories, but the blob id is the primary key — so a
     * global id let two accounts share one row pointing at a file that
     * exists in only one account's directory (the second account's ref
     * then rendered nothing and degraded to UNAVAILABLE at startup, and
     * a purge of the first account deleted the file under the second
     * account's ref). Account 0 keeps the legacy id (existing installs'
     * rows stay valid); other accounts get an {@code a<N>_} prefix, so
     * the same media in two accounts is two rows in two directories.
     * The 5-arg form above is unchanged (its format is pinned by tests).
     */
    public static String blobIdFor(int accountId, long documentId, long photoId, int dcId,
                                   long size, String sha256Hex) {
        String base = blobIdFor(documentId, photoId, dcId, size, sha256Hex);
        return accountId == 0 ? base : "a" + accountId + "_" + base;
    }

    /** SHA-256 hex of bytes (single-pass helper used by tests and the copy path). */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Ref state machine (Phase 2.2 §6.6, Phase 2.3 §2.2/§4.5).
    // ------------------------------------------------------------------

    public static final String STATE_ENQUEUED = "ENQUEUED";
    public static final String STATE_DOWNLOADING = "DOWNLOADING";
    public static final String STATE_PINNED = "PINNED";
    public static final String STATE_COPYING = "COPYING";
    public static final String STATE_COPIED = "COPIED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATE_CANCELLED = "CANCELLED";
    public static final String STATE_GATE_OFF = "GATE_OFF";
    public static final String STATE_SWEPT = "SWEPT";

    /** Terminal states: the delete gate releases only after one of these commits. */
    public static boolean isTerminalState(String state) {
        return STATE_COPIED.equals(state) || STATE_FAILED.equals(state) || STATE_UNAVAILABLE.equals(state)
                || STATE_CANCELLED.equals(state) || STATE_GATE_OFF.equals(state) || STATE_SWEPT.equals(state);
    }

    /** Active pin states that block a delete (L1/L2 consult these). */
    public static boolean isActivePinState(String state) {
        return STATE_PINNED.equals(state) || STATE_COPYING.equals(state)
                || STATE_DOWNLOADING.equals(state) || STATE_ENQUEUED.equals(state);
    }

    /** One terminal-reason vocabulary (Phase 2.3 §4.5). */
    public static final String REASON_SOURCE_GONE = "SOURCE_GONE";
    public static final String REASON_SIZE_MISMATCH = "SIZE_MISMATCH";
    public static final String REASON_NO_SPACE = "NO_SPACE";
    public static final String REASON_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";
    public static final String REASON_STUCK = "STUCK";
    public static final String REASON_GATE_OFF = "GATE_OFF";
    public static final String REASON_ANCHOR_LOST = "ANCHOR_LOST";
    public static final String REASON_PRE_ACTIVATION_MEDIA = "PRE_ACTIVATION_MEDIA";
    public static final String REASON_NO_PIN_SITE = "NO_PIN_SITE";
    /**
     * Phase 3.1 (D-11): a PINNED freeze pin whose source never
     * materialised and cannot be re-downloaded after a restart — the old
     * TL object and the PendingLoad died with the process, and the
     * re-drive rebuilds the NEW message, not the old media (R-08).
     * Distinct from SOURCE_GONE (the source existed and vanished).
     */
    public static final String REASON_NO_PIN_SOURCE = "NO_PIN_SOURCE";
    public static final String REASON_EDIT_DATE_UNKNOWN = "EDIT_DATE_UNKNOWN";

    // ------------------------------------------------------------------
    // Deferred-delete release (Phase 2.3 §2.2(c), hole H-6).
    // ------------------------------------------------------------------

    public enum ReleaseDecision {
        /** File matches the pin — delete it through the normal path. */
        DELETE,
        /** File is gone already — mark DONE, nothing to delete. */
        ALREADY_GONE,
        /** Size/mtime mismatch — the path was reused; mark DONE, do NOT delete. */
        KEEP_MISMATCH
    }

    /**
     * Release decision for a deferred delete. The path is deleted only if
     * it still is the pinned file (same size AND mtime); a reused path is
     * left alone (a cache file; normal eviction manages it).
     */
    public static ReleaseDecision decideRelease(boolean fileExists, long currentSize, long currentMtime,
                                                long sizeAtPin, long mtimeAtPin) {
        if (!fileExists) {
            return ReleaseDecision.ALREADY_GONE;
        }
        if (currentSize != sizeAtPin || currentMtime != mtimeAtPin) {
            return ReleaseDecision.KEEP_MISMATCH;
        }
        return ReleaseDecision.DELETE;
    }

    /** Attempts budget: 3 failed re-drives → FAILED → release (Phase 2.3 §2.2(e)). */
    public static boolean attemptsExhausted(int attempts) {
        return attempts >= MAX_COPY_ATTEMPTS;
    }

    /** Next revisionSeq: monotonic per message, EDIT rows only (LIVE rows: 0). */
    public static int nextRevisionSeq(int maxExistingRevisionSeq) {
        return maxExistingRevisionSeq + 1;
    }

    /** Watchdog probe rule (R-19): fire only for a stuck DOWNLOADING job. */
    public static boolean shouldProbeDownload(String state, long nowMs, long lastProgressMs) {
        return STATE_DOWNLOADING.equals(state) && (nowMs - lastProgressMs) >= WATCHDOG_DELAY_MS;
    }
}
