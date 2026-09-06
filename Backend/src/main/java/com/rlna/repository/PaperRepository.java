package com.rlna.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rlna.entity.Paper;

public interface PaperRepository extends JpaRepository<Paper, UUID> {

    Optional<Paper> findByIdAndUserId(UUID id, UUID userId);

    /** Batch counterpart to {@link #findByIdAndUserId}, ownership still enforced in the query. */
    List<Paper> findByIdInAndUserId(Collection<UUID> ids, UUID userId);

    Optional<Paper> findByUserIdAndContentHash(UUID userId, String contentHash);

    List<Paper> findByUserIdAndProjectId(UUID userId, UUID projectId);

    long countByUserIdAndProjectId(UUID userId, UUID projectId);

    long countByUserId(UUID userId);

    /**
     * Library listing. Each filter is optional and applied only when supplied,
     * which keeps one query for the whole screen instead of a combinatorial set
     * of derived methods. The owner filter is not optional.
     *
     * <p>{@code query} takes an empty string rather than null for "no filter".
     * A null bound into {@code LIKE CONCAT(...)} has no column to take its type
     * from, so the driver sends it as bytea and PostgreSQL rejects the statement
     * with "function lower(bytea) does not exist".
     */
    @Query("""
            SELECT p FROM Paper p
            WHERE p.userId = :userId
              AND (:projectId IS NULL OR p.projectId = :projectId)
              AND (:status    IS NULL OR p.processingStatus = :status)
              AND (:year      IS NULL OR p.publicationYear = :year)
              AND (:readingStatus IS NULL OR p.readingStatus = :readingStatus)
              AND (:query = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<Paper> search(@Param("userId") UUID userId,
                       @Param("projectId") UUID projectId,
                       @Param("status") String status,
                       @Param("year") Integer year,
                       @Param("readingStatus") String readingStatus,
                       @Param("query") String query,
                       Pageable pageable);

    /** Tag filtering needs the Postgres array containment operator. */
    @Query(value = """
            SELECT * FROM papers p
            WHERE p.user_id = :userId
              AND p.tags @> ARRAY[CAST(:tag AS text)]
            ORDER BY p.updated_at DESC
            """, nativeQuery = true)
    List<Paper> findByTag(@Param("userId") UUID userId, @Param("tag") String tag);

    /**
     * Fuzzy title match used to warn about a re-upload under a different
     * filename. Trigram similarity is cheap, needs no embedding, and catches the
     * common case before the file is even indexed (Section 9.6).
     */
    @Query(value = """
            SELECT * FROM papers p
            WHERE p.user_id = :userId
              AND p.title IS NOT NULL
              AND similarity(p.title, :title) >= :threshold
            ORDER BY similarity(p.title, :title) DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Paper> findSimilarTitle(@Param("userId") UUID userId,
                                     @Param("title") String title,
                                     @Param("threshold") double threshold);

    @Query("SELECT p.processingStatus, COUNT(p) FROM Paper p WHERE p.userId = :userId "
            + "AND (:projectId IS NULL OR p.projectId = :projectId) GROUP BY p.processingStatus")
    List<Object[]> countByStatus(@Param("userId") UUID userId, @Param("projectId") UUID projectId);

    @Query("SELECT p.publicationYear, COUNT(p) FROM Paper p WHERE p.userId = :userId "
            + "AND (:projectId IS NULL OR p.projectId = :projectId) AND p.publicationYear IS NOT NULL "
            + "GROUP BY p.publicationYear ORDER BY p.publicationYear")
    List<Object[]> countByPublicationYear(@Param("userId") UUID userId, @Param("projectId") UUID projectId);

    @Query("SELECT p FROM Paper p WHERE p.userId = :userId AND p.projectId = :projectId "
            + "AND p.processingStatus = 'completed'")
    List<Paper> findCompletedInProject(@Param("userId") UUID userId, @Param("projectId") UUID projectId);

    /**
     * Papers whose chunks were built by a superseded indexing strategy.
     *
     * <p>Only completed papers that still have their PDF: a re-index rebuilds
     * from the stored file, so a metadata-only record has nothing to rebuild
     * from and is not a candidate.
     */
    @Query("SELECT p FROM Paper p WHERE p.userId = :userId AND p.indexVersion < :version "
            + "AND p.processingStatus = 'completed' AND p.storageObjectKey IS NOT NULL "
            + "ORDER BY p.createdAt")
    List<Paper> findOutdatedIndexes(@Param("userId") UUID userId, @Param("version") int version);

    /** Distinct tags across a library, for the filter control. */
    @Query(value = "SELECT DISTINCT unnest(tags) AS tag FROM papers WHERE user_id = :userId ORDER BY tag",
           nativeQuery = true)
    List<String> findDistinctTags(@Param("userId") UUID userId);
}
