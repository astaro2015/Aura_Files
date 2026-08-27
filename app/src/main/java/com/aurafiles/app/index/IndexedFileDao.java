package com.aurafiles.app.index;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface IndexedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<IndexedFileEntity> files);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND uri = :uri AND size = :size AND modifiedAt = :modifiedAt LIMIT 1")
    IndexedFileEntity findUnchanged(String rootId, String uri, long size, long modifiedAt);

    @Query("DELETE FROM indexed_files WHERE rootId = :rootId AND lastSeenScan != :generation")
    void deleteNotSeen(String rootId, long generation);

    @Query("DELETE FROM indexed_files WHERE rootId = :rootId")
    void deleteRoot(String rootId);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId")
    long count(String rootId);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId")
    long totalBytes(String rootId);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND category = :category")
    long categoryCount(String rootId, String category);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId AND category = :category")
    long categoryBytes(String rootId, String category);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND category = :category ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> byCategory(String rootId, String category, int limit);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND sourceFolder = :sourceFolder")
    long sourceCount(String rootId, String sourceFolder);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId AND sourceFolder = :sourceFolder")
    long sourceBytes(String rootId, String sourceFolder);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND sourceFolder = :sourceFolder ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> bySourceFolder(String rootId, String sourceFolder, int limit);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND (sourceFolder = 'Миниатюры и кэш' OR lower(uri) LIKE '%/cache/%' OR lower(uri) LIKE '%/temp/%' OR lower(uri) LIKE '%/tmp/%' OR extension IN ('tmp', 'temp', 'log', 'bak')) ORDER BY size DESC LIMIT :limit")
    List<IndexedFileEntity> temporary(String rootId, int limit);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> recent(String rootId, int limit);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId ORDER BY size DESC LIMIT :limit")
    List<IndexedFileEntity> largest(String rootId, int limit);

    @Query("SELECT size FROM indexed_files WHERE rootId = :rootId AND size > 0 GROUP BY size HAVING COUNT(*) > 1")
    List<Long> duplicateSizes(String rootId);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND size = :size ORDER BY uri")
    List<IndexedFileEntity> bySize(String rootId, long size);

    @Query("UPDATE indexed_files SET quickHash = :quickHash, sha256 = :sha256 WHERE uri = :uri")
    void updateHashes(String uri, String quickHash, String sha256);
}
