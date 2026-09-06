package com.rlna.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rlna.entity.PaperChunk;

public interface PaperChunkRepository extends JpaRepository<PaperChunk, UUID> {

    long countByPaperId(UUID paperId);

    List<PaperChunk> findByPaperIdOrderByChunkIndex(UUID paperId);

    /**
     * Hydrates chunks for evidence display. The join onto {@code papers} is what
     * enforces ownership: a chunk id belonging to another user simply does not
     * come back, so a forged evidence reference resolves to nothing.
     */
    @Query("""
            SELECT c FROM PaperChunk c
            WHERE c.id IN :ids
              AND c.paperId IN (SELECT p.id FROM Paper p WHERE p.userId = :userId)
            """)
    List<PaperChunk> findAllOwnedByIds(@Param("ids") Collection<UUID> ids, @Param("userId") UUID userId);

    @Query("SELECT COUNT(c) FROM PaperChunk c WHERE c.paperId IN "
            + "(SELECT p.id FROM Paper p WHERE p.userId = :userId "
            + "AND (:projectId IS NULL OR p.projectId = :projectId))")
    long countForUser(@Param("userId") UUID userId, @Param("projectId") UUID projectId);

    /**
     * Full-text keyword search over the generated tsvector column. Ownership is
     * applied inside the same statement as the ranking, never afterwards
     * (Section 13.3 rule 2).
     */
    @Query(value = """
            SELECT c.id, c.paper_id, c.section_type, c.chunk_index,
                   ts_headline('english', c.content, q, 'MaxFragments=1,MaxWords=40,MinWords=15') AS snippet,
                   ts_rank(c.content_tsv, q) AS rank,
                   p.title
            FROM paper_chunks c
            JOIN papers p ON p.id = c.paper_id,
                 plainto_tsquery('english', :query) q
            WHERE p.user_id = :userId
              AND (CAST(:projectId AS uuid) IS NULL OR p.project_id = CAST(:projectId AS uuid))
              AND c.content_tsv @@ q
            ORDER BY rank DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> keywordSearch(@Param("userId") UUID userId,
                                 @Param("projectId") UUID projectId,
                                 @Param("query") String query,
                                 @Param("limit") int limit);
}
