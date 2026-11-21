package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * DAO-level regression tests for the Phase 3.1 DeferredDelete scope fix.
 *
 * <p>Proves, against the REAL {@link AliGramDatabase} (in-memory) under
 * Robolectric, that every dialog-scoped deferred-delete operation touches
 * exactly one (account, dialog):
 *
 * <ul>
 *   <li>{@code deleteByAccountAndDialog} deletes only the rows whose ref
 *       belongs to that dialog (resolved via
 *       {@code refId -> PreservedRef.dialogId}, because
 *       {@link DeferredDelete} has no {@code dialogId} column);</li>
 *   <li>rows with {@code refId == 0} (unattributed — no live ref) are
 *       NEVER matched by a dialog-scoped operation;</li>
 *   <li>the account-scoped {@code getPendingForRef(account, refId)} and
 *       {@code getPendingForAccount} never leak another account's rows;</li>
 *   <li>{@code deleteForRef} deletes only that ref's rows.</li>
 * </ul>
 *
 * <p>Threading mirrors production (no {@code allowMainThreadQueries()}):
 * every DAO call runs on a worker thread via {@link #onDb(Callable)}.
 * The controller wiring (clearPreservedForDialog / onDialogDeleted
 * releasing per dropped ref instead of calling the account-wide
 * {@code deleteForAccount}) is NOT executable in this harness — it needs
 * UserConfig / FileLoader / the copy queue — and is verified by code
 * review + the DAO contract proven here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class AliGramDeferredDeleteScopeTest {

    private static final long USER_0 = 1000L;
    private static final long USER_1 = 2000L;
    private static final int ACCOUNT_0 = 0;
    private static final int ACCOUNT_1 = 1;
    private static final long DIALOG_A = -100111L;
    private static final long DIALOG_B = -100222L;
    private static final long T0 = 1_700_000_000L;

    private AliGramDatabase db;
    private ExecutorService dbExecutor;

    @Before
    public void setUp() {
        dbExecutor = Executors.newSingleThreadExecutor();
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AliGramDatabase.class).build();
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) {
            try {
                onDb(() -> {
                    db.close();
                    return null;
                });
            } catch (Exception ignored) {
            }
            db = null;
        }
        if (dbExecutor != null) {
            dbExecutor.shutdownNow();
            dbExecutor.awaitTermination(10, TimeUnit.SECONDS);
            dbExecutor = null;
        }
    }

    private <T> T onDb(Callable<T> work) throws Exception {
        Future<T> f = dbExecutor.submit(work);
        return f.get(30, TimeUnit.SECONDS);
    }

    private void onDbVoid(Runnable work) throws Exception {
        onDb(() -> {
            work.run();
            return null;
        });
    }

    private static PreservedRef newRef(long userId, int accountId, long dialogId, int messageId) {
        PreservedRef ref = new PreservedRef();
        ref.userId = userId;
        ref.accountId = accountId;
        ref.dialogId = dialogId;
        ref.messageId = messageId;
        ref.role = "LIVE";
        ref.revisionSeq = 0;
        ref.ayuFakeId = 0;
        ref.entityCreateDate = (int) T0;
        ref.editDate = 0;
        ref.documentId = 555L;
        ref.photoId = 0;
        ref.dcId = 2;
        ref.blobId = null;
        ref.state = AliGramEligibility.STATE_ENQUEUED;
        ref.expectedFileName = "f" + messageId + ".mp4";
        ref.expectedSize = 4096L;
        ref.attempts = 0;
        ref.source = "INGEST";
        ref.updatedAt = T0;
        return ref;
    }

    private static DeferredDelete newDeferred(int accountId, long refId, String path) {
        DeferredDelete row = new DeferredDelete();
        row.accountId = accountId;
        row.refId = refId;
        row.absolutePath = path;
        row.sizeAtPin = 4096L;
        row.mtimeAtPin = T0;
        row.requestedBy = "DELETE";
        row.cancelDeferred = false;
        row.state = "PENDING";
        row.createdAt = T0;
        return row;
    }

    // ------------------------------------------------------------------
    // deleteByAccountAndDialog: dialog isolation (same account).
    // ------------------------------------------------------------------

    @Test
    public void deleteByAccountAndDialog_touchesOnlyThatDialog() throws Exception {
        long refA = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11)));
        long refB = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_B, 21)));
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refA, "/cache/a.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refB, "/cache/b.mp4"));
        });

        onDbVoid(() -> db.deferredDeleteDao().deleteByAccountAndDialog(ACCOUNT_0, DIALOG_A));

        List<DeferredDelete> pending = onDb(() -> db.deferredDeleteDao().getAllPending());
        assertEquals(1, pending.size());
        assertEquals(refB, pending.get(0).refId);
        assertEquals("/cache/b.mp4", pending.get(0).absolutePath);
    }

    // ------------------------------------------------------------------
    // deleteByAccountAndDialog: account isolation (same dialog id).
    // ------------------------------------------------------------------

    @Test
    public void deleteByAccountAndDialog_touchesOnlyThatAccount() throws Exception {
        long ref0 = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11)));
        long ref1 = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_1, ACCOUNT_1, DIALOG_A, 11)));
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, ref0, "/cache/a0.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_1, ref1, "/cache/a1.mp4"));
        });

        onDbVoid(() -> db.deferredDeleteDao().deleteByAccountAndDialog(ACCOUNT_0, DIALOG_A));

        List<DeferredDelete> pending = onDb(() -> db.deferredDeleteDao().getAllPending());
        assertEquals(1, pending.size());
        assertEquals(ACCOUNT_1, pending.get(0).accountId);
        assertEquals(ref1, pending.get(0).refId);
    }

    // ------------------------------------------------------------------
    // deleteByAccountAndDialog: refId == 0 rows are never matched.
    // ------------------------------------------------------------------

    @Test
    public void deleteByAccountAndDialog_neverMatchesUnattributedRows() throws Exception {
        long refA = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11)));
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refA, "/cache/a.mp4"));
            // Unattributed row (Layer-2 guard recorded it with no live ref).
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, 0, "/cache/orphan.mp4"));
        });

        onDbVoid(() -> db.deferredDeleteDao().deleteByAccountAndDialog(ACCOUNT_0, DIALOG_A));

        List<DeferredDelete> pending = onDb(() -> db.deferredDeleteDao().getAllPending());
        assertEquals(1, pending.size());
        assertEquals(0L, pending.get(0).refId);
        assertEquals("/cache/orphan.mp4", pending.get(0).absolutePath);
    }

    // ------------------------------------------------------------------
    // getPendingForDialog: dialog-scoped reads, PENDING only.
    // ------------------------------------------------------------------

    @Test
    public void getPendingForDialog_returnsOnlyThatDialogPendingRows() throws Exception {
        long refA = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11)));
        long refB = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_B, 21)));
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refA, "/cache/a.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refB, "/cache/b.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refA, "/cache/a-done.mp4"));
            // Room does not write the generated id back onto the passed
            // entity, so resolve the row through a query before marking it.
            DeferredDelete doneRow = db.deferredDeleteDao()
                    .getPendingForPath(ACCOUNT_0, "/cache/a-done.mp4").get(0);
            db.deferredDeleteDao().setState(doneRow.id, "DONE");
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, 0, "/cache/orphan.mp4"));
        });

        List<DeferredDelete> forA = onDb(() -> db.deferredDeleteDao().getPendingForDialog(ACCOUNT_0, DIALOG_A));
        assertEquals(1, forA.size());
        assertEquals("/cache/a.mp4", forA.get(0).absolutePath);

        List<DeferredDelete> forB = onDb(() -> db.deferredDeleteDao().getPendingForDialog(ACCOUNT_0, DIALOG_B));
        assertEquals(1, forB.size());
        assertEquals("/cache/b.mp4", forB.get(0).absolutePath);
    }

    // ------------------------------------------------------------------
    // getPendingForRef(account, refId): account-scoped reads.
    // ------------------------------------------------------------------

    @Test
    public void getPendingForRef_accountScoped() throws Exception {
        long ref0 = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11)));
        long ref1 = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_1, ACCOUNT_1, DIALOG_A, 11)));
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, ref0, "/cache/a0.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_1, ref1, "/cache/a1.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, 0, "/cache/a0-orphan.mp4"));
        });
        assertEquals(1, onDb(() -> db.deferredDeleteDao().getPendingForRef(ACCOUNT_0, ref0)).size());
        assertEquals(1, onDb(() -> db.deferredDeleteDao().getPendingForRef(ACCOUNT_1, ref1)).size());
        // Cross-account lookup finds nothing, even though the refId exists.
        assertTrue(onDb(() -> db.deferredDeleteDao().getPendingForRef(ACCOUNT_1, ref0)).isEmpty());
        assertTrue(onDb(() -> db.deferredDeleteDao().getPendingForRef(ACCOUNT_0, ref1)).isEmpty());
        // NOTE — the DAO does return unattributed rows for refId 0 (they
        // match accountId + refId + PENDING literally). The protection for
        // them is NOT in this query: it is the early return for refId == 0
        // in AliGramPreservation.releaseDeferredDeletesForRef, which must
        // never be called with 0. The DAO-level guarantee is only that a
        // foreign refId can never match (above).
        assertEquals(1, onDb(() -> db.deferredDeleteDao().getPendingForRef(ACCOUNT_0, 0)).size());
    }

    // ------------------------------------------------------------------
    // deleteForRef: exactly one ref's rows.
    // ------------------------------------------------------------------

    @Test
    public void deleteForRef_deletesOnlyThatRefRows() throws Exception {
        long refA = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11)));
        long refB = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_B, 21)));
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refA, "/cache/a1.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refA, "/cache/a2.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refB, "/cache/b.mp4"));
        });

        onDbVoid(() -> db.deferredDeleteDao().deleteForRef(refA));

        List<DeferredDelete> pending = onDb(() -> db.deferredDeleteDao().getAllPending());
        assertEquals(1, pending.size());
        assertEquals(refB, pending.get(0).refId);
    }

    // ------------------------------------------------------------------
    // getPendingForAccount: account-scoped reads (startup sweep input).
    // ------------------------------------------------------------------

    @Test
    public void getPendingForAccount_returnsOnlyThatAccountRows() throws Exception {
        long ref0 = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11)));
        long ref1 = onDb(() -> db.preservedRefDao().insertRef(newRef(USER_1, ACCOUNT_1, DIALOG_A, 11)));
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, ref0, "/cache/a0.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_1, ref1, "/cache/a1.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_1, 0, "/cache/a1-orphan.mp4"));
        });

        assertEquals(1, onDb(() -> db.deferredDeleteDao().getPendingForAccount(ACCOUNT_0)).size());
        assertEquals(2, onDb(() -> db.deferredDeleteDao().getPendingForAccount(ACCOUNT_1)).size());
    }
}
