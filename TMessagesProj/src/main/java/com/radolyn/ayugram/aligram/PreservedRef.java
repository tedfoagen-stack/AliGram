package com.radolyn.ayugram.aligram;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * AliGram preservation job row. This row IS the job (Phase 2.2 §6.3,
 * corrected by Phase 2.3 §2.2): identity is carried end to end by this row,
 * never by a notification payload or a parent-object parse.
 *
 * <p>Identity: exact join key is {@code ayuFakeId} (EditedMessage /
 * DeletedMessage Room PK) + {@code revisionSeq} (monotonic per message,
 * EDIT rows only; LIVE rows are 0). {@code entityCreateDate} is display
 * only, never a join key. Pin columns ({@code sourcePath/sourceSize/
 * sourceMtime}) are the Phase 2.3 §2.2(b) addition: the durable pin
 * registry consulted by the deferred-delete guards.
 */
@Entity(
        tableName = "PreservedRef",
        indices = {
                @Index(value = {"userId", "dialogId", "messageId", "role", "revisionSeq"}, unique = true),
                @Index(value = {"ayuFakeId"}),
                @Index(value = {"accountId", "expectedFileName"}),
                @Index(value = {"sourcePath"}),
                @Index(value = {"userId", "dialogId", "messageId", "entityCreateDate"})
        }
)
public class PreservedRef {
    @PrimaryKey(autoGenerate = true)
    public long refId;

    /** Own account user id (multi-account key). */
    public long userId;
    public int accountId;
    /** Raw signed dialog id, never abs(). */
    public long dialogId;
    public int messageId;
    /** "LIVE" | "DELETED" | "EDIT". */
    public String role;
    /** Monotonic per (userId, dialogId, messageId), EDIT rows only (LIVE: 0). */
    public int revisionSeq;
    /** EditedMessage/DeletedMessage.fakeId when known, else 0. THE join key. */
    public long ayuFakeId;
    /** Copied from the Ayu row. Display + fallback discriminator only. */
    public int entityCreateDate;
    /** oldMessage.edit_date (server seconds; 0 for the original). */
    public int editDate;
    /** SHA-256 of (old text + textEntities bytes), nullable. */
    public String textHash;
    /** Media identity captured at freeze/ingest time. */
    public long documentId;
    public long photoId;
    public int dcId;
    /** FK to PreservedBlob, null until copied. */
    public String blobId;
    /** ENQUEUED | DOWNLOADING | PINNED | COPYING | COPIED | FAILED |
     *  UNAVAILABLE | CANCELLED | GATE_OFF | SWEPT. */
    public String state;
    /** FileLoader attach name, derived ONCE from the exact object passed to loadFile. */
    public String expectedFileName;
    /** Bytes, 0 when unknown (photo size-variant estimate). */
    public long expectedSize;
    /** Failure counter for the attempts-budget rule (3 → FAILED). */
    public int attempts;
    /** Nullable; one terminal vocabulary (see AliGramEligibility reasons). */
    public String lastError;
    /** Which gate went off, for GATE_OFF rows (P / D / E). */
    public String gateName;
    /** "INGEST" | "EDIT_FREEZE" | "LIVE_SYNC". Diagnostics + policy. */
    public String source;
    public long updatedAt;

    // --- Durable pin registry (Phase 2.3 §2.2(b)) ---
    /** Absolute path of the pinned cache file. */
    public String sourcePath;
    public long sourceSize;
    public long sourceMtime;
    /** Pending-delete file names recorded at pin time (crash-recovery seed). */
    public String pendingDeletePaths;
    /** True when the in-flight download's cancel was deferred to release time. */
    public boolean cancelDeferred;

    // --- Media display facts carried on the ref (render needs them) ---
    public String originalFileName;
    public int photoWidth;
    public int photoHeight;
}
