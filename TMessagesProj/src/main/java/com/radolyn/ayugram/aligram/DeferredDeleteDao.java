package com.radolyn.ayugram.aligram;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/** DAO for {@link DeferredDelete} (Phase 2.3 §2.2(a)). */
@Dao
public interface DeferredDeleteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(DeferredDelete row);

    @Query("SELECT * FROM DeferredDelete WHERE refId = :refId AND state = 'PENDING'")
    List<DeferredDelete> getPendingForRef(long refId);

    @Query("SELECT * FROM DeferredDelete WHERE accountId = :accountId AND absolutePath = :absolutePath AND state = 'PENDING'")
    List<DeferredDelete> getPendingForPath(int accountId, String absolutePath);

    @Query("SELECT * FROM DeferredDelete WHERE state = 'PENDING'")
    List<DeferredDelete> getAllPending();

    @Query("UPDATE DeferredDelete SET state = :state WHERE id = :id")
    void setState(long id, String state);

    @Query("DELETE FROM DeferredDelete WHERE state = 'DONE'")
    void deleteDone();

    @Query("DELETE FROM DeferredDelete WHERE accountId = :accountId")
    void deleteForAccount(int accountId);
}
