package com.radolyn.ayugram.aligram;

/**
 * Single source of truth for the AliGram {@code SharedPreferences} keys that
 * must survive a settings export/import (userconfing, account 0).
 *
 * <p>Background: {@code SettingsBackupHelper} exports userconfing through a
 * fixed whitelist, so per-chat Preserve Media opt-ins + activation
 * times/states and the hidden-folder code hash + hidden list were silently
 * dropped on export; after a fresh install + import the user had to set
 * everything up again. The export filter consults
 * {@link #isAliGramUserconfigKey(String)} for these keys.
 *
 * <p>The key strings live HERE and nowhere else:
 * {@link AliGramPreservation} and {@link AliGramHiddenFolders} delegate
 * their {@code KEY_*} constants to these fields, so the export list cannot
 * drift from the keys the features actually read and write. Preserved
 * media themselves (Room DB + blob files) are deliberately NOT part of
 * settings export and are not covered here.
 *
 * <p>Security note: {@link #HIDDEN_FOLDER_KEY_HASH} is a salted SHA-256 of
 * the local hidden-folder code, not a credential for any remote service —
 * that is why it is exempt from the exporter's "API keys" filter (see
 * {@code SettingsBackupHelper}). The plaintext code is never stored.
 *
 * <p>Pure Java: no Android imports, so the JVM unit tests can compile this
 * class directly.
 */
public final class AliGramExportKeys {

    private AliGramExportKeys() {
    }

    // --- Preserve Media opt-ins + activation (AliGramPreservation) ---
    /** Per-chat opt-in flag, suffixed with the signed dialog id. */
    public static final String PRESERVE_FLAG_PREFIX = "ayuPreserveMedia_";
    /** Activation server-time stamp, suffixed with the signed dialog id. */
    public static final String PRESERVE_SINCE_PREFIX = "ayuPreserveMediaSince_";
    /** Activation state (PENDING / STAMPED / OFF), suffixed with the dialog id. */
    public static final String PRESERVE_STATE_PREFIX = "ayuPreserveMediaState_";
    /** Activation device-time stamp, suffixed with the signed dialog id. */
    public static final String PRESERVE_DEVICE_TIME_PREFIX = "ayuPreserveMediaDeviceTime_";
    /** Server-time estimate at enable moment, suffixed with the dialog id. */
    public static final String PRESERVE_ENABLE_SERVER_PREFIX = "ayuPreserveMediaEnableServer_";

    // --- Hidden folders (AliGramHiddenFolders) ---
    /** Per-filter hidden flag, suffixed with the SERVER filter id. */
    public static final String HIDDEN_FOLDER_OPT_PREFIX = "ayuFolderOpt_";
    /** Salted SHA-256 hex of the hidden-folder code. Never the code itself. */
    public static final String HIDDEN_FOLDER_KEY_HASH = "ayuFolderKeyHash";
    /** 16-byte salt for the code hash, hex-encoded. */
    public static final String HIDDEN_FOLDER_KEY_SALT = "ayuFolderKeySalt";

    /**
     * True iff {@code key} is an AliGram userconfing key that settings
     * export must carry (and import must restore). Prefix matches cover
     * the per-dialog / per-filter suffixed keys; the hash and salt are
     * exact matches — a longer key that merely starts with them is not
     * one of ours.
     */
    public static boolean isAliGramUserconfigKey(String key) {
        if (key == null) {
            return false;
        }
        return key.startsWith(PRESERVE_FLAG_PREFIX)
                || key.startsWith(PRESERVE_SINCE_PREFIX)
                || key.startsWith(PRESERVE_STATE_PREFIX)
                || key.startsWith(PRESERVE_DEVICE_TIME_PREFIX)
                || key.startsWith(PRESERVE_ENABLE_SERVER_PREFIX)
                || key.startsWith(HIDDEN_FOLDER_OPT_PREFIX)
                || HIDDEN_FOLDER_KEY_HASH.equals(key)
                || HIDDEN_FOLDER_KEY_SALT.equals(key);
    }
}
