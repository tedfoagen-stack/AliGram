package com.radolyn.ayugram.aligram;

import androidx.room.Database;
import androidx.room.RoomDatabase;

/**
 * Separate Room database {@code aligram-preservation} v1 (Phase 2.2 §6.1).
 * No {@code cache4.db} change (stays v176) and no {@code ayu-data}
 * migration (stays v26). A downgrade wipes only the preservation index;
 * blobs are swept as orphans by the next newer build.
 */
@Database(
        entities = {PreservedRef.class, PreservedBlob.class, DeferredDelete.class},
        version = 1,
        exportSchema = true
)
public abstract class AliGramDatabase extends RoomDatabase {
    public abstract PreservedRefDao preservedRefDao();

    public abstract PreservedBlobDao preservedBlobDao();

    public abstract DeferredDeleteDao deferredDeleteDao();
}
