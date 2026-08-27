package com.aurafiles.app.index;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "indexed_roots")
public class IndexedRootEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String uri;
    @NonNull public String displayName;
    public long lastScanStarted;
    public long lastScanCompleted;
    public long filesCount;
    public long totalBytes;
    public long scanGeneration;

    public IndexedRootEntity(
            @NonNull String id,
            @NonNull String uri,
            @NonNull String displayName,
            long lastScanStarted,
            long lastScanCompleted,
            long filesCount,
            long totalBytes,
            long scanGeneration
    ) {
        this.id = id;
        this.uri = uri;
        this.displayName = displayName;
        this.lastScanStarted = lastScanStarted;
        this.lastScanCompleted = lastScanCompleted;
        this.filesCount = filesCount;
        this.totalBytes = totalBytes;
        this.scanGeneration = scanGeneration;
    }
}

