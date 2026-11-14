package com.radolyn.ayugram.aligram;

import androidx.annotation.NonNull;
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
                // NOTE (Phase 3.1, D-05): the v1 indices are deliberately
                // UNCHANGED in v2. The account filter in the DAO queries
                // is served well enough by the (documentId, dcId, size)
                // index + a post-filter on this small table, and keeping
                // the indices stable keeps the v1→v2 migration a single
                // ALTER TABLE — Room validates the full schema (indices
                // included) after a manual migration, so a changed index
                // set would need matching DROP/CREATE INDEX statements.
                @Index(value = {"documentId", "dcId", "size"}),
                @Index(value = {"sha256"})
        }
)
public class PreservedBlob {
    /** "d<documentId>_<dcId>_<size>_<sha16>" or "p<photoId>_…" (see AliGramEligibility.blobIdFor). */
    @PrimaryKey
    @NonNull
    public String blobId;
    /**
     * Owning account (Phase 3.1, D-05 — Room v2). Blob files live in
     * per-account directories, so blob identity is scoped by account:
     * the same media in two accounts is two rows (and two files), never
     * one row pointing at another account's directory. Rows migrated
     * from v1 carry 0 (the v1 default); account 0 also keeps the legacy
     * unprefixed blob ids, so migrated account-0 rows stay valid.
     */
    public int accountId;
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
