package com.aurafiles.app.index;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {IndexedFileEntity.class, IndexedRootEntity.class}, version = 1, exportSchema = false)
public abstract class AuraIndexDatabase extends RoomDatabase {
    public abstract IndexedFileDao indexedFileDao();
    public abstract RootDao rootDao();

    private static volatile AuraIndexDatabase INSTANCE;

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
                ).build();
                INSTANCE = value;
            }
            return value;
        }
    }
}

