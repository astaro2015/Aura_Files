package com.aurafiles.app.index;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface RootDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(IndexedRootEntity root);

    @Query("SELECT * FROM indexed_roots WHERE id = :id LIMIT 1")
    IndexedRootEntity get(String id);

    @Query("SELECT * FROM indexed_roots ORDER BY lastScanCompleted DESC")
    List<IndexedRootEntity> all();
}

