package com.aurafiles.app.index;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {IndexedFileEntity.class, IndexedRootEntity.class}, version = 3, exportSchema = false)
public abstract class AuraIndexDatabase extends RoomDatabase {
    public abstract IndexedFileDao indexedFileDao();
    public abstract RootDao rootDao();

    private static volatile AuraIndexDatabase INSTANCE;

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE indexed_files ADD COLUMN readerSupported INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE indexed_files ADD COLUMN temporaryCandidate INTEGER NOT NULL DEFAULT 0");

            // Preserve useful collections immediately after upgrade; the next scan refreshes them from the central classifier.
            db.execSQL("UPDATE indexed_files SET readerSupported = 1 WHERE lower(name) LIKE '%.fb2.zip' OR extension IN ('epub','fb2','mobi','azw','azw3','prc','docx','rtf','md','markdown','txt','html','htm','pdf','djvu','djv','cbz','cbr')");
            db.execSQL("UPDATE indexed_files SET temporaryCandidate = 1 WHERE sourceFolder = 'Миниатюры и кэш' OR lower(uri) LIKE '%/cache/%' OR lower(uri) LIKE '%/temp/%' OR lower(uri) LIKE '%/tmp/%' OR extension IN ('tmp','temp','log','bak')");

            db.execSQL("CREATE INDEX IF NOT EXISTS index_indexed_files_rootId_category_modifiedAt ON indexed_files(rootId, category, modifiedAt)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_indexed_files_rootId_sourceFolder_modifiedAt ON indexed_files(rootId, sourceFolder, modifiedAt)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_indexed_files_rootId_readerSupported_modifiedAt ON indexed_files(rootId, readerSupported, modifiedAt)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_indexed_files_rootId_temporaryCandidate_size ON indexed_files(rootId, temporaryCandidate, size)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_indexed_files_rootId_modifiedAt ON indexed_files(rootId, modifiedAt)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_indexed_files_rootId_size ON indexed_files(rootId, size)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_indexed_files_rootId_size_sha256 ON indexed_files(rootId, size, sha256)");
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Older indexes could leave non-ZIP archive formats in "Other" until a full rescan.
            // Repair the cached category immediately on upgrade so Archives is complete at first launch.
            db.execSQL("UPDATE indexed_files SET category = 'Archives' " +
                    "WHERE lower(extension) IN ('zip','rar','7z','tar','gz','bz2','xz','tgz','tbz2','txz') " +
                    "AND lower(name) NOT LIKE '%.fb2.zip'");
        }
    };

    public static AuraIndexDatabase get(Context context) {
        AuraIndexDatabase value = INSTANCE;
        if (value != null) return value;
        synchronized (AuraIndexDatabase.class) {
            value = INSTANCE;
            if (value == null) {
                value = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AuraIndexDatabase.class,
                        "aura-index.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
                INSTANCE = value;
            }
            return value;
        }
    }
}
