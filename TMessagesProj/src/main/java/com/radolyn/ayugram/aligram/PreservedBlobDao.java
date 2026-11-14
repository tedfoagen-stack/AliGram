package com.radolyn.ayugram.aligram;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/** DAO for {@link PreservedBlob}. Dedupe happens at copy time: if a blob
 *  with the same (documentId|photoId, dcId, size, sha256) already exists,
 *  the temp copy is discarded and the existing blob is reused (refcount+1). */
@Dao
public interface PreservedBlobDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertBlob(PreservedBlob blob);

    @Query("SELECT * FROM PreservedBlob WHERE documentId = :documentId AND dcId = :dcId AND size = :size")
    List<PreservedBlob> getBlobsByDocument(long documentId, int dcId, long size);

    @Query("SELECT * FROM PreservedBlob WHERE photoId = :photoId AND dcId = :dcId AND size = :size")
    List<PreservedBlob> getBlobsByPhoto(long photoId, int dcId, long size);

    @Query("SELECT * FROM PreservedBlob WHERE blobId = :blobId LIMIT 1")
    PreservedBlob getById(String blobId);

    @Query("SELECT * FROM PreservedBlob WHERE documentId = :documentId AND dcId = :dcId AND size = :size AND sha256 = :sha256 LIMIT 1")
    PreservedBlob getByDocumentKey(long documentId, int dcId, long size, String sha256);

    @Query("SELECT * FROM PreservedBlob WHERE photoId = :photoId AND dcId = :dcId AND size = :size AND sha256 = :sha256 LIMIT 1")
    PreservedBlob getByPhotoKey(long photoId, int dcId, long size, String sha256);

    @Query("UPDATE PreservedBlob SET refCount = refCount + 1 WHERE blobId = :blobId")
    void incrementRefCount(String blobId);

    @Query("UPDATE PreservedBlob SET refCount = refCount - 1 WHERE blobId = :blobId")
    void decrementRefCount(String blobId);

    @Query("SELECT * FROM PreservedBlob WHERE refCount <= 0")
    List<PreservedBlob> getOrphanBlobs();

    /** Blobs no live PreservedRef points at (recomputed from the refs
     * table, ignoring the stored refCount — which a purge/clear leaves
     * stale). Used after purge/clear to drop orphaned rows promptly. */
    @Query("SELECT * FROM PreservedBlob WHERE blobId NOT IN (SELECT DISTINCT blobId FROM PreservedRef WHERE blobId IS NOT NULL)")
    List<PreservedBlob> getUnreferencedBlobs();

    @Query("DELETE FROM PreservedBlob WHERE blobId = :blobId")
    void deleteById(String blobId);

    @Query("SELECT COUNT(*) FROM PreservedBlob")
    int countBlobs();

    @Query("SELECT COALESCE(SUM(size), 0) FROM PreservedBlob")
    long totalBytes();
}
