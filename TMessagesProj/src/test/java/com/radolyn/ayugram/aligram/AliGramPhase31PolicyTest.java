package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.ui.ChatActivity;

/**
 * Phase 3.1 (D-13): production-code regression tests for the H-9/R-19
 * branch table and for the Phase 3.1 account-scoped blob identity.
 *
 * <p>Background: the T-H9-1/2/3 and T-R19-1/2 tests in
 * {@code AliGramEligibilityTest} assert on local model helpers, not on
 * production code — and the model itself was WRONG: it assumed
 * {@code ChatActivity.MODE_QUICK_REPLIES == 2}, but production says 5
 * (2 is {@code MODE_PINNED}). The production branches compare against
 * the symbolic constants everywhere, so the wrong model value never
 * changed runtime behaviour — but it proves the model tests cannot
 * catch a renumbering. These tests assert on the PRODUCTION constants
 * themselves (compile-time constants, so no Android class is loaded at
 * runtime — javac inlines the values), so a renumbering of any mode
 * fails loudly here.
 */
public class AliGramPhase31PolicyTest {

    @Test
    public void h9ModesMatchProductionConstants() {
        // The delete dispatch in MessagesStorage.markMessagesAsDeletedInternal
        // compares the mode against these symbols: 0 = normal (deletes rows
        // AND files), MODE_SCHEDULED / MODE_QUICK_REPLIES = SQL only on
        // scheduled_messages_v2 / quick_replies_messages, no files at all.
        assertEquals(0, ChatActivity.MODE_DEFAULT);
        assertEquals(1, ChatActivity.MODE_SCHEDULED);
        assertEquals(2, ChatActivity.MODE_PINNED);
        assertEquals(5, ChatActivity.MODE_QUICK_REPLIES);
        // Every mode is distinct — the old model value 2 for quick replies
        // was actually MODE_PINNED, not quick replies.
        assertNotEquals(ChatActivity.MODE_SCHEDULED, ChatActivity.MODE_QUICK_REPLIES);
        assertNotEquals(ChatActivity.MODE_PINNED, ChatActivity.MODE_QUICK_REPLIES);
        assertNotEquals(ChatActivity.MODE_PINNED, ChatActivity.MODE_SCHEDULED);
        assertNotEquals(ChatActivity.MODE_DEFAULT, ChatActivity.MODE_SCHEDULED);
    }

    @Test
    public void blobIdIsAccountScoped() {
        String sha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        String legacy = AliGramEligibility.blobIdFor(123L, 0L, 2, 1000L, sha);
        // Account 0 keeps the legacy id (migrated v1 rows stay valid).
        assertEquals(legacy, AliGramEligibility.blobIdFor(0, 123L, 0L, 2, 1000L, sha));
        assertTrue(legacy.startsWith("d123_2_1000_abcdef0123456789"));
        // Another account: same media, different blob id, prefixed.
        String other = AliGramEligibility.blobIdFor(1, 123L, 0L, 2, 1000L, sha);
        assertNotEquals(legacy, other);
        assertTrue(other.startsWith("a1_d123_2_1000_abcdef0123456789"));
        assertEquals(legacy, other.substring("a1_".length()));
        // Deterministic per account.
        assertEquals(other, AliGramEligibility.blobIdFor(1, 123L, 0L, 2, 1000L, sha));
        // Photo identity, account-scoped the same way.
        String photo = AliGramEligibility.blobIdFor(2, 0L, 456L, 2, 800L, "ffffffffffffffff");
        assertTrue(photo.startsWith("a2_p456_2_800_ffffffffffffffff"));
    }

    @Test
    public void noPinSourceReasonIsDistinct() {
        assertEquals("NO_PIN_SOURCE", AliGramEligibility.REASON_NO_PIN_SOURCE);
        assertNotEquals(AliGramEligibility.REASON_SOURCE_GONE, AliGramEligibility.REASON_NO_PIN_SOURCE);
    }
}
