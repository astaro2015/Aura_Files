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


    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш')")
    long visibleCount(String rootId, boolean showHidden, boolean showThumbnails);

    @Query("SELECT category, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM indexed_files WHERE rootId = :rootId AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') GROUP BY category")
    List<CategoryAggregate> visibleCategoryAggregates(String rootId, boolean showHidden, boolean showThumbnails);

    @Query("SELECT sourceFolder, COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes FROM indexed_files WHERE rootId = :rootId AND sourceFolder IN ('Загрузки','Камера') AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') GROUP BY sourceFolder")
    List<SourceAggregate> visibleSourceAggregates(String rootId, boolean showHidden, boolean showThumbnails);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND readerSupported = 1 AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш')")
    long visibleBookCount(String rootId, boolean showHidden, boolean showThumbnails);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId AND readerSupported = 1 AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш')")
    long visibleBookBytes(String rootId, boolean showHidden, boolean showThumbnails);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND category = :category AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> visibleByCategory(String rootId, String category, int limit, boolean showHidden, boolean showThumbnails);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND category = 'Images' " +
            "AND (:showHidden = 1 OR name NOT LIKE '.%') " +
            "AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') " +
            "AND (:query = '' OR LOWER(name) LIKE '%' || LOWER(:query) || '%') " +
            "AND (" +
            " :sourceFilter = 'All' " +
            " OR (:sourceFilter = 'Camera' AND sourceFolder = 'Камера') " +
            " OR (:sourceFilter = 'Screenshots' AND sourceFolder = 'Снимки экрана') " +
            " OR (:sourceFilter = 'WhatsApp' AND sourceFolder = 'WhatsApp') " +
            " OR (:sourceFilter = 'Telegram' AND sourceFolder = 'Telegram') " +
            " OR (:sourceFilter = 'Downloads' AND sourceFolder = 'Загрузки') " +
            " OR (:sourceFilter = 'Other' AND sourceFolder NOT IN ('Камера','Снимки экрана','WhatsApp','Telegram','Загрузки'))" +
            ")")
    long visibleImageCount(String rootId, String sourceFilter, String query, boolean showHidden, boolean showThumbnails);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND category = 'Images' " +
            "AND (:showHidden = 1 OR name NOT LIKE '.%') " +
            "AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') " +
            "AND (:query = '' OR LOWER(name) LIKE '%' || LOWER(:query) || '%') " +
            "AND (" +
            " :sourceFilter = 'All' " +
            " OR (:sourceFilter = 'Camera' AND sourceFolder = 'Камера') " +
            " OR (:sourceFilter = 'Screenshots' AND sourceFolder = 'Снимки экрана') " +
            " OR (:sourceFilter = 'WhatsApp' AND sourceFolder = 'WhatsApp') " +
            " OR (:sourceFilter = 'Telegram' AND sourceFolder = 'Telegram') " +
            " OR (:sourceFilter = 'Downloads' AND sourceFolder = 'Загрузки') " +
            " OR (:sourceFilter = 'Other' AND sourceFolder NOT IN ('Камера','Снимки экрана','WhatsApp','Telegram','Загрузки'))" +
            ") " +
            "ORDER BY " +
            "CASE WHEN :sortMode = 'Name' AND :ascending = 1 THEN LOWER(name) END ASC, " +
            "CASE WHEN :sortMode = 'Name' AND :ascending = 0 THEN LOWER(name) END DESC, " +
            "CASE WHEN :sortMode = 'Modified' AND :ascending = 1 THEN modifiedAt END ASC, " +
            "CASE WHEN :sortMode = 'Modified' AND :ascending = 0 THEN modifiedAt END DESC, " +
            "CASE WHEN :sortMode = 'Size' AND :ascending = 1 THEN size END ASC, " +
            "CASE WHEN :sortMode = 'Size' AND :ascending = 0 THEN size END DESC, " +
            "CASE WHEN :sortMode = 'Type' AND :ascending = 1 THEN LOWER(extension) END ASC, " +
            "CASE WHEN :sortMode = 'Type' AND :ascending = 0 THEN LOWER(extension) END DESC, " +
            "LOWER(name) ASC, uri ASC LIMIT :limit OFFSET :offset")
    List<IndexedFileEntity> visibleImagesPage(
            String rootId, String sourceFilter, String query, String sortMode, boolean ascending,
            int limit, int offset, boolean showHidden, boolean showThumbnails
    );

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND readerSupported = 1 AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> visibleBooks(String rootId, int limit, boolean showHidden, boolean showThumbnails);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND sourceFolder = :sourceFolder AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> visibleBySourceFolder(String rootId, String sourceFolder, int limit, boolean showHidden, boolean showThumbnails);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND temporaryCandidate = 1 AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') ORDER BY size DESC LIMIT :limit")
    List<IndexedFileEntity> visibleTemporary(String rootId, int limit, boolean showHidden, boolean showThumbnails);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND temporaryCandidate = 1 AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш')")
    long visibleTemporaryCount(String rootId, boolean showHidden, boolean showThumbnails);

    @Query("SELECT COALESCE(SUM(size), 0) FROM indexed_files WHERE rootId = :rootId AND temporaryCandidate = 1 AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш')")
    long visibleTemporaryBytes(String rootId, boolean showHidden, boolean showThumbnails);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') ORDER BY modifiedAt DESC LIMIT :limit")
    List<IndexedFileEntity> visibleRecent(String rootId, int limit, boolean showHidden, boolean showThumbnails);

    @Query("SELECT * FROM indexed_files WHERE rootId = :rootId AND size >= :minBytes AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш') ORDER BY size DESC LIMIT :limit")
    List<IndexedFileEntity> visibleLargestAtLeast(String rootId, long minBytes, int limit, boolean showHidden, boolean showThumbnails);

    @Query("SELECT COUNT(*) FROM indexed_files WHERE rootId = :rootId AND size >= :minBytes AND (:showHidden = 1 OR name NOT LIKE '.%') AND (:showThumbnails = 1 OR sourceFolder != 'Миниатюры и кэш')")
    long visibleLargeCount(String rootId, long minBytes, boolean showHidden, boolean showThumbnails);

    @Query("SELECT f.* FROM indexed_files f INNER JOIN (SELECT size FROM indexed_files WHERE rootId = :rootId AND size > 0 GROUP BY size HAVING COUNT(*) > 1) d ON f.size = d.size WHERE f.rootId = :rootId ORDER BY f.size, f.uri")
    List<IndexedFileEntity> duplicateCandidates(String rootId);

    @Query("SELECT f.* FROM indexed_files f INNER JOIN (SELECT size, sha256 FROM indexed_files WHERE rootId = :rootId AND size > 0 AND sha256 IS NOT NULL AND sha256 != '' GROUP BY size, sha256 HAVING COUNT(*) > 1) d ON f.size = d.size AND f.sha256 = d.sha256 WHERE f.rootId = :rootId ORDER BY f.size DESC, f.sha256, f.uri")
    List<IndexedFileEntity> exactDuplicateHashedFiles(String rootId);
}
