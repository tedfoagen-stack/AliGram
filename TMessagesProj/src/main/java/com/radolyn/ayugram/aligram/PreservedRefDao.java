package com.radolyn.ayugram.aligram;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.util.List;

/** DAO for {@link PreservedRef}. All calls are synchronous; call only from
 *  background threads (storage queue / globalQueue / copy queue /
 *  fileLoaderQueue) — never from the UI thread. */
@Dao
public interface PreservedRefDao {

    @Insert
    long insertRef(PreservedRef ref);

    @Update
    void updateRef(PreservedRef ref);

    @Query("SELECT * FROM PreservedRef WHERE refId = :refId LIMIT 1")
    PreservedRef getRefById(long refId);

    @Query("SELECT * FROM PreservedRef")
    List<PreservedRef> getAllRefs();

    @Query("SELECT * FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId")
    List<PreservedRef> getRefsForDialog(long userId, long dialogId);

    @Query("SELECT * FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId AND role = 'LIVE' LIMIT 1")
    PreservedRef getLiveRef(long userId, long dialogId, int messageId);

    @Query("SELECT * FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId AND role = :role AND revisionSeq = :revisionSeq LIMIT 1")
    PreservedRef getRef(long userId, long dialogId, int messageId, String role, int revisionSeq);

    @Query("SELECT * FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId")
    List<PreservedRef> getRefsForMessage(long userId, long dialogId, int messageId);

    @Query("SELECT * FROM PreservedRef WHERE ayuFakeId = :ayuFakeId AND ayuFakeId != 0 LIMIT 1")
    PreservedRef getRefByAyuFakeId(long ayuFakeId);

    @Query("SELECT * FROM PreservedRef WHERE ayuFakeId = :ayuFakeId AND ayuFakeId != 0 AND role = :role LIMIT 1")
    PreservedRef getRefByAyuFakeIdAndRole(long ayuFakeId, String role);

    /** Waiting rows for one completion event (fan-out claim source). */
    @Query("SELECT * FROM PreservedRef WHERE accountId = :accountId AND expectedFileName = :expectedFileName AND state IN ('ENQUEUED', 'DOWNLOADING', 'PINNED')")
    List<PreservedRef> getWaitingJobsForFile(int accountId, String expectedFileName);

    /**
     * Phase 3.1 (D-02): active refs (any pin state, incl. COPYING) for
     * one attach file name. The name mirror
     * ({@code AliGramPreservation.pinnedNameMirror}) is keyed by file
     * name and shared by the fan-out refs for one file, so it must only
     * be cleared when NO active ref still names the file — otherwise
     * the {@code cancelDownloading} pin guard would miss a still-pinned
     * freeze copy.
     */
    @Query("SELECT * FROM PreservedRef WHERE accountId = :accountId AND expectedFileName = :expectedFileName AND state IN ('ENQUEUED', 'DOWNLOADING', 'PINNED', 'COPYING')")
    List<PreservedRef> getActiveRefsForFileName(int accountId, String expectedFileName);

    /** Active pins covering one source path (Layer-1/Layer-2 guard + release check). */
    @Query("SELECT * FROM PreservedRef WHERE sourcePath = :sourcePath AND state IN ('PINNED', 'COPYING', 'DOWNLOADING', 'ENQUEUED')")
    List<PreservedRef> getActivePinsForPath(String sourcePath);

    @Query("SELECT * FROM PreservedRef WHERE state = 'PINNED'")
    List<PreservedRef> getPinnedRefs();

    @Query("SELECT * FROM PreservedRef WHERE state IN ('ENQUEUED', 'DOWNLOADING', 'COPYING', 'PINNED')")
    List<PreservedRef> getNonTerminalRefs();

    @Query("SELECT * FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId AND state IN ('ENQUEUED', 'DOWNLOADING', 'COPYING')")
    List<PreservedRef> getPendingJobsForDialog(long userId, long dialogId);

    @Query("SELECT COUNT(*) FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId AND state IN ('ENQUEUED', 'DOWNLOADING', 'COPYING')")
    int getPendingJobCountForDialog(long userId, long dialogId);

    @Query("SELECT COALESCE(SUM(expectedSize), 0) FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId AND state IN ('ENQUEUED', 'DOWNLOADING', 'COPYING')")
    long getPendingBytesForDialog(long userId, long dialogId);

    @Query("SELECT COALESCE(MAX(revisionSeq), 0) FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId AND role = 'EDIT'")
    int getMaxEditRevisionSeq(long userId, long dialogId, int messageId);

    @Query("SELECT * FROM PreservedRef WHERE accountId = :accountId")
    List<PreservedRef> getRefsForAccount(int accountId);

    @Query("SELECT * FROM PreservedRef WHERE accountId = :accountId AND state IN ('ENQUEUED', 'DOWNLOADING', 'COPYING', 'PINNED')")
    List<PreservedRef> getActiveRefsForAccount(int accountId);

    @Query("SELECT COUNT(*) FROM PreservedRef WHERE blobId = :blobId")
    int countRefsForBlob(String blobId);

    @Query("DELETE FROM PreservedRef WHERE refId = :refId")
    void deleteRefById(long refId);

    @Query("DELETE FROM PreservedRef WHERE userId = :userId")
    void deleteRefsForAccount(long userId);

    /**
     * Phase 3.1 (D-03): delete by the STABLE account id, never by
     * {@code userId}. {@code MessagesController.performLogout} calls
     * {@code UserConfig.clearConfig()} (which zeroes
     * {@code clientUserId}) BEFORE the AliGram purge hook, so a purge
     * keyed on {@code ownUserId(account)} deleted nothing — every
     * {@code PreservedRef} row survived logout. The account id cannot
     * be cleared out from under the purge, so this is robust to
     * call-site ordering. Use this for account removal; keep
     * {@link #deleteRefsForAccount(long)} only for callers that still
     * hold a valid user id.
     */
    @Query("DELETE FROM PreservedRef WHERE accountId = :accountId")
    void deleteRefsForAccountId(int accountId);

    @Query("DELETE FROM PreservedRef WHERE userId = :userId AND dialogId = :dialogId")
    void deleteRefsForDialog(long userId, long dialogId);

    /**
     * Fan-out claim (Phase 2.2 §1.7): move every waiting row for one file to
     * COPYING; the caller then copies once and binds per job. A second delivery finds no waiting rows and is a no-op.
     *
     * <p>NOT transactional by itself: Room 2.8.4 ignores {@code @Transaction}
     * on DAO {@code default} methods — proven by scenario 12 (Phase 3.2):
     * the standalone claim's first UPDATE committed in autocommit and the
     * rollback assertion failed. Callers MUST use
     * {@link #claimJobsInTransaction} — never call this method directly
     * outside a {@code runInTransaction} block.
     */
    default List<PreservedRef> claimJobsForFile(int accountId, String expectedFileName) {
        List<PreservedRef> jobs = getWaitingJobsForFile(accountId, expectedFileName);
        for (PreservedRef job : jobs) {
            job.state = AliGramEligibility.STATE_COPYING;
            job.updatedAt = System.currentTimeMillis() / 1000;
            updateRef(job);
        }
        return jobs;
    }

    /**
     * Transactional fan-out claim — the ONLY way to call
     * {@link #claimJobsForFile}.
     *
     * <p>The transaction is provided here, by the caller, via
     * {@code runInTransaction}, because the {@code @Transaction}
     * annotation on the {@code default} method is ignored by Room
     * (see above). Every caller must go through this helper.
     */
    static List<PreservedRef> claimJobsInTransaction(AliGramDatabase db, int accountId, String expectedFileName) {
        return db.runInTransaction(() -> db.preservedRefDao().claimJobsForFile(accountId, expectedFileName));
    }

    /** Next EDIT revisionSeq for a message, assigned in one transaction. */
    @Transaction
    default int nextRevisionSeq(long userId, long dialogId, int messageId) {
        return AliGramEligibility.nextRevisionSeq(getMaxEditRevisionSeq(userId, dialogId, messageId));
    }
}
