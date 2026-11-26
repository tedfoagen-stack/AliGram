package com.radolyn.ayugram.aligram;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Deferred-delete request (Phase 2.3 §2.2(a)). When a deleter encounters a
 * pinned source file it records the request here and skips the file; the
 * copy-queue worker releases it only after the pin reaches a terminal
 * state (COPIED / FAILED / UNAVAILABLE), re-validating size+mtime.
 *
 * <p>Uniqueness: {@code UNIQUE(accountId, absolutePath, refId)},
 * {@code INSERT OR IGNORE}.
 */
@Entity(
        tableName = "DeferredDelete",
        indices = {
                @Index(value = {"accountId", "absolutePath", "refId"}, unique = true),
                @Index(value = {"refId"}),
                @Index(value = {"state"})
        }
)
public class DeferredDelete {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public int accountId;
    /** Pinning PreservedRef; 0 when no live ref exists for the path. */
    public long refId;
    public String absolutePath;
    public long sizeAtPin;
    public long mtimeAtPin;
    /** EDIT_TAIL | REMOTE_MARK | CLEAR_HISTORY | DIALOG_DELETE |
     *  USER_HISTORY_DELETE | MEDIA_REMOVAL | LOCAL_DELETE | EVICTION | DELETE. */
    public String requestedBy;
    /** Deferred cancel of the in-flight download (edit-tail rule, §2.2(a) note 3). */
    public boolean cancelDeferred;
    /** PENDING | RELEASED | DONE. */
    public String state;
    /** Device seconds (staleness budget). */
    public long createdAt;
}
