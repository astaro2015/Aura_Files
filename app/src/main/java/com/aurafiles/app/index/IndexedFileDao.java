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

    @Query("SELECT uri, size, modifiedAt, sha256, quickHash FROM indexed_files WHERE rootId = :rootId")
    List<IndexedHashSnapshot> hashSnapshots(String rootId);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND uri = :uri LIMIT 1")
    IndexedFileEntity byUri(String rootId, String uri);

    @Query("DELETE FROM indexed_files WHERE rootId = :rootId AND lastSeenScan != :generation")
    void deleteNotSeen(String rootId, long generation);

    @Query("DELETE FROM indexed_files WHERE rootId = :rootId")
    void deleteRoot(String rootId);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId")
    long count(String rootId);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId")
    long totalBytes(String rootId);

    @Query("SELECT category, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM indexed_files WHERE rootId = :rootId GROUP BY category")
    List<CategoryAggregate> categoryAggregates(String rootId);

    @Query("SELECT sourceFolder, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM indexed_files WHERE rootId = :rootId AND sourceFolder IN ('Загрузки','Камера') GROUP BY sourceFolder")
    List<SourceAggregate> sourceAggregates(String rootId);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND readerSupported = 1")
    long bookCount(String rootId);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId AND readerSupported = 1")
    long bookBytes(String rootId);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND category = :category ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> byCategory(String rootId, String category, int limit);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND readerSupported = 1 ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> books(String rootId, int limit);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND sourceFolder = :sourceFolder ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> bySourceFolder(String rootId, String sourceFolder, int limit);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND temporaryCandidate = 1 ORDER BY size DESC LIMIT :limit")
    List<IndexedFileEntity> temporary(String rootId, int limit);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND temporaryCandidate = 1")
    long temporaryCount(String rootId);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId AND temporaryCandidate = 1")
    long temporaryBytes(String rootId);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> recent(String rootId, int limit);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND size >= :minBytes ORDER BY size DESC LIMIT :limit")
    List<IndexedFileEntity> largestAtLeast(String rootId, long minBytes, int limit);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND size >= :minBytes")
    long largeCount(String rootId, long minBytes);

    @Query("DELETE FROM indexed_files WHERE rootId = :rootId AND uri IN (:uris)")
    void deleteUris(String rootId, List<String> uris);

    @Query("SELECT f.* FROM indexed_files f INNER JOIN (SELECT size FROM indexed_files WHERE rootId = :rootId AND size > 0 GROUP BY size HAVING COUNT(*) > 1) d ON f.size = d.size WHERE f.rootId = :rootId ORDER BY f.size, f.uri")
    List<IndexedFileEntity> duplicateCandidates(String rootId);

    @Query("SELECT f.* FROM indexed_files f INNER JOIN (SELECT size, sha256 FROM indexed_files WHERE rootId = :rootId AND size > 0 AND sha256 IS NOT NULL AND sha256 != '' GROUP BY size, sha256 HAVING COUNT(*) > 1) d ON f.size = d.size AND f.sha256 = d.sha256 WHERE f.rootId = :rootId ORDER BY f.size DESC, f.sha256, f.uri")
    List<IndexedFileEntity> exactDuplicateHashedFiles(String rootId);
}
