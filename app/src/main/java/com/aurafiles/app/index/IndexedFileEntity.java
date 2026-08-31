package com.aurafiles.app.index;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "indexed_files",
        indices = {
                @Index(value = {"uri"}, unique = true),
                @Index(value = {"rootId"}),
                @Index(value = {"parentUri"}),
                @Index(value = {"name"}),
                @Index(value = {"extension"}),
                @Index(value = {"size"}),
                @Index(value = {"modifiedAt"}),
                @Index(value = {"category"}),
                @Index(value = {"sha256"}),
                @Index(value = {"rootId", "category", "modifiedAt"}),
                @Index(value = {"rootId", "sourceFolder", "modifiedAt"}),
                @Index(value = {"rootId", "readerSupported", "modifiedAt"}),
                @Index(value = {"rootId", "temporaryCandidate", "size"}),
                @Index(value = {"rootId", "modifiedAt"}),
                @Index(value = {"rootId", "size"}),
                @Index(value = {"rootId", "size", "sha256"})
        }
)
public class IndexedFileEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @NonNull public String rootId;
    @NonNull public String uri;
    @NonNull public String parentUri;
    @NonNull public String name;
    @NonNull public String extension;
    public String mimeType;
    public long size;
    public long modifiedAt;
    @NonNull public String category;
    @NonNull public String sourceFolder;
    @ColumnInfo(defaultValue = "0") public boolean readerSupported;
    @ColumnInfo(defaultValue = "0") public boolean temporaryCandidate;
    public String sha256;
    public String quickHash;
    public long lastSeenScan;

    public IndexedFileEntity(
            @NonNull String rootId,
            @NonNull String uri,
            @NonNull String parentUri,
            @NonNull String name,
            @NonNull String extension,
            String mimeType,
            long size,
            long modifiedAt,
            @NonNull String category,
            @NonNull String sourceFolder,
            boolean readerSupported,
            boolean temporaryCandidate,
            String sha256,
            String quickHash,
            long lastSeenScan
    ) {
        this.rootId = rootId;
        this.uri = uri;
        this.parentUri = parentUri;
        this.name = name;
        this.extension = extension;
        this.mimeType = mimeType;
        this.size = size;
        this.modifiedAt = modifiedAt;
        this.category = category;
        this.sourceFolder = sourceFolder;
        this.readerSupported = readerSupported;
        this.temporaryCandidate = temporaryCandidate;
        this.sha256 = sha256;
        this.quickHash = quickHash;
        this.lastSeenScan = lastSeenScan;
    }
}

