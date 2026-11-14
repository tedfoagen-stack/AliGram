package com.radolyn.ayugram.aligram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Room integration tests for the AliGram preservation database.
 *
 * <p>Scope: the REAL {@link AliGramDatabase} (in-memory, plus a file-backed
 * instance for the restart scenarios) under Robolectric. Nothing here is
 * mocked: every scenario performs real DAO operations against a real Room
 * schema and asserts on real rows, refcounts and query results. This closes
 * IMPLEMENTATION-REPORT.md §7 limitation 10 ("Refcount/orphan behaviour is
 * DAO-level — no Room-level test exists") and the Phase 3.1 item (3) ask for
 * real Room integration tests.
 *
 * <p>Threading — mirrors production (AliGramData deliberately has no
 * {@code allowMainThreadQueries()}, and every DAO javadoc says "background
 * threads only"): Robolectric runs {@code @Test} methods on the main-looper
 * thread, and Room refuses DAO calls there. So EVERY DAO call in this file
 * runs on a worker thread via {@link #onDb(Callable)} — never on the test
 * thread, and {@code allowMainThreadQueries()} is never used. Scenario 4
 * additionally runs binds on a pool of worker threads.
 *
 * <p>Determinism: fixed ids, fixed timestamps ({@code T0}), no wall-clock
 * or random dependence. A fresh in-memory DB is built per test.
 *
 * <p>Dialog-scoped deferred cleanup uses the real DAO primitive
 * {@code DeferredDeleteDao.deleteByAccountAndDialog(int, long)} (Phase
 * 3.1 fix): it resolves the dialog through
 * {@code refId -> PreservedRef.dialogId} because {@link DeferredDelete}
 * carries no {@code dialogId} column, so rows with {@code refId == 0}
 * are never matched and rows of other dialogs/accounts survive. See the
 * scenario-8 test, which calls it directly. The per-ref equivalent
 * {@code deleteForRef(long)} also exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36) // app targetSdk; within Robolectric 4.17's supported 23..37 range
public class AliGramRoomIntegrationTest {

    // Fixed fixtures: userIds are per-account, dialogIds are raw signed ids.
    private static final long USER_0 = 1000L;
    private static final long USER_1 = 2000L;
    private static final int ACCOUNT_0 = 0;
    private static final int ACCOUNT_1 = 1;
    private static final long DIALOG_A = -100111L;
    private static final long DIALOG_B = -100222L;
    private static final long T0 = 1_700_000_000L; // fixed device-seconds stamp

    private static final String BLOB_DOC = "d555_2_4096_0123456789abcdef";
    private static final String BLOB_PHOTO = "p777_2_2048_fedcba9876543210";
    private static final String SHA_DOC = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA_PHOTO = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    private AliGramDatabase db;
    private ExecutorService dbExecutor;

    // ------------------------------------------------------------------
    // Setup / teardown
    // ------------------------------------------------------------------

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
                // Already closed by a restart scenario; ignore.
            }
            db = null;
        }
        if (dbExecutor != null) {
            dbExecutor.shutdownNow();
            dbExecutor.awaitTermination(10, TimeUnit.SECONDS);
            dbExecutor = null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers — every DAO call runs on a worker thread (see class javadoc).
    // ------------------------------------------------------------------

    /** Run {@code work} on the DB worker thread and return its result. */
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

    private static PreservedRef newRef(long userId, int accountId, long dialogId,
                                       int messageId, String role, int revisionSeq,
                                       String state, String expectedFileName) {
        PreservedRef ref = new PreservedRef();
        ref.userId = userId;
        ref.accountId = accountId;
        ref.dialogId = dialogId;
        ref.messageId = messageId;
        ref.role = role;
        ref.revisionSeq = revisionSeq;
        ref.ayuFakeId = 0;
        ref.entityCreateDate = (int) T0;
        ref.editDate = 0;
        ref.documentId = 555L;
        ref.photoId = 0;
        ref.dcId = 2;
        ref.blobId = null;
        ref.state = state;
        ref.expectedFileName = expectedFileName;
        ref.expectedSize = 4096L;
        ref.attempts = 0;
        ref.source = "INGEST";
        ref.updatedAt = T0;
        return ref;
    }

    private static PreservedBlob newBlob(String blobId, long documentId, long photoId,
                                         String sha256, long size) {
        PreservedBlob blob = new PreservedBlob();
        blob.blobId = blobId;
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
        row.sizeAtPin = 4096L;
        row.mtimeAtPin = T0;
        row.requestedBy = "DELETE";
        row.cancelDeferred = false;
        row.state = "PENDING";
        row.createdAt = T0;
        return row;
    }

    /**
     * Mirror of the production bind point
     * (AliGramPreservation.copyForRef/bindCopied): insert-or-ignore the blob
     * at refCount 0, increment, bind the ref as COPIED. Callers run this on
     * a worker thread, so it is used inside {@link #onDb(Callable)} or a
     * transaction body — it performs DAO calls directly.
     */
    private void bindRefToBlob(PreservedRef ref, String blobId) {
        PreservedBlobDao blobDao = db.preservedBlobDao();
        PreservedRefDao refDao = db.preservedRefDao();
        PreservedBlob existing = blobDao.getById(blobId);
        if (existing == null) {
            PreservedBlob blob = BLOB_DOC.equals(blobId)
                    ? newBlob(BLOB_DOC, 555L, 0, SHA_DOC, 4096L)
                    : newBlob(BLOB_PHOTO, 0, 777L, SHA_PHOTO, 2048L);
            blobDao.insertBlob(blob);
        }
        blobDao.incrementRefCount(blobId);
        ref.blobId = blobId;
        ref.state = AliGramEligibility.STATE_COPIED;
        ref.updatedAt = T0;
        refDao.updateRef(ref);
    }

    /**
     * Mirror of production {@code dropRefAndDecrement}: decrement the
     * blob's refcount (when bound) and delete the ref row.
     */
    private void dropRefAndDecrement(PreservedRef ref) {
        PreservedBlobDao blobDao = db.preservedBlobDao();
        PreservedRefDao refDao = db.preservedRefDao();
        if (ref.blobId != null) {
            blobDao.decrementRefCount(ref.blobId);
        }
        refDao.deleteRefById(ref.refId);
    }

    /**
     * Mirror of production {@code sweepRefcountZeroBlobs}: delete every
     * refcount-0 blob that no ref points at.
     */
    private void sweepRefcountZeroBlobs() {
        PreservedBlobDao blobDao = db.preservedBlobDao();
        PreservedRefDao refDao = db.preservedRefDao();
        for (PreservedBlob blob : blobDao.getOrphanBlobs()) {
            if (refDao.countRefsForBlob(blob.blobId) > 0) {
                continue;
            }
            blobDao.deleteById(blob.blobId);
        }
    }

    /**
     * Mirror of production {@code sweepUnreferencedBlobs}: delete every
     * blob no live ref points at, recomputed from the refs table (ignores
     * the stored refCount, which a purge/clear leaves stale).
     */
    private void sweepUnreferencedBlobs() {
        PreservedBlobDao blobDao = db.preservedBlobDao();
        for (PreservedBlob blob : blobDao.getUnreferencedBlobs()) {
            blobDao.deleteById(blob.blobId);
        }
    }

    // NOTE: this helper MUST run its query on the worker thread via onDb —
    // the @Test methods run on the Robolectric main-looper thread and Room
    // refuses DAO calls there ("Cannot access database on the main
    // thread"). Calling getById() directly here failed all eight
    // scenarios that assert refcounts (run 43630560041:
    // IllegalStateException at line 260). It is never called from inside
    // another onDb() body, so no deadlock on the single-thread executor.
    private int refCountOf(String blobId) throws Exception {
        PreservedBlob blob = onDb(() -> db.preservedBlobDao().getById(blobId));
        return blob == null ? -1 : blob.refCount;
    }

    // ------------------------------------------------------------------
    // Scenario 1 — refcount increments correctly.
    // ------------------------------------------------------------------

    @Test
    public void scenario1_refcountIncrementsOnSecondBind() throws Exception {
        long refId1 = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f11.mp4")));
        long refId2 = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 12, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f12.mp4")));

        onDbVoid(() -> {
            bindRefToBlob(db.preservedRefDao().getRefById(refId1), BLOB_DOC);
        });
        assertEquals(1, refCountOf(BLOB_DOC));
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().countRefsForBlob(BLOB_DOC)));

        onDbVoid(() -> {
            bindRefToBlob(db.preservedRefDao().getRefById(refId2), BLOB_DOC);
        });
        assertEquals(2, refCountOf(BLOB_DOC));
        assertEquals(2, (int) onDb(() -> db.preservedRefDao().countRefsForBlob(BLOB_DOC)));
        assertEquals(BLOB_DOC,
                onDb(() -> db.preservedRefDao().getRefById(refId2)).blobId);
    }

    // ------------------------------------------------------------------
    // Scenario 2 — refcount decrements correctly.
    // ------------------------------------------------------------------

    @Test
    public void scenario2_refcountDecrementsAndReachesZero() throws Exception {
        long refId1 = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f11.mp4")));
        long refId2 = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 12, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f12.mp4")));
        onDbVoid(() -> {
            bindRefToBlob(db.preservedRefDao().getRefById(refId1), BLOB_DOC);
            bindRefToBlob(db.preservedRefDao().getRefById(refId2), BLOB_DOC);
        });
        assertEquals(2, refCountOf(BLOB_DOC));

        onDbVoid(() -> dropRefAndDecrement(db.preservedRefDao().getRefById(refId1)));
        assertEquals(1, refCountOf(BLOB_DOC));
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().countRefsForBlob(BLOB_DOC)));
        // Still referenced: neither orphan query may list it.
        assertTrue(onDb(() -> db.preservedBlobDao().getOrphanBlobs()).isEmpty());
        assertTrue(onDb(() -> db.preservedBlobDao().getUnreferencedBlobs()).isEmpty());

        onDbVoid(() -> dropRefAndDecrement(db.preservedRefDao().getRefById(refId2)));
        assertEquals(0, refCountOf(BLOB_DOC));
        assertEquals(0, (int) onDb(() -> db.preservedRefDao().countRefsForBlob(BLOB_DOC)));
        // Now eligible for the sweep, and the sweep removes it.
        assertEquals(1, onDb(() -> db.preservedBlobDao().getOrphanBlobs()).size());
        assertEquals(1, onDb(() -> db.preservedBlobDao().getUnreferencedBlobs()).size());
        onDbVoid(this::sweepRefcountZeroBlobs);
        assertNull(onDb(() -> db.preservedBlobDao().getById(BLOB_DOC)));
        assertEquals(0, (int) onDb(() -> db.preservedBlobDao().countBlobs()));
    }

    // ------------------------------------------------------------------
    // Scenario 3 — duplicate binding is idempotent (unique index).
    //
    // Production has NO insert-or-ignore ref DAO: maybeIngest guards with
    // getLiveRef and catches the constraint failure around insertRef
    // ("Unique-index race ... -> no-op"). This test proves both halves of
    // that contract against the real index: the duplicate insert fails,
    // and the pre-check + catch pattern leaves exactly one ref and an
    // unchanged refcount.
    // ------------------------------------------------------------------

    @Test
    public void scenario3_duplicateBindIsIdempotent() throws Exception {
        PreservedRef first = newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                AliGramEligibility.STATE_ENQUEUED, "f11.mp4");
        long refId = onDb(() -> db.preservedRefDao().insertRef(first));
        onDbVoid(() -> bindRefToBlob(db.preservedRefDao().getRefById(refId), BLOB_DOC));
        assertEquals(1, refCountOf(BLOB_DOC));

        // (a) A direct second insert of the same unique tuple must fail on
        // the unique index (userId, dialogId, messageId, role, revisionSeq).
        PreservedRef duplicate = newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                AliGramEligibility.STATE_ENQUEUED, "f11.mp4");
        try {
            onDb(() -> db.preservedRefDao().insertRef(duplicate));
            fail("duplicate ref insert must fail on the unique index");
        } catch (ExecutionException e) {
            assertConstraintFailure(e.getCause());
        }

        // (b) State is unchanged by the failed duplicate: one ref, refCount 1.
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForMessage(USER_0, DIALOG_A, 11)).size());
        assertEquals(1, refCountOf(BLOB_DOC));

        // (c) The production idempotency pattern — look up first, insert
        // only when absent — binds the same message a second time with no
        // extra row and no extra refcount.
        onDbVoid(() -> {
            PreservedRefDao dao = db.preservedRefDao();
            if (dao.getLiveRef(USER_0, DIALOG_A, 11) == null) {
                long id = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f11.mp4"));
                bindRefToBlob(dao.getRefById(id), BLOB_DOC);
            }
        });
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForMessage(USER_0, DIALOG_A, 11)).size());
        assertEquals(1, refCountOf(BLOB_DOC));

        // (d) A different role / revisionSeq for the same message is a
        // distinct row (the unique key includes role + revisionSeq).
        onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "EDIT", 1,
                        AliGramEligibility.STATE_ENQUEUED, "f11e.mp4")));
        assertEquals(2, (int) onDb(() -> db.preservedRefDao().getRefsForMessage(USER_0, DIALOG_A, 11)).size());
    }

    private static void assertConstraintFailure(Throwable cause) {
        assertNotNull("expected a constraint-failure cause, got none", cause);
        boolean isConstraint = cause instanceof SQLiteConstraintException
                || cause.getClass().getSimpleName().contains("Constraint")
                || (cause.getMessage() != null && cause.getMessage().contains("UNIQUE"));
        assertTrue("expected a UNIQUE-constraint failure, got "
                + cause.getClass().getName() + ": " + cause.getMessage(), isConstraint);
    }

    // ------------------------------------------------------------------
    // Scenario 4 — concurrent completion does not corrupt refcounts.
    //
    // Eight worker threads bind eight distinct refs to one blob at once
    // (start gate so they contend). Each bind is one Room transaction
    // (increment + insert + update), retried on a SQLite busy/locked
    // failure. Asserted: the deterministic FINAL count (8 refs, refCount
    // 8) — never the ordering. Room serializes the writes; the test
    // proves no increment is lost and no duplicate row appears.
    // ------------------------------------------------------------------

    @Test
    public void scenario4_concurrentBindsKeepExactRefcount() throws Exception {
        final int threads = 8;
        onDbVoid(() -> db.preservedBlobDao().insertBlob(newBlob(BLOB_DOC, 555L, 0, SHA_DOC, 4096L)));
        assertEquals(0, refCountOf(BLOB_DOC));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int messageId = 100 + i;
                pool.submit(() -> {
                    try {
                        startGate.await(30, TimeUnit.SECONDS);
                        boolean bound = false;
                        RuntimeException last = null;
                        for (int attempt = 0; attempt < 30 && !bound; attempt++) {
                            try {
                                db.runInTransaction(() -> {
                                    db.preservedBlobDao().incrementRefCount(BLOB_DOC);
                                    PreservedRef ref = newRef(USER_0, ACCOUNT_0, DIALOG_A,
                                            messageId, "LIVE", 0,
                                            AliGramEligibility.STATE_ENQUEUED,
                                            "f" + messageId + ".mp4");
                                    ref.refId = db.preservedRefDao().insertRef(ref);
                                    ref.blobId = BLOB_DOC;
                                    ref.state = AliGramEligibility.STATE_COPIED;
                                    ref.updatedAt = T0;
                                    db.preservedRefDao().updateRef(ref);
                                });
                                bound = true;
                            } catch (RuntimeException e) {
                                last = e;
                                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                                if (msg.contains("locked") || msg.contains("busy")) {
                                    try {
                                        Thread.sleep(5L);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                } else {
                                    break; // real failure: do not mask it with retries
                                }
                            }
                        }
                        if (!bound) {
                            synchronized (failures) {
                                failures.add(last != null ? last
                                        : new IllegalStateException("bind never completed"));
                            }
                        }
                    } catch (Throwable t) {
                        synchronized (failures) {
                            failures.add(t);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue("concurrent binds did not finish in time",
                    done.await(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertTrue("concurrent binds failed: " + failures, failures.isEmpty());
        assertEquals(threads, (int) onDb(() -> db.preservedRefDao().countRefsForBlob(BLOB_DOC)));
        assertEquals(threads, refCountOf(BLOB_DOC));
        assertEquals(threads, (int) onDb(() -> db.preservedRefDao().getAllRefs()).size());
    }

    // ------------------------------------------------------------------
    // Scenario 5 — failed transactions roll back completely.
    // ------------------------------------------------------------------

    @Test
    public void scenario5_failedTransactionRollsBackCompletely() throws Exception {
        long refId = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f11.mp4")));
        onDbVoid(() -> bindRefToBlob(db.preservedRefDao().getRefById(refId), BLOB_DOC));
        assertEquals(1, refCountOf(BLOB_DOC));

        // (a) A transaction that throws mid-way leaves no partial rows and
        // no partial refcount change.
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            onDb(() -> {
                db.runInTransaction(() -> {
                    PreservedRef doomed = newRef(USER_0, ACCOUNT_0, DIALOG_A, 99, "LIVE", 0,
                            AliGramEligibility.STATE_ENQUEUED, "f99.mp4");
                    db.preservedRefDao().insertRef(doomed);
                    db.preservedBlobDao().incrementRefCount(BLOB_DOC);
                    throw new IllegalStateException("simulated mid-transaction failure");
                });
                return null;
            });
            fail("transaction body must propagate its failure");
        } catch (ExecutionException e) {
            thrown.set(e.getCause());
        }
        assertNotNull(thrown.get());
        assertTrue(thrown.get() instanceof IllegalStateException);
        assertNull(onDb(() -> db.preservedRefDao().getRef(USER_0, DIALOG_A, 99, "LIVE", 0)));
        assertEquals(1, refCountOf(BLOB_DOC));
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getAllRefs()).size());

        // (b) Claim-then-insert failing on the unique index rolls back the
        // claim too: claim the waiting job, then insert a duplicate row in
        // the same transaction; the constraint failure must undo both the
        // claim (state stays ENQUEUED) and the refcount increment.
        onDbVoid(() -> {
            PreservedRef waiting = newRef(USER_0, ACCOUNT_0, DIALOG_A, 21, "LIVE", 0,
                    AliGramEligibility.STATE_ENQUEUED, "shared.mp4");
            waiting.refId = db.preservedRefDao().insertRef(waiting);
        });
        try {
            onDb(() -> {
                db.runInTransaction(() -> {
                    // Claim nested inside the outer runInTransaction —
                    // the outer transaction is what provides the
                    // atomicity (the @Transaction annotation on the DAO
                    // default method is ignored by Room; Phase 3.2).
                    db.preservedRefDao().claimJobsForFile(ACCOUNT_0, "shared.mp4");
                    db.preservedBlobDao().incrementRefCount(BLOB_DOC);
                    // Duplicate of the existing message-11 LIVE row.
                    db.preservedRefDao().insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A,
                            11, "LIVE", 0, AliGramEligibility.STATE_ENQUEUED, "f11.mp4"));
                });
                return null;
            });
            fail("duplicate insert inside the transaction must fail");
        } catch (ExecutionException e) {
            assertConstraintFailure(e.getCause());
        }
        assertEquals(AliGramEligibility.STATE_ENQUEUED,
                onDb(() -> db.preservedRefDao().getRef(USER_0, DIALOG_A, 21, "LIVE", 0)).state);
        assertEquals(1, refCountOf(BLOB_DOC));
    }

    // ------------------------------------------------------------------
    // Scenario 6 — DeferredDelete lifecycle and recovery.
    //
    // NOTE: an in-memory DB cannot survive close/reopen by design (a
    // rebuild is a fresh empty DB), so this scenario uses a FILE-BACKED
    // temp DB in the Robolectric cache dir — the only honest way to
    // simulate a restart. The file is deleted in the finally block.
    // ------------------------------------------------------------------

    @Test
    public void scenario6_deferredDeleteLifecycleSurvivesRestart() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        File dbFile = new File(ctx.getCacheDir(), "aligram-room-test-6.db");
        deleteDbFiles(dbFile);
        AliGramDatabase fileDb = Room.databaseBuilder(ctx, AliGramDatabase.class,
                dbFile.getAbsolutePath()).build();
        final AliGramDatabase firstDb = fileDb; // lambdas need an effectively-final handle
        ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> refFuture = fileExecutor.submit(() -> {
                PreservedRef ref = newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_PINNED, "f11.mp4");
                ref.sourcePath = "/cache/f11.mp4";
                ref.sourceSize = 4096L;
                ref.sourceMtime = T0;
                return firstDb.preservedRefDao().insertRef(ref);
            });
            long refId = refFuture.get(30, TimeUnit.SECONDS);
            fileExecutor.submit(() -> {
                firstDb.deferredDeleteDao().insert(
                        newDeferred(ACCOUNT_0, refId, "/cache/f11.mp4"));
                return null;
            }).get(30, TimeUnit.SECONDS);

            // Restart: close and rebuild from the same file.
            fileExecutor.submit(() -> {
                firstDb.close();
                return null;
            }).get(30, TimeUnit.SECONDS);
            fileDb = Room.databaseBuilder(ctx, AliGramDatabase.class,
                    dbFile.getAbsolutePath()).build();
            final AliGramDatabase reopened = fileDb;

            // The PENDING row and its PINNED ref survive the restart.
            List<DeferredDelete> pending = fileExecutor.submit(
                    () -> reopened.deferredDeleteDao().getAllPending()).get(30, TimeUnit.SECONDS);
            assertEquals(1, pending.size());
            assertEquals("PENDING", pending.get(0).state);
            assertEquals(refId, pending.get(0).refId);
            assertEquals(AliGramEligibility.STATE_PINNED,
                    fileExecutor.submit(() -> reopened.preservedRefDao().getRefById(refId))
                            .get(30, TimeUnit.SECONDS).state);

            // Release on terminal copy state: the ref reaches COPIED, no
            // active pin covers the path any more, the row goes DONE and is
            // then removed by deleteDone() — the production release funnel.
            fileExecutor.submit(() -> {
                PreservedRef ref = reopened.preservedRefDao().getRefById(refId);
                ref.state = AliGramEligibility.STATE_COPIED;
                reopened.preservedRefDao().updateRef(ref);
                return null;
            }).get(30, TimeUnit.SECONDS);
            assertTrue(fileExecutor.submit(
                    () -> reopened.preservedRefDao().getActivePinsForPath("/cache/f11.mp4"))
                    .get(30, TimeUnit.SECONDS).isEmpty());
            fileExecutor.submit(() -> {
                for (DeferredDelete row : reopened.deferredDeleteDao().getPendingForRef(refId)) {
                    reopened.deferredDeleteDao().setState(row.id, "DONE");
                }
                return null;
            }).get(30, TimeUnit.SECONDS);
            assertTrue(fileExecutor.submit(
                    () -> reopened.deferredDeleteDao().getAllPending())
                    .get(30, TimeUnit.SECONDS).isEmpty());
            fileExecutor.submit(() -> {
                reopened.deferredDeleteDao().deleteDone();
                return null;
            }).get(30, TimeUnit.SECONDS);
        } finally {
            final AliGramDatabase toClose = fileDb;
            try {
                fileExecutor.submit(() -> {
                    toClose.close();
                    return null;
                }).get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            fileExecutor.shutdownNow();
            fileExecutor.awaitTermination(10, TimeUnit.SECONDS);
            deleteDbFiles(dbFile);
        }
    }

    private static void deleteDbFiles(File dbFile) {
        //noinspection ResultOfMethodCallIgnored
        dbFile.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getPath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile.getPath() + "-shm").delete();
    }

    // ------------------------------------------------------------------
    // Scenario 7 — multiple accounts remain isolated.
    // ------------------------------------------------------------------

    @Test
    public void scenario7_accountsAreIsolated() throws Exception {
        // Account 0 and account 1 share one blob; each has its own ref and
        // its own deferred row.
        long ref0 = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f11.mp4")));
        long ref1 = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_1, ACCOUNT_1, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f11.mp4")));
        onDbVoid(() -> {
            bindRefToBlob(db.preservedRefDao().getRefById(ref0), BLOB_DOC);
            bindRefToBlob(db.preservedRefDao().getRefById(ref1), BLOB_DOC);
        });
        onDbVoid(() -> {
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, ref0, "/cache/a0.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_1, ref1, "/cache/a1.mp4"));
        });
        assertEquals(2, refCountOf(BLOB_DOC));

        // Account-scoped reads see only their own rows.
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForAccount(ACCOUNT_0)).size());
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForAccount(ACCOUNT_1)).size());
        assertEquals(1, (int) onDb(() -> db.deferredDeleteDao().getPendingForPath(ACCOUNT_0, "/cache/a0.mp4")).size());
        assertTrue(onDb(() -> db.deferredDeleteDao().getPendingForPath(ACCOUNT_1, "/cache/a0.mp4")).isEmpty());

        // Account-scoped cleanup (refs keyed by userId, deferred rows by
        // accountId — the DAO asymmetry, see scenario-8 note) touches only
        // account 0: its ref is dropped + decremented, its deferred row is
        // deleted; account 1 is untouched and the shared blob survives.
        onDbVoid(() -> {
            dropRefAndDecrement(db.preservedRefDao().getRefById(ref0));
            db.preservedRefDao().deleteRefsForAccount(USER_0);
            db.deferredDeleteDao().deleteForAccount(ACCOUNT_0);
        });
        assertTrue(onDb(() -> db.preservedRefDao().getRefsForAccount(ACCOUNT_0)).isEmpty());
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForAccount(ACCOUNT_1)).size());
        assertEquals(1, refCountOf(BLOB_DOC));
        assertNotNull(onDb(() -> db.preservedBlobDao().getById(BLOB_DOC)));
        assertEquals(1, (int) onDb(() -> db.deferredDeleteDao().getAllPending()).size());
        assertEquals(ACCOUNT_1, onDb(() -> db.deferredDeleteDao().getAllPending()).get(0).accountId);
    }

    // ------------------------------------------------------------------
    // Scenario 8 — multiple dialogs remain isolated.
    //
    // Dialog-scoped deferred cleanup uses the real DAO primitive
    // DeferredDeleteDao.deleteByAccountAndDialog(int, long) (Phase 3.1
    // fix): it resolves the dialog through refId -> PreservedRef.dialogId,
    // because DeferredDelete has no dialogId column. It deliberately does
    // NOT call the account-wide deleteForAccount() for a dialog cleanup —
    // that call was the Phase 3.1 critical bug (clearPreservedForDialog /
    // onDialogDeleted delete deferred rows account-wide).
    // ------------------------------------------------------------------

    @Test
    public void scenario8_dialogsAreIsolated() throws Exception {
        long refA = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "fA.mp4")));
        long refB = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_B, 21, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "fB.mp4")));
        onDbVoid(() -> {
            bindRefToBlob(db.preservedRefDao().getRefById(refA), BLOB_DOC);
            bindRefToBlob(db.preservedRefDao().getRefById(refB), BLOB_PHOTO);
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refA, "/cache/a.mp4"));
            db.deferredDeleteDao().insert(newDeferred(ACCOUNT_0, refB, "/cache/b.mp4"));
        });
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForDialog(USER_0, DIALOG_A)).size());
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForDialog(USER_0, DIALOG_B)).size());

        // Dialog-scoped cleanup of dialog A only: delete its deferred rows
        // with the dialog-scoped DAO primitive, drop its refs (with
        // refcount decrement), then sweep the blobs that reached refcount 0
        // (as production clearPreservedForDialog does after dropping the
        // refs). The assertions below are unchanged from the pre-fix
        // version, which modelled the same scoping with getPendingForRef +
        // setState + deleteDone.
        onDbVoid(() -> {
            PreservedRefDao refDao = db.preservedRefDao();
            DeferredDeleteDao deferredDao = db.deferredDeleteDao();
            deferredDao.deleteByAccountAndDialog(ACCOUNT_0, DIALOG_A);
            for (PreservedRef ref : refDao.getRefsForDialog(USER_0, DIALOG_A)) {
                dropRefAndDecrement(ref);
            }
            deferredDao.deleteDone();
            sweepRefcountZeroBlobs();
        });

        assertTrue(onDb(() -> db.preservedRefDao().getRefsForDialog(USER_0, DIALOG_A)).isEmpty());
        assertEquals(1, (int) onDb(() -> db.preservedRefDao().getRefsForDialog(USER_0, DIALOG_B)).size());
        assertEquals("COPIED",
                onDb(() -> db.preservedRefDao().getRefById(refB)).state);
        // Dialog A's blob was swept (refcount 0); dialog B's blob is intact.
        assertEquals(-1, refCountOf(BLOB_DOC));
        assertNull(onDb(() -> db.preservedBlobDao().getById(BLOB_DOC)));
        assertEquals(1, refCountOf(BLOB_PHOTO));
        // Only dialog B's deferred row is still PENDING.
        List<DeferredDelete> pending = onDb(() -> db.deferredDeleteDao().getAllPending());
        assertEquals(1, pending.size());
        assertEquals(refB, pending.get(0).refId);
        assertEquals("/cache/b.mp4", pending.get(0).absolutePath);
    }

    // ------------------------------------------------------------------
    // Scenario 9 — orphaned blobs are detected and safely cleaned.
    // ------------------------------------------------------------------

    @Test
    public void scenario9_orphanBlobsDetectedAndSweptSafely() throws Exception {
        // Referenced blob (survives) + true orphan (refCount 0, no refs) +
        // stale-count blob (refCount 5, no refs — getUnreferencedBlobs must
        // still find it even though getOrphanBlobs, which trusts the
        // stored count, does not).
        long refId = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "f11.mp4")));
        onDbVoid(() -> {
            bindRefToBlob(db.preservedRefDao().getRefById(refId), BLOB_DOC);
            db.preservedBlobDao().insertBlob(newBlob(BLOB_PHOTO, 0, 777L, SHA_PHOTO, 2048L));
            PreservedBlob stale = newBlob("d999_2_100_aaaaaaaaaaaaaaaa", 999L, 0,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 100L);
            stale.refCount = 5;
            db.preservedBlobDao().insertBlob(stale);
        });
        assertEquals(3, (int) onDb(() -> db.preservedBlobDao().countBlobs()));

        List<PreservedBlob> orphans = onDb(() -> db.preservedBlobDao().getOrphanBlobs());
        assertEquals(1, orphans.size());
        assertEquals(BLOB_PHOTO, orphans.get(0).blobId);

        List<PreservedBlob> unreferenced = onDb(() -> db.preservedBlobDao().getUnreferencedBlobs());
        assertEquals(2, unreferenced.size());
        List<String> unreferencedIds = new ArrayList<>();
        for (PreservedBlob b : unreferenced) {
            unreferencedIds.add(b.blobId);
        }
        assertTrue(unreferencedIds.contains(BLOB_PHOTO));
        assertTrue(unreferencedIds.contains("d999_2_100_aaaaaaaaaaaaaaaa"));
        assertFalse(unreferencedIds.contains(BLOB_DOC));

        // The recomputed sweep removes exactly the unreferenced blobs.
        onDbVoid(this::sweepUnreferencedBlobs);
        assertEquals(1, (int) onDb(() -> db.preservedBlobDao().countBlobs()));
        assertNotNull(onDb(() -> db.preservedBlobDao().getById(BLOB_DOC)));
        assertEquals(1, refCountOf(BLOB_DOC));
        assertTrue(onDb(() -> db.preservedBlobDao().getUnreferencedBlobs()).isEmpty());
    }

    // ------------------------------------------------------------------
    // Scenario 10 — restart / reconciliation restores consistent state.
    //
    // File-backed temp DB (same reason as scenario 6): state must persist
    // across close/reopen. Seeds mixed states, including an interrupted
    // copy, stale refcounts in both directions, a true orphan, and a
    // PENDING deferred row for a terminal ref. The reconcile helper below
    // mirrors the DAO-level steps of production startupReconcile
    // (re-derive refcounts from PreservedRef rows, sweep unreferenced
    // blobs, reset interrupted COPYING/DOWNLOADING refs to ENQUEUED,
    // resolve PENDING deferred rows whose ref is terminal). Steps that
    // need the filesystem / FileLoader / MessagesStorage (blob-file
    // existence, .tmp-* sweep, re-drive, actual file deletion) are NOT
    // executable in this harness and are not claimed here.
    // ------------------------------------------------------------------

    @Test
    public void scenario10_restartReconciliationRestoresConsistency() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        File dbFile = new File(ctx.getCacheDir(), "aligram-room-test-10.db");
        deleteDbFiles(dbFile);
        AliGramDatabase fileDb = Room.databaseBuilder(ctx, AliGramDatabase.class,
                dbFile.getAbsolutePath()).build();
        final AliGramDatabase firstDb = fileDb; // lambdas need an effectively-final handle
        ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
        try {
            // Seed, on a worker thread: ref R1 COPYING (interrupted copy)
            // bound to blob A (stored refCount 2, only 1 ref — stale +1);
            // ref R2 DOWNLOADING, unbound; ref R3 COPIED bound to blob B
            // (stored refCount 0, 1 ref — stale -1); blob C true orphan
            // (refCount 0, no refs); blob D stale orphan (refCount 3, no
            // refs); deferred PENDING for terminal R3 and for live R1.
            final long[] ids = fileExecutor.submit(() -> {
                PreservedRefDao refDao = firstDb.preservedRefDao();
                PreservedBlobDao blobDao = firstDb.preservedBlobDao();
                DeferredDeleteDao deferredDao = firstDb.deferredDeleteDao();
                PreservedBlob blobA = newBlob(BLOB_DOC, 555L, 0, SHA_DOC, 4096L);
                blobA.refCount = 2;
                blobDao.insertBlob(blobA);
                PreservedBlob blobB = newBlob(BLOB_PHOTO, 0, 777L, SHA_PHOTO, 2048L);
                blobB.refCount = 0;
                blobDao.insertBlob(blobB);
                blobDao.insertBlob(newBlob("d888_2_512_bbbbbbbbbbbbbbbb", 888L, 0,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 512L));
                PreservedBlob blobD = newBlob("d999_2_100_aaaaaaaaaaaaaaaa", 999L, 0,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 100L);
                blobD.refCount = 3;
                blobDao.insertBlob(blobD);

                PreservedRef r1 = newRef(USER_0, ACCOUNT_0, DIALOG_A, 11, "LIVE", 0,
                        AliGramEligibility.STATE_COPYING, "f11.mp4");
                r1.blobId = BLOB_DOC;
                r1.refId = refDao.insertRef(r1);
                PreservedRef r2 = newRef(USER_0, ACCOUNT_0, DIALOG_A, 12, "LIVE", 0,
                        AliGramEligibility.STATE_DOWNLOADING, "f12.mp4");
                r2.refId = refDao.insertRef(r2);
                PreservedRef r3 = newRef(USER_0, ACCOUNT_0, DIALOG_A, 13, "LIVE", 0,
                        AliGramEligibility.STATE_COPIED, "f13.mp4");
                r3.blobId = BLOB_PHOTO;
                r3.refId = refDao.insertRef(r3);
                deferredDao.insert(newDeferred(ACCOUNT_0, r3.refId, "/cache/f13.mp4"));
                deferredDao.insert(newDeferred(ACCOUNT_0, r1.refId, "/cache/f11.mp4"));
                return new long[]{r1.refId, r2.refId, r3.refId};
            }).get(30, TimeUnit.SECONDS);
            final long r1Id = ids[0];
            final long r2Id = ids[1];
            final long r3Id = ids[2];

            // Restart: close and rebuild from the same file.
            fileExecutor.submit(() -> {
                firstDb.close();
                return null;
            }).get(30, TimeUnit.SECONDS);
            fileDb = Room.databaseBuilder(ctx, AliGramDatabase.class,
                    dbFile.getAbsolutePath()).build();
            final AliGramDatabase reopened = fileDb;

            // Pre-reconcile: the inconsistent state is really there.
            assertEquals(2, (int) fileExecutor.submit(
                    () -> reopened.preservedBlobDao().getById(BLOB_DOC).refCount)
                    .get(30, TimeUnit.SECONDS));
            assertEquals(0, (int) fileExecutor.submit(
                    () -> reopened.preservedBlobDao().getById(BLOB_PHOTO).refCount)
                    .get(30, TimeUnit.SECONDS));
            assertEquals(4, (int) fileExecutor.submit(
                    () -> reopened.preservedBlobDao().countBlobs())
                    .get(30, TimeUnit.SECONDS));

            // Reconcile (DAO-level mirror of startupReconcile steps 2/4/5).
            fileExecutor.submit(() -> {
                reconcileDatabase(reopened);
                return null;
            }).get(30, TimeUnit.SECONDS);

            // Post-reconcile: refcounts re-derived from the refs table.
            assertEquals(1, (int) fileExecutor.submit(
                    () -> reopened.preservedBlobDao().getById(BLOB_DOC).refCount)
                    .get(30, TimeUnit.SECONDS));
            assertEquals(1, (int) fileExecutor.submit(
                    () -> reopened.preservedBlobDao().getById(BLOB_PHOTO).refCount)
                    .get(30, TimeUnit.SECONDS));
            // Orphans (true + stale-count) are gone; referenced blobs stay.
            assertEquals(2, (int) fileExecutor.submit(
                    () -> reopened.preservedBlobDao().countBlobs())
                    .get(30, TimeUnit.SECONDS));
            assertNull(fileExecutor.submit(
                    () -> reopened.preservedBlobDao().getById("d888_2_512_bbbbbbbbbbbbbbbb"))
                    .get(30, TimeUnit.SECONDS));
            assertNull(fileExecutor.submit(
                    () -> reopened.preservedBlobDao().getById("d999_2_100_aaaaaaaaaaaaaaaa"))
                    .get(30, TimeUnit.SECONDS));
            assertTrue(fileExecutor.submit(
                    () -> reopened.preservedBlobDao().getUnreferencedBlobs())
                    .get(30, TimeUnit.SECONDS).isEmpty());
            // Interrupted jobs are back to ENQUEUED for re-drive; the
            // finished COPY is untouched.
            assertEquals(AliGramEligibility.STATE_ENQUEUED, fileExecutor.submit(
                    () -> reopened.preservedRefDao().getRefById(r1Id).state)
                    .get(30, TimeUnit.SECONDS));
            assertEquals(AliGramEligibility.STATE_ENQUEUED, fileExecutor.submit(
                    () -> reopened.preservedRefDao().getRefById(r2Id).state)
                    .get(30, TimeUnit.SECONDS));
            assertEquals(AliGramEligibility.STATE_COPIED, fileExecutor.submit(
                    () -> reopened.preservedRefDao().getRefById(r3Id).state)
                    .get(30, TimeUnit.SECONDS));
            // Deferred row for the terminal ref is DONE; the live ref's
            // row is still PENDING (its pin is still active).
            List<DeferredDelete> pending = fileExecutor.submit(
                    () -> reopened.deferredDeleteDao().getAllPending())
                    .get(30, TimeUnit.SECONDS);
            assertEquals(1, pending.size());
            assertEquals(r1Id, pending.get(0).refId);
        } finally {
            final AliGramDatabase toClose = fileDb;
            try {
                fileExecutor.submit(() -> {
                    toClose.close();
                    return null;
                }).get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            fileExecutor.shutdownNow();
            fileExecutor.awaitTermination(10, TimeUnit.SECONDS);
            deleteDbFiles(dbFile);
        }
    }

    /**
     * DAO-level mirror of the reconcile steps in production
     * {@code startupReconcile} that need no filesystem/FileLoader: sweep
     * unreferenced blobs, re-derive every surviving blob's refcount from
     * the refs table (the DAO has no setRefCount, so the delta is applied
     * with the existing increment/decrement queries), reset interrupted
     * COPYING/DOWNLOADING refs to ENQUEUED, and resolve PENDING deferred
     * rows whose ref reached a terminal state. Must run on a worker thread.
     */
    private static void reconcileDatabase(AliGramDatabase database) {
        PreservedRefDao refDao = database.preservedRefDao();
        PreservedBlobDao blobDao = database.preservedBlobDao();
        DeferredDeleteDao deferredDao = database.deferredDeleteDao();
        for (PreservedBlob blob : blobDao.getUnreferencedBlobs()) {
            blobDao.deleteById(blob.blobId);
        }
        // Re-derive refcounts for the blobs the refs still point at.
        for (PreservedRef ref : refDao.getAllRefs()) {
            if (ref.blobId == null) {
                continue;
            }
            PreservedBlob blob = blobDao.getById(ref.blobId);
            if (blob == null) {
                continue;
            }
            int expected = refDao.countRefsForBlob(ref.blobId);
            while (blob.refCount < expected) {
                blobDao.incrementRefCount(ref.blobId);
                blob.refCount++;
            }
            while (blob.refCount > expected) {
                blobDao.decrementRefCount(ref.blobId);
                blob.refCount--;
            }
        }
        for (PreservedRef ref : refDao.getNonTerminalRefs()) {
            if (AliGramEligibility.STATE_COPYING.equals(ref.state)
                    || AliGramEligibility.STATE_DOWNLOADING.equals(ref.state)) {
                ref.state = AliGramEligibility.STATE_ENQUEUED;
                ref.updatedAt = T0;
                refDao.updateRef(ref);
            }
        }
        for (DeferredDelete row : deferredDao.getAllPending()) {
            PreservedRef ref = refDao.getRefById(row.refId);
            if (ref != null && AliGramEligibility.isTerminalState(ref.state)) {
                deferredDao.setState(row.id, "DONE");
            }
        }
    }

    // ------------------------------------------------------------------
    // Scenario 11 — concurrent claims claim each job exactly once.
    //
    // THE missing check for claimJobsForFile atomicity (Phase 3.1 report
    // §11 item 6, Phase 3.2 investigation phase3-2-claim-atomicity.md):
    // N threads claim the same file at the same time behind a start
    // gate. Exactly one caller must win each waiting job: the union of
    // all returned lists must be exactly the seeded set, with no
    // duplicate refId anywhere. The claim goes through the transactional
    // helper (the production path — see scenario 12). The claim call is
    // retried on a
    // transient SQLite busy/locked failure (same bounded-retry pattern
    // as scenario 4): Robolectric's shadow SQLite throws SQLITE_BUSY
    // under thread contention, and that contention mode is NOT the
    // atomicity property under test — the production callers of
    // claimJobsForFile are single-threaded, so a busy failure here
    // would be a test-harness artefact, not a product defect. A retry
    // re-claims only still-waiting jobs, so it cannot mask a double
    // claim: a duplicated refId would still appear in the union. The
    // claim is repeated for 10 rounds (re-seeded each round) because a
    // single round can miss a rare interleave.
    // ------------------------------------------------------------------

    @Test
    public void scenario11_concurrentClaimsClaimEachJobExactlyOnce() throws Exception {
        final int threads = 8;
        final int rounds = 10;
        final String fileName = "shared.mp4";
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            final long[] ids = seedClaimJobs(fileName);
            final long waiting1 = ids[0];
            final long waiting2 = ids[1];
            final long waiting3 = ids[2];
            final long alreadyCopying = ids[3];
            final long otherFile = ids[4];

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            List<List<Long>> claimedPerThread = new ArrayList<>();
            List<Throwable> errors = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            startGate.await(30, TimeUnit.SECONDS);
                            // Direct DAO call from a pool thread (same
                            // pattern as scenario 4): never via the
                            // single-thread onDb executor, which would
                            // serialize the calls and defeat the test.
                            List<PreservedRef> claimed = null;
                            RuntimeException lastBusy = null;
                            for (int attempt = 0; attempt < 30; attempt++) {
                                try {
                                    // Production path: the claim runs inside
                                    // the transactional helper, never bare.
                                    claimed = PreservedRefDao.claimJobsInTransaction(db, ACCOUNT_0, fileName);
                                    lastBusy = null;
                                    break;
                                } catch (RuntimeException e) {
                                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                                    if (msg.contains("locked") || msg.contains("busy")) {
                                        lastBusy = e;
                                        try {
                                            Thread.sleep(5L);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    } else {
                                        throw e;
                                    }
                                }
                            }
                            if (lastBusy != null) {
                                throw lastBusy;
                            }
                            List<Long> got = new ArrayList<>();
                            if (claimed != null) {
                                for (PreservedRef r : claimed) {
                                    got.add(r.refId);
                                }
                            }
                            synchronized (claimedPerThread) {
                                claimedPerThread.add(got);
                            }
                        } catch (Throwable t) {
                            synchronized (errors) {
                                errors.add(t);
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
                startGate.countDown();
                if (!done.await(60, TimeUnit.SECONDS)) {
                    failures.add("round " + round + ": concurrent claims did not finish in time");
                    continue;
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(10, TimeUnit.SECONDS);
            }
            if (!errors.isEmpty()) {
                failures.add("round " + round + ": claim threw under contention: " + errors);
                cleanupClaimJobs(ids);
                continue;
            }
            List<Long> allClaimed = new ArrayList<>();
            for (List<Long> got : claimedPerThread) {
                allClaimed.addAll(got);
            }
            List<Long> union = new ArrayList<>(new java.util.HashSet<>(allClaimed));
            List<Long> expected = new ArrayList<>();
            expected.add(waiting1);
            expected.add(waiting2);
            expected.add(waiting3);
            java.util.Collections.sort(union);
            java.util.Collections.sort(expected);
            if (allClaimed.size() != 3 || !union.equals(expected)) {
                failures.add("round " + round + ": expected exactly the 3 waiting jobs claimed "
                        + "once each, got claimed=" + allClaimed + " union=" + union);
            }
            // Final states: the three waiting jobs are COPYING; the
            // already-COPYING control is untouched; the other-file
            // control is still ENQUEUED (claim is scoped by file name).
            assertEquals(AliGramEligibility.STATE_COPYING,
                    onDb(() -> db.preservedRefDao().getRefById(waiting1)).state);
            assertEquals(AliGramEligibility.STATE_COPYING,
                    onDb(() -> db.preservedRefDao().getRefById(waiting2)).state);
            assertEquals(AliGramEligibility.STATE_COPYING,
                    onDb(() -> db.preservedRefDao().getRefById(waiting3)).state);
            assertEquals(AliGramEligibility.STATE_COPYING,
                    onDb(() -> db.preservedRefDao().getRefById(alreadyCopying)).state);
            assertEquals(AliGramEligibility.STATE_ENQUEUED,
                    onDb(() -> db.preservedRefDao().getRefById(otherFile)).state);
            cleanupClaimJobs(ids);
        }
        assertTrue("concurrent claim failures: " + failures, failures.isEmpty());
    }

    /**
     * Seeds one claim fixture: three waiting jobs for {@code fileName}
     * (ENQUEUED, DOWNLOADING, PINNED — all three states
     * {@code getWaitingJobsForFile} matches), one already-COPYING control
     * for the same file, one ENQUEUED control for a different file.
     * Returns the five refIds in that order. Runs on the DB worker thread.
     */
    private long[] seedClaimJobs(String fileName) throws Exception {
        return onDb(() -> {
            PreservedRefDao dao = db.preservedRefDao();
            long w1 = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 31, "LIVE", 0,
                    AliGramEligibility.STATE_ENQUEUED, fileName));
            long w2 = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 32, "LIVE", 0,
                    AliGramEligibility.STATE_DOWNLOADING, fileName));
            long w3 = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 33, "LIVE", 0,
                    AliGramEligibility.STATE_PINNED, fileName));
            long copying = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 34, "LIVE", 0,
                    AliGramEligibility.STATE_COPYING, fileName));
            long other = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 35, "LIVE", 0,
                    AliGramEligibility.STATE_ENQUEUED, "other.mp4"));
            return new long[]{w1, w2, w3, copying, other};
        });
    }

    /**
     * Seeds a claim fixture for the standalone-rollback scenario: two
     * waiting jobs for {@code fileName} (ENQUEUED, DOWNLOADING) and one
     * ENQUEUED control for a different file. Deliberately NO
     * already-COPYING control (unlike {@link #seedClaimJobs}): the
     * fault-injection trigger in scenario 12 counts COPYING rows, and a
     * pre-existing COPYING row would make it fire on the FIRST update,
     * so the test would pass vacuously even with no transaction.
     * Returns the three refIds in that order.
     */
    private long[] seedClaimJobsStandalone(String fileName) throws Exception {
        return onDb(() -> {
            PreservedRefDao dao = db.preservedRefDao();
            long w1 = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 31, "LIVE", 0,
                    AliGramEligibility.STATE_ENQUEUED, fileName));
            long w2 = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 32, "LIVE", 0,
                    AliGramEligibility.STATE_DOWNLOADING, fileName));
            long other = dao.insertRef(newRef(USER_0, ACCOUNT_0, DIALOG_A, 35, "LIVE", 0,
                    AliGramEligibility.STATE_ENQUEUED, "other.mp4"));
            return new long[]{w1, w2, other};
        });
    }

    private void cleanupClaimJobs(long[] ids) throws Exception {
        onDbVoid(() -> {
            PreservedRefDao dao = db.preservedRefDao();
            for (long id : ids) {
                dao.deleteRefById(id);
            }
        });
    }

    // ------------------------------------------------------------------
    // Scenario 12 — a mid-claim failure rolls back the claim itself,
    // with NO outer transaction.
    //
    // This is the test scenario 5(b) is NOT: the claim runs standalone
    // through the transactional helper (the production path), so only
    // the runInTransaction wrapper can save it. Fault injection without
    // touching production code: a BEFORE UPDATE trigger aborts the
    // SECOND COPYING update of the fixture (the WHEN clause counts the
    // COPYING rows already written on this connection: 0 before the
    // first update, 1 before the second). The abort must propagate out
    // of claimJobsForFile, and BOTH rows must still be ENQUEUED
    // afterwards. If the helper's transaction did not wrap the claim,
    // the first UPDATE has already committed (autocommit) and the first
    // row stays COPYING — the test fails with exactly that diagnosis.
    // (The original form of this test — calling the DAO method bare —
    // failed exactly that way on run 43633746250, proving Room ignores
    // @Transaction on DAO default methods; that is why the helper
    // exists. The trigger lives only on this test's fresh in-memory DB
    // and is dropped in the finally block.)
    // ------------------------------------------------------------------

    @Test
    public void scenario12_standaloneClaimRollsBackMidClaimFailure() throws Exception {
        final String trigger = "aligram_claim_test_abort";
        final long[] ids = seedClaimJobsStandalone("shared.mp4");
        final long waiting1 = ids[0];
        final long waiting2 = ids[1];
        final long otherFile = ids[2];
        try {
            onDb(() -> {
                db.getOpenHelper().getWritableDatabase().execSQL(
                        "CREATE TRIGGER " + trigger + " "
                                + "BEFORE UPDATE OF state ON PreservedRef "
                                + "WHEN NEW.state = 'COPYING' "
                                + "AND (SELECT COUNT(*) FROM PreservedRef WHERE state = 'COPYING') >= 1 "
                                + "BEGIN "
                                + "SELECT RAISE(ABORT, 'aligram-test: abort second COPYING update'); "
                                + "END");
                return null;
            });

            // Standalone claim through the transactional helper —
            // deliberately NOT wrapped in db.runInTransaction here
            // (that is what scenario 5(b) did, and it is why 5(b)
            // cannot prove this); the helper itself provides the
            // transaction, exactly as the production call site does.
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            try {
                onDb(() -> PreservedRefDao.claimJobsInTransaction(db, ACCOUNT_0, "shared.mp4"));
                fail("claim must throw when its second COPYING update aborts");
            } catch (ExecutionException e) {
                thrown.set(e.getCause());
            }
            assertNotNull("claim threw nothing; trigger did not fire?", thrown.get());
            assertTrue("expected a SQLite error from the trigger, got "
                    + thrown.get().getClass().getName() + ": " + thrown.get().getMessage(),
                    thrown.get() instanceof android.database.sqlite.SQLiteException
                            || (thrown.get().getMessage() != null
                            && thrown.get().getMessage().contains("aligram-test")));

            // Rollback assertion: with the helper's runInTransaction
            // wrapper both rows are ENQUEUED again; without one the
            // first row is COPYING (its UPDATE committed in autocommit).
            assertEquals("first claimed row was NOT rolled back: the helper's transaction did not wrap the claim",
                    AliGramEligibility.STATE_ENQUEUED,
                    onDb(() -> db.preservedRefDao().getRefById(waiting1)).state);
            assertEquals(AliGramEligibility.STATE_ENQUEUED,
                    onDb(() -> db.preservedRefDao().getRefById(waiting2)).state);
            assertEquals("other-file control must stay ENQUEUED (claim is scoped by file name)",
                    AliGramEligibility.STATE_ENQUEUED,
                    onDb(() -> db.preservedRefDao().getRefById(otherFile)).state);
        } finally {
            try {
                onDb(() -> {
                    db.getOpenHelper().getWritableDatabase()
                            .execSQL("DROP TRIGGER IF EXISTS " + trigger);
                    return null;
                });
            } catch (Exception ignored) {
                // The DB is per-test in-memory; a leftover trigger dies with it.
            }
            cleanupClaimJobs(ids);
        }
    }

    // ------------------------------------------------------------------
    // Scenario 12b — diagnostic probe (Phase 3.2): isolate WHY scenario
    // 12's trigger-aborted claim did NOT roll back on CI run 43634264861
    // even though the claim runs inside db.runInTransaction (the
    // helper). Pure SQLite rolls this back correctly — verified locally
    // with Python's sqlite3 3.45.1 (BEGIN IMMEDIATE; UPDATE w1 -> COPYING;
    // UPDATE w2 aborted by the trigger; ROLLBACK; w1 is ENQUEUED again).
    // So the engine semantics are sound and the breakage is in the
    // Room/Robolectric layer, not the trigger or the helper.
    //
    // Minimal form, no WHEN subquery: ONE row, an unconditional BEFORE
    // UPDATE trigger that aborts ANY COPYING update, plus a probe of
    // inTransaction() inside the transaction. Outcomes distinguish:
    //   - trigger fires + inTx true + row ENQUEUED
    //       => transactions hold for trigger aborts; scenario 12's
    //       failure is specific to its two-row WHEN-subquery setup.
    //   - trigger fires + inTx true + row COPYING
    //       => the trigger abort bypasses the transaction in this
    //       Robolectric harness; scenario 12's trigger injection is
    //       invalid HERE and its rollback property must be proven by
    //       another injection (5(b) + scenario 11 already cover the
    //       mechanism and the concurrency invariant).
    //   - inTx false
    //       => beginTransaction was a no-op on this connection.
    // Steps: https://github.com/tedfoagen-stack/AliGram/actions/runs/43634264861
    // (72 tests, 1 failed — scenario 12, ComparisonFailure at :1276).
    // ------------------------------------------------------------------

    @Test
    public void scenario12b_triggerAbortTransactionProbe() throws Exception {
        final long id = onDb(() -> db.preservedRefDao().insertRef(
                newRef(USER_0, ACCOUNT_0, DIALOG_A, 77, "LIVE", 0,
                        AliGramEligibility.STATE_ENQUEUED, "probe.mp4")));
        onDb(() -> {
            db.getOpenHelper().getWritableDatabase().execSQL(
                    "CREATE TRIGGER aligram_probe_abort "
                    + "BEFORE UPDATE OF state ON PreservedRef "
                    + "WHEN NEW.state = 'COPYING' "
                    + "BEGIN SELECT RAISE(ABORT, 'aligram-probe'); END");
            return null;
        });
        // The trigger must exist on the connection the claim uses.
        final int triggerCount = onDb(() -> {
            final android.database.Cursor c = db.getOpenHelper().getWritableDatabase()
                    .query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = ?",
                            new Object[]{"aligram_probe_abort"});
            try {
                c.moveToFirst();
                return c.getInt(0);
            } finally {
                c.close();
            }
        });
        assertEquals("probe trigger was not created", 1, triggerCount);

        final AtomicBoolean inTx = new AtomicBoolean(false);
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            onDb(() -> db.runInTransaction(() -> {
                inTx.set(db.getOpenHelper().getWritableDatabase().inTransaction());
                final PreservedRef row = db.preservedRefDao().getRefById(id);
                row.state = AliGramEligibility.STATE_COPYING;
                db.preservedRefDao().updateRef(row);
                return null;
            }));
            fail("probe UPDATE must throw when the trigger aborts it");
        } catch (ExecutionException e) {
            thrown.set(e.getCause());
        }
        assertNotNull("probe UPDATE threw nothing", thrown.get());
        assertTrue("probe trigger did not fire: " + thrown.get(),
                thrown.get().getMessage() != null
                        && thrown.get().getMessage().contains("aligram-probe"));
        assertTrue("runInTransaction did not begin a transaction on this connection",
                inTx.get());
        assertEquals("probe row was NOT rolled back: the trigger abort bypassed the transaction in this harness",
                AliGramEligibility.STATE_ENQUEUED,
                onDb(() -> db.preservedRefDao().getRefById(id)).state);
    }

    // ------------------------------------------------------------------
    // Scenario 13 — sequential double claim is a no-op (baseline).
    //
    // Pins the DAO javadoc ("a second delivery finds no waiting rows
    // and is a no-op") as a standalone assertion: claim once, get both
    // jobs; claim again, get an empty list; states unchanged. This is
    // the sequential baseline the concurrent scenario 11 compares
    // against — if 13 passes and 11 fails, the failure is purely a
    // concurrency property, not a logic error in the claim itself.
    // ------------------------------------------------------------------

    @Test
    public void scenario13_sequentialDoubleClaimIsNoop() throws Exception {
        final long[] ids = seedClaimJobs("shared.mp4");
        List<PreservedRef> first = onDb(() ->
                PreservedRefDao.claimJobsInTransaction(db, ACCOUNT_0, "shared.mp4"));
        assertEquals(3, first.size());
        assertEquals(AliGramEligibility.STATE_COPYING,
                onDb(() -> db.preservedRefDao().getRefById(ids[0])).state);
        assertEquals(AliGramEligibility.STATE_COPYING,
                onDb(() -> db.preservedRefDao().getRefById(ids[1])).state);
        assertEquals(AliGramEligibility.STATE_COPYING,
                onDb(() -> db.preservedRefDao().getRefById(ids[2])).state);
        List<PreservedRef> second = onDb(() ->
                PreservedRefDao.claimJobsInTransaction(db, ACCOUNT_0, "shared.mp4"));
        assertTrue("second claim must find no waiting rows", second.isEmpty());
        cleanupClaimJobs(ids);
    }
}
