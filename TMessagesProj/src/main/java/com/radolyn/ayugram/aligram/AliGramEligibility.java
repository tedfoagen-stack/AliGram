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
    // Adjustable limits (2026-11-15): user settings from the AliGram
    // section of the Experimental settings screen. The legacy constants
    // above stay the defaults; a setting of 0 means "untouched" and
    // reproduces today's per-network caps exactly. All resolution here
    // is pure (no Android / SharedPreferences) — the settings are read
    // in AliGramPreservation.limits() and passed in as a Limits value.
    // ------------------------------------------------------------------

    /** Hard ceiling for a user-chosen video cap, in MB. */
    public static final int VIDEO_CAP_CUSTOM_MAX_MB = 100;
    /** Minimum for a user-chosen video cap, in MB. */
    public static final int VIDEO_CAP_CUSTOM_MIN_MB = 1;
    /** Hard ceiling for a user-chosen document cap, in MB (selector max). */
    public static final int DOCUMENT_CAP_MAX_MB = 50;

    /**
     * Effective preservation limits resolved from user settings.
     *
     * <ul>
     *   <li>{@code videoCapMb}: preset video cap in MB, applied to BOTH
     *       networks; 0 = untouched (legacy 10 MiB mobile / 15 MiB Wi-Fi).</li>
     *   <li>{@code videoCapCustomMb}: free-form video cap in MB; 0 = unset.
     *       Only 1..100 is honored; anything else is ignored (a valid
     *       custom value wins over the preset).</li>
     *   <li>{@code docCapMb}: preset document cap in MB, both networks;
     *       0 = untouched (legacy 1 MiB mobile / 3 MiB Wi-Fi).</li>
     *   <li>{@code maxPendingJobs}: max pending jobs per chat (default 25).</li>
     *   <li>{@code maxPendingBytesMb}: max pending bytes per chat, in MB
     *       (default 500).</li>
     * </ul>
     */
    public static final class Limits {
        public final int videoCapMb;
        public final int videoCapCustomMb;
        public final int docCapMb;
        public final int maxPendingJobs;
        public final int maxPendingBytesMb;

        public Limits(int videoCapMb, int videoCapCustomMb, int docCapMb,
                      int maxPendingJobs, int maxPendingBytesMb) {
            this.videoCapMb = videoCapMb;
            this.videoCapCustomMb = videoCapCustomMb;
            this.docCapMb = docCapMb;
            this.maxPendingJobs = maxPendingJobs;
            this.maxPendingBytesMb = maxPendingBytesMb;
        }

        /** Defaults reproduce today's behavior exactly. */
        public static Limits defaults() {
            return new Limits(0, 0, 0, MAX_PENDING_JOBS_PER_CHAT,
                    (int) (MAX_PENDING_BYTES_PER_CHAT / (1024L * 1024L)));
        }
    }

    /** Custom video cap clamp: 0/negative = unset; otherwise 1..100. */
    public static int clampCustomVideoCapMb(int rawMb) {
        if (rawMb < VIDEO_CAP_CUSTOM_MIN_MB) {
            return 0;
        }
        return Math.min(rawMb, VIDEO_CAP_CUSTOM_MAX_MB);
    }

    /**
     * Effective video cap in MB: custom (1..100) wins, then preset
     * (clamped to 1..100), else 0 = legacy per-network caps.
     */
    public static int resolveVideoCapMb(Limits limits) {
        int custom = clampCustomVideoCapMb(limits.videoCapCustomMb);
        if (custom > 0) {
            return custom;
        }
        if (limits.videoCapMb > 0) {
            return Math.min(Math.max(limits.videoCapMb, VIDEO_CAP_CUSTOM_MIN_MB), VIDEO_CAP_CUSTOM_MAX_MB);
        }
        return 0;
    }

    /** Effective document cap in MB: preset clamped to 1..50, else 0 = legacy. */
    public static int resolveDocCapMb(Limits limits) {
        if (limits.docCapMb <= 0) {
            return 0;
        }
        return Math.min(Math.max(limits.docCapMb, 1), DOCUMENT_CAP_MAX_MB);
    }

    /** Effective video cap in bytes for a network preset under these limits. */
    public static long videoCapBytes(NetworkPreset preset, Limits limits) {
        int mb = resolveVideoCapMb(limits);
        if (mb == 0) {
            return preset == NetworkPreset.WIFI ? VIDEO_CAP_WIFI : VIDEO_CAP_MOBILE;
        }
        return (long) mb * 1024L * 1024L;
    }

    /** Effective document cap in bytes for a network preset under these limits. */
    public static long docCapBytes(NetworkPreset preset, Limits limits) {
        int mb = resolveDocCapMb(limits);
        if (mb == 0) {
            return preset == NetworkPreset.WIFI ? DOCUMENT_CAP_WIFI : DOCUMENT_CAP_MOBILE;
        }
        return (long) mb * 1024L * 1024L;
    }

    /** Size cap in bytes for a class under a network preset and user limits. -1 = no size check (photo). */
    public static long sizeCapBytes(MediaClass mediaClass, NetworkPreset preset, Limits limits) {
        switch (mediaClass) {
            case PHOTO:
                return -1;
            case VIDEO:
            case ROUND_VIDEO:
            case GIF:
                return videoCapBytes(preset, limits);
            case VOICE: {
                long docCap = docCapBytes(preset, limits);
                return Math.max(VOICE_FLOOR, docCap);
            }
            case DOCUMENT:
            case MUSIC:
                return docCapBytes(preset, limits);
            default:
                return 0;
        }
    }

    /** {@code size} within cap under user limits; {@code size < 0} (unknown) fails closed except photos. */
    public static boolean isWithinSizeCap(MediaClass mediaClass, long size, NetworkPreset preset, Limits limits) {
        long cap = sizeCapBytes(mediaClass, preset, limits);
        if (cap < 0) {
            return true; // photo: no size check
        }
        if (cap == 0) {
            return false;
        }
        return size >= 0 && size <= cap;
    }

    /** Per-chat budget under user limits: max pending jobs / max pending bytes. */
    public static boolean withinBudget(int pendingJobs, long pendingBytes, long newSize, Limits limits) {
        int maxJobs = limits.maxPendingJobs > 0 ? limits.maxPendingJobs : MAX_PENDING_JOBS_PER_CHAT;
        long maxBytes = limits.maxPendingBytesMb > 0
                ? (long) limits.maxPendingBytesMb * 1024L * 1024L
                : MAX_PENDING_BYTES_PER_CHAT;
        if (pendingJobs >= maxJobs) {
            return false;
        }
        return pendingBytes + Math.max(0, newSize) <= maxBytes;
    }

    // ------------------------------------------------------------------
    // Ingest / freeze / binding predicates (Phase 2.3 §4.3).
    // ------------------------------------------------------------------

    /**
     * H-I ingest: {@code P ∧ (D ∨ E) ∧ M/T ∧ ¬secret/TTL/once ∧ media-class ∧
     * size-cap ∧ (channel ⇒ post) ∧ ¬roaming ∧ budget ∧
     * activation-boundary}. X / X_s / bot do NOT gate ingest.
     *
     * <p>DOMINANCE (2026-11-21): there is deliberately NO archive /
     * folder / mute parameter — an opted-in dialog preserves regardless
     * of Archive folder, custom folder, or mute. The M/T argument is
     * resolved by the caller: for an opted-in dialog
     * {@code AliGramPreservation.saveMediaEnabledForDialog} returns true
     * no matter what the Message Saving toggles say, so a veto there can
     * never silently block an opted-in dialog (for a non-opted-in dialog
     * the toggles still apply). Global Automatic media download
     * (downloadMask) is likewise never an input here.
     *
     * <p>Roaming blocks LIVE ingest only as a PAUSE: no ref is created,
     * so the startup / post-stamp sweep re-attempts the message when the
     * network is normal again — it is never a permanent failure.
     *
     * @param preExistingRow true if a messages_v2 row existed before this ingest.
     *                       The startup sweep passes false: the sweep replaces
     *                       this gate with its own no-LIVE-ref check, so a
     *                       first-ingest failure is re-driven, not permanent.
     */
    public static boolean canIngest(boolean optedIn, boolean deleteMasterOn, boolean editMasterOn,
                                    boolean saveMediaOn, boolean isSecretTtlOnce,
                                    boolean mediaClassOk, boolean sizeOk,
                                    boolean isChannelPostOk, boolean isRoaming,
                                    boolean withinBudget, int messageDate, int activationTime,
                                    ActivationState activationState, boolean preExistingRow) {
        return ingestRejectReason(optedIn, deleteMasterOn, editMasterOn, saveMediaOn, isSecretTtlOnce,
                mediaClassOk, sizeOk, isChannelPostOk, isRoaming, withinBudget,
                messageDate, activationTime, activationState, preExistingRow) == null;
    }

    /**
     * First failing ingest gate, or {@code null} when ingest is allowed.
     * Gate names are stable strings for the per-message rejection log in
     * {@code AliGramPreservation.maybeIngest}: only dialog/message ids are
     * ever logged alongside them — never media content, tokens, or paths.
     */
    public static String ingestRejectReason(boolean optedIn, boolean deleteMasterOn, boolean editMasterOn,
                                            boolean saveMediaOn, boolean isSecretTtlOnce,
                                            boolean mediaClassOk, boolean sizeOk,
                                            boolean isChannelPostOk, boolean isRoaming,
                                            boolean withinBudget, int messageDate, int activationTime,
                                            ActivationState activationState, boolean preExistingRow) {
        if (!optedIn) {
            return "OPTED_OUT";
        }
        if (!deleteMasterOn && !editMasterOn) {
            return "MASTERS_OFF";
        }
        if (!saveMediaOn) {
            return "SAVE_MEDIA_OFF";
        }
        if (isSecretTtlOnce) {
            return "SECRET_TTL";
        }
        if (!mediaClassOk || !sizeOk) {
            return !mediaClassOk ? "MEDIA_CLASS" : "SIZE_CAP";
        }
        if (!isChannelPostOk) {
            return "CHANNEL_POST";
        }
        if (isRoaming) {
            return "ROAMING_PAUSED";
        }
        if (!withinBudget) {
            return "BUDGET";
        }
        if (!isGateOpen(activationState, activationTime)) {
            return "GATE_CLOSED";
        }
        if (!isAfterActivation(messageDate, activationTime)) {
            return "BEFORE_ACTIVATION";
        }
        if (preExistingRow) {
            return "PRE_EXISTING";
        }
        return null;
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

    // ------------------------------------------------------------------
    // Preserve Media DOMINANCE + retry-until-success (2026-11-21).
    //
    // Ali's requirement: any channel/group with Preserve Media ON must
    // download + preserve media regardless of Archive folder, custom
    // folder, mute, channel type (broadcast/megagroup/private/public),
    // global Automatic media download OFF, or the Message Saving
    // toggles. The dominance is enforced at the CALLER
    // (AliGramPreservation.saveMediaEnabledForDialog returns true for an
    // opted-in dialog, and the Archive/post plumbing was removed from
    // the MessagesStorage hook entirely) — this class only carries the
    // pure rules that make those caller decisions testable:
    //   * canIngest has NO archive/folder/mute input at all: there is
    //     no parameter a caller could pass to re-enable the gate.
    //   * isChannelPostOk: only a KNOWN broadcast channel with
    //     post==false is excluded. Megagroup messages (post==false by
    //     construction) pass; an unknown chat passes (dominance favours
    //     preservation — in practice Telegram sets post=true on every
    //     broadcast post, so the exclusion is defensive-only).
    //   * effectiveActivationTime: the PENDING-window fix. The stamp is
    //     server time at stamp moment, so a message that arrived after
    //     the user enabled preservation but before the stamp has
    //     date <= stamp and would be lost by the strict '>' rule. The
    //     enable-moment server estimate (recorded by
    //     setPreservationEnabled) is the effective boundary when it is
    //     positive and earlier than the stamp; otherwise the stamp.
    //     The estimate can only be wrong by timeDifference staleness
    //     (typically seconds) — the same class of error the stamp
    //     itself already accepts.
    //   * Download retry: a failed download retries with exponential
    //     backoff (60s, 2m, 4m, 8m, 16m, 32m, then 60m) up to 24
    //     attempts (~a day of retries incl. startup re-drives); only
    //     then does it go terminal FAILED. Roaming never counts as an
    //     attempt: it pauses the retry (re-check after
    //     ROAMING_RETRY_DELAY_MS), it never fails the job.
    // Legitimate exceptions that still block: secret/TTL/once media
    // (v1 exclusion, by design) and Ali's own AliGram caps (video cap,
    // per-chat file count, storage budget). Nothing else.
    // ------------------------------------------------------------------

    /** Sweep bound: at most this many recent messages per opted-in dialog. */
    public static final int SWEEP_MAX_MESSAGES_PER_DIALOG = 50;
    /** Sweep bound: only messages from the last 7 days are re-driven. */
    public static final int SWEEP_RECENT_WINDOW_SECONDS = 7 * 24 * 3600;
    /** Download-failure retry ceiling (replaces the old 3-attempt death). */
    public static final int MAX_DOWNLOAD_ATTEMPTS = 24;
    /** First download-retry delay; doubles per attempt, capped at 1h. */
    public static final long DOWNLOAD_RETRY_BASE_DELAY_MS = 60_000L;
    /** Download-retry delay cap (also the steady-state retry interval). */
    public static final long DOWNLOAD_RETRY_MAX_DELAY_MS = 3_600_000L;
    /** Roaming pause: re-check after 15 minutes, never count an attempt. */
    public static final long ROAMING_RETRY_DELAY_MS = 15 * 60_000L;

    /**
     * Channel-post rule. {@code isKnownBroadcast} is true only when the
     * chat was resolved AND is a broadcast channel (not a megagroup).
     * Megagroup / unknown-chat / non-channel messages always pass.
     */
    public static boolean isChannelPostOk(boolean isKnownBroadcast, boolean post) {
        return !isKnownBroadcast || post;
    }

    /**
     * Effective activation boundary for the strict {@code date >}
     * rule: the enable-moment server estimate when it is positive and
     * earlier than the stamp, else the stamp itself.
     */
    public static int effectiveActivationTime(int stampTime, int enableServerTime) {
        if (enableServerTime > 0 && enableServerTime < stampTime) {
            return enableServerTime;
        }
        return stampTime;
    }

    /** Exponential backoff for download retries: 60s, 2m, 4m … capped at 1h. */
    public static long downloadRetryDelayMs(int attempts) {
        if (attempts < 1) {
            return DOWNLOAD_RETRY_BASE_DELAY_MS;
        }
        long delay = DOWNLOAD_RETRY_BASE_DELAY_MS << Math.min(attempts - 1, 6);
        return Math.min(delay, DOWNLOAD_RETRY_MAX_DELAY_MS);
    }

    /** Download attempts budget: 24 failed downloads → terminal FAILED. */
    public static boolean downloadAttemptsExhausted(int attempts) {
        return attempts >= MAX_DOWNLOAD_ATTEMPTS;
    }

    /**
     * Startup/sweep re-drive rule for terminal download failures: a
     * FAILED ref whose last error was a download failure and whose
     * download budget is not exhausted is re-driven (reset to ENQUEUED),
     * so a transient failure survives restarts. FAILED for any other
     * reason (SOURCE_GONE, NO_SPACE, …) is never revived here.
     */
    public static boolean shouldRedriveFailedDownload(String lastError, int attempts) {
        return REASON_DOWNLOAD_FAILED.equals(lastError) && !downloadAttemptsExhausted(attempts);
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
