package com.radolyn.ayugram.aligram;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.TextUtils;

import com.radolyn.ayugram.messages.AyuSavePreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.nextalone.nagram.NaConfig;

/**
 * AliGram preservation controller — the single entry point for the feature.
 *
 * <p>Authoritative design: DESIGN-PHASE2-3 (overrides Phase 2.2 on conflict),
 * with the Phase 3 investigation results (H-9 closed with no new hook;
 * R-19 second wake-up watchdog/probe; R-A9 / R-7: stamping requires a sync
 * event inside the current process, never a restored timeDifference alone).
 *
 * <p>Threading: the preservation Room DB is synchronous-DAO only and is
 * touched from background threads only (storage queue, globalQueue, the
 * dedicated {@code aligramCopyQueue}, fileLoaderQueue). UI-thread entry
 * points (toggle, settings) write SharedPreferences only and post DB work.
 * Every public hook catches {@code Throwable} at its own boundary: a
 * preservation failure never corrupts or blocks the original Telegram
 * operation — the revision is recorded UNAVAILABLE, never silently claimed.
 *
 * <p>Copy-only: the only file-moving code on this path is
 * {@link AliGramFileCopy}. No {@code moveOrCopyFile}, no rename of a
 * source, no copy-then-delete.
 */
public final class AliGramPreservation {

    private static final String TAG = "AliGram";

    // --- Per-account SharedPreferences keys (signed dialog id, never abs()) ---
    private static final String KEY_FLAG_PREFIX = "ayuPreserveMedia_";
    private static final String KEY_SINCE_PREFIX = "ayuPreserveMediaSince_";
    private static final String KEY_STATE_PREFIX = "ayuPreserveMediaState_";
    private static final String KEY_DEVICE_TIME_PREFIX = "ayuPreserveMediaDeviceTime_";
    private static final String STATE_PENDING = "PENDING";
    private static final String STATE_STAMPED = "STAMPED";
    private static final String STATE_OFF = "OFF";

    /** Watchdog probes for the R-19 second wake-up (max probes per job). */
    private static final int MAX_WATCHDOG_PROBES = 2;

    // --- Queues / mirrors / in-flight state (all process-local) ---
    /** Single-thread queue owning byte copies and the delete gate (Phase 2.3 §2.2). Never exposed. */
    private static final DispatchQueue copyQueue = new DispatchQueue("aligramCopyQueue");
    private static final ConcurrentHashMap<Integer, Boolean> startupDone = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> roundSeen = new ConcurrentHashMap<>();

    /** In-memory pin mirror: absolute path → refId (durable row is authoritative). */
    private static final ConcurrentHashMap<String, Long> pinnedPathMirror = new ConcurrentHashMap<>();
    /** File-name mirror for the UI-thread cancel filter (no DAO on the UI thread). */
    private static final ConcurrentHashMap<String, Long> pinnedNameMirror = new ConcurrentHashMap<>();

    /** In-flight H-I downloads, keyed by pendingKey(account, expectedFileName) — the H-C fan-out set. */
    private static final ConcurrentHashMap<String, PendingLoad> pendingLoads = new ConcurrentHashMap<>();

    /** Captured-delete notes, keyed {@code account|dialogId|messageId} (snapshot → mark ordering). */
    private static final ConcurrentHashMap<String, Long> capturedDeletes = new ConcurrentHashMap<>();

    /**
     * UI-safe render-resolution cache. Room forbids main-thread queries, and
     * the render path (AyuMessageHistory.createMessageObject) runs on the
     * UI thread — so resolution is a map lookup here, never a DAO call.
     * Populated on bind (copyQueue/globalQueue) and rebuilt at startup.
     */
    private static final ConcurrentHashMap<Long, File> resolvedBlobByFakeId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> liveBlobByMessage = new ConcurrentHashMap<>();

    private static String liveBlobKey(int account, long dialogId, int messageId) {
        return account + "|" + dialogId + "|" + messageId;
    }

    /** Record a newly COPIED blob file for UI-safe render resolution. */
    private static void noteCopiedFile(PreservedRef job, String blobId, String ext) {
        try {
            File file = AliGramFileCopy.blobFileFor(job.accountId, blobId, ext);
            if (!file.isFile()) {
                return;
            }
            if ("LIVE".equals(job.role)) {
                liveBlobByMessage.put(liveBlobKey(job.accountId, job.dialogId, job.messageId), file);
            }
            if (job.ayuFakeId != 0) {
                resolvedBlobByFakeId.put(job.ayuFakeId, file);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forgetResolvedFilesForRef(PreservedRef job) {
        try {
            if (job.ayuFakeId != 0) {
                resolvedBlobByFakeId.remove(job.ayuFakeId);
            }
            liveBlobByMessage.remove(liveBlobKey(job.accountId, job.dialogId, job.messageId));
        } catch (Throwable ignored) {
        }
    }

    private AliGramPreservation() {
    }

    // ==================================================================
    // Small value types
    // ==================================================================

    public static final class Activation {
        public final AliGramEligibility.ActivationState state;
        public final int time;

        public Activation(AliGramEligibility.ActivationState state, int time) {
            this.state = state;
            this.time = time;
        }
    }

    /** Media identity captured once from the TL object (the 1280 photo side — §1.7 trap). */
    public static final class MediaIdentity {
        public AliGramEligibility.MediaClass mediaClass = AliGramEligibility.MediaClass.UNSUPPORTED;
        public TLRPC.Document document;
        public TLRPC.Photo photo;
        public TLRPC.PhotoSize photoSize;
        public long documentId;
        public long photoId;
        public int dcId;
        public String expectedFileName;
        public long expectedSize;
        public String ext;
        public String mimeType;
        public int photoWidth;
        public int photoHeight;
        public String originalFileName;
        public boolean isSecretTtlOnce;
    }

    /** In-memory pending download (TL objects the watchdog probe / retry re-issues). */
    private static final class PendingLoad {
        TLRPC.Document document;
        TLRPC.PhotoSize photoSize;
        TLRPC.Photo photo;
        String parent;
        long dialogId;
        int messageId;
        volatile long lastProgressMs;
        volatile int probeCount;
    }

    // ==================================================================
    // Preferences / activation (Phase 2.3 §1 + R-7)
    // ==================================================================

    private static SharedPreferences prefsFor(int account) {
        return UserConfig.getInstance(account).getPreferences();
    }

    public static boolean isOptedIn(int account, long dialogId) {
        try {
            return prefsFor(account).getBoolean(KEY_FLAG_PREFIX + dialogId, false);
        } catch (Throwable e) {
            return false;
        }
    }

    public static Activation getActivation(int account, long dialogId) {
        try {
            SharedPreferences prefs = prefsFor(account);
            String state = prefs.getString(KEY_STATE_PREFIX + dialogId, STATE_OFF);
            int time = prefs.getInt(KEY_SINCE_PREFIX + dialogId, 0);
            AliGramEligibility.ActivationState parsed = STATE_STAMPED.equals(state)
                    ? AliGramEligibility.ActivationState.STAMPED
                    : AliGramEligibility.ActivationState.PENDING;
            return new Activation(parsed, time);
        } catch (Throwable e) {
            return new Activation(AliGramEligibility.ActivationState.PENDING, 0);
        }
    }

    /**
     * Toggle the per-chat opt-in. Enabling resets the activation record to
     * PENDING and tries to stamp immediately; the gate stays closed until a
     * server sync in this process proves the timestamp (R-7). Disabling
     * keeps the timestamp on disk but voids it, and cancels in-flight jobs
     * for the dialog, both halves (mark GATE_OFF + cancelLoadFile).
     */
    public static boolean setPreservationEnabled(int account, long dialogId, boolean enabled) {
        try {
            SharedPreferences prefs = prefsFor(account);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_FLAG_PREFIX + dialogId, enabled);
            if (enabled) {
                editor.putString(KEY_STATE_PREFIX + dialogId, STATE_PENDING);
                editor.apply();
                maybeStampPending(account);
            } else {
                editor.putString(KEY_STATE_PREFIX + dialogId, STATE_OFF);
                editor.apply();
                cancelJobsForDialog(account, dialogId, "P");
            }
            return enabled;
        } catch (Throwable e) {
            FileLog.e(TAG + ": setPreservationEnabled failed", e);
            return isOptedIn(account, dialogId);
        }
    }

    public static boolean togglePreservation(int account, long dialogId) {
        return setPreservationEnabled(account, dialogId, !isOptedIn(account, dialogId));
    }

    /**
     * PENDING → STAMPED transition. Requires, in this process: the
     * connection state is Connected AND at least one server-originated
     * round (getDifference / updates) has completed — R-7: never stamp
     * from a restored timeDifference alone, and never from Connected
     * alone. There is NO fallback: the 30 s Connected-only fallback was
     * REMOVED in Phase 3.1 because Connected cannot guarantee trustworthy
     * time (see ~/workspace/aligram/phase3-1-r7-note.md — Connected is
     * set on the first valid data on a generic connection,
     * ConnectionsManager.cpp:885, which can precede the handshake that
     * recomputes timeDifference, and reconnects reuse sessions without
     * any new handshake). If the conditions are not met the activation
     * simply stays PENDING and preservation stays disabled — a late
     * activation is safe, an early one enrols pre-activation messages.
     * Never accepts A ≤ 0; never uses device time as a fallback
     * (device time is stored for diagnostics only).
     */
    public static void maybeStampPending(int account) {
        try {
            boolean connected = false;
            try {
                connected = ConnectionsManager.getInstance(account).getConnectionState()
                        == ConnectionsManager.ConnectionStateConnected;
            } catch (Throwable ignored) {
            }
            boolean round = Boolean.TRUE.equals(roundSeen.get(account));
            if (AliGramEligibility.mayStampActivation(connected, round)) {
                stampPendingNow(account);
                return;
            }
            // No fallback exists any more: staying PENDING is the safe
            // state. Log why, so a stuck PENDING is diagnosable.
            if (connected && !round) {
                FileLog.d(TAG + ": activation stays PENDING — Connected but no server-originated round yet in this process (R-7, no fallback)");
            } else if (!connected) {
                FileLog.d(TAG + ": activation stays PENDING — not Connected (R-7)");
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": maybeStampPending failed", e);
        }
    }

    private static void stampPendingNow(int account) {
        SharedPreferences prefs = prefsFor(account);
        Map<String, ?> all = prefs.getAll();
        List<String> pendingKeys = new ArrayList<>();
        for (String key : all.keySet()) {
            if (key.startsWith(KEY_STATE_PREFIX) && STATE_PENDING.equals(all.get(key))) {
                pendingKeys.add(key);
            }
        }
        if (pendingKeys.isEmpty()) {
            return;
        }
        int serverNow;
        try {
            serverNow = ConnectionsManager.getInstance(account).getCurrentTime();
        } catch (Throwable e) {
            return;
        }
        if (serverNow <= 0) {
            FileLog.d(TAG + ": refusing to stamp A<=0, staying PENDING");
            return;
        }
        int deviceNow = (int) (System.currentTimeMillis() / 1000);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : pendingKeys) {
            String dialogPart = key.substring(KEY_STATE_PREFIX.length());
            editor.putInt(KEY_SINCE_PREFIX + dialogPart, serverNow);
            editor.putString(KEY_STATE_PREFIX + dialogPart, STATE_STAMPED);
            editor.putLong(KEY_DEVICE_TIME_PREFIX + dialogPart, deviceNow);
        }
        editor.apply();
        FileLog.d(TAG + ": stamped " + pendingKeys.size() + " pending activation(s) at server time "
                + serverNow);
    }

    /**
     * Called from the end of {@code MessagesController.processUpdateArray}
     * for SERVER-ORIGINATED batches only (the call site gates on
     * {@code fromGetDifference || date != 0}): a completed getDifference /
     * updates round proves, in this process, that the session is live and
     * the server accepted our clock (a wrong timeDifference would be
     * rejected with bad_msg_id / bad_server_salt and corrected by the
     * native handlers). Locally synthesised batches (read-state updates
     * built from cache, {@code date == 0} and not from getDifference) do
     * NOT call this — they prove no server contact and must never set
     * {@code roundSeen} (Phase 3.1 hardening of the R-7 signal; see
     * phase3-1-r7-note.md §4).
     */
    public static void onServerRoundCompleted(int account) {
        try {
            roundSeen.put(account, Boolean.TRUE);
            maybeStampPending(account);
        } catch (Throwable e) {
            FileLog.e(TAG + ": onServerRoundCompleted failed", e);
        }
    }

    // ==================================================================
    // Masters / M-T / network / scope
    // ==================================================================

    public static boolean deleteMasterOn() {
        try {
            return NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean editMasterOn() {
        try {
            return NaConfig.INSTANCE.getEnableSaveEditsHistory().Bool();
        } catch (Throwable e) {
            return false;
        }
    }

    /** M/T-only semantics (AyuMessageUtils prefs overload, without the D gate). */
    public static boolean saveMediaEnabledForDialog(int account, long dialogId) {
        try {
            if (!NaConfig.INSTANCE.getMessageSavingSaveMedia().Bool()) {
                return false;
            }
            if (DialogObject.isUserDialog(dialogId)) {
                return NaConfig.INSTANCE.getSaveMediaInPrivateChats().Bool();
            }
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(Math.abs(dialogId));
            if (chat == null) {
                return true;
            }
            boolean isPublic = ChatObject.isPublic(chat);
            if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                if (isPublic) {
                    return NaConfig.INSTANCE.getSaveMediaInPublicChannels().Bool();
                }
                return NaConfig.INSTANCE.getSaveMediaInPrivateChannels().Bool();
            } else if (isPublic) {
                return NaConfig.INSTANCE.getSaveMediaInPublicGroups().Bool();
            } else {
                return NaConfig.INSTANCE.getSaveMediaInPrivateGroups().Bool();
            }
        } catch (Throwable e) {
            return false;
        }
    }

    /** v1 scope is groups/channels only. */
    public static boolean isGroupOrChannel(int account, long dialogId) {
        try {
            return MessagesController.getInstance(account).getChat(Math.abs(dialogId)) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isRoaming(int account) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isRoaming();
        } catch (Throwable e) {
            return false;
        }
    }

    public static AliGramEligibility.NetworkPreset currentPreset(int account) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.getType() == ConnectivityManager.TYPE_WIFI) {
                    return AliGramEligibility.NetworkPreset.WIFI;
                }
            }
        } catch (Throwable ignored) {
        }
        return AliGramEligibility.NetworkPreset.MOBILE;
    }

    /**
     * Effective preservation limits from the global AliGram settings
     * (NaConfig; Experimental settings → AliGram section). Never throws:
     * an unreadable or malformed value falls back to today's defaults
     * (see {@link AliGramEligibility.Limits#defaults()}). The custom video
     * cap is a free-form string; only 1..100 is honored.
     */
    public static AliGramEligibility.Limits limits() {
        int videoCapMb = 0;
        int videoCapCustomMb = 0;
        int docCapMb = 0;
        int maxJobs = 0;
        int budgetMb = 0;
        try {
            videoCapMb = NaConfig.INSTANCE.getAliGramVideoCapMb().Int();
            docCapMb = NaConfig.INSTANCE.getAliGramDocCapMb().Int();
            maxJobs = NaConfig.INSTANCE.getAliGramMaxFilesPerChat().Int();
            budgetMb = NaConfig.INSTANCE.getAliGramBudgetMbPerChat().Int();
            String custom = NaConfig.INSTANCE.getAliGramVideoCapCustomMb().String();
            if (custom != null) {
                try {
                    videoCapCustomMb = Integer.parseInt(custom.trim());
                } catch (NumberFormatException ignored) {
                    videoCapCustomMb = 0;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return new AliGramEligibility.Limits(videoCapMb, videoCapCustomMb, docCapMb, maxJobs, budgetMb);
    }

    public static long ownUserId(int account) {
        try {
            return UserConfig.getInstance(account).getClientUserId();
        } catch (Throwable e) {
            return 0;
        }
    }

    // ==================================================================
    // Media identity (Phase 2.2 §1.7/§4, Phase 2.3 §3)
    // ==================================================================

    public static MediaIdentity identifyMedia(int account, TLRPC.Message message) {
        MediaIdentity out = new MediaIdentity();
        if (message == null) {
            return out;
        }
        long dialogId = MessageObject.getDialogId(message);
        boolean ttl = (message.media != null && message.media.ttl_seconds != 0)
                || message.ttl_period != 0
                || DialogObject.isEncryptedDialog(dialogId);
        out.isSecretTtlOnce = ttl;
        boolean isStory = message.media instanceof TLRPC.TL_messageMediaStory;
        boolean isWebpage = message.media instanceof TLRPC.TL_messageMediaWebPage;
        boolean isSticker = MessageObject.isStickerMessage(message) || MessageObject.isAnimatedStickerMessage(message);
        boolean isVoice = MessageObject.isVoiceMessage(message);
        boolean isMusic = MessageObject.isMusicMessage(message);
        boolean isRound = MessageObject.isRoundVideoMessage(message);
        boolean isGif = MessageObject.isGifMessage(message);
        boolean isVideo = MessageObject.isVideoMessage(message);
        TLRPC.Document document = MessageObject.getDocument(message);
        TLRPC.Photo photo = MessageObject.getPhoto(message);
        boolean isPhoto = photo != null;
        out.mediaClass = AliGramEligibility.classifyMedia(isPhoto, isVideo, isRound, isGif, isVoice,
                isMusic, isSticker, isWebpage, isStory, ttl);
        if (isStory || isWebpage) {
            return out; // excluded in v1; no file binding exists for these classes
        }
        if (isSticker || document == null && photo == null) {
            if (document != null) {
                fillDocumentIdentity(out, document);
            }
            return out;
        }
        if (document != null && (isVoice || isMusic || isRound || isGif || isVideo || !isPhoto)) {
            fillDocumentIdentity(out, document);
            return out;
        }
        if (photo != null) {
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
            if (photoSize == null) {
                out.mediaClass = AliGramEligibility.MediaClass.UNSUPPORTED;
                return out;
            }
            out.photo = photo;
            out.photoSize = photoSize;
            out.photoId = photo.id;
            out.dcId = photoSize.location != null ? photoSize.location.dc_id : 0;
            out.expectedFileName = FileLoader.getAttachFileName(photoSize);
            out.expectedSize = photoSize.size;
            out.photoWidth = photoSize.w;
            out.photoHeight = photoSize.h;
            out.ext = extensionOf(out.expectedFileName);
            out.originalFileName = out.expectedFileName;
            return out;
        }
        if (document != null) {
            fillDocumentIdentity(out, document);
        }
        return out;
    }

    private static void fillDocumentIdentity(MediaIdentity out, TLRPC.Document document) {
        out.document = document;
        out.documentId = document.id;
        out.dcId = document.dc_id;
        out.expectedFileName = FileLoader.getAttachFileName(document);
        out.expectedSize = document.size;
        out.mimeType = document.mime_type;
        out.originalFileName = FileLoader.getDocumentFileName(document);
        out.ext = extensionOf(out.expectedFileName);
        if (out.ext == null || out.ext.isEmpty()) {
            out.ext = extensionOf(out.originalFileName);
        }
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot + 1);
        return ext.length() > 10 ? "" : ext;
    }

    /** The existing {@code sent_…} String parent, built exactly as MessagesStorage:12746. */
    public static String sentParentFor(int account, TLRPC.Message message) {
        try {
            MessageObject messageObject = new MessageObject(account, message, false, false);
            long channelId = message.peer_id != null ? message.peer_id.channel_id : 0;
            return "sent_" + channelId + "_" + message.id + "_"
                    + DialogObject.getPeerDialogId(message.peer_id) + "_"
                    + messageObject.type + "_" + messageObject.getSize();
        } catch (Throwable e) {
            return "sent_0_" + (message == null ? 0 : message.id) + "_0_0_0";
        }
    }

    /** Cheap pre-check for the pre-existence probe (skip the SQL when it cannot matter). */
    public static boolean shouldProbePreExisting(int account, long dialogId, TLRPC.Message message) {
        if (message == null || !isOptedIn(account, dialogId)) {
            return false;
        }
        Activation act = getActivation(account, dialogId);
        return AliGramEligibility.isGateOpen(act.state, act.time)
                && AliGramEligibility.isAfterActivation(message.date, act.time);
    }

    // ==================================================================
    // Init / startup reconcile
    // ==================================================================

    /** Lazily opens the DB and runs the startup sweep once per process per account. */
    public static boolean ensureInitialized(int account) {
        if (!AliGramData.ensureInitialized()) {
            return false;
        }
        if (startupDone.putIfAbsent(account, Boolean.TRUE) == null) {
            rebuildPinMirror();
            copyQueue.postRunnable(() -> {
                try {
                    startupReconcile(account);
                } catch (Throwable e) {
                    FileLog.e(TAG + ": startupReconcile failed", e);
                }
            });
        }
        return true;
    }

    /** Rebuild the in-memory pin mirror from durable rows (mirror is a cache; DB is authoritative). */
    public static void rebuildPinMirror() {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            for (PreservedRef ref : dao.getNonTerminalRefs()) {
                if (ref.sourcePath != null && !ref.sourcePath.isEmpty()) {
                    pinnedPathMirror.put(ref.sourcePath, ref.refId);
                    if (ref.expectedFileName != null) {
                        pinnedNameMirror.put(ref.expectedFileName, ref.refId);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": rebuildPinMirror failed", e);
        }
    }

    private static void mirrorPut(PreservedRef ref) {
        if (ref.sourcePath != null && !ref.sourcePath.isEmpty()) {
            pinnedPathMirror.put(ref.sourcePath, ref.refId);
        }
        if (ref.expectedFileName != null && !ref.expectedFileName.isEmpty()) {
            pinnedNameMirror.put(ref.expectedFileName, ref.refId);
        }
    }

    private static void mirrorRemoveForRef(PreservedRef ref) {
        if (ref == null) {
            return;
        }
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (ref.sourcePath != null && !ref.sourcePath.isEmpty()) {
                boolean stillPinned = false;
                if (dao != null) {
                    List<PreservedRef> active = dao.getActivePinsForPath(ref.sourcePath);
                    if (active != null) {
                        for (PreservedRef other : active) {
                            if (other.refId != ref.refId) {
                                stillPinned = true;
                                break;
                            }
                        }
                    }
                }
                if (!stillPinned) {
                    pinnedPathMirror.remove(ref.sourcePath);
                }
            }
            if (ref.expectedFileName != null && !ref.expectedFileName.isEmpty()) {
                // Phase 3.1 (D-02): the name mirror is shared by the fan-out
                // refs for one file (LIVE + EDIT pin on the same download),
                // so only clear it when NO other active ref still names the
                // file — otherwise the cancelDownloading pin guard would
                // miss a still-pinned freeze copy. Mirrors the path branch
                // above, which already re-checks the DAO.
                boolean stillNeeded = false;
                if (dao != null) {
                    List<PreservedRef> active = dao.getActiveRefsForFileName(ref.accountId, ref.expectedFileName);
                    if (active != null) {
                        for (PreservedRef other : active) {
                            if (other.refId != ref.refId) {
                                stillNeeded = true;
                                break;
                            }
                        }
                    }
                }
                if (!stillNeeded) {
                    pinnedNameMirror.remove(ref.expectedFileName);
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": mirrorRemoveForRef failed", e);
        }
    }

    /** Mirror-only pin check — safe on ANY thread, including the UI thread. */
    public static boolean isPathPinned(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            return false;
        }
        return pinnedPathMirror.containsKey(absolutePath);
    }

    /** Mirror-only file-name check (cancel filter on the UI thread). */
    public static boolean isFileNamePinned(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return pinnedNameMirror.containsKey(fileName);
    }

    /** Background-thread pin check: mirror first, durable DAO fallback on a miss. */
    public static boolean isPathPinnedForAccount(int account, String absolutePath) {
        if (isPathPinned(absolutePath)) {
            return true;
        }
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return false;
            }
            List<PreservedRef> active = dao.getActivePinsForPath(absolutePath);
            return active != null && !active.isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    // ==================================================================
    // H-I ingest (Phase 2.3 §1.3 + §4.3)
    // ==================================================================

    /**
     * Ingest hook, called from MessagesStorage.putMessagesInternal (normal
     * branch) immediately after the :12680 auto-download block.
     * {@code preExisting} is the probe result computed BEFORE this call's
     * REPLACE (null = probe not evaluated, e.g. P off — ineligible anyway).
     * Never treats {@code downloadMask != 0} as its own gate.
     */
    public static void maybeIngest(int account, TLRPC.Message message, Boolean preExisting,
                                   boolean isArchived, boolean isChannelPostOk) {
        try {
            if (message == null || !ensureInitialized(account)) {
                return;
            }
            long dialogId = MessageObject.getDialogId(message);
            if (!isOptedIn(account, dialogId) || !isGroupOrChannel(account, dialogId)) {
                return;
            }
            Activation act = getActivation(account, dialogId);
            MediaIdentity ident = identifyMedia(account, message);
            boolean dOn = deleteMasterOn();
            boolean eOn = editMasterOn();
            boolean mt = saveMediaEnabledForDialog(account, dialogId);
            boolean mediaOk = AliGramEligibility.isPreservableClass(ident.mediaClass);
            boolean sizeOk = ident.expectedFileName != null
                    && AliGramEligibility.isWithinSizeCap(ident.mediaClass, ident.expectedSize, currentPreset(account), limits());
            boolean roaming = isRoaming(account);
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            long userId = ownUserId(account);
            boolean withinBudget = AliGramEligibility.withinBudget(
                    dao.getPendingJobCountForDialog(userId, dialogId),
                    dao.getPendingBytesForDialog(userId, dialogId),
                    ident.expectedSize, limits());
            boolean eligible = AliGramEligibility.canIngest(true, dOn, eOn, mt, ident.isSecretTtlOnce,
                    mediaOk, sizeOk, isArchived, isChannelPostOk, roaming, withinBudget,
                    message.date, act.time, act.state, preExisting != null && preExisting);
            if (!eligible) {
                return;
            }
            if (dao.getLiveRef(userId, dialogId, message.id) != null) {
                return; // idempotency: duplicate push + getDifference replay
            }
            PreservedRef ref = newPreservedRef(account, userId, dialogId, message.id, "LIVE", 0,
                    ident, AliGramEligibility.STATE_ENQUEUED, "INGEST");
            ref.editDate = message.edit_date;
            try {
                ref.refId = dao.insertRef(ref);
            } catch (Throwable e) {
                // Unique-index race (same message ingested twice concurrently) → no-op.
                return;
            }
            File source = FileLoader.getInstance(account).getPathToMessage(message);
            if (source != null && source.toString().length() > 0 && source.isFile() && source.length() > 0) {
                ref.sourcePath = source.getAbsolutePath();
                ref.sourceSize = source.length();
                ref.sourceMtime = source.lastModified();
                try {
                    dao.updateRef(ref);
                } catch (Throwable ignored) {
                }
                mirrorPut(ref);
                enqueueCopy(ref.refId);
            } else {
                issueDownload(ref, ident, message);
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": maybeIngest failed", e);
        }
    }

    private static PreservedRef newPreservedRef(int account, long userId, long dialogId, int messageId,
                                                String role, int revisionSeq, MediaIdentity ident,
                                                String state, String source) {
        PreservedRef ref = new PreservedRef();
        ref.userId = userId;
        ref.accountId = account;
        ref.dialogId = dialogId;
        ref.messageId = messageId;
        ref.role = role;
        ref.revisionSeq = revisionSeq;
        ref.ayuFakeId = 0;
        ref.entityCreateDate = 0;
        ref.editDate = 0;
        ref.documentId = ident.documentId;
        ref.photoId = ident.photoId;
        ref.dcId = ident.dcId;
        ref.expectedFileName = ident.expectedFileName;
        ref.expectedSize = ident.expectedSize;
        ref.state = state;
        ref.attempts = 0;
        ref.source = source;
        ref.originalFileName = ident.originalFileName;
        ref.photoWidth = ident.photoWidth;
        ref.photoHeight = ident.photoHeight;
        ref.updatedAt = System.currentTimeMillis() / 1000;
        return ref;
    }

    /** Issue the direct FileLoader.loadFile with the existing sent_… parent (never download_queue). */
    private static void issueDownload(PreservedRef ref, MediaIdentity ident, TLRPC.Message message) {
        PendingLoad load = new PendingLoad();
        load.document = ident.document;
        load.photoSize = ident.photoSize;
        load.photo = ident.photo;
        load.parent = sentParentFor(ref.accountId, message);
        load.dialogId = ref.dialogId;
        load.messageId = ref.messageId;
        load.lastProgressMs = System.currentTimeMillis();
        load.probeCount = 0;
        if (ref.expectedFileName != null) {
            pendingLoads.put(pendingKey(ref.accountId, ref.expectedFileName), load);
        }
        ref.state = AliGramEligibility.STATE_DOWNLOADING;
        ref.updatedAt = System.currentTimeMillis() / 1000;
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao != null) {
                dao.updateRef(ref);
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": issueDownload state update failed", e);
        }
        mirrorPut(ref);
        loadFileFromPending(ref.accountId, load);
        scheduleWatchdog(ref.refId, AliGramEligibility.WATCHDOG_DELAY_MS);
    }

    private static void loadFileFromPending(int account, PendingLoad load) {
        if (load == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                FileLoader loader = FileLoader.getInstance(account);
                if (load.photoSize != null) {
                    loader.loadFile(ImageLocation.getForPhoto(load.photoSize, load.photo), load.parent, null, 1, 0);
                } else if (load.document != null) {
                    loader.loadFile(load.document, load.parent, 1, 0);
                }
            } catch (Throwable e) {
                FileLog.e(TAG + ": loadFile failed", e);
            }
        });
    }

    private static String pendingKey(int account, String fileName) {
        return account + "|" + fileName;
    }

    private static void enqueueCopy(long refId) {
        copyQueue.postRunnable(() -> {
            try {
                copyForRef(refId, null);
            } catch (Throwable e) {
                FileLog.e(TAG + ": copyForRef failed", e);
            }
        });
    }

    // ==================================================================
    // H-C completion (Phase 2.2 §1.7 + R-19 second wake-up)
    // ==================================================================

    /**
     * fileLoaded wake-up (called from DownloadController's fileLoaded
     * branch). The notification is a wake-up only: identity comes from the
     * durable job rows. Fan-out claim (→ COPYING in one transaction),
     * re-validation, then one copy per job. H-C is registered BEFORE H-I
     * issues loadFile (the observer lives in DownloadController, which is
     * constructed before any ingest can run), and H-C is lookup-only: a
     * completion with no waiting row is a no-op (opening an old message
     * must never enrol it).
     *
     * <p>Phase 3.1 (D-01, RELEASE-BLOCKING): {@code DownloadController.
     * didReceivedNotification} runs on the UI thread — the
     * {@code fileLoaded}/{@code fileLoadFailed} notifications are posted
     * via {@code AndroidUtilities.runOnUIThread} (ImageLoader's
     * {@code fileDidLoaded}/{@code fileDidFailedLoad} delegate, and
     * NotificationCenter delivers synchronously on the posting thread) —
     * but this body does synchronous Room DAO, and the DB is deliberately
     * built WITHOUT {@code allowMainThreadQueries()}, so every call threw
     * {@code IllegalStateException} inside the catch-all and never
     * claimed a job. The whole body therefore runs on
     * {@code aligramCopyQueue}: this method only posts and returns.
     */
    public static void onFileLoaded(int account, String fileName, File file) {
        try {
            copyQueue.postRunnable(() -> onFileLoadedInternal(account, fileName, file));
        } catch (Throwable e) {
            FileLog.e(TAG + ": onFileLoaded post failed", e);
        }
    }

    private static void onFileLoadedInternal(int account, String fileName, File file) {
        try {
            if (fileName == null || !ensureInitialized(account)) {
                return;
            }
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            AliGramDatabase db = AliGramData.getDatabase();
            if (db == null) {
                return; // cannot claim without the database handle
            }
            // Phase 3.2 (§11.6): the claim must run in one transaction —
            // Room ignores @Transaction on the DAO default method, so the
            // transaction is provided by PreservedRefDao.claimJobsInTransaction.
            List<PreservedRef> claimed = PreservedRefDao.claimJobsInTransaction(db, account, fileName);
            if (claimed == null || claimed.isEmpty()) {
                return; // lookup-only: no waiting job ⇒ no-op
            }
            for (PreservedRef job : claimed) {
                try {
                    if (!revalidateJobForCopy(account, job)) {
                        continue;
                    }
                    if (file != null && file.isFile()) {
                        job.sourcePath = file.getAbsolutePath();
                        job.sourceSize = file.length();
                        job.sourceMtime = file.lastModified();
                        dao.updateRef(job);
                        // Phase 3.1 (D-04): the file just materialised —
                        // refresh any PENDING deferred rows recorded with
                        // 0,0 while it was still downloading.
                        refreshDeferredForPath(job.sourcePath, job.sourceSize, job.sourceMtime);
                    }
                    mirrorPut(job);
                    final long refId = job.refId;
                    copyQueue.postRunnable(() -> {
                        try {
                            copyForRef(refId, file);
                        } catch (Throwable e) {
                            FileLog.e(TAG + ": H-C copy failed", e);
                        }
                    });
                } catch (Throwable e) {
                    FileLog.e(TAG + ": H-C claim handling failed", e);
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": onFileLoaded failed", e);
        }
    }

    /** H-C re-validation (Phase 2.3 §4.5): state + P ∧ (D ∨ E) at completion time. */
    private static boolean revalidateJobForCopy(int account, PreservedRef job) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return false;
            }
            if (!isOptedIn(account, job.dialogId)) {
                terminateJob(job, AliGramEligibility.STATE_GATE_OFF, "P", AliGramEligibility.REASON_GATE_OFF);
                return false;
            }
            if (!deleteMasterOn() && !editMasterOn()) {
                terminateJob(job, AliGramEligibility.STATE_GATE_OFF, "D+E", AliGramEligibility.REASON_GATE_OFF);
                return false;
            }
            // A deleted message's LIVE ref that was already bound to DELETED
            // keeps its role; a ref whose message is gone without a binding
            // is dropped by the delete-event path, not here.
            return true;
        } catch (Throwable e) {
            FileLog.e(TAG + ": revalidateJobForCopy failed", e);
            return false;
        }
    }

    /**
     * fileLoadFailed wake-up: attempts budget + backoff retry; NO_SPACE has no retry loop.
     *
     * <p>Phase 3.1 (D-01, RELEASE-BLOCKING): same UI-thread problem as
     * {@link #onFileLoaded} — this body does synchronous Room DAO but was
     * called from {@code DownloadController.didReceivedNotification} on
     * the UI thread, so the failure signal was lost the same way (attempts
     * never incremented, backoff never scheduled). Posted to
     * {@code aligramCopyQueue} at entry; see there.
     */
    public static void onFileLoadFailed(int account, String fileName, int canceled) {
        try {
            copyQueue.postRunnable(() -> onFileLoadFailedInternal(account, fileName, canceled));
        } catch (Throwable e) {
            FileLog.e(TAG + ": onFileLoadFailed post failed", e);
        }
    }

    private static void onFileLoadFailedInternal(int account, String fileName, int canceled) {
        try {
            if (fileName == null || !ensureInitialized(account)) {
                return;
            }
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            List<PreservedRef> waiting = dao.getWaitingJobsForFile(account, fileName);
            if (waiting == null || waiting.isEmpty()) {
                return;
            }
            for (PreservedRef job : waiting) {
                try {
                    if (canceled == 1 && !isOptedIn(account, job.dialogId)) {
                        terminateJob(job, AliGramEligibility.STATE_GATE_OFF, "P", AliGramEligibility.REASON_GATE_OFF);
                        continue;
                    }
                    job.attempts++;
                    job.updatedAt = System.currentTimeMillis() / 1000;
                    if (AliGramEligibility.attemptsExhausted(job.attempts)) {
                        job.state = AliGramEligibility.STATE_FAILED;
                        job.lastError = AliGramEligibility.REASON_DOWNLOAD_FAILED;
                        dao.updateRef(job);
                        mirrorRemoveForRef(job);
                        releaseDeferredDeletesForRef(job.accountId, job.refId);
                    } else {
                        job.state = AliGramEligibility.STATE_ENQUEUED;
                        dao.updateRef(job);
                        long delayMs = job.attempts == 1 ? 60_000L : 600_000L;
                        final long refId = job.refId;
                        copyQueue.postRunnable(() -> {
                            try {
                                retryDownloadForRef(refId);
                            } catch (Throwable e) {
                                FileLog.e(TAG + ": retryDownload failed", e);
                            }
                        }, delayMs);
                    }
                } catch (Throwable e) {
                    FileLog.e(TAG + ": onFileLoadFailed job handling failed", e);
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": onFileLoadFailed failed", e);
        }
    }

    private static void retryDownloadForRef(long refId) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            // Re-find the job by id (it may have changed state since scheduling).
            PreservedRef job = dao.getRefById(refId);
            if (job == null || !AliGramEligibility.STATE_ENQUEUED.equals(job.state)) {
                return;
            }
            if (!isOptedIn(job.accountId, job.dialogId) || (!deleteMasterOn() && !editMasterOn())) {
                terminateJob(job, AliGramEligibility.STATE_GATE_OFF, "P/D+E", AliGramEligibility.REASON_GATE_OFF);
                return;
            }
            PendingLoad load = job.expectedFileName == null ? null
                    : pendingLoads.get(pendingKey(job.accountId, job.expectedFileName));
            if (load == null) {
                job.state = AliGramEligibility.STATE_FAILED;
                job.lastError = AliGramEligibility.REASON_DOWNLOAD_FAILED;
                dao.updateRef(job);
                mirrorRemoveForRef(job);
                releaseDeferredDeletesForRef(job.accountId, job.refId);
                return;
            }
            load.lastProgressMs = System.currentTimeMillis();
            job.state = AliGramEligibility.STATE_DOWNLOADING;
            job.updatedAt = System.currentTimeMillis() / 1000;
            dao.updateRef(job);
            loadFileFromPending(job.accountId, load);
            scheduleWatchdog(job.refId, AliGramEligibility.WATCHDOG_DELAY_MS);
        } catch (Throwable e) {
            FileLog.e(TAG + ": retryDownloadForRef failed", e);
        }
    }

    /** Progress tick for the watchdog (called from DownloadController's fileLoadProgressChanged branch). */
    public static void onFileLoadProgress(int account, String fileName) {
        try {
            PendingLoad load = fileName == null ? null : pendingLoads.get(pendingKey(account, fileName));
            if (load != null) {
                load.lastProgressMs = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleWatchdog(long refId, long delayMs) {
        copyQueue.postRunnable(() -> {
            try {
                watchdogCheck(refId);
            } catch (Throwable e) {
                FileLog.e(TAG + ": watchdogCheck failed", e);
            }
        }, delayMs);
    }

    /**
     * R-19 second wake-up. The broadcast {@code fileLoaded} is guaranteed
     * for a normal shared-operation finish, but a completed-preload video
     * converted to a full load has its completion swallowed in
     * {@code FileLoader.didFinishLoadingFile} (preload-finished early
     * return) and never publishes {@code fileLoaded}. The watchdog: (1)
     * reconcile — if the file is already complete on disk, copy directly;
     * (1b, Phase 3.1 D-14) reconcile the operation's TEMP file too — a
     * whole-file preload leaves the complete bytes in {@code <stem>.temp}
     * with no final file, and the probe in (2) would re-swallow, so a
     * temp file whose length equals the expected size is copied directly;
     * (2) otherwise re-issue {@code loadFile} as a probe (after the
     * swallow, the operation was removed from {@code loadOperationPaths},
     * so the probe creates a fresh operation whose already-exists path
     * re-publishes {@code fileLoaded}); (3) at most two probes, then the
     * startup re-drive is the last safety net.
     */
    private static void watchdogCheck(long refId) {
        PreservedRefDao dao = AliGramData.refDao();
        if (dao == null) {
            return;
        }
        PreservedRef job = dao.getRefById(refId);
        if (job == null || !AliGramEligibility.STATE_DOWNLOADING.equals(job.state)) {
            return;
        }
        PendingLoad load = job.expectedFileName == null ? null
                : pendingLoads.get(pendingKey(job.accountId, job.expectedFileName));
        if (load == null) {
            return; // nothing to probe with; startup re-drive is the safety net
        }
        long now = System.currentTimeMillis();
        if (!AliGramEligibility.shouldProbeDownload(job.state, now, load.lastProgressMs)) {
            scheduleWatchdog(refId, AliGramEligibility.WATCHDOG_DELAY_MS);
            return;
        }
        // (1) Reconciliation: is the file already complete on disk?
        File onDisk = resolveSourceFile(job, load);
        if (onDisk != null && onDisk.isFile() && job.expectedSize > 0 && onDisk.length() >= job.expectedSize) {
            FileLog.d(TAG + ": watchdog reconciled complete file on disk for ref " + refId);
            job.sourcePath = onDisk.getAbsolutePath();
            job.sourceSize = onDisk.length();
            job.sourceMtime = onDisk.lastModified();
            job.state = AliGramEligibility.STATE_COPYING;
            dao.updateRef(job);
            mirrorPut(job);
            // Phase 3.1 (D-04): the file just materialised — refresh any
            // PENDING deferred rows recorded with 0,0.
            refreshDeferredForPath(job.sourcePath, job.sourceSize, job.sourceMtime);
            copyForRef(refId, onDisk);
            return;
        }
        // (1b) Phase 3.1 (D-14): the completed-preload swallow leaves the
        // WHOLE file in the operation's temp file (<stem>.temp in
        // MEDIA_DIR_CACHE) with no final file and no fileLoaded — and the
        // probe in step (2) re-swallows, because a fresh operation's
        // start() sees downloadedBytes == totalBytesCount and finishes
        // with preload=true again. So a temp file whose length EQUALS
        // the expected size is a complete source: copy from it directly.
        // copyVerified still enforces size + SHA, so a partial temp is
        // never bound. (A longer temp cannot happen for this attach — the
        // temp name is per-attach — but == is used deliberately: the
        // copy would reject a mismatch anyway.)
        File tempSource = resolveTempSourceFile(job, load);
        if (tempSource != null && tempSource.isFile() && job.expectedSize > 0
                && tempSource.length() == job.expectedSize) {
            FileLog.d(TAG + ": watchdog reconciled complete temp file on disk for ref " + refId
                    + " (completed-preload swallow, D-14)");
            job.sourcePath = tempSource.getAbsolutePath();
            job.sourceSize = tempSource.length();
            job.sourceMtime = tempSource.lastModified();
            job.state = AliGramEligibility.STATE_COPYING;
            dao.updateRef(job);
            mirrorPut(job);
            refreshDeferredForPath(job.sourcePath, job.sourceSize, job.sourceMtime);
            copyForRef(refId, tempSource);
            return;
        }
        // (2) Probe: re-issue loadFile.
        if (load.probeCount >= MAX_WATCHDOG_PROBES) {
            FileLog.d(TAG + ": watchdog probe budget exhausted for ref " + refId + "; leaving for startup re-drive");
            return;
        }
        load.probeCount++;
        load.lastProgressMs = now;
        FileLog.d(TAG + ": watchdog probe #" + load.probeCount + " for ref " + refId + " (" + job.expectedFileName + ")");
        loadFileFromPending(job.accountId, load);
        scheduleWatchdog(refId, AliGramEligibility.WATCHDOG_DELAY_MS);
    }

    private static File resolveSourceFile(PreservedRef job, PendingLoad load) {
        try {
            FileLoader loader = FileLoader.getInstance(job.accountId);
            if (load.photoSize != null) {
                return loader.getPathToAttach(load.photoSize);
            }
            if (load.document != null) {
                return loader.getPathToAttach(load.document);
            }
        } catch (Throwable ignored) {
        }
        if (job.sourcePath != null && !job.sourcePath.isEmpty()) {
            return new File(job.sourcePath);
        }
        return null;
    }

    /**
     * Phase 3.1 (D-14): the operation's temp file for a job (see
     * {@code FileLoader.getTempPathToAttach}). Only a plain {@code .temp}
     * file is ever accepted here — never a {@code .temp.enc} (ciphertext
     * must never be copied as a blob) and never a final file (step (1)
     * already handles that). Returns null when there is no temp candidate.
     */
    private static File resolveTempSourceFile(PreservedRef job, PendingLoad load) {
        try {
            FileLoader loader = FileLoader.getInstance(job.accountId);
            File temp = null;
            if (load.photoSize != null) {
                temp = loader.getTempPathToAttach(load.photoSize);
            } else if (load.document != null) {
                temp = loader.getTempPathToAttach(load.document);
            }
            if (temp != null && temp.getName().endsWith(".temp")) {
                return temp;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================================================================
    // Copy worker (single-writer for the state column, on copyQueue)
    // ==================================================================

    /**
     * Copy one ref to a verified, content-addressed blob. Runs only on
     * {@code aligramCopyQueue}. {@code completedFile} is the file from the
     * {@code fileLoaded} notification when known; otherwise the source is
     * resolved from the ref's pinned path / PendingLoad objects.
     */
    private static void copyForRef(long refId, File completedFile) {
        PreservedRefDao dao = AliGramData.refDao();
        PreservedBlobDao blobDao = AliGramData.blobDao();
        if (dao == null || blobDao == null) {
            return;
        }
        PreservedRef job = dao.getRefById(refId);
        if (job == null || AliGramEligibility.isTerminalState(job.state)) {
            return; // already terminal/absent
        }
        String previousBlobId = job.blobId;
        File source = completedFile;
        if (source == null || !source.isFile()) {
            if (job.sourcePath != null && !job.sourcePath.isEmpty()) {
                source = new File(job.sourcePath);
            }
        }
        if (source == null || !source.isFile()) {
            // Source gone. If a download is still in flight for this file,
            // H-C will wake us; otherwise count a failed re-drive.
            PendingLoad load = job.expectedFileName == null ? null
                    : pendingLoads.get(pendingKey(job.accountId, job.expectedFileName));
            if (load != null && AliGramEligibility.STATE_PINNED.equals(job.state)) {
                return; // still-downloading old file: deferred-cancel rule, wait for H-C
            }
            job.attempts++;
            job.updatedAt = System.currentTimeMillis() / 1000;
            if (AliGramEligibility.attemptsExhausted(job.attempts)) {
                job.state = AliGramEligibility.STATE_FAILED;
                job.lastError = AliGramEligibility.REASON_SOURCE_GONE;
                dao.updateRef(job);
                mirrorRemoveForRef(job);
                releaseDeferredDeletesForRef(job.accountId, job.refId);
            } else {
                dao.updateRef(job);
            }
            return;
        }
        String pendingName = "pending-" + job.refId + ".blob";
        AliGramFileCopy.CopyResult result = AliGramFileCopy.copyVerified(
                source, job.accountId, pendingName, job.expectedSize, job.refId);
        if (!result.ok) {
            job.attempts++;
            job.updatedAt = System.currentTimeMillis() / 1000;
            if (AliGramEligibility.REASON_NO_SPACE.equals(result.error)
                    || AliGramEligibility.attemptsExhausted(job.attempts)) {
                job.state = AliGramEligibility.STATE_FAILED;
                job.lastError = result.error;
                dao.updateRef(job);
                mirrorRemoveForRef(job);
                releaseDeferredDeletesForRef(job.accountId, job.refId);
            } else {
                job.state = AliGramEligibility.STATE_ENQUEUED;
                dao.updateRef(job);
            }
            return;
        }
        // Phase 3.1 (D-04): the copy succeeded, so the source is complete
        // on disk with its final size/mtime — refresh any PENDING deferred
        // rows recorded with 0,0 while it was still downloading, so the
        // release after bind can pass decideRelease.
        refreshDeferredForPath(source.getAbsolutePath(), source.length(), source.lastModified());
        // Dedupe at copy time: same (accountId, documentId|photoId, dcId, size, sha256) ⇒ reuse.
        // Phase 3.1 (D-05): the blob id AND the lookup are account-scoped —
        // a global id/lookup bound a ref to a blob file in another
        // account's directory (no file in this account's dir, render
        // missed, UNAVAILABLE at startup, purge of the other account
        // deleted the file under this ref).
        String blobId = AliGramEligibility.blobIdFor(job.accountId, job.documentId, job.photoId, job.dcId,
                result.size, result.sha256Hex);
        String ext = job.expectedFileName != null ? extensionOf(job.expectedFileName) : "";
        if (ext.isEmpty()) {
            ext = extensionOf(job.originalFileName);
        }
        PreservedBlob existing = null;
        try {
            if (job.documentId != 0) {
                existing = blobDao.getByDocumentKey(job.accountId, job.documentId, job.dcId, result.size, result.sha256Hex);
            } else if (job.photoId != 0) {
                existing = blobDao.getByPhotoKey(job.accountId, job.photoId, job.dcId, result.size, result.sha256Hex);
            }
        } catch (Throwable ignored) {
        }
        if (existing != null) {
            AliGramFileCopy.deleteQuietly(result.finalFile);
            bindCopied(job, existing.blobId, dao, blobDao, previousBlobId);
            return;
        }
        PreservedBlob blob = new PreservedBlob();
        blob.blobId = blobId;
        blob.accountId = job.accountId;
        blob.documentId = job.documentId;
        blob.photoId = job.photoId;
        blob.dcId = job.dcId;
        blob.size = result.size;
        blob.sha256 = result.sha256Hex;
        blob.mimeType = null;
        blob.ext = ext;
        blob.fileName = blobId + (ext.isEmpty() ? "" : "." + ext);
        blob.originalFileName = job.originalFileName;
        blob.photoWidth = job.photoWidth;
        blob.photoHeight = job.photoHeight;
        blob.refCount = 0; // incremented by bindCopied below, same task
        blob.createdAt = System.currentTimeMillis() / 1000;
        try {
            blobDao.insertBlob(blob);
        } catch (Throwable e) {
            // Another job published the same blob concurrently: reuse it.
            PreservedBlob raced = blobDao.getById(blobId);
            if (raced == null) {
                FileLog.e(TAG + ": blob insert failed", e);
                job.state = AliGramEligibility.STATE_FAILED;
                job.lastError = AliGramEligibility.REASON_DOWNLOAD_FAILED;
                dao.updateRef(job);
                AliGramFileCopy.deleteQuietly(result.finalFile);
                mirrorRemoveForRef(job);
                releaseDeferredDeletesForRef(job.accountId, job.refId);
                return;
            }
            AliGramFileCopy.deleteQuietly(result.finalFile);
            bindCopied(job, raced.blobId, dao, blobDao, previousBlobId);
            return;
        }
        // Publish temp→final (same directory, atomic rename).
        File finalFile = AliGramFileCopy.blobFileFor(job.accountId, blobId, ext);
        boolean published = result.finalFile.renameTo(finalFile);
        if (!published && !finalFile.exists()) {
            AliGramFileCopy.deleteQuietly(result.finalFile);
            job.state = AliGramEligibility.STATE_FAILED;
            job.lastError = AliGramEligibility.REASON_NO_SPACE;
            dao.updateRef(job);
            mirrorRemoveForRef(job);
            releaseDeferredDeletesForRef(job.accountId, job.refId);
            return;
        }
        if (!published) {
            AliGramFileCopy.deleteQuietly(result.finalFile);
        }
        if (!bindCopied(job, blobId, dao, blobDao, previousBlobId)) {
            // Ref re-pointed or terminated mid-copy (D-10): the published
            // blob is orphaned (refCount 0, no ref points at it) — sweep
            // it promptly instead of leaving it for a later sweep.
            sweepRefcountZeroBlobs(job.accountId);
        }
    }

    /**
     * Commit point: bind blobId, mark COPIED, release the delete gate.
     *
     * <p>Phase 3.1 (D-10 + D-09 fix (2)): the single-writer rule for the
     * state column is enforced here by re-reading the ref immediately
     * before the bind. {@code liveSyncEdit} (storage queue) and
     * {@code bindEditRevision} (globalQueue) mutate a live ref's
     * identity/state off {@code copyQueue} while a {@code copyForRef}
     * for the same ref may be mid-copy holding a stale snapshot — and a
     * plain {@code dao.updateRef(job)} writes the whole entity, so the
     * last writer wins: a copy of the OLD media could land as COPIED on
     * a ref that was already re-pointed at the NEW media. When the
     * fresh row is gone or its identity/state differ from the snapshot,
     * the bind is ABORTED (false, without touching the ref or the blob
     * refcount); callers drop their temp output, and the publish path
     * sweeps the orphaned blob. The refcount increment lives here, after
     * the check, so an aborted bind never leaks a count. A same-media
     * role change (LIVE→EDIT by bindEditRevision, same identity) is
     * NOT an abort: the copy is of that same media, so binding it to
     * the (now EDIT) ref is correct.
     *
     * <p>Residual micro-race (by design, not closed here): the re-read
     * and the update are not one transaction, so a re-point landing
     * between them could still win the last-writer race. Closing it
     * needs a transactional bind (state/identity checked in SQL). The
     * window is a few DAO calls wide, and the re-point would have to
     * land exactly there; the audit's prescribed fix is this re-read.
     */
    private static boolean bindCopied(PreservedRef job, String blobId, PreservedRefDao dao,
                                      PreservedBlobDao blobDao, String previousBlobId) {
        PreservedRef fresh = null;
        try {
            fresh = dao.getRefById(job.refId);
        } catch (Throwable ignored) {
        }
        if (fresh == null
                || fresh.documentId != job.documentId || fresh.photoId != job.photoId
                || fresh.dcId != job.dcId || fresh.expectedSize != job.expectedSize
                || (fresh.expectedFileName == null ? job.expectedFileName != null
                        : !fresh.expectedFileName.equals(job.expectedFileName))
                || fresh.state == null || !fresh.state.equals(job.state)) {
            FileLog.d(TAG + ": bind aborted for ref " + job.refId + " — ref re-pointed or terminated mid-copy");
            return false;
        }
        if (previousBlobId != null && !previousBlobId.equals(blobId)) {
            try {
                blobDao.decrementRefCount(previousBlobId);
            } catch (Throwable ignored) {
            }
        }
        try {
            blobDao.incrementRefCount(blobId);
        } catch (Throwable ignored) {
        }
        job.blobId = blobId;
        job.state = AliGramEligibility.STATE_COPIED;
        job.updatedAt = System.currentTimeMillis() / 1000;
        dao.updateRef(job);
        if (job.expectedFileName != null) {
            pendingLoads.remove(pendingKey(job.accountId, job.expectedFileName));
        }
        mirrorRemoveForRef(job);
        try {
            PreservedBlob blob = blobDao.getById(blobId);
            noteCopiedFile(job, blobId, blob == null ? "" : blob.ext);
        } catch (Throwable ignored) {
        }
        releaseDeferredDeletesForRef(job.accountId, job.refId);
        sweepRefcountZeroBlobs(job.accountId);
        return true;
    }

    /** Terminal transition + both-halves cancellation helper. */
    private static void terminateJob(PreservedRef job, String terminalState, String gateName, String reason) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            job.state = terminalState;
            job.gateName = gateName;
            job.lastError = reason;
            job.updatedAt = System.currentTimeMillis() / 1000;
            dao.updateRef(job);
            mirrorRemoveForRef(job);
            cancelLoadFileForJob(job);
            if (job.expectedFileName != null) {
                pendingLoads.remove(pendingKey(job.accountId, job.expectedFileName));
            }
            releaseDeferredDeletesForRef(job.accountId, job.refId);
        } catch (Throwable e) {
            FileLog.e(TAG + ": terminateJob failed", e);
        }
    }

    /** Cancel both halves: the FileLoader operation (deleteFile=false) + the job row. */
    private static void cancelLoadFileForJob(PreservedRef job) {
        try {
            PendingLoad load = job.expectedFileName == null ? null
                    : pendingLoads.get(pendingKey(job.accountId, job.expectedFileName));
            final int account = job.accountId;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    FileLoader loader = FileLoader.getInstance(account);
                    if (load != null && load.document != null) {
                        loader.cancelLoadFile(load.document, false);
                    } else if (load != null && load.photoSize != null) {
                        loader.cancelLoadFile(load.photoSize, false);
                    } else if (job.expectedFileName != null) {
                        loader.cancelLoadFile(job.expectedFileName);
                    }
                } catch (Throwable e) {
                    FileLog.e(TAG + ": cancelLoadFile failed", e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(TAG + ": cancelLoadFileForJob failed", e);
        }
    }

    /** P-off / both-masters-off: cancel every pending job for the dialog, both halves. */
    public static void cancelJobsForDialog(int account, long dialogId, String gateName) {
        try {
            if (!ensureInitialized(account)) {
                return;
            }
            copyQueue.postRunnable(() -> {
                try {
                    PreservedRefDao dao = AliGramData.refDao();
                    if (dao == null) {
                        return;
                    }
                    long userId = ownUserId(account);
                    for (PreservedRef job : dao.getPendingJobsForDialog(userId, dialogId)) {
                        // In-flight freeze copies (PINNED) and their copies
                        // (COPYING) complete and bind, never cancelled (C-15).
                        if (AliGramEligibility.STATE_PINNED.equals(job.state)
                                || AliGramEligibility.STATE_COPYING.equals(job.state)) {
                            continue;
                        }
                        terminateJob(job, AliGramEligibility.STATE_GATE_OFF, gateName,
                                AliGramEligibility.REASON_GATE_OFF);
                    }
                } catch (Throwable e) {
                    FileLog.e(TAG + ": cancelJobsForDialog failed", e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(TAG + ": cancelJobsForDialog failed", e);
        }
    }

    // ==================================================================
    // Deferred delete (Phase 2.3 §2.2) — Layer 1 / Layer 2 support
    // ==================================================================

    /**
     * Record a deferred-delete request (INSERT OR IGNORE on the unique key).
     *
     * <p>Phase 3.1: the {@code refId} is validated against the durable row.
     * Both record paths resolve it through a path-keyed mirror
     * ({@code pinnedPathMirror}, shared across accounts because cache
     * paths can be shared), so it may belong to another account's ref.
     * A foreign or dangling refId is never stored — it would let one
     * account's release sweep another account's rows. It degrades to 0
     * (unattributed): the row is then released only by the account
     * startup sweep, which re-validates the pin per path.
     */
    public static void recordDeferredDelete(int account, long refId, String absolutePath,
                                            long sizeAtPin, long mtimeAtPin,
                                            String requestedBy, boolean cancelDeferred) {
        try {
            if (absolutePath == null || absolutePath.isEmpty() || !ensureInitialized(account)) {
                return;
            }
            DeferredDeleteDao dao = AliGramData.deferredDao();
            if (dao == null) {
                return;
            }
            if (refId != 0) {
                try {
                    PreservedRefDao refDao = AliGramData.refDao();
                    PreservedRef pin = refDao == null ? null : refDao.getRefById(refId);
                    if (pin == null || pin.accountId != account) {
                        refId = 0; // foreign / dangling mirror entry: unattributed
                    }
                } catch (Throwable ignored) {
                    refId = 0;
                }
            }
            // Phase 3.1 (D-04): a row recorded with sizeAtPin/mtimeAtPin
            // == 0,0 can NEVER pass decideRelease (any existing file
            // mismatches 0,0), so Layer-2 rows and pins made before the
            // download finished were marked DONE without deleting the
            // file. Stat the file here when the caller passed 0,0: the
            // Layer-2 guard only knows the path, and a pin's
            // sourceSize/sourceMtime are 0 while its source is still
            // downloading. When the file does not exist (yet), keep 0,0 —
            // the release then sees ALREADY_GONE, and the
            // materialisation refresh (refreshDeferredForPath) fills in
            // the real values once the file lands.
            if (sizeAtPin == 0 && mtimeAtPin == 0) {
                try {
                    File f = new File(absolutePath);
                    if (f.isFile()) {
                        sizeAtPin = f.length();
                        mtimeAtPin = f.lastModified();
                    }
                } catch (Throwable ignored) {
                }
            }
            DeferredDelete row = new DeferredDelete();
            row.accountId = account;
            row.refId = refId;
            row.absolutePath = absolutePath;
            row.sizeAtPin = sizeAtPin;
            row.mtimeAtPin = mtimeAtPin;
            row.requestedBy = requestedBy;
            row.cancelDeferred = cancelDeferred;
            row.state = "PENDING";
            row.createdAt = System.currentTimeMillis() / 1000;
            dao.insert(row);
        } catch (Throwable e) {
            FileLog.e(TAG + ": recordDeferredDelete failed", e);
        }
    }

    /** Overload for the Layer-2 guard in FileLoader.deleteFiles, which
     * knows only the absolute path: resolve the pin refId from the mirror
     * (0 when the mirror has no entry — the release still happens, keyed
     * on the path). */
    public static void recordDeferredDelete(int account, String absolutePath,
                                            long sizeAtPin, long mtimeAtPin,
                                            String requestedBy, boolean cancelDeferred) {
        long refId = pinnedRefIdForPath(absolutePath);
        recordDeferredDelete(account, refId, absolutePath, sizeAtPin, mtimeAtPin, requestedBy, cancelDeferred);
    }

    private static long pinnedRefIdForPath(String absolutePath) {
        if (absolutePath == null) {
            return 0;
        }
        Long refId = pinnedPathMirror.get(absolutePath);
        return refId == null ? 0 : refId;
    }

    /**
     * Layer-1 helper used inside MessagesStorage.addFilesToDelete: if the
     * file is pinned, record the deferred delete and tell the caller to
     * skip appending it to {@code filesToDelete}. Returns true when pinned.
     */
    public static boolean skipPinnedFile(int account, File file, String requestedBy) {
        try {
            if (file == null || file.toString().length() == 0) {
                return false;
            }
            String path = file.getAbsolutePath();
            if (!isPathPinnedForAccount(account, path)) {
                return false;
            }
            long refId = 0;
            long sizeAtPin = 0;
            long mtimeAtPin = 0;
            try {
                PreservedRefDao dao = AliGramData.refDao();
                if (dao != null) {
                    List<PreservedRef> active = dao.getActivePinsForPath(path);
                    if (active != null && !active.isEmpty()) {
                        PreservedRef pin = active.get(0);
                        refId = pin.refId;
                        sizeAtPin = pin.sourceSize;
                        mtimeAtPin = pin.sourceMtime;
                    }
                }
            } catch (Throwable ignored) {
            }
            recordDeferredDelete(account, refId, path, sizeAtPin, mtimeAtPin, requestedBy, true);
            return true;
        } catch (Throwable e) {
            FileLog.e(TAG + ": skipPinnedFile failed", e);
            return false;
        }
    }

    /**
     * Phase 3.1 (D-04): refresh PENDING deferred rows for a path whose
     * file has just materialised with its final size/mtime (completion
     * or watchdog reconcile or successful copy). Rows recorded while the
     * source was still downloading carry 0,0; without this refresh the
     * release decision could never pass and the file would leak.
     */
    private static void refreshDeferredForPath(String absolutePath, long size, long mtime) {
        try {
            DeferredDeleteDao dao = AliGramData.deferredDao();
            if (dao != null && absolutePath != null && !absolutePath.isEmpty()) {
                dao.refreshSizeMtimeForPath(absolutePath, size, mtime);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Release deferred deletes for one ref. Only the copy-queue worker (and
     * the startup sweep, on the same queue) executes this, and only after
     * the ref's terminal transition has committed. Re-checks that no other
     * active pin covers the path and re-validates size+mtime (H-6): on a
     * mismatch the row is marked DONE without deleting.
     *
     * <p>Phase 3.1 scoping: {@code refId == 0} returns immediately — every
     * unattributed row shares refId 0, and releasing "for ref 0" would
     * sweep them all, across dialogs and accounts. Rows are fetched with
     * the account-scoped {@code getPendingForRef(account, refId)}, so a
     * foreign refId can never release this account's rows.
     */
    public static void releaseDeferredDeletesForRef(int account, long refId) {
        try {
            if (refId == 0) {
                return; // unattributed rows: startup sweep only, never by ref
            }
            DeferredDeleteDao dao = AliGramData.deferredDao();
            if (dao == null) {
                return;
            }
            List<DeferredDelete> rows = dao.getPendingForRef(account, refId);
            if (rows == null) {
                return;
            }
            for (DeferredDelete row : rows) {
                releaseDeferredRow(account, row);
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": releaseDeferredDeletesForRef failed", e);
        }
    }

    /**
     * Release one PENDING deferred row: skip it while any active pin still
     * covers the path (another dialog/account may pin the same file),
     * otherwise delete the file when size+mtime still match the pin and
     * mark the row DONE (DONE on mismatch / already-gone too — the
     * request is settled either way). Shared by the per-ref release and
     * the startup sweep, so the two paths cannot drift apart.
     */
    private static void releaseDeferredRow(int account, DeferredDelete row) {
        try {
            DeferredDeleteDao dao = AliGramData.deferredDao();
            PreservedRefDao refDao = AliGramData.refDao();
            if (dao == null || refDao == null) {
                return;
            }
            List<PreservedRef> active = refDao.getActivePinsForPath(row.absolutePath);
            if (active != null && !active.isEmpty()) {
                return; // another active pin covers the path; released when it terminates
            }
            File file = new File(row.absolutePath);
            AliGramEligibility.ReleaseDecision decision;
            if (row.sizeAtPin == 0 && row.mtimeAtPin == 0 && file.exists()) {
                // Phase 3.1 (D-04): a row recorded with 0,0 was never
                // verified against any file — there is nothing to
                // mismatch against, so decideRelease would answer
                // KEEP_MISMATCH forever. No active pin covers the path
                // (checked above), and Telegram asked for this path to
                // be deleted: delete it. Pre-existing 0,0 rows (written
                // before the record-time stat + materialisation refresh)
                // are covered by this; new rows get real values instead.
                // decideRelease itself is unchanged for verified pins.
                decision = AliGramEligibility.ReleaseDecision.DELETE;
                FileLog.d(TAG + ": releasing unverified (0,0) deferred delete for " + row.absolutePath);
            } else {
                decision = AliGramEligibility.decideRelease(
                        file.exists(), file.exists() ? file.length() : 0,
                        file.exists() ? file.lastModified() : 0,
                        row.sizeAtPin, row.mtimeAtPin);
            }
            if (decision == AliGramEligibility.ReleaseDecision.DELETE) {
                if (row.cancelDeferred) {
                    cancelLoadFileByName(account, file.getName());
                }
                ArrayList<File> toDelete = new ArrayList<>();
                toDelete.add(file);
                // The guarded public entry: no active pin remains, so it deletes.
                FileLoader.getInstance(account).deleteFiles(toDelete, 0);
            }
            dao.setState(row.id, "DONE");
        } catch (Throwable e) {
            FileLog.e(TAG + ": deferred release row failed", e);
        }
    }

    private static void cancelLoadFileByName(int account, String fileName) {
        if (fileName == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                FileLoader.getInstance(account).cancelLoadFile(fileName);
            } catch (Throwable ignored) {
            }
        });
    }

    /** Drop refcount-0 blobs (recomputed from refs, never trusted blindly). */
    private static void sweepRefcountZeroBlobs(int account) {
        try {
            PreservedBlobDao blobDao = AliGramData.blobDao();
            PreservedRefDao refDao = AliGramData.refDao();
            if (blobDao == null || refDao == null) {
                return;
            }
            HashSet<String> swept = new HashSet<>();
            for (PreservedBlob blob : blobDao.getOrphanBlobs()) {
                if (refDao.countRefsForBlob(blob.blobId) > 0) {
                    continue;
                }
                deleteBlobRowAndFiles(blob, blobDao);
                swept.add(blob.blobId);
            }
            // Phase 3.1 (D-09): a crash between incrementRefCount and
            // bindCopied leaves a blob with refCount > 0 and NO ref
            // pointing at it, and getOrphanBlobs (refCount <= 0) never
            // sees it — it leaked forever. Sweep the recomputed
            // unreferenced set too (ignores the stored refCount, like
            // sweepUnreferencedBlobs does after purge/clear).
            for (PreservedBlob blob : blobDao.getUnreferencedBlobs()) {
                if (!swept.add(blob.blobId)) {
                    continue;
                }
                deleteBlobRowAndFiles(blob, blobDao);
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": sweepRefcountZeroBlobs failed", e);
        }
    }

    /**
     * Delete one orphaned blob row and its file. Phase 3.1 (D-05): the
     * file is deleted from EVERY account directory, not just the
     * caller's — a pre-D-05 or migrated row may carry a stale
     * {@code accountId} while its file actually lives in another
     * account's dir, and deleting only the caller's dir would leak the
     * file. The name is the content-addressed blob id, so deleting it
     * from a directory that does not contain it is a no-op.
     */
    private static void deleteBlobRowAndFiles(PreservedBlob blob, PreservedBlobDao blobDao) {
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                AliGramFileCopy.deleteQuietly(AliGramFileCopy.blobFileFor(a, blob.blobId, blob.ext));
            }
            blobDao.deleteById(blob.blobId);
        } catch (Throwable e) {
            FileLog.e(TAG + ": deleteBlobRowAndFiles failed", e);
        }
    }

    // ==================================================================
    // H-E Stage A pin + LIVE-sync (Phase 2.3 §2.2, §4.1, §4.3)
    // ==================================================================

    /**
     * Stage A pin (storage queue, inside the load_type == -2 branch, BEFORE
     * addFilesToDelete). Metadata-only, never throwing, never byte I/O.
     * Returns the pin refId or -1. The pin insert is synchronous: it is the
     * happens-before edge the deferred-delete proof rests on (Phase 2.3
     * §2.3 step 1) — never route it through a suspend function or another
     * queue.
     */
    public static long pinForEdit(int account, long dialogId, TLRPC.Message oldMessage,
                                  TLRPC.Message newMessage, boolean sameMedia) {
        try {
            if (sameMedia || oldMessage == null || newMessage == null || !ensureInitialized(account)) {
                return -1;
            }
            if (!isOptedIn(account, dialogId) || !editMasterOn()) {
                return -1;
            }
            if (newMessage.from_id == null) {
                return -1; // legacy hook never fires for unsigned channel posts
            }
            if (AyuSavePreferences.getSaveDeletedExclusion(dialogId)
                    || (oldMessage.from_id != null
                    && AyuSavePreferences.getSaveDeletedExclusion(oldMessage.from_id.user_id))) {
                return -1; // X gates the freeze (P-4 / C-04)
            }
            MediaIdentity oldIdent = identifyMedia(account, oldMessage);
            if (!AliGramEligibility.isPreservableClass(oldIdent.mediaClass)) {
                return -1;
            }
            // old-media-eligible (i)+(ii): a LIVE ref exists, or the old
            // media's exact identity already exists as a PreservedBlob.
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (dao == null || blobDao == null) {
                return -1;
            }
            long userId = ownUserId(account);
            boolean eligible = dao.getLiveRef(userId, dialogId, oldMessage.id) != null
                    || findBlobForIdentity(account, blobDao, oldIdent) != null;
            if (!eligible) {
                return -1;
            }
            File source = FileLoader.getInstance(account).getPathToMessage(oldMessage);
            PreservedRef pin = newPreservedRef(account, userId, dialogId, oldMessage.id, "EDIT", 0,
                    oldIdent, AliGramEligibility.STATE_PINNED, "EDIT_FREEZE");
            pin.editDate = oldMessage.edit_date;
            if (source != null && source.toString().length() > 0) {
                pin.sourcePath = source.getAbsolutePath();
                if (source.isFile()) {
                    pin.sourceSize = source.length();
                    pin.sourceMtime = source.lastModified();
                }
            }
            pin.pendingDeletePaths = pendingDeletePathsFor(account, oldMessage);
            try {
                pin.refId = dao.insertRef(pin);
            } catch (Throwable e) {
                PreservedRef existing = dao.getRef(userId, dialogId, oldMessage.id, "EDIT", 0);
                if (existing == null) {
                    return -1;
                }
                return existing.refId;
            }
            mirrorPut(pin);
            return pin.refId;
        } catch (Throwable e) {
            FileLog.e(TAG + ": pinForEdit failed", e);
            return -1;
        }
    }

    /** H-3 fix: pin the old media before emptyMessagesMedia blanks it. */
    public static long pinForMediaRemoval(int account, long dialogId, TLRPC.Message message) {
        try {
            if (message == null || !ensureInitialized(account)) {
                return -1;
            }
            if (!isOptedIn(account, dialogId) || !editMasterOn()) {
                return -1;
            }
            MediaIdentity ident = identifyMedia(account, message);
            if (!AliGramEligibility.isPreservableClass(ident.mediaClass)) {
                return -1;
            }
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return -1;
            }
            long userId = ownUserId(account);
            File source = FileLoader.getInstance(account).getPathToMessage(message);
            PreservedRef pin = newPreservedRef(account, userId, dialogId, message.id, "EDIT", 0,
                    ident, AliGramEligibility.STATE_PINNED, "EDIT_FREEZE");
            pin.editDate = message.edit_date;
            if (source != null && source.toString().length() > 0) {
                pin.sourcePath = source.getAbsolutePath();
                if (source.isFile()) {
                    pin.sourceSize = source.length();
                    pin.sourceMtime = source.lastModified();
                }
            }
            pin.pendingDeletePaths = pendingDeletePathsFor(account, message);
            try {
                pin.refId = dao.insertRef(pin);
            } catch (Throwable e) {
                return -1;
            }
            mirrorPut(pin);
            return pin.refId;
        } catch (Throwable e) {
            FileLog.e(TAG + ": pinForMediaRemoval failed", e);
            return -1;
        }
    }

    /** Pending-delete file names for one message (same paths addFilesToDelete collects). */
    private static String pendingDeletePathsFor(int account, TLRPC.Message message) {
        try {
            List<String> paths = new ArrayList<>();
            FileLoader loader = FileLoader.getInstance(account);
            TLRPC.Photo photo = MessageObject.getPhoto(message);
            TLRPC.Document document = MessageObject.getDocument(message);
            if (photo != null) {
                for (TLRPC.PhotoSize size : photo.sizes) {
                    File f = loader.getPathToAttach(size, false);
                    if (f != null && f.toString().length() > 0) {
                        paths.add(f.getAbsolutePath());
                    }
                }
            } else if (document != null) {
                File f = loader.getPathToAttach(document, false);
                if (f != null && f.toString().length() > 0) {
                    paths.add(f.getAbsolutePath());
                }
                for (TLRPC.PhotoSize thumb : document.thumbs) {
                    File tf = loader.getPathToAttach(thumb);
                    if (tf != null && tf.toString().length() > 0) {
                        paths.add(tf.getAbsolutePath());
                    }
                }
            }
            return TextUtils.join(",", paths);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Policy (ii) test: does the exact media identity already exist as a blob in THIS account?
     *  Phase 3.1 (D-05): the lookup is account-scoped — another account's
     *  blob proves nothing here, because its file lives in that
     *  account's directory, not this one. */
    private static PreservedBlob findBlobForIdentity(int account, PreservedBlobDao blobDao, MediaIdentity ident) {
        try {
            List<PreservedBlob> candidates;
            if (ident.documentId != 0) {
                candidates = blobDao.getBlobsByDocument(account, ident.documentId, ident.dcId, ident.expectedSize);
            } else if (ident.photoId != 0) {
                candidates = blobDao.getBlobsByPhoto(account, ident.photoId, ident.dcId, ident.expectedSize);
            } else {
                return null;
            }
            return candidates == null || candidates.isEmpty() ? null : candidates.get(0);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * LIVE-sync step (Phase 2.3 §4.1). Runs on the storage queue in the
     * else (non-restricted-edit) branch, directly after the sameMedia
     * computation and before addFilesToDelete — OUTSIDE both the from_id
     * check and the E check. Guarantees that after any media-changing edit
     * the LIVE ref names the current media, or no LIVE ref exists — never
     * an obsolete version. Idempotent: when the LIVE ref already equals the
     * new media it is a no-op, so it is safe to run even when H-E will
     * also run. Fail-safe: absent/0 edit_date ⇒ no enrolment/re-point,
     * log EDIT_DATE_UNKNOWN.
     */
    public static void liveSyncEdit(int account, long dialogId, TLRPC.Message oldMessage,
                                    TLRPC.Message newMessage, boolean sameMedia,
                                    boolean restrictedEditDiscard) {
        try {
            if (sameMedia || restrictedEditDiscard || oldMessage == null || newMessage == null
                    || !ensureInitialized(account)) {
                return;
            }
            if (!isOptedIn(account, dialogId)) {
                return;
            }
            if (newMessage.edit_date <= 0) {
                FileLog.d(TAG + ": EDIT_DATE_UNKNOWN for message " + newMessage.id + " in " + dialogId);
                return;
            }
            Activation act = getActivation(account, dialogId);
            if (!AliGramEligibility.isGateOpen(act.state, act.time)) {
                return;
            }
            MediaIdentity newIdent = identifyMedia(account, newMessage);
            boolean dOn = deleteMasterOn();
            boolean eOn = editMasterOn();
            boolean mt = saveMediaEnabledForDialog(account, dialogId);
            boolean mediaOk = AliGramEligibility.isPreservableClass(newIdent.mediaClass);
            boolean sizeOk = newIdent.expectedFileName != null
                    && AliGramEligibility.isWithinSizeCap(newIdent.mediaClass, newIdent.expectedSize,
                    currentPreset(account), limits());
            if (!AliGramEligibility.canEnrolNewMediaAtEdit(true, dOn, eOn, mt, newIdent.isSecretTtlOnce,
                    mediaOk, sizeOk, newMessage.edit_date, act.time, act.state, false, false)) {
                return;
            }
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (dao == null || blobDao == null) {
                return;
            }
            long userId = ownUserId(account);
            PreservedRef live = dao.getLiveRef(userId, dialogId, newMessage.id);
            if (live != null && live.documentId == newIdent.documentId && live.photoId == newIdent.photoId
                    && live.dcId == newIdent.dcId && live.expectedSize == newIdent.expectedSize) {
                return; // no-op: LIVE ref already names the new media
            }
            if (live != null) {
                // Re-point: cancel the old in-flight download — but NEVER
                // when a freeze pin still waits on the same file. Stage A
                // pinForEdit runs first in the same hook; cancelling its
                // download would strand the freeze copy with no H-C wake-up
                // (the pin has no watchdog; it would only fail at startup
                // after the attempts budget).
                boolean pinNeedsFile = false;
                try {
                    List<PreservedRef> waiting = dao.getWaitingJobsForFile(account, live.expectedFileName);
                    if (waiting != null) {
                        for (PreservedRef w : waiting) {
                            if (w.refId != live.refId
                                    && AliGramEligibility.STATE_PINNED.equals(w.state)) {
                                pinNeedsFile = true;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (!pinNeedsFile) {
                    cancelLoadFileForJob(live);
                }
                if (live.blobId != null) {
                    try {
                        blobDao.decrementRefCount(live.blobId);
                    } catch (Throwable ignored) {
                    }
                    live.blobId = null;
                }
                live.documentId = newIdent.documentId;
                live.photoId = newIdent.photoId;
                live.dcId = newIdent.dcId;
                live.expectedFileName = newIdent.expectedFileName;
                live.expectedSize = newIdent.expectedSize;
                live.originalFileName = newIdent.originalFileName;
                live.photoWidth = newIdent.photoWidth;
                live.photoHeight = newIdent.photoHeight;
                live.editDate = newMessage.edit_date;
                live.state = AliGramEligibility.STATE_ENQUEUED;
                live.source = "LIVE_SYNC";
                live.updatedAt = System.currentTimeMillis() / 1000;
                dao.updateRef(live);
                mirrorRemoveForRef(live);
            } else {
                // C-01: a first LIVE ref for the new media (old message had none).
                live = newPreservedRef(account, userId, dialogId, newMessage.id, "LIVE", 0,
                        newIdent, AliGramEligibility.STATE_ENQUEUED, "LIVE_SYNC");
                live.editDate = newMessage.edit_date;
                try {
                    live.refId = dao.insertRef(live);
                } catch (Throwable e) {
                    return; // idempotent race: another path created it
                }
            }
            File source = FileLoader.getInstance(account).getPathToMessage(newMessage);
            if (source != null && source.toString().length() > 0 && source.isFile() && source.length() > 0) {
                live.sourcePath = source.getAbsolutePath();
                live.sourceSize = source.length();
                live.sourceMtime = source.lastModified();
                dao.updateRef(live);
                mirrorPut(live);
                enqueueCopy(live.refId);
            } else {
                issueDownload(live, newIdent, newMessage);
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": liveSyncEdit failed", e);
        }
    }

    /**
     * Phase B bind (globalQueue, inside onMessageEditedInner, after the
     * no-op early return and the Ayu insert). Assigns revisionSeq and binds
     * the PINNED row to the fresh fakeId; converts LIVE→EDIT on same-media
     * edits; creates the UNAVAILABLE row for policy (i) old revisions.
     */
    public static void bindEditRevision(AyuSavePreferences prefs, TLRPC.Message newMessage,
                                        TLRPC.Message oldMessage, boolean sameMedia, long fakeId) {
        try {
            if (prefs == null || fakeId == 0 || !ensureInitialized(prefs.getAccountId())) {
                return;
            }
            int account = prefs.getAccountId();
            long userId = prefs.getUserId();
            long dialogId = prefs.getDialogId();
            int messageId = prefs.getMessageId();
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (dao == null || blobDao == null) {
                return;
            }
            int seq = dao.nextRevisionSeq(userId, dialogId, messageId);
            if (!sameMedia) {
                PreservedRef pin = dao.getRef(userId, dialogId, messageId, "EDIT", 0);
                if (pin != null && AliGramEligibility.STATE_PINNED.equals(pin.state)) {
                    pin.ayuFakeId = fakeId;
                    pin.revisionSeq = seq;
                    pin.entityCreateDate = prefs.getRequestCatchTime();
                    pin.editDate = oldMessage != null ? oldMessage.edit_date : 0;
                    dao.updateRef(pin);
                    enqueueCopy(pin.refId);
                    return;
                }
                // No pin: freeze was skipped (E off, X, or old media
                // ineligible). Policy (i): record the old revision as
                // UNAVAILABLE when the old media was preservable-class.
                if (editMasterOn() && isOptedIn(account, dialogId) && oldMessage != null
                        && !AyuSavePreferences.getSaveDeletedExclusion(dialogId)) {
                    MediaIdentity oldIdent = identifyMedia(account, oldMessage);
                    if (AliGramEligibility.isPreservableClass(oldIdent.mediaClass)) {
                        PreservedRef unavailable = newPreservedRef(account, userId, dialogId, messageId,
                                "EDIT", seq, oldIdent, AliGramEligibility.STATE_UNAVAILABLE, "EDIT_FREEZE");
                        unavailable.ayuFakeId = fakeId;
                        unavailable.entityCreateDate = prefs.getRequestCatchTime();
                        unavailable.editDate = oldMessage.edit_date;
                        Activation act = getActivation(account, dialogId);
                        unavailable.lastError = oldMessage.date < act.time
                                ? AliGramEligibility.REASON_PRE_ACTIVATION_MEDIA
                                : AliGramEligibility.REASON_NO_PIN_SITE;
                        try {
                            dao.insertRef(unavailable);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return;
            }
            // sameMedia: LIVE ref converts to this EDIT ref; a new LIVE ref
            // is created pointing at the same blob (refcount +1). No copy.
            PreservedRef live = dao.getLiveRef(userId, dialogId, messageId);
            if (live != null) {
                String blobId = live.blobId;
                String liveState = live.state;
                File liveFile = liveBlobByMessage.remove(liveBlobKey(account, dialogId, messageId));
                live.role = "EDIT";
                live.ayuFakeId = fakeId;
                live.revisionSeq = seq;
                live.entityCreateDate = prefs.getRequestCatchTime();
                live.editDate = oldMessage != null ? oldMessage.edit_date : 0;
                dao.updateRef(live);
                if (liveFile != null && liveFile.isFile()) {
                    resolvedBlobByFakeId.put(fakeId, liveFile);
                }
                MediaIdentity ident = new MediaIdentity();
                ident.documentId = live.documentId;
                ident.photoId = live.photoId;
                ident.dcId = live.dcId;
                ident.expectedFileName = live.expectedFileName;
                ident.expectedSize = live.expectedSize;
                ident.originalFileName = live.originalFileName;
                ident.photoWidth = live.photoWidth;
                ident.photoHeight = live.photoHeight;
                PreservedRef newLive = newPreservedRef(account, userId, dialogId, messageId,
                        "LIVE", 0, ident, AliGramEligibility.STATE_ENQUEUED, "EDIT_FREEZE");
                newLive.blobId = blobId;
                newLive.sourcePath = live.sourcePath;
                newLive.sourceSize = live.sourceSize;
                newLive.sourceMtime = live.sourceMtime;
                newLive.editDate = newMessage != null ? newMessage.edit_date : 0;
                if (AliGramEligibility.STATE_COPIED.equals(liveState) && blobId != null) {
                    newLive.state = AliGramEligibility.STATE_COPIED;
                    try {
                        blobDao.incrementRefCount(blobId);
                    } catch (Throwable ignored) {
                    }
                } else if (AliGramEligibility.STATE_DOWNLOADING.equals(liveState)
                        || AliGramEligibility.STATE_COPYING.equals(liveState)) {
                    // The download is shared: H-C fan-out claims both refs by file name.
                    newLive.state = AliGramEligibility.STATE_ENQUEUED;
                } else {
                    newLive.state = liveState;
                }
                try {
                    newLive.refId = dao.insertRef(newLive);
                    if (newLive.sourcePath != null) {
                        mirrorPut(newLive);
                    }
                    if (AliGramEligibility.STATE_COPIED.equals(newLive.state) && blobId != null) {
                        try {
                            PreservedBlob blob = blobDao.getById(blobId);
                            noteCopiedFile(newLive, blobId, blob == null ? "" : blob.ext);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else if (oldMessage != null && editMasterOn() && isOptedIn(account, dialogId)) {
                // No LIVE ref (message never enrolled): policy (i)+(ii).
                MediaIdentity oldIdent = identifyMedia(account, oldMessage);
                if (AliGramEligibility.isPreservableClass(oldIdent.mediaClass)) {
                    PreservedBlob existingBlob = findBlobForIdentity(account, blobDao, oldIdent);
                    PreservedRef editRef = newPreservedRef(account, userId, dialogId, messageId,
                            "EDIT", seq, oldIdent, AliGramEligibility.STATE_UNAVAILABLE, "EDIT_FREEZE");
                    editRef.ayuFakeId = fakeId;
                    editRef.entityCreateDate = prefs.getRequestCatchTime();
                    editRef.editDate = oldMessage.edit_date;
                    if (existingBlob != null) {
                        // (ii): bytes already durable via another message — zero new I/O.
                        editRef.blobId = existingBlob.blobId;
                        editRef.state = AliGramEligibility.STATE_COPIED;
                        try {
                            blobDao.incrementRefCount(existingBlob.blobId);
                        } catch (Throwable ignored) {
                        }
                    } else {
                        Activation act = getActivation(account, dialogId);
                        editRef.lastError = oldMessage.date < act.time
                                ? AliGramEligibility.REASON_PRE_ACTIVATION_MEDIA
                                : AliGramEligibility.REASON_NO_PIN_SITE;
                    }
                    try {
                        dao.insertRef(editRef);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": bindEditRevision failed", e);
        }
    }

    // ==================================================================
    // H-D delete binding + anchor loss (Phase 2.3 §4.4/§4.5)
    // ==================================================================

    /** LIVE → DELETED, bound to DeletedMessage.fakeId (called after the Ayu insert). */
    public static void bindDeleted(AyuSavePreferences prefs, long fakeMsgId) {
        try {
            if (prefs == null || fakeMsgId == 0 || !ensureInitialized(prefs.getAccountId())) {
                return;
            }
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            PreservedRef live = dao.getLiveRef(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId());
            if (live == null) {
                return; // legacy metadata-only row; nothing to bind
            }
            File liveFile = liveBlobByMessage.remove(
                    liveBlobKey(prefs.getAccountId(), prefs.getDialogId(), prefs.getMessageId()));
            live.role = "DELETED";
            live.ayuFakeId = fakeMsgId;
            live.entityCreateDate = prefs.getRequestCatchTime();
            dao.updateRef(live);
            if (liveFile != null && liveFile.isFile()) {
                resolvedBlobByFakeId.put(fakeMsgId, liveFile);
            } else if (live.blobId != null) {
                try {
                    PreservedBlobDao blobDao = AliGramData.blobDao();
                    PreservedBlob blob = blobDao == null ? null : blobDao.getById(live.blobId);
                    if (blob != null) {
                        noteCopiedFile(live, live.blobId, blob.ext);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": bindDeleted failed", e);
        }
    }

    /**
     * Delete event while the delete axis is off or blocked (D off / X /
     * bot): the pending LIVE job is terminated ANCHOR_LOST (not GATE_OFF)
     * and both halves are cancelled. Called from onMessageDeletedInner's
     * gate-blocked early return.
     */
    public static void onDeleteGateBlocked(AyuSavePreferences prefs) {
        try {
            if (prefs == null || !ensureInitialized(prefs.getAccountId())) {
                return;
            }
            terminateLiveForDelete(prefs.getAccountId(), prefs.getUserId(), prefs.getDialogId(),
                    prefs.getMessageId());
        } catch (Throwable e) {
            FileLog.e(TAG + ": onDeleteGateBlocked failed", e);
        }
    }

    /** Drop a LIVE ref whose anchor is gone: terminate the job, sweep the blob at refcount 0. */
    public static void terminateLiveForDelete(int account, long userId, long dialogId, int messageId) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (dao == null || blobDao == null) {
                return;
            }
            PreservedRef live = dao.getLiveRef(userId, dialogId, messageId);
            if (live == null) {
                return;
            }
            if (AliGramEligibility.isTerminalState(live.state)
                    && !AliGramEligibility.STATE_COPIED.equals(live.state)) {
                return;
            }
            if (AliGramEligibility.STATE_DOWNLOADING.equals(live.state)
                    || AliGramEligibility.STATE_ENQUEUED.equals(live.state)
                    || AliGramEligibility.STATE_COPYING.equals(live.state)) {
                cancelLoadFileForJob(live);
            }
            if (live.blobId != null) {
                try {
                    blobDao.decrementRefCount(live.blobId);
                } catch (Throwable ignored) {
                }
            }
            if (live.expectedFileName != null) {
                pendingLoads.remove(pendingKey(account, live.expectedFileName));
            }
            dao.deleteRefById(live.refId);
            mirrorRemoveForRef(live);
            forgetResolvedFilesForRef(live);
            // Phase 3.1 (D-07): this path inlines its own delete instead
            // of going through dropRefAndDecrement, so it needs the
            // release explicitly — otherwise a LIVE job dropped
            // ANCHOR_LOST (delete-event with the delete axis off, or the
            // mark-path sweep) leaves its deferred deletes for the
            // startup sweep only. Same idempotency as the funnel: only
            // PENDING rows for this ref, active pins re-checked.
            releaseDeferredDeletesForRef(account, live.refId);
            FileLog.d(TAG + ": LIVE ref dropped ANCHOR_LOST for message " + messageId + " in " + dialogId);
            sweepRefcountZeroBlobs(account);
        } catch (Throwable e) {
            FileLog.e(TAG + ": terminateLiveForDelete failed", e);
        }
    }

    // --- Captured-delete notes (snapshot runnable → mark runnable, same storage queue) ---

    private static String capturedKey(int account, long dialogId, int messageId) {
        return account + "|" + dialogId + "|" + messageId;
    }

    /** Called by the Ayu snapshot runnable for every message it posts to onMessageDeleted. */
    public static void noteCapturedDelete(int account, long dialogId, int messageId) {
        capturedDeletes.put(capturedKey(account, dialogId, messageId), System.currentTimeMillis());
    }

    private static boolean consumeCapturedDelete(int account, long dialogId, int messageId) {
        return capturedDeletes.remove(capturedKey(account, dialogId, messageId)) != null;
    }

    /**
     * Mark-path sweep (storage queue, end of the normal delete branch):
     * for every deleted message that was NOT captured by the snapshot, the
     * LIVE job is terminated ANCHOR_LOST. Captured messages are skipped —
     * their binding runs on globalQueue and must not be raced.
     */
    public static void onMessagesMarkedDeleted(int account, long dialogId, List<Integer> messageIds) {
        try {
            if (messageIds == null || messageIds.isEmpty() || !ensureInitialized(account)) {
                return;
            }
            long userId = ownUserId(account);
            for (Integer messageId : messageIds) {
                if (messageId == null) {
                    continue;
                }
                if (consumeCapturedDelete(account, dialogId, messageId)) {
                    continue;
                }
                terminateLiveForDelete(account, userId, dialogId, messageId);
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": onMessagesMarkedDeleted failed", e);
        }
    }

    /** Drop one ref row, decrementing its blob (swept at refcount 0 by the caller). */
    private static void dropRefAndDecrement(PreservedRef ref) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (dao == null) {
                return;
            }
            if (ref.blobId != null && blobDao != null) {
                try {
                    blobDao.decrementRefCount(ref.blobId);
                } catch (Throwable ignored) {
                }
            }
            if (ref.expectedFileName != null) {
                pendingLoads.remove(pendingKey(ref.accountId, ref.expectedFileName));
            }
            dao.deleteRefById(ref.refId);
            mirrorRemoveForRef(ref);
            forgetResolvedFilesForRef(ref);
            // Phase 3.1 (D-07): the single funnel for all anchor-loss
            // drops — ending the pin releases this ref's deferred
            // deletes now instead of leaving them for the startup sweep.
            // Idempotent with the per-ref releases the four dialog flows
            // (onDialogDeleted / onDialogClearedExcept /
            // onChannelHistoryCleared / clearPreservedForDialog) already
            // do after calling here: those find the rows DONE and no-op
            // (release only touches PENDING rows, and the release
            // re-checks active pins per path, so a file another ref
            // still pins survives). Verified: the sibling D-06 fix added
            // those per-ref releases; this funnel call makes the release
            // happen for every drop path, including future callers.
            releaseDeferredDeletesForRef(ref.accountId, ref.refId);
        } catch (Throwable e) {
            FileLog.e(TAG + ": dropRefAndDecrement failed", e);
        }
    }

    /** Whole-dialog delete / clear (P-DLG): drop that dialog's refs, release as anchor loss. */
    public static void onDialogDeleted(int account, long dialogId) {
        try {
            if (!ensureInitialized(account)) {
                return;
            }
            copyQueue.postRunnable(() -> {
                try {
                    PreservedRefDao dao = AliGramData.refDao();
                    if (dao == null) {
                        return;
                    }
                    long userId = ownUserId(account);
                    // Every ref of the dialog (all roles): the dialog's
                    // messages are gone, so no ref stays anchored.
                    // Phase 3.1: dropping a ref ends its pin, so each
                    // dropped ref's deferred rows are released right here
                    // (per-ref, exact dialog — never account-wide). The
                    // release re-checks active pins per path, so a file
                    // another dialog still pins survives. Rows with
                    // refId == 0 are unattributed and are released only
                    // by the startup sweep, never here.
                    for (PreservedRef ref : dao.getRefsForDialog(userId, dialogId)) {
                        if (AliGramEligibility.isActivePinState(ref.state)) {
                            cancelLoadFileForJob(ref);
                        }
                        dropRefAndDecrement(ref);
                        releaseDeferredDeletesForRef(account, ref.refId);
                    }
                    sweepRefcountZeroBlobs(account);
                } catch (Throwable e) {
                    FileLog.e(TAG + ": onDialogDeleted failed", e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(TAG + ": onDialogDeleted failed", e);
        }
    }

    /** deleteDialog(messagesOnly == 2) keeps the last two messages: drop the
     * refs of every other message in the dialog, leaving the kept ids anchored. */
    public static void onDialogClearedExcept(int account, long dialogId, ArrayList<Integer> keptMessageIds) {
        try {
            if (!ensureInitialized(account)) {
                return;
            }
            copyQueue.postRunnable(() -> {
                try {
                    PreservedRefDao dao = AliGramData.refDao();
                    if (dao == null) {
                        return;
                    }
                    long userId = ownUserId(account);
                    HashSet<Integer> kept = new HashSet<>(keptMessageIds == null ? new ArrayList<>() : keptMessageIds);
                    for (PreservedRef ref : dao.getRefsForDialog(userId, dialogId)) {
                        if (kept.contains((int) ref.messageId)) {
                            continue;
                        }
                        if (AliGramEligibility.isActivePinState(ref.state)) {
                            cancelLoadFileForJob(ref);
                        }
                        dropRefAndDecrement(ref);
                        // Phase 3.1: per-ref release (see onDialogDeleted).
                        // Kept messages' refs are not dropped, so their
                        // deferred rows are never released here.
                        releaseDeferredDeletesForRef(account, ref.refId);
                    }
                    sweepRefcountZeroBlobs(account);
                } catch (Throwable e) {
                    FileLog.e(TAG + ": onDialogClearedExcept failed", e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(TAG + ": onDialogClearedExcept failed", e);
        }
    }

    /** Clear-history (single-message internal): drop the LIVE refs of the
     * channel with messageId <= maxMessageId — their anchor rows are gone.
     * DELETED / EDIT refs keep their Ayu anchors and are untouched. */
    public static void onChannelHistoryCleared(int account, long dialogId, int maxMessageId) {
        try {
            if (!ensureInitialized(account)) {
                return;
            }
            copyQueue.postRunnable(() -> {
                try {
                    PreservedRefDao dao = AliGramData.refDao();
                    if (dao == null) {
                        return;
                    }
                    long userId = ownUserId(account);
                    for (PreservedRef ref : dao.getRefsForDialog(userId, dialogId)) {
                        if (!"LIVE".equals(ref.role) || ref.messageId > maxMessageId) {
                            continue;
                        }
                        if (AliGramEligibility.isActivePinState(ref.state)) {
                            cancelLoadFileForJob(ref);
                        }
                        dropRefAndDecrement(ref);
                        // Phase 3.1: per-ref release (see onDialogDeleted).
                        // Refs above maxMessageId keep their pins, so their
                        // deferred rows are never released here.
                        releaseDeferredDeletesForRef(account, ref.refId);
                    }
                    sweepRefcountZeroBlobs(account);
                } catch (Throwable e) {
                    FileLog.e(TAG + ": onChannelHistoryCleared failed", e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(TAG + ": onChannelHistoryCleared failed", e);
        }
    }

    // ==================================================================
    // Startup reconciliation (Phase 2.2 §5.6/§7.3, Phase 2.3 §2.2(e))
    // ==================================================================

    private static void startupReconcile(int account) {
        PreservedRefDao dao = AliGramData.refDao();
        PreservedBlobDao blobDao = AliGramData.blobDao();
        DeferredDeleteDao deferredDao = AliGramData.deferredDao();
        if (dao == null || blobDao == null || deferredDao == null) {
            return;
        }
        // 0. Rebuild the UI-safe render-resolution cache from durable rows.
        rebuildRenderCache(account);
        // 1. Sweep .tmp-* files older than one run.
        try {
            File blobDir = AliGramFileCopy.getBlobDir(account);
            if (blobDir.isDirectory()) {
                File[] files = blobDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().contains(".tmp-")) {
                            AliGramFileCopy.deleteQuietly(f);
                        }
                    }
                    // Phase 3.1 (D-09): also sweep orphan pending-*.blob
                    // files — a crash between the two renames in
                    // copyForRef leaves one behind, and the old sweep
                    // only matched ".tmp-". A pending file is owned by
                    // a live non-terminal ref of the same id; anything
                    // else (no ref, terminal ref, unparseable name) is
                    // orphaned. A live job's stale pending file is
                    // KEPT here — copyVerified deletes + retries it when
                    // the copy is re-driven (D-09 fix (1)).
                    for (File f : files) {
                        String name = f.getName();
                        if (!name.startsWith("pending-") || !name.endsWith(".blob")) {
                            continue;
                        }
                        boolean owned = false;
                        try {
                            long ownerId = Long.parseLong(name.substring("pending-".length(), name.length() - ".blob".length()));
                            PreservedRef owner = dao.getRefById(ownerId);
                            owned = owner != null && owner.accountId == account
                                    && !AliGramEligibility.isTerminalState(owner.state);
                        } catch (Throwable ignored) {
                            owned = false;
                        }
                        if (!owned) {
                            AliGramFileCopy.deleteQuietly(f);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": tmp sweep failed", e);
        }
        // 2. Backup split-brain: refs whose blob file is missing → UNAVAILABLE.
        try {
            for (PreservedRef ref : dao.getAllRefs()) {
                if (ref.accountId != account || ref.blobId == null
                        || AliGramEligibility.STATE_UNAVAILABLE.equals(ref.state)) {
                    continue;
                }
                PreservedBlob blob = blobDao.getById(ref.blobId);
                if (blob == null) {
                    ref.blobId = null;
                    ref.state = AliGramEligibility.STATE_UNAVAILABLE;
                    ref.lastError = AliGramEligibility.REASON_SOURCE_GONE;
                    dao.updateRef(ref);
                    continue;
                }
                File blobFile = AliGramFileCopy.blobFileFor(account, blob.blobId, blob.ext);
                if (!blobFile.isFile()) {
                    ref.blobId = null;
                    ref.state = AliGramEligibility.STATE_UNAVAILABLE;
                    ref.lastError = AliGramEligibility.REASON_SOURCE_GONE;
                    dao.updateRef(ref);
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": split-brain reconcile failed", e);
        }
        // 3. ayuFakeId verification (cross-device import degrades gracefully).
        try {
            verifyAyuFakeIds(account);
        } catch (Throwable e) {
            FileLog.e(TAG + ": fakeId verification failed", e);
        }
        // D-12 synthesis (BEFORE step 4, deliberately — not in step 5 as
        // the audit suggests its placement): pendingDeletePaths is the
        // crash-recovery seed, written at pin time but never read — if
        // the process died between the pin insert and the Layer-1 record
        // in the same storage pass, no DeferredDelete row exists for the
        // file. Synthesise one per recorded path (INSERT OR IGNORE: a
        // row Layer-1 already recorded is not duplicated; the size/mtime
        // come from the ref, or are statted at record time when 0,0).
        // It runs before step 4 so that step 4's terminal transitions
        // (GATE_OFF / UNAVAILABLE / FAILED) release the synthesised rows
        // through their normal release calls; rows for pins that stay
        // active are skipped by the release's active-pin re-check and
        // release when the pin terminates. Cancel intent comes from the
        // pin (cancelDeferred) for the same reason.
        try {
            for (PreservedRef ref : dao.getNonTerminalRefs()) {
                if (ref.accountId != account) {
                    continue;
                }
                if (!AliGramEligibility.STATE_PINNED.equals(ref.state)
                        && !AliGramEligibility.STATE_COPYING.equals(ref.state)) {
                    continue;
                }
                if (ref.pendingDeletePaths == null || ref.pendingDeletePaths.isEmpty()) {
                    continue;
                }
                for (String rawPath : ref.pendingDeletePaths.split(",")) {
                    String path = rawPath == null ? "" : rawPath.trim();
                    if (path.isEmpty()) {
                        continue;
                    }
                    recordDeferredDelete(account, ref.refId, path, ref.sourceSize, ref.sourceMtime,
                            "EDIT_TAIL", ref.cancelDeferred);
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": deferred-delete synthesis failed", e);
        }
        // 4. Reset DOWNLOADING/COPYING → ENQUEUED after gate re-check;
        //    re-drive PINNED rows whose source is on disk; PINNED rows
        //    with no source go straight to UNAVAILABLE (D-11 — they can
        //    never be re-downloaded after a restart, so the attempts
        //    budget is not burned on them).
        try {
            long userId = ownUserId(account);
            for (PreservedRef ref : dao.getNonTerminalRefs()) {
                if (ref.accountId != account) {
                    continue;
                }
                boolean gatesOn = isOptedIn(account, ref.dialogId)
                        && (deleteMasterOn() || editMasterOn())
                        && saveMediaEnabledForDialog(account, ref.dialogId)
                        && !isRoaming(account);
                if (!gatesOn) {
                    if (AliGramEligibility.STATE_PINNED.equals(ref.state)) {
                        continue; // in-flight freeze copies complete and bind (C-15)
                    }
                    terminateJob(ref, AliGramEligibility.STATE_GATE_OFF, "startup",
                            AliGramEligibility.REASON_GATE_OFF);
                    continue;
                }
                if (AliGramEligibility.STATE_DOWNLOADING.equals(ref.state)
                        || AliGramEligibility.STATE_COPYING.equals(ref.state)) {
                    ref.state = AliGramEligibility.STATE_ENQUEUED;
                    ref.updatedAt = System.currentTimeMillis() / 1000;
                    dao.updateRef(ref);
                    redriveEnqueuedJob(account, ref);
                } else if (AliGramEligibility.STATE_PINNED.equals(ref.state)) {
                    File source = ref.sourcePath == null ? null : new File(ref.sourcePath);
                    if (source != null && source.isFile()) {
                        copyQueue.postRunnable(() -> {
                            try {
                                copyForRef(ref.refId, source);
                            } catch (Throwable e) {
                                FileLog.e(TAG + ": pinned re-drive failed", e);
                            }
                        });
                    } else {
                        // Phase 3.1 (D-11): a PINNED row whose source
                        // never materialised cannot be re-downloaded
                        // after a restart — the old TL object and the
                        // PendingLoad are gone with the process, and
                        // redriveEnqueuedJob rebuilds the NEW message,
                        // not the old media (R-08: old-media re-download
                        // after restart is out of v1 scope). Do NOT burn
                        // the attempts budget over three restarts: go
                        // straight to UNAVAILABLE (not FAILED) with the
                        // distinct NO_PIN_SOURCE reason, and release its
                        // deferred deletes now.
                        ref.state = AliGramEligibility.STATE_UNAVAILABLE;
                        ref.lastError = AliGramEligibility.REASON_NO_PIN_SOURCE;
                        ref.updatedAt = System.currentTimeMillis() / 1000;
                        dao.updateRef(ref);
                        mirrorRemoveForRef(ref);
                        releaseDeferredDeletesForRef(account, ref.refId);
                    }
                } else if (AliGramEligibility.STATE_ENQUEUED.equals(ref.state)) {
                    redriveEnqueuedJob(account, ref);
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": gate re-check/re-drive failed", e);
        }
        // 5. Sweep unanchored refs + refcount-0 blobs; release orphaned deferred deletes.
        try {
            sweepRefcountZeroBlobs(account);
            // Account-scoped (Phase 3.1): never iterate all accounts' rows
            // here. releaseDeferredRow re-checks active pins per path, so
            // a file another dialog/account still pins is never deleted.
            // (One deliberate unification: rows with cancelDeferred=true
            // now also cancel the load, as the per-ref release does. At
            // startup no load is in flight in this process, so the cancel
            // is a no-op.)
            for (DeferredDelete row : deferredDao.getPendingForAccount(account)) {
                releaseDeferredRow(account, row);
            }
            long cutoff = System.currentTimeMillis() - 3600_000L;
            capturedDeletes.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        } catch (Throwable e) {
            FileLog.e(TAG + ": startup sweep failed", e);
        }
    }

    /**
     * Re-drive an ENQUEUED job after restart. If the file is already on
     * disk, copy it; otherwise re-obtain the message from cache4.db
     * (MessagesStorage.getMessage) and re-issue the download. When the
     * message itself is gone (or the TL object cannot be rebuilt), the job
     * is FAILED — never silently claimed, and never re-driven in a loop.
     */
    private static void redriveEnqueuedJob(int account, PreservedRef ref) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            if (ref.sourcePath != null && !ref.sourcePath.isEmpty()) {
                File source = new File(ref.sourcePath);
                if (source.isFile() && source.length() > 0) {
                    copyQueue.postRunnable(() -> {
                        try {
                            copyForRef(ref.refId, source);
                        } catch (Throwable e) {
                            FileLog.e(TAG + ": redrive copy failed", e);
                        }
                    });
                    return;
                }
            }
            TLRPC.Message message = null;
            try {
                message = MessagesStorage.getInstance(account).getMessage(ref.dialogId, ref.messageId);
            } catch (Throwable e) {
                FileLog.e(TAG + ": redrive message lookup failed", e);
            }
            if (message == null) {
                ref.state = AliGramEligibility.STATE_FAILED;
                ref.lastError = AliGramEligibility.REASON_DOWNLOAD_FAILED;
                ref.updatedAt = System.currentTimeMillis() / 1000;
                dao.updateRef(ref);
                mirrorRemoveForRef(ref);
                releaseDeferredDeletesForRef(account, ref.refId);
                return;
            }
            MediaIdentity ident = identifyMedia(account, message);
            if (ident.expectedFileName == null || !ident.expectedFileName.equals(ref.expectedFileName)) {
                // Identity drift since enqueue: re-derive from the live message.
                ref.expectedFileName = ident.expectedFileName;
                ref.documentId = ident.documentId;
                ref.photoId = ident.photoId;
                ref.dcId = ident.dcId;
                ref.expectedSize = ident.expectedSize;
                dao.updateRef(ref);
            }
            File source = FileLoader.getInstance(account).getPathToMessage(message);
            if (source != null && source.toString().length() > 0 && source.isFile() && source.length() > 0) {
                ref.sourcePath = source.getAbsolutePath();
                ref.sourceSize = source.length();
                ref.sourceMtime = source.lastModified();
                dao.updateRef(ref);
                mirrorPut(ref);
                enqueueCopy(ref.refId);
            } else {
                issueDownload(ref, ident, message);
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": redriveEnqueuedJob failed", e);
        }
    }

    /** Each stored ayuFakeId != 0 must still resolve to an Ayu row with matching identity. */
    private static void verifyAyuFakeIds(int account) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            if (dao == null) {
                return;
            }
            // Touching AyuData runs its static{} DB init on first access.
            for (PreservedRef ref : dao.getAllRefs()) {
                if (ref.accountId != account || ref.ayuFakeId == 0) {
                    continue;
                }
                boolean ok = false;
                try {
                    if ("EDIT".equals(ref.role)) {
                        ok = com.radolyn.ayugram.database.AyuData.getEditedMessageDao()
                                .existsByFakeId(ref.ayuFakeId);
                    } else if ("DELETED".equals(ref.role)) {
                        ok = com.radolyn.ayugram.database.AyuData.getDeletedMessageDao()
                                .existsByFakeId(ref.ayuFakeId);
                    } else {
                        ok = true; // LIVE refs have no Ayu row yet
                    }
                } catch (Throwable ignored) {
                    ok = true; // verification unavailable: do not drop rows on a probe failure
                }
                if (!ok) {
                    ref.state = AliGramEligibility.STATE_UNAVAILABLE;
                    ref.lastError = "FAKEID_DANGLING";
                    dao.updateRef(ref);
                    FileLog.d(TAG + ": dangling ayuFakeId " + ref.ayuFakeId + " → UNAVAILABLE");
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": verifyAyuFakeIds failed", e);
        }
    }

    /** Rebuild the UI-safe render-resolution cache from durable COPIED refs (startup). */
    private static void rebuildRenderCache(int account) {
        try {
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (dao == null || blobDao == null) {
                return;
            }
            for (PreservedRef ref : dao.getAllRefs()) {
                if (ref.accountId != account || !AliGramEligibility.STATE_COPIED.equals(ref.state)
                        || ref.blobId == null) {
                    continue;
                }
                PreservedBlob blob = blobDao.getById(ref.blobId);
                if (blob == null) {
                    continue;
                }
                File file = AliGramFileCopy.blobFileFor(account, blob.blobId, blob.ext);
                if (!file.isFile()) {
                    continue;
                }
                if ("LIVE".equals(ref.role)) {
                    liveBlobByMessage.put(liveBlobKey(account, ref.dialogId, ref.messageId), file);
                }
                if (ref.ayuFakeId != 0) {
                    resolvedBlobByFakeId.put(ref.ayuFakeId, file);
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": rebuildRenderCache failed", e);
        }
    }

    // ==================================================================
    // Render resolution (H-R) + account purge + settings stats
    // ==================================================================

    /** Resolve the preserved blob file for a revision (by Ayu fakeId). Null when none.
     *  UI-safe: a map lookup only, never a Room query (render runs on the UI thread). */
    public static File resolvePreservedFileForRevision(int account, long ayuFakeId) {
        if (ayuFakeId == 0) {
            return null;
        }
        File file = resolvedBlobByFakeId.get(ayuFakeId);
        return file != null && file.isFile() ? file : null;
    }

    /** Resolve the preserved blob file for a live message (LIVE ref, COPIED). Null when none.
     *  UI-safe: a map lookup only, never a Room query. */
    public static File resolvePreservedFileForMessage(int account, long dialogId, int messageId) {
        File file = liveBlobByMessage.get(liveBlobKey(account, dialogId, messageId));
        return file != null && file.isFile() ? file : null;
    }

    /** Storage stats for the settings row: {enabledChats, blobCount, totalBytes}.
     *  Background thread only (Room queries). Callers on the UI thread must
     *  post to a background queue and update the UI from the result. */
    /** Settings "clear preserved media": drop every ref + deferred row of
     * this account, delete the blob subtree, and drop blob rows no ref
     * points at. Per-chat opt-ins and activation timestamps are KEPT
     * (unlike purgeAccount) so enabled chats keep preserving new media. */
    public static void clearPreservedStorage(int account) {
        try {
            if (ensureInitialized(account)) {
                copyQueue.postRunnable(() -> {
                    try {
                        PreservedRefDao dao = AliGramData.refDao();
                        DeferredDeleteDao deferredDao = AliGramData.deferredDao();
                        if (dao != null) {
                            // Phase 3.1 (D-02): collect the rows first,
                            // delete them, and only THEN clean the
                            // mirrors — mirrorRemoveForRef re-checks the
                            // DAO for other active refs, so cleaning
                            // before the delete would see the doomed rows
                            // as still active and leave stale-pinned
                            // entries behind.
                            ArrayList<PreservedRef> accountRefs = new ArrayList<>();
                            for (PreservedRef ref : dao.getAllRefs()) {
                                if (ref.accountId == account) {
                                    accountRefs.add(ref);
                                }
                            }
                            // Phase 3.1 (D-03): key the delete on the
                            // account id, not the user id (same trap as
                            // purgeAccount — the user id is not stable
                            // across account lifecycle events).
                            dao.deleteRefsForAccountId(account);
                            for (PreservedRef ref : accountRefs) {
                                forgetResolvedFilesForRef(ref);
                                mirrorRemoveForRef(ref);
                            }
                        }
                        if (deferredDao != null) {
                            // Account-wide ON PURPOSE here (settings clear-all):
                            // this operation means "this account, everything".
                            // Never use deleteForAccount from a dialog flow.
                            deferredDao.deleteForAccount(account);
                        }
                        sweepUnreferencedBlobs();
                        File blobDir = AliGramFileCopy.getBlobDir(account);
                        deleteDirectoryRecursive(blobDir);
                        pendingLoads.entrySet().removeIf(entry -> entry.getKey().startsWith(account + "|"));
                    } catch (Throwable e) {
                        FileLog.e(TAG + ": clearPreservedStorage worker failed", e);
                    }
                });
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": clearPreservedStorage failed", e);
        }
    }

    /** Drop blob rows no PreservedRef points at (recomputed, ignoring the
     * stored refCount — a purge/clear leaves it stale). */
    private static void sweepUnreferencedBlobs() {
        try {
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (blobDao == null) {
                return;
            }
            for (PreservedBlob blob : blobDao.getUnreferencedBlobs()) {
                blobDao.deleteById(blob.blobId);
            }
        } catch (Throwable ignored) {
        }
    }

    public static long[] getPreservedStats(int account) {
        try {
            if (!ensureInitialized(account)) {
                return new long[]{0, 0, 0};
            }
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            int enabledChats = 0;
            try {
                for (Map.Entry<String, ?> entry : prefsFor(account).getAll().entrySet()) {
                    if (entry.getKey().startsWith(KEY_FLAG_PREFIX) && Boolean.TRUE.equals(entry.getValue())) {
                        enabledChats++;
                    }
                }
            } catch (Throwable ignored) {
            }
            // Per-account (the blob table itself is global, but a blob only
            // counts here when one of this account's refs points at it).
            long blobs = 0;
            long bytes = 0;
            try {
                if (dao != null && blobDao != null) {
                    HashSet<String> seen = new HashSet<>();
                    for (PreservedRef ref : dao.getRefsForAccount(account)) {
                        if (ref.blobId == null || !seen.add(ref.blobId)) {
                            continue;
                        }
                        PreservedBlob blob = blobDao.getById(ref.blobId);
                        if (blob != null) {
                            blobs++;
                            bytes += blob.size;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return new long[]{enabledChats, blobs, bytes};
        } catch (Throwable e) {
            return new long[]{0, 0, 0};
        }
    }

    /** Per-chat stats for the chat dialog: {refs, copied blobs, bytes}. */
    public static long[] getPreservedStatsForDialog(int account, long dialogId) {
        try {
            if (!ensureInitialized(account)) {
                return new long[]{0, 0, 0};
            }
            PreservedRefDao dao = AliGramData.refDao();
            PreservedBlobDao blobDao = AliGramData.blobDao();
            if (dao == null || blobDao == null) {
                return new long[]{0, 0, 0};
            }
            List<PreservedRef> refs = dao.getRefsForDialog(ownUserId(account), dialogId);
            long blobs = 0;
            long bytes = 0;
            HashSet<String> seen = new HashSet<>();
            for (PreservedRef ref : refs) {
                if (ref.blobId == null || !seen.add(ref.blobId)) {
                    continue;
                }
                PreservedBlob blob = blobDao.getById(ref.blobId);
                if (blob != null) {
                    blobs++;
                    bytes += blob.size;
                }
            }
            return new long[]{refs.size(), blobs, bytes};
        } catch (Throwable e) {
            return new long[]{0, 0, 0};
        }
    }

    /** Explicit per-chat clear: refs deleted, refcount-0 blobs deleted. */
    public static void clearPreservedForDialog(int account, long dialogId) {
        try {
            if (!ensureInitialized(account)) {
                return;
            }
            copyQueue.postRunnable(() -> {
                try {
                    PreservedRefDao dao = AliGramData.refDao();
                    if (dao == null) {
                        return;
                    }
                    long userId = ownUserId(account);
                    // Phase 3.1 (critical fix): the old code called
                    // deferredDao.deleteForAccount(account) here, deleting
                    // the WHOLE account's deferred rows for a per-chat
                    // clear. The fix releases exactly this dialog's rows,
                    // per dropped ref — the release re-checks active pins
                    // per path, so a file another dialog still pins
                    // survives, and rows of other dialogs/accounts are
                    // never touched. Unattributed (refId == 0) rows are
                    // left for the startup sweep.
                    for (PreservedRef ref : dao.getRefsForDialog(userId, dialogId)) {
                        dropRefAndDecrement(ref);
                        releaseDeferredDeletesForRef(account, ref.refId);
                    }
                    sweepRefcountZeroBlobs(account);
                } catch (Throwable e) {
                    FileLog.e(TAG + ": clearPreservedForDialog failed", e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(TAG + ": clearPreservedForDialog failed", e);
        }
    }

    /** Account removal (P-8): purge that account's refs and blob subtree. */
    public static void purgeAccount(int account) {
        try {
            if (ensureInitialized(account)) {
                copyQueue.postRunnable(() -> {
                    try {
                        PreservedRefDao dao = AliGramData.refDao();
                        DeferredDeleteDao deferredDao = AliGramData.deferredDao();
                        if (dao != null) {
                            // Phase 3.1 (D-02): collect the rows first,
                            // delete them, and only THEN clean the
                            // mirrors — mirrorRemoveForRef re-checks the
                            // DAO for other active refs, so cleaning
                            // before the delete would see the doomed rows
                            // as still active and leave stale-pinned
                            // entries behind.
                            ArrayList<PreservedRef> accountRefs = new ArrayList<>();
                            for (PreservedRef ref : dao.getAllRefs()) {
                                if (ref.accountId == account) {
                                    accountRefs.add(ref);
                                }
                            }
                            // Phase 3.1 (D-03, RELEASE-BLOCKING): delete
                            // by accountId, NOT by userId. performLogout
                            // calls UserConfig.clearConfig() (clientUserId
                            // = 0) BEFORE this hook, so the old
                            // deleteRefsForAccount(ownUserId(account))
                            // deleted zero rows and every PreservedRef /
                            // PreservedBlob row survived logout.
                            // Residual note (audit §1.2 D-03): the purge
                            // still runs as a copyQueue runnable — if the
                            // process dies between logout and the runnable,
                            // the purge never runs. It cannot be made
                            // synchronous here: performLogout runs on the
                            // UI thread and Room forbids main-thread
                            // queries. The rows are inert without the blob
                            // subtree/prefs, and the next startup's
                            // split-brain check marks them UNAVAILABLE.
                            dao.deleteRefsForAccountId(account);
                            for (PreservedRef ref : accountRefs) {
                                forgetResolvedFilesForRef(ref);
                                mirrorRemoveForRef(ref);
                            }
                        }
                        if (deferredDao != null) {
                            // Account-wide ON PURPOSE here (account removal):
                            // this operation means "this account, everything".
                            // Never use deleteForAccount from a dialog flow.
                            deferredDao.deleteForAccount(account);
                        }
                        sweepUnreferencedBlobs();
                        // Delete the blob subtree (files first, then dirs).
                        File blobDir = AliGramFileCopy.getBlobDir(account);
                        deleteDirectoryRecursive(blobDir);
                        pendingLoads.entrySet().removeIf(entry -> entry.getKey().startsWith(account + "|"));
                    } catch (Throwable e) {
                        FileLog.e(TAG + ": purgeAccount worker failed", e);
                    }
                });
            }
            // Prefs of this account are per-account; remove our keys.
            try {
                SharedPreferences prefs = prefsFor(account);
                SharedPreferences.Editor editor = prefs.edit();
                for (String key : prefs.getAll().keySet()) {
                    if (key.startsWith(KEY_FLAG_PREFIX) || key.startsWith(KEY_SINCE_PREFIX)
                            || key.startsWith(KEY_STATE_PREFIX) || key.startsWith(KEY_DEVICE_TIME_PREFIX)) {
                        editor.remove(key);
                    }
                }
                editor.apply();
            } catch (Throwable ignored) {
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": purgeAccount failed", e);
        }
    }

    private static void deleteDirectoryRecursive(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectoryRecursive(f);
                } else {
                    AliGramFileCopy.deleteQuietly(f);
                }
            }
        }
        try {
            dir.delete();
        } catch (Throwable ignored) {
        }
    }
}
