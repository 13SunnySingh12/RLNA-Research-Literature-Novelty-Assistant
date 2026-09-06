package com.rlna.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rlna.entity.Concept;

public interface ConceptRepository extends JpaRepository<Concept, UUID> {

    List<Concept> findByPaperId(UUID paperId);

    /** Concept frequency across a library, for the analytics dashboard. */
    @Query("""
            SELECT c.concept, c.conceptType, COUNT(DISTINCT c.paperId)
            FROM Concept c
            WHERE c.paperId IN (
                SELECT p.id FROM Paper p
                WHERE p.userId = :userId AND (:projectId IS NULL OR p.projectId = :projectId))
            GROUP BY c.concept, c.conceptType
            ORDER BY COUNT(DISTINCT c.paperId) DESC
            """)
    List<Object[]> topConcepts(@Param("userId") UUID userId, @Param("projectId") UUID projectId);

    /**
     * Concept frequency by publication year, which is what turns the keyword
     * list into a "which topics are rising" chart (Section 9.8).
     */
    @Query("""
            SELECT p.publicationYear, c.concept, COUNT(DISTINCT c.paperId)
            FROM Concept c JOIN Paper p ON p.id = c.paperId
            WHERE p.userId = :userId
              AND (:projectId IS NULL OR p.projectId = :projectId)
              AND p.publicationYear IS NOT NULL
              AND c.conceptType IN ('keyword', 'method')
            GROUP BY p.publicationYear, c.concept
            ORDER BY p.publicationYear
            """)
    List<Object[]> conceptsByYear(@Param("userId") UUID userId, @Param("projectId") UUID projectId);

    void deleteByPaperId(UUID paperId);
}
