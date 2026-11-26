package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic JVM unit tests for {@link AliGramHiddenFolders} (step 1 core).
 *
 * <p>Pure-Java only: prefs are a {@link Map}-backed fake injected via
 * {@link AliGramHiddenFolders#setPrefsProviderForTest}, the timer is a fake
 * {@link AliGramHiddenFolders.Scheduler}. No Robolectric, no Android
 * framework calls in any tested path. Covers investigation §4 tests 1–15
 * plus multi-account isolation, auto-lock firing and commit validation.
 */
public class AliGramHiddenFoldersTest {

    private static final int ACC = 0;
    private static final int ACC2 = 1;

    /** Fake Saved-Messages pseudo-dialog id (own user id in production). */
    private static final long SAVED_MESSAGES_ID = 999111L;
    /** Replies bot — never hidden (investigation §4 test 12). */
    private static final long REPLIES_ID = 708513L;

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

        /** Advances the fake clock and runs every task whose time has come. */
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

        long lastPostedDelay() {
            return postedDelays.isEmpty() ? -1 : postedDelays.get(postedDelays.size() - 1);
        }
    }

    private Map<Integer, AliGramHiddenFolders.PrefsStore> stores;
    private FakeScheduler scheduler;

    @Before
    public void setUp() {
        AliGramHiddenFolders.resetForTest();
        stores = new HashMap<>();
        AliGramHiddenFolders.setPrefsProviderForTest(
                account -> stores.computeIfAbsent(account,
                        k -> AliGramHiddenFolders.newInMemoryStore()));
        scheduler = new FakeScheduler();
        AliGramHiddenFolders.setSchedulerForTest(scheduler);
    }

    @After
    public void tearDown() {
        AliGramHiddenFolders.resetForTest();
    }

    private AliGramHiddenFolders.PrefsStore prefs(int account) {
        return stores.computeIfAbsent(account,
                k -> AliGramHiddenFolders.newInMemoryStore());
    }

    /** Independent SHA-256(salt||code||salt) — the SharedConfig construction. */
    private static String expectedHash(String code, byte[] salt) throws Exception {
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[32 + codeBytes.length];
        System.arraycopy(salt, 0, bytes, 0, 16);
        System.arraycopy(codeBytes, 0, bytes, 16, codeBytes.length);
        System.arraycopy(salt, 0, bytes, codeBytes.length + 16, 16);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // §4-1 setupStoresHashOnly
    // ------------------------------------------------------------------
    @Test
    public void setupStoresHashOnly() throws Exception {
        assertTrue(AliGramHiddenFolders.setupCode(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.hasCodeSet(ACC));

        Map<String, ?> all = prefs(ACC).getAll();
        assertTrue(all.containsKey("ayuFolderKeyHash"));
        assertTrue(all.containsKey("ayuFolderKeySalt"));
        String hash = (String) all.get("ayuFolderKeyHash");
        String saltHex = (String) all.get("ayuFolderKeySalt");
        // SHA-256 hex (64 chars) and 16-byte salt hex (32 chars).
        assertEquals(64, hash.length());
        assertEquals(32, saltHex.length());
        // Hash equals the independently computed SharedConfig construction.
        byte[] salt = AliGramHiddenFolders.hexToBytes(saltHex);
        assertEquals(16, salt.length);
        assertEquals(expectedHash("1234", salt), hash);
        // No key or value contains the plaintext code or a forbidden word.
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey().toLowerCase();
            assertFalse(key.contains("hidden"));
            assertFalse(key.contains("hide"));
            assertFalse(key.contains("lock"));
            assertFalse(key.contains("code"));
            assertFalse(key.contains("1234"));
            if (e.getValue() instanceof String) {
                assertFalse(((String) e.getValue()).contains("1234"));
            }
        }
    }

    // ------------------------------------------------------------------
    // §4-2 setupRejectsBadLengths
    // ------------------------------------------------------------------
    @Test
    public void setupRejectsBadLengths() {
        assertFalse(AliGramHiddenFolders.setupCode(ACC, "123"));
        assertFalse(AliGramHiddenFolders.setupCode(ACC, "1234567"));
        assertFalse(AliGramHiddenFolders.setupCode(ACC, "12ab"));
        assertFalse(AliGramHiddenFolders.setupCode(ACC, "abcd"));
        assertFalse(AliGramHiddenFolders.setupCode(ACC, ""));
        assertFalse(AliGramHiddenFolders.setupCode(ACC, null));
        assertFalse(AliGramHiddenFolders.setupCode(ACC, "##1234"));
        assertFalse(AliGramHiddenFolders.hasCodeSet(ACC));
    }

    // ------------------------------------------------------------------
    // §4-3 unlockByHashOnly
    // ------------------------------------------------------------------
    @Test
    public void unlockByHashOnly() {
        assertTrue(AliGramHiddenFolders.setupCode(ACC, "123456"));
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC));

        // 4-digit prefix of the 6-digit code must NOT unlock.
        assertFalse(AliGramHiddenFolders.handleSearchText(ACC, "1234"));
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC));
        // Wrong code, wrong length: no unlock, falls through to search.
        assertFalse(AliGramHiddenFolders.handleSearchText(ACC, "654321"));
        assertFalse(AliGramHiddenFolders.handleSearchText(ACC, "1234567"));
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC));
        // Exact code unlocks.
        assertTrue(AliGramHiddenFolders.handleSearchText(ACC, "123456"));
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
    }

    // ------------------------------------------------------------------
    // §4-4 setupAfterSetIgnored
    // ------------------------------------------------------------------
    @Test
    public void setupAfterSetIgnored() {
        assertTrue(AliGramHiddenFolders.setupCode(ACC, "1234"));
        assertFalse(AliGramHiddenFolders.setupCode(ACC, "9999"));
        assertFalse(AliGramHiddenFolders.commitSetup(ACC, "##9999"));
        assertFalse(AliGramHiddenFolders.commitSetup(ACC, "9999"));
        assertTrue(AliGramHiddenFolders.matchesCode(ACC, "1234"));
        assertFalse(AliGramHiddenFolders.matchesCode(ACC, "9999"));
    }

    // ------------------------------------------------------------------
    // §4-5 toggleRoundTrip
    // ------------------------------------------------------------------
    @Test
    public void toggleRoundTrip() {
        assertTrue(AliGramHiddenFolders.setupCode(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.handleSearchText(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
        // Same code again re-locks.
        assertTrue(AliGramHiddenFolders.handleSearchText(ACC, "1234"));
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC));
        // Wrong code while unlocked stays unlocked (falls to search).
        assertTrue(AliGramHiddenFolders.handleSearchText(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
        assertFalse(AliGramHiddenFolders.handleSearchText(ACC, "0000"));
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
    }

    // ------------------------------------------------------------------
    // §4-6 hiddenMembershipDominant
    // ------------------------------------------------------------------
    @Test
    public void hiddenMembershipDominant() {
        assertTrue(AliGramHiddenFolders.isHiddenByMembership(true, true, false));
        // Dominance: hidden even when ALSO in a visible filter.
        assertTrue(AliGramHiddenFolders.isHiddenByMembership(true, true, true));
        assertFalse(AliGramHiddenFolders.isHiddenByMembership(true, false, true));
        assertFalse(AliGramHiddenFolders.isHiddenByMembership(false, true, false));
        assertFalse(AliGramHiddenFolders.isHiddenByMembership(false, false, false));
        // Default filter (id == 0) can never be hidden.
        assertFalse(AliGramHiddenFolders.isFilterHidden(ACC, 0));
        AliGramHiddenFolders.setFilterHidden(ACC, 0, true);
        assertFalse(AliGramHiddenFolders.isFilterHidden(ACC, 0));
        assertTrue(AliGramHiddenFolders.hiddenFilterIds(ACC).isEmpty());
    }

    // ------------------------------------------------------------------
    // §4-7 hiddenKeyedByServerId
    // ------------------------------------------------------------------
    @Test
    public void hiddenKeyedByServerId() {
        AliGramHiddenFolders.setFilterHidden(ACC, 5, true);
        // Flag is keyed by server id only: no local id appears anywhere.
        assertTrue(AliGramHiddenFolders.isFilterHidden(ACC, 5));
        assertTrue(prefs(ACC).getAll().containsKey("ayuFolderOpt_5"));
        // Survives a (simulated) localId change: nothing is keyed by localId,
        // and membership is supplied fresh per rebuild.
        AliGramHiddenFolders.rebuildHiddenDialogCache(ACC, filterId ->
                filterId == 5 ? Arrays.asList(11L, 12L) : Collections.emptyList());
        assertTrue(AliGramHiddenFolders.isDialogHidden(ACC, 11L));
        // Prune keeps the flag while the filter still exists...
        AliGramHiddenFolders.pruneHiddenFilters(ACC, Arrays.asList(5, 7));
        assertTrue(AliGramHiddenFolders.isFilterHidden(ACC, 5));
        // ...and drops it once the filter id is gone (server delete/recreate).
        AliGramHiddenFolders.pruneHiddenFilters(ACC, Arrays.asList(7));
        assertFalse(AliGramHiddenFolders.isFilterHidden(ACC, 5));
        assertFalse(prefs(ACC).getAll().containsKey("ayuFolderOpt_5"));
    }

    // ------------------------------------------------------------------
    // §4-8 lockUnlockTransitions
    // ------------------------------------------------------------------
    @Test
    public void lockUnlockTransitions() {
        long before = System.currentTimeMillis();
        AliGramHiddenFolders.unlock(ACC);
        long startedAt = AliGramHiddenFolders.getUnlockStartedAt(ACC);
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
        assertTrue(startedAt >= before);
        assertTrue(startedAt <= System.currentTimeMillis());
        // Unlock schedules exactly AUTO_LOCK_MS.
        assertEquals(1, scheduler.pendingCount());
        assertEquals(AliGramHiddenFolders.AUTO_LOCK_MS, scheduler.lastPostedDelay());
        assertTrue(scheduler.postedDelays().contains(3600000L));

        // Manual re-lock cancels the timer.
        int cancelsBefore = scheduler.cancelCalls();
        AliGramHiddenFolders.lock(ACC);
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC));
        assertEquals(0, AliGramHiddenFolders.getUnlockStartedAt(ACC));
        assertEquals(0, scheduler.pendingCount());
        assertTrue(scheduler.cancelCalls() > cancelsBefore);

        // lock() while already locked is a no-op (no scheduler traffic).
        int cancelsAfterLock = scheduler.cancelCalls();
        AliGramHiddenFolders.lock(ACC);
        assertEquals(cancelsAfterLock, scheduler.cancelCalls());
        assertEquals(0, scheduler.pendingCount());
    }

    // ------------------------------------------------------------------
    // §4-9 expiryCheck
    // ------------------------------------------------------------------
    @Test
    public void expiryCheck() {
        AliGramHiddenFolders.unlock(ACC);
        long t = 1_700_000_000_000L;
        AliGramHiddenFolders.setUnlockStartedAtForTest(ACC, t);
        assertFalse(AliGramHiddenFolders.isAutoLockExpired(ACC, t + 3599_000L));
        assertTrue(AliGramHiddenFolders.isAutoLockExpired(ACC, t + 3600_000L));
        assertTrue(AliGramHiddenFolders.isAutoLockExpired(ACC, t + 3600_001L));
        // Not unlocked => nothing to expire.
        AliGramHiddenFolders.lock(ACC);
        assertFalse(AliGramHiddenFolders.isAutoLockExpired(ACC, t + 7200_000L));
    }

    // ------------------------------------------------------------------
    // §4-10 unreadExclusionMath
    // ------------------------------------------------------------------
    @Test
    public void unreadExclusionMath() {
        Map<Long, Integer> unread = new HashMap<>();
        unread.put(11L, 30);
        unread.put(12L, 5);
        unread.put(13L, 7);
        Set<Long> hidden = new HashSet<>(Arrays.asList(11L, 13L));
        assertEquals(5L, AliGramHiddenFolders.excludeHiddenUnread(42L, unread, hidden));
        assertEquals(42L, AliGramHiddenFolders.excludeHiddenUnread(42L, unread,
                Collections.emptySet()));
        assertEquals(42L, AliGramHiddenFolders.excludeHiddenUnread(42L, unread,
                new HashSet<>(Collections.singletonList(99L))));
        // Clamp at zero, never negative.
        assertEquals(0L, AliGramHiddenFolders.excludeHiddenUnread(5L, unread, hidden));
        assertEquals(0L, AliGramHiddenFolders.excludeHiddenUnread(0L, unread, hidden));
    }

    // ------------------------------------------------------------------
    // §4-11 notificationGatePredicate
    // ------------------------------------------------------------------
    @Test
    public void notificationGatePredicate() {
        assertTrue(AliGramHiddenFolders.shouldSuppressNotification(true, true));
        assertFalse(AliGramHiddenFolders.shouldSuppressNotification(true, false));
        assertFalse(AliGramHiddenFolders.shouldSuppressNotification(false, true));
        assertFalse(AliGramHiddenFolders.shouldSuppressNotification(false, false));
        // Story-push variant uses the same predicate.
        assertTrue(AliGramHiddenFolders.shouldSuppressNotification(true, true));
        assertFalse(AliGramHiddenFolders.shouldSuppressNotification(false, true));
    }

    // ------------------------------------------------------------------
    // §4-12 searchFilterPredicate
    // ------------------------------------------------------------------
    @Test
    public void searchFilterPredicate() {
        Set<Long> hidden = new HashSet<>(Arrays.asList(2L, 3L, REPLIES_ID, SAVED_MESSAGES_ID));
        // The predicate the UI passes must never hide Saved Messages / Replies.
        java.util.function.Predicate<Long> isHidden = id ->
                hidden.contains(id) && id != REPLIES_ID && id != SAVED_MESSAGES_ID;
        List<Long> mixed = Arrays.asList(1L, 2L, 3L, REPLIES_ID, SAVED_MESSAGES_ID, 4L);
        Set<Long> kept = AliGramHiddenFolders.filterHiddenIds(mixed, isHidden);
        assertEquals(new HashSet<>(Arrays.asList(1L, REPLIES_ID, SAVED_MESSAGES_ID, 4L)), kept);
        // Order is preserved (LinkedHashSet).
        assertEquals(Arrays.asList(1L, REPLIES_ID, SAVED_MESSAGES_ID, 4L),
                new ArrayList<>(kept));
        assertTrue(AliGramHiddenFolders.filterHiddenIds(null, isHidden).isEmpty());
        assertEquals(new HashSet<>(Arrays.asList(1L, 2L)),
                AliGramHiddenFolders.filterHiddenIds(Arrays.asList(1L, 2L), null));
    }

    // ------------------------------------------------------------------
    // §4-13 preservedStatsExclusion
    // ------------------------------------------------------------------
    @Test
    public void preservedStatsExclusion() {
        long[] total = new long[]{5, 100, 1000};
        Map<Long, long[]> perDialog = new HashMap<>();
        perDialog.put(11L, new long[]{1, 10, 100});
        perDialog.put(12L, new long[]{1, 20, 200});
        perDialog.put(13L, new long[]{1, 5, 50});
        Set<Long> hidden = new HashSet<>(Arrays.asList(11L, 12L));
        assertArrayEquals(new long[]{3, 70, 700},
                AliGramHiddenFolders.filterPreservedStats(total, perDialog, hidden));
        // Hidden dialog with no per-dialog entry subtracts nothing.
        assertArrayEquals(new long[]{5, 100, 1000},
                AliGramHiddenFolders.filterPreservedStats(total, perDialog,
                        new HashSet<>(Collections.singletonList(99L))));
        // Clamp at zero, never negative.
        assertArrayEquals(new long[]{0, 0, 0},
                AliGramHiddenFolders.filterPreservedStats(new long[]{1, 5, 50},
                        perDialog, new HashSet<>(Arrays.asList(11L, 12L, 13L))));
        assertArrayEquals(new long[]{0, 0, 0},
                AliGramHiddenFolders.filterPreservedStats(null, perDialog, hidden));
    }

    // ------------------------------------------------------------------
    // §4-14 noPlaintextAnywhere
    // ------------------------------------------------------------------
    @Test
    public void noPlaintextAnywhere() {
        assertTrue(AliGramHiddenFolders.setupCode(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.setupCode(ACC2, "9876"));
        AliGramHiddenFolders.setFilterHidden(ACC, 7, true);
        AliGramHiddenFolders.setFilterHidden(ACC2, 9, true);
        for (int account : new int[]{ACC, ACC2}) {
            for (Map.Entry<String, ?> e : prefs(account).getAll().entrySet()) {
                String key = e.getKey();
                assertFalse(key.toLowerCase().contains("hidden"));
                assertFalse(key.toLowerCase().contains("hide"));
                assertFalse(key.toLowerCase().contains("lock"));
                assertFalse(key.toLowerCase().contains("code"));
                assertFalse(key.contains("1234"));
                assertFalse(key.contains("9876"));
                if (e.getValue() instanceof String) {
                    String v = (String) e.getValue();
                    assertFalse(v.equals("1234"));
                    assertFalse(v.equals("9876"));
                    assertFalse(v.contains("1234"));
                    assertFalse(v.contains("9876"));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // §4-15 debounceSetup (fake scheduler; separation, not Android Handler)
    // ------------------------------------------------------------------
    @Test
    public void debounceSetup() {
        // Rapid typing: each keystroke cancels the pending commit.
        AliGramHiddenFolders.onSetupTextChanged(ACC, "##123");
        AliGramHiddenFolders.onSetupTextChanged(ACC, "##1234");
        AliGramHiddenFolders.onSetupTextChanged(ACC, "##12345");
        assertFalse(AliGramHiddenFolders.hasCodeSet(ACC));
        // Only ONE commit is pending, scheduled 1000 ms out.
        assertEquals(1, scheduler.pendingCount());
        assertEquals(AliGramHiddenFolders.SETUP_DEBOUNCE_MS, scheduler.lastPostedDelay());
        // 999 ms of quiet: still nothing committed.
        scheduler.advance(999);
        assertFalse(AliGramHiddenFolders.hasCodeSet(ACC));
        // The quiet window elapses: only the FINAL value commits.
        scheduler.advance(1);
        assertTrue(AliGramHiddenFolders.hasCodeSet(ACC));
        assertTrue(AliGramHiddenFolders.matchesCode(ACC, "12345"));
        assertFalse(AliGramHiddenFolders.matchesCode(ACC, "1234"));
        assertFalse(AliGramHiddenFolders.matchesCode(ACC, "123"));
        // The intermediate ##1234 was never committed.
        assertEquals(0, scheduler.pendingCount());
    }

    // ------------------------------------------------------------------
    // Extra: handleSearchText never commits setup (the keystroke-prefix trap)
    // ------------------------------------------------------------------
    @Test
    public void handleSearchTextNeverCommitsSetup() {
        assertFalse(AliGramHiddenFolders.handleSearchText(ACC, "##1234"));
        assertFalse(AliGramHiddenFolders.hasCodeSet(ACC));
        assertFalse(AliGramHiddenFolders.handleSearchText(ACC, "##123456"));
        assertFalse(AliGramHiddenFolders.hasCodeSet(ACC));
        // Setup candidates are recognised, but only via the debounced path.
        assertTrue(AliGramHiddenFolders.isSetupCandidate("##1234"));
        assertTrue(AliGramHiddenFolders.isSetupCandidate("##123456"));
        assertFalse(AliGramHiddenFolders.isSetupCandidate("##123"));
        assertFalse(AliGramHiddenFolders.isSetupCandidate("##1234567"));
        assertFalse(AliGramHiddenFolders.isSetupCandidate("1234"));
        assertFalse(AliGramHiddenFolders.isSetupCandidate(null));
    }

    // ------------------------------------------------------------------
    // Extra: auto-lock runnable fires at +3600s
    // ------------------------------------------------------------------
    @Test
    public void autoLockRunnableFires() {
        AliGramHiddenFolders.unlock(ACC);
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
        scheduler.advance(3599_000);
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
        scheduler.advance(1000);
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC));
        assertEquals(0, scheduler.pendingCount());
    }

    // ------------------------------------------------------------------
    // Extra: isDialogHidden follows lock state + cache
    // ------------------------------------------------------------------
    @Test
    public void isDialogHiddenFollowsLockState() {
        AliGramHiddenFolders.setFilterHidden(ACC, 5, true);
        AliGramHiddenFolders.rebuildHiddenDialogCache(ACC, filterId ->
                filterId == 5 ? Arrays.asList(11L, 12L) : Collections.emptyList());
        // Locked by default: hidden dialogs are hidden, others are not.
        assertTrue(AliGramHiddenFolders.isDialogHidden(ACC, 11L));
        assertTrue(AliGramHiddenFolders.isDialogHiddenCached(ACC, 12L));
        assertFalse(AliGramHiddenFolders.isDialogHidden(ACC, 13L));
        // Unlock: nothing is hidden.
        AliGramHiddenFolders.unlock(ACC);
        assertFalse(AliGramHiddenFolders.isDialogHidden(ACC, 11L));
        // Re-lock: hidden again (cache rebuilt on the transitions).
        AliGramHiddenFolders.lock(ACC);
        assertTrue(AliGramHiddenFolders.isDialogHidden(ACC, 11L));
        // Unhide: dialog visible even while locked.
        AliGramHiddenFolders.setFilterHidden(ACC, 5, false);
        assertFalse(AliGramHiddenFolders.isDialogHidden(ACC, 11L));
    }

    // ------------------------------------------------------------------
    // Extra: multi-account isolation
    // ------------------------------------------------------------------
    @Test
    public void multiAccountIsolation() {
        assertTrue(AliGramHiddenFolders.setupCode(ACC, "1234"));
        assertFalse(AliGramHiddenFolders.hasCodeSet(ACC2));
        assertFalse(AliGramHiddenFolders.matchesCode(ACC2, "1234"));
        AliGramHiddenFolders.setFilterHidden(ACC, 5, true);
        assertFalse(AliGramHiddenFolders.isFilterHidden(ACC2, 5));
        assertTrue(AliGramHiddenFolders.handleSearchText(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC2));
    }

    // ------------------------------------------------------------------
    // Extra: commitSetup validation
    // ------------------------------------------------------------------
    @Test
    public void commitSetupValidation() {
        assertTrue(AliGramHiddenFolders.commitSetup(ACC, "##12345"));
        assertTrue(AliGramHiddenFolders.matchesCode(ACC, "12345"));
        assertFalse(AliGramHiddenFolders.commitSetup(ACC2, "123"));
        assertFalse(AliGramHiddenFolders.commitSetup(ACC2, null));
        assertFalse(AliGramHiddenFolders.commitSetup(ACC2, "##12ab"));
        assertFalse(AliGramHiddenFolders.hasCodeSet(ACC2));
        assertTrue(AliGramHiddenFolders.commitSetup(ACC2, "4321"));
        assertTrue(AliGramHiddenFolders.matchesCode(ACC2, "4321"));
    }

    // ------------------------------------------------------------------
    // Extra: auto-return to main after the exact-code toggle only
    // ------------------------------------------------------------------
    @Test
    public void shouldDismissSearchOnlyForExactCodeToggle() {
        // Exact code + toggle consumed → dismiss search, return to main.
        assertTrue(AliGramHiddenFolders.shouldDismissSearch("1234", true));
        assertTrue(AliGramHiddenFolders.shouldDismissSearch("123456", true));
        // ## first-time setup never dismisses: the commit is debounced and
        // the field is cleared later by the setup-clear runnable.
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("##1234", false));
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("##123456", false));
        // Ordinary search text never dismisses (toggle did not consume it).
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("1234", false));
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("hello", false));
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("", false));
        assertFalse(AliGramHiddenFolders.shouldDismissSearch(null, false));
        // Defensive: a toggle claim on non-code text never dismisses.
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("123", true));
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("1234567", true));
        assertFalse(AliGramHiddenFolders.shouldDismissSearch("##1234", true));
        assertFalse(AliGramHiddenFolders.shouldDismissSearch(null, true));
    }

    @Test
    public void toggleThenDismissEndToEnd() {
        // Full intercept flow: typing the exact code toggles the lock AND
        // the UI dismisses search; typing it again re-locks AND dismisses.
        assertTrue(AliGramHiddenFolders.setupCode(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.handleSearchText(ACC, "1234"));
        assertTrue(AliGramHiddenFolders.isUnlocked(ACC));
        assertTrue(AliGramHiddenFolders.shouldDismissSearch("1234", true));
        assertTrue(AliGramHiddenFolders.handleSearchText(ACC, "1234"));
        assertFalse(AliGramHiddenFolders.isUnlocked(ACC));
        assertTrue(AliGramHiddenFolders.shouldDismissSearch("1234", true));
    }

    @Test
    public void dismissActionUsesFragmentSearchFieldCollapse() {
        // Regression test for the 1262 bug: the exact-code toggle unlocked
        // the folders but the app stayed in search showing "no results
        // found", because the branch called closeSearching() — a no-op in
        // the FragmentSearchField flow (actionBar.isSearchFieldVisible()
        // is never set there: the ActionBar search item is hidden, its
        // click listener replaced, zero openSearch() calls). The dismiss
        // MUST resolve to collapsing the search through the
        // FragmentSearchField watcher, never to the ActionBar
        // close-search path. There is no CLOSE_SEARCHING action to
        // return by design: the no-op mechanism cannot be expressed.
        assertEquals(AliGramHiddenFolders.SearchDismissAction.COLLAPSE_SEARCH_FIELD,
                AliGramHiddenFolders.dismissAction("1234", true));
        assertEquals(AliGramHiddenFolders.SearchDismissAction.COLLAPSE_SEARCH_FIELD,
                AliGramHiddenFolders.dismissAction("123456", true));
        // ## setup and ordinary text resolve to no dismiss at all.
        assertEquals(AliGramHiddenFolders.SearchDismissAction.NONE,
                AliGramHiddenFolders.dismissAction("##1234", true));
        assertEquals(AliGramHiddenFolders.SearchDismissAction.NONE,
                AliGramHiddenFolders.dismissAction("##1234", false));
        assertEquals(AliGramHiddenFolders.SearchDismissAction.NONE,
                AliGramHiddenFolders.dismissAction("hello", false));
        assertEquals(AliGramHiddenFolders.SearchDismissAction.NONE,
                AliGramHiddenFolders.dismissAction("1234", false));
        assertEquals(AliGramHiddenFolders.SearchDismissAction.NONE,
                AliGramHiddenFolders.dismissAction("123", true));
        assertEquals(AliGramHiddenFolders.SearchDismissAction.NONE,
                AliGramHiddenFolders.dismissAction(null, true));
        assertEquals(AliGramHiddenFolders.SearchDismissAction.NONE,
                AliGramHiddenFolders.dismissAction("", false));
    }
}
