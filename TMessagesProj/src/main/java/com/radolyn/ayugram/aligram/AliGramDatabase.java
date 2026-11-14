package com.radolyn.ayugram.aligram;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Separate Room database {@code aligram-preservation} (Phase 2.2 §6.1).
 * No {@code cache4.db} change (stays v176) and no {@code ayu-data}
 * migration (stays v26). A downgrade wipes only the preservation index;
 * blobs are swept as orphans by the next newer build.
 *
 * <p>v2 (Phase 3.1, D-05): {@code PreservedBlob} gains {@code accountId}
 * — blob identity is account-scoped, matching the per-account blob
 * directories. Migrated v1 rows carry 0; account 0 also keeps the legacy
 * unprefixed blob ids, so migrated account-0 rows stay valid.
 */
@Database(
        entities = {PreservedRef.class, PreservedBlob.class, DeferredDelete.class},
        version = 2,
        exportSchema = true
)
public abstract class AliGramDatabase extends RoomDatabase {

    /** v1 → v2 (Phase 3.1, D-05): add the accountId column; default 0. */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `PreservedBlob` ADD COLUMN `accountId` INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract PreservedRefDao preservedRefDao();

    public abstract PreservedBlobDao preservedBlobDao();

    public abstract DeferredDeleteDao deferredDeleteDao();
}
