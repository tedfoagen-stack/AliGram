package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * DAO-level regression tests for the Phase 3.1 audit fixes that are
 * expressible against the REAL {@link AliGramDatabase} (in-memory) under
 * Robolectric — the DAO contracts the controller fixes rest on:
 *
 * <ul>
 *   <li>D-03: {@code deleteRefsForAccountId} deletes by the stable
 *       account id (a purge keyed on user id 0 — the value
 *       {@code performLogout} leaves behind — deletes nothing);</li>
 *   <li>D-05: blob identity lookups are account-scoped (same media in
 *       two accounts is two rows);</li>
 *   <li>D-04: {@code refreshSizeMtimeForPath} fills in a PENDING
 *       deferred row's 0,0 size/mtime on materialisation.</li>
 * </ul>
 *
 * <p>Threading mirrors production (no {@code allowMainThreadQueries()}):
 * every DAO call runs on a worker thread via {@link #onDb(Callable)}.
 * The controller wiring itself (purgeAccount, copyForRef dedupe,
 * releaseDeferredRow) is NOT executable in this harness — it needs
 * UserConfig / FileLoader / the copy queue — and is verified by code
 * review + the DAO contracts proven here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class AliGramPhase31DaoTest {

    private static final long USER_0 = 1000L;
    private static final long USER_1 = 2000L;
    private static final int ACCOUNT_0 = 0;
    private static final int ACCOUNT_1 = 1;
    private static final long DIALOG_A = -100111L;
    private static final long T0 = 1_700_000_000L;

    private static final String SHA_DOC = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_PHOTO = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

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

    private static PreservedBlob newBlob(int accountId, String blobId, long documentId,
                                         long photoId, String sha256, long size) {
        PreservedBlob blob = new PreservedBlob();
        blob.blobId = blobId;
        blob.accountId = accountId;
        blob.documentId = documentId;
        blob.photoId = photoId;
        blob.dcId = 2;
        blob.size = size;
        blob.sha256 = sha256;
        blob.mimeType = "video/mp4";
        blob.ext = "mp4";
        blob.fileName = blobId + ".mp4";
        blob.originalFileName = "clip.mp4";
        blob.photoWidth = 0;
        blob.photoHeight = 0;
        blob.refCount = 0;
        blob.createdAt = T0;
        return blob;
    }

    private static DeferredDelete newDeferred(int accountId, long refId, String path) {
        DeferredDelete row = new DeferredDelete();
        row.accountId = accountId;
        row.refId = refId;
        row.absolutePath = path;
        row.sizeAtPin = 0;
        row.mtimeAtPin = 0;
        row.requestedBy = "DELETE";
        row.cancelDeferred = false;
        row.state = "PENDING";
        row.createdAt = T0;
        return row;
    }

    // ------------------------------------------------------------------
    // D-03 — deleteRefsForAccountId
    // ------------------------------------------------------------------

    @Test
    public void d03_deleteByAccountIdDeletesOnlyThatAccount() throws Exception {
        onDbVoid(() -> {
            db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 1));
            db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 2));
            db.preservedRefDao().insertRef(newRef(USER_1, ACCOUNT_1, DIALOG_A, 1));
        });
        // The old buggy key: performLogout zeroes clientUserId before the
        // purge hook, so deleteRefsForAccount(0) deleted nothing.
        onDbVoid(() -> db.preservedRefDao().deleteRefsForAccount(0L));
        assertEquals(3, onDb(() -> db.preservedRefDao().getAllRefs()).size());
        // The fix: key on the stable account id.
        onDbVoid(() -> db.preservedRefDao().deleteRefsForAccountId(ACCOUNT_0));
        assertEquals(0, onDb(() -> db.preservedRefDao().getRefsForAccount(ACCOUNT_0)).size());
        assertEquals(1, onDb(() -> db.preservedRefDao().getRefsForAccount(ACCOUNT_1)).size());
        onDbVoid(() -> db.preservedRefDao().deleteRefsForAccountId(ACCOUNT_1));
        assertTrue(onDb(() -> db.preservedRefDao().getAllRefs()).isEmpty());
    }

    // ------------------------------------------------------------------
    // D-05 — account-scoped blob identity
    // ------------------------------------------------------------------

    @Test
    public void d05_blobLookupsAreAccountScoped() throws Exception {
        onDbVoid(() -> {
            // Same media (same document key) in two accounts: two rows.
            db.preservedBlobDao().insertBlob(newBlob(ACCOUNT_0, "d555_2_4096_aaaaaaaaaaaaaaaa",
                    555L, 0L, SHA_DOC, 4096L));
            db.preservedBlobDao().insertBlob(newBlob(ACCOUNT_1, "a1_d555_2_4096_aaaaaaaaaaaaaaaa",
                    555L, 0L, SHA_DOC, 4096L));
            // Same photo in two accounts: two rows.
            db.preservedBlobDao().insertBlob(newBlob(ACCOUNT_0, "p777_2_2048_bbbbbbbbbbbbbbbb",
                    0L, 777L, SHA_PHOTO, 2048L));
            db.preservedBlobDao().insertBlob(newBlob(ACCOUNT_1, "a1_p777_2_2048_bbbbbbbbbbbbbbbb",
                    0L, 777L, SHA_PHOTO, 2048L));
        });
        PreservedBlob doc0 = onDb(() -> db.preservedBlobDao()
                .getByDocumentKey(ACCOUNT_0, 555L, 2, 4096L, SHA_DOC));
        PreservedBlob doc1 = onDb(() -> db.preservedBlobDao()
                .getByDocumentKey(ACCOUNT_1, 555L, 2, 4096L, SHA_DOC));
        assertNotNull(doc0);
        assertNotNull(doc1);
        assertEquals("d555_2_4096_aaaaaaaaaaaaaaaa", doc0.blobId);
        assertEquals("a1_d555_2_4096_aaaaaaaaaaaaaaaa", doc1.blobId);
        assertEquals(1, onDb(() -> db.preservedBlobDao()
                .getBlobsByDocument(ACCOUNT_0, 555L, 2, 4096L)).size());
        assertEquals(1, onDb(() -> db.preservedBlobDao()
                .getBlobsByDocument(ACCOUNT_1, 555L, 2, 4096L)).size());
        assertEquals("d555_2_4096_aaaaaaaaaaaaaaaa", onDb(() -> db.preservedBlobDao()
                .getBlobsByDocument(ACCOUNT_0, 555L, 2, 4096L)).get(0).blobId);

        PreservedBlob photo0 = onDb(() -> db.preservedBlobDao()
                .getByPhotoKey(ACCOUNT_0, 777L, 2, 2048L, SHA_PHOTO));
        PreservedBlob photo1 = onDb(() -> db.preservedBlobDao()
                .getByPhotoKey(ACCOUNT_1, 777L, 2, 2048L, SHA_PHOTO));
        assertNotNull(photo0);
        assertNotNull(photo1);
        assertEquals("p777_2_2048_bbbbbbbbbbbbbbbb", photo0.blobId);
        assertEquals("a1_p777_2_2048_bbbbbbbbbbbbbbbb", photo1.blobId);
        assertEquals(1, onDb(() -> db.preservedBlobDao()
                .getBlobsByPhoto(ACCOUNT_0, 777L, 2, 2048L)).size());
        assertEquals(1, onDb(() -> db.preservedBlobDao()
                .getBlobsByPhoto(ACCOUNT_1, 777L, 2, 2048L)).size());
    }

    @Test
    public void d05_blobAccountIdSurvivesRoundTrip() throws Exception {
        onDbVoid(() -> db.preservedBlobDao().insertBlob(
                newBlob(ACCOUNT_1, "a1_d555_2_4096_aaaaaaaaaaaaaaaa", 555L, 0L, SHA_DOC, 4096L)));
        PreservedBlob found = onDb(() -> db.preservedBlobDao().getById("a1_d555_2_4096_aaaaaaaaaaaaaaaa"));
        assertNotNull(found);
        assertEquals(ACCOUNT_1, found.accountId);
        assertNull(onDb(() -> db.preservedBlobDao()
                .getByDocumentKey(ACCOUNT_0, 555L, 2, 4096L, SHA_DOC)));
    }

    // ------------------------------------------------------------------
    // D-04 — refreshSizeMtimeForPath
    // ------------------------------------------------------------------

    @Test
    public void d04_refreshFillsInPendingRowValues() throws Exception {
        onDbVoid(() -> db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, 5L, "/cache/a.mp4")));
        onDbVoid(() -> db.deferredDeleteDao()
                .refreshSizeMtimeForPath("/cache/a.mp4", 4096L, T0));
        DeferredDelete row = onDb(() -> db.deferredDeleteDao()
                .getPendingForRef(ACCOUNT_0, 5L)).get(0);
        assertEquals(4096L, row.sizeAtPin);
        assertEquals(T0, row.mtimeAtPin);
        assertEquals("PENDING", row.state);
        // A second refresh updates the values again (materialisation can
        // only happen once, but the update is idempotent-safe).
        onDbVoid(() -> db.deferredDeleteDao()
                .refreshSizeMtimeForPath("/cache/a.mp4", 8192L, T0 + 10));
        DeferredDelete again = onDb(() -> db.deferredDeleteDao()
                .getPendingForRef(ACCOUNT_0, 5L)).get(0);
        assertEquals(8192L, again.sizeAtPin);
        // Refreshing an unknown path touches nothing.
        onDbVoid(() -> db.deferredDeleteDao()
                .refreshSizeMtimeForPath("/cache/nope.mp4", 1L, 1L));
        assertEquals(1, onDb(() -> db.deferredDeleteDao().getPendingForAccount(ACCOUNT_0)).size());
    }

    @Test
    public void d04_refreshIgnoresDoneRows() throws Exception {
        onDbVoid(() -> db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, 5L, "/cache/a.mp4")));
        long rowId = onDb(() -> db.deferredDeleteDao()
                .getPendingForRef(ACCOUNT_0, 5L)).get(0).id;
        onDbVoid(() -> db.deferredDeleteDao().setState(rowId, "DONE"));
        // The row is DONE: it no longer shows up as pending, and a refresh
        // for its path must not resurrect or rewrite it (the SQL filters
        // state = 'PENDING'); the pending set stays empty.
        onDbVoid(() -> db.deferredDeleteDao()
                .refreshSizeMtimeForPath("/cache/a.mp4", 4096L, T0));
        assertTrue(onDb(() -> db.deferredDeleteDao().getPendingForAccount(ACCOUNT_0)).isEmpty());
    }
}
