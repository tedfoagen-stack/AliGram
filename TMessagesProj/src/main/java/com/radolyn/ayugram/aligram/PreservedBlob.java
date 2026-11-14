package com.radolyn.ayugram.aligram;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One row per distinct media blob (content-addressed, refcounted).
 * Phase 2.2 §6.2. The file on disk is named {@code <blobId>.<ext>} under
 * {@code <getNoBackupFilesDir()>/aligram/[account<N>/]blobs/}; it is
 * deleted only when {@code refCount} reaches 0, never by mediaPath.
 */
@Entity(
        tableName = "PreservedBlob",
        indices = {
                @Index(value = {"documentId", "dcId", "size"}),
                @Index(value = {"sha256"})
        }
)
public class PreservedBlob {
    /** "d<documentId>_<dcId>_<size>_<sha16>" or "p<photoId>_…" (see AliGramEligibility.blobIdFor). */
    @PrimaryKey
    public String blobId;
    /** 0 when photo. */
    public long documentId;
    /** 0 when document. */
    public long photoId;
    public int dcId;
    /** Bytes, actual copied size. */
    public long size;
    /** Full hex SHA-256, computed during the copy stream (single pass). */
    public String sha256;
    public String mimeType;
    public String ext;
    /** Deterministic file name: {@code <blobId>.<ext>}. */
    public String fileName;
    /** User-facing name at freeze time (for render display). */
    public String originalFileName;
    /** 0 when not a photo (render needs dimensions; blob names carry none). */
    public int photoWidth;
    public int photoHeight;
    /** Number of PreservedRef rows pointing here; file deleted only at 0. */
    public int refCount;
    public long createdAt;
}
