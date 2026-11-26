package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Deterministic JVM unit tests for {@link AliGramExportKeys} — the settings
 * export/import round-trip at the level that is testable without Android.
 *
 * <p>What is covered here: the export predicate admits exactly the eight
 * AliGram userconfing key shapes (five Preserve Media per-dialog keys,
 * hidden-folder per-filter flag, code hash, code salt) and rejects
 * near-misses, so a settings export carries the opt-ins + activation
 * stamps + hidden-folder code/list, and a fresh install + import restores
 * them. What is NOT covered here (and must be verified by code review +
 * CI): {@code SettingsBackupHelper} itself — it imports Gson/Android UI
 * classes and cannot compile in the pure-JVM harness. Its wiring is two
 * lines: the userconfing filter consults this predicate, and import writes
 * userconfing keys back unconditionally (only nkmrcfg is filtered on
 * import), so any key this predicate admits round-trips.
 */
public class AliGramExportKeysTest {

    // --- Preserve Media keys: one per dialog id (signed, never abs()) ---

    @Test
    public void preserveFlagKeyIsExported() {
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMedia_-1001234567890"));
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMedia_12345"));
    }

    @Test
    public void preserveSinceKeyIsExported() {
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMediaSince_-1001234567890"));
    }

    @Test
    public void preserveStateKeyIsExported() {
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMediaState_-1001234567890"));
    }

    @Test
    public void preserveDeviceTimeKeyIsExported() {
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMediaDeviceTime_-1001234567890"));
    }

    @Test
    public void preserveEnableServerKeyIsExported() {
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMediaEnableServer_-1001234567890"));
    }

    // --- Hidden-folder keys ---

    @Test
    public void hiddenFolderOptKeyIsExported() {
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderOpt_7"));
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderOpt_0"));
    }

    @Test
    public void hiddenFolderHashAndSaltAreExported() {
        // The hash is a salted SHA-256 of a LOCAL code, not a remote
        // credential — it must survive export even with "include API keys"
        // unchecked (the exporter's api-keys filter exempts AliGram keys).
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderKeyHash"));
        assertTrue(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderKeySalt"));
    }

    // --- Near-misses and non-AliGram keys must NOT be exported ---

    @Test
    public void barePreservePrefixWithoutDialogIsNotExported() {
        // No AliGram key is ever stored without its dialog suffix.
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMedia"));
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMediaSince"));
    }

    @Test
    public void lookalikeKeysAreNotExported() {
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("ayuPreserveMediaX_123"));
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderOpt"));
        // Exact match only for the hash/salt: a longer key starting with
        // them is not one of ours.
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderKeyHashX"));
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderKeySalt2"));
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("ayuFolderKey"));
    }

    @Test
    public void unrelatedAndNullKeysAreNotExported() {
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("saveIncomingPhotos"));
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey("passcodeHash"));
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey(""));
        assertFalse(AliGramExportKeys.isAliGramUserconfigKey(null));
    }

    // --- The full round-trip key set, as the exporter sees it ---

    @Test
    public void fullRoundTripKeySet() {
        // Every key a fresh install must get back for Preserve Media +
        // hidden folders to behave exactly as before the export.
        String dialog = "-1001234567890";
        String[] keys = {
                "ayuPreserveMedia_" + dialog,
                "ayuPreserveMediaSince_" + dialog,
                "ayuPreserveMediaState_" + dialog,
                "ayuPreserveMediaDeviceTime_" + dialog,
                "ayuPreserveMediaEnableServer_" + dialog,
                "ayuFolderOpt_7",
                "ayuFolderKeyHash",
                "ayuFolderKeySalt",
        };
        for (String key : keys) {
            assertTrue("expected exportable: " + key,
                    AliGramExportKeys.isAliGramUserconfigKey(key));
        }
    }
}
