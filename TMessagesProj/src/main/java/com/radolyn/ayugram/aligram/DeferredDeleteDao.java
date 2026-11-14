package com.radolyn.ayugram.aligram;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * DAO for {@link DeferredDelete} (Phase 2.3 §2.2(a)).
 *
 * <p>Scoping contract (Phase 3.1 — the DeferredDelete scope fix):
 * {@link DeferredDelete} carries NO {@code dialogId} column, so a
 * dialog-scoped operation must resolve the dialog through
 * {@code refId -> PreservedRef.dialogId}. The subquery in
 * {@link #deleteByAccountAndDialog} / {@link #getPendingForDialog} does
 * exactly that. Two consequences follow and are pinned by tests:
 *
 * <ul>
 *   <li>Rows with {@code refId == 0} (no live ref — e.g. the Layer-2
 *       FileLoader guard recorded the row when the pin mirror had no
 *       entry) are UNATTRIBUTED: no dialog-scoped query can ever match
 *       them, so they survive dialog cleanups and are released only by
 *       the account startup sweep, which re-validates the pin per path.
 *       A dialog cleanup must never delete them.</li>
 *   <li>{@code refId} is the globally unique {@code PreservedRef} primary
 *       key, so a refId-keyed operation is account-safe by construction —
 *       but the account-scoped overload
 *       {@link #getPendingForRef(int, long)} is still preferred in
 *       production release paths, so a foreign refId recorded by mistake
 *       (see {@code AliGramPreservation.recordDeferredDelete}) can never
 *       release another account's rows.</li>
 * </ul>
 *
 * <p>The only account-wide delete that remains is
 * {@link #deleteForAccount}, used solely by the two account-wide
 * operations that mean it ({@code purgeAccount} on logout/account
 * removal and {@code clearPreservedStorage} from settings). It must
 * never be called from a dialog-scoped flow.
 */
@Dao
public interface DeferredDeleteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(DeferredDelete row);

    @Query("SELECT * FROM DeferredDelete WHERE refId = :refId AND state = 'PENDING'")
    List<DeferredDelete> getPendingForRef(long refId);

    /**
     * Account-scoped variant of {@link #getPendingForRef(long)} — the one
     * production release paths must use. A row is returned only when BOTH
     * its {@code accountId} and its {@code refId} match, so a foreign
     * refId (recorded when the pin mirror held another account's ref)
     * can never release this account's rows.
     */
    @Query("SELECT * FROM DeferredDelete WHERE accountId = :accountId AND refId = :refId AND state = 'PENDING'")
    List<DeferredDelete> getPendingForRef(int accountId, long refId);

    @Query("SELECT * FROM DeferredDelete WHERE accountId = :accountId AND absolutePath = :absolutePath AND state = 'PENDING'")
    List<DeferredDelete> getPendingForPath(int accountId, String absolutePath);

    /**
     * PENDING rows of one dialog, resolved through the owning ref:
     * the row's {@code refId} must point at a {@code PreservedRef} of
     * this account and dialog. Rows with {@code refId == 0} are never
     * returned (unattributed — see the class javadoc).
     */
    @Query("SELECT * FROM DeferredDelete WHERE accountId = :accountId AND state = 'PENDING' AND refId IN (SELECT refId FROM PreservedRef WHERE accountId = :accountId AND dialogId = :dialogId)")
    List<DeferredDelete> getPendingForDialog(int accountId, long dialogId);

    /** All PENDING rows of one account (startup sweep; never cross-account). */
    @Query("SELECT * FROM DeferredDelete WHERE accountId = :accountId AND state = 'PENDING'")
    List<DeferredDelete> getPendingForAccount(int accountId);

    @Query("SELECT * FROM DeferredDelete WHERE state = 'PENDING'")
    List<DeferredDelete> getAllPending();

    @Query("UPDATE DeferredDelete SET state = :state WHERE id = :id")
    void setState(long id, String state);

    /** Terminal rows only (a DONE row's file was already handled). */
    @Query("DELETE FROM DeferredDelete WHERE state = 'DONE'")
    void deleteDone();

    /**
     * ACCOUNT-WIDE delete. Legitimate only for {@code purgeAccount} and
     * {@code clearPreservedStorage}, which mean "this account, everything".
     * Never call it from a dialog-scoped flow — that was the Phase 3.1
     * critical bug (it deleted other dialogs' deferred rows).
     */
    @Query("DELETE FROM DeferredDelete WHERE accountId = :accountId")
    void deleteForAccount(int accountId);

    /**
     * Dialog-scoped delete (Phase 3.1 fix): delete every deferred row of
     * exactly one (account, dialog), resolving the dialog through
     * {@code refId -> PreservedRef.dialogId} because {@link DeferredDelete}
     * has no {@code dialogId} column. Rows with {@code refId == 0} are
     * never matched (unattributed). Callers that drop refs must call this
     * while the dialog's refs still exist — afterwards the subquery
     * resolves to nothing. Production dialog cleanups normally RELEASE
     * rows first (which deletes the file with size+mtime re-validation);
     * this is the row-level primitive for those cleanups and for tests.
     */
    @Query("DELETE FROM DeferredDelete WHERE accountId = :accountId AND refId IN (SELECT refId FROM PreservedRef WHERE accountId = :accountId AND dialogId = :dialogId)")
    void deleteByAccountAndDialog(int accountId, long dialogId);

    /**
     * Delete every deferred row of one ref. Safe across accounts because
     * {@code refId} is the globally unique {@code PreservedRef} primary
     * key. Only call it after the ref is dropped or terminal and its rows
     * have been released (i.e. they are DONE); never use it to drop a
     * still-PENDING row whose file release is owed — use the release path
     * ({@code releaseDeferredDeletesForRef}) for that.
     */
    @Query("DELETE FROM DeferredDelete WHERE refId = :refId")
    void deleteForRef(long refId);
}
