package com.radolyn.ayugram.aligram;

import androidx.room.Room;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Lazy holder for the AliGram Room database, mirroring the {@code AyuData}
 * builder pattern minus {@code allowMainThreadQueries()}: all preservation
 * DB access runs on background threads (storage queue / globalQueue /
 * copy queue / fileLoaderQueue), never on the UI thread.
 *
 * <p>Deliberately NOT a {@code static {}} initializer: a DB failure must
 * never crash the app at class-load time (cf. the ayu-data v26→27 risk).
 * {@link #ensureInitialized()} returns false when the DB is unavailable;
 * callers then degrade to legacy-only behaviour and log.
 */
public final class AliGramData {
    public static final String DATABASE_NAME = "aligram-preservation";

    private static volatile AliGramDatabase database;

    private AliGramData() {
    }

    public static synchronized boolean ensureInitialized() {
        if (database != null) {
            return true;
        }
        try {
            database = Room.databaseBuilder(ApplicationLoader.applicationContext, AliGramDatabase.class, DATABASE_NAME)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build();
            return true;
        } catch (Throwable e) {
            FileLog.e("AliGram: failed to open preservation database", e);
            database = null;
            return false;
        }
    }

    public static AliGramDatabase getDatabase() {
        return database;
    }

    public static PreservedRefDao refDao() {
        return database == null ? null : database.preservedRefDao();
    }

    public static PreservedBlobDao blobDao() {
        return database == null ? null : database.preservedBlobDao();
    }

    public static DeferredDeleteDao deferredDao() {
        return database == null ? null : database.deferredDeleteDao();
    }

    /** Test/support hook: drop the cached instance (the file on disk is untouched). */
    public static synchronized void resetForTests() {
        database = null;
    }
}
