package com.rlna.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rlna.entity.AnalysisResult;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, UUID> {

    /** The cache lookup (Section 25.2). Scoped by user so results never cross accounts. */
    Optional<AnalysisResult> findByUserIdAndCacheKey(UUID userId, String cacheKey);

    Optional<AnalysisResult> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            SELECT a FROM AnalysisResult a
            WHERE a.userId = :userId
              AND (:type      IS NULL OR a.analysisType = :type)
              AND (:projectId IS NULL OR a.projectId = :projectId)
              AND (:paperId   IS NULL OR a.paperId = :paperId)
            ORDER BY a.createdAt DESC
            """)
    Page<AnalysisResult> history(@Param("userId") UUID userId,
                                 @Param("type") String type,
                                 @Param("projectId") UUID projectId,
                                 @Param("paperId") UUID paperId,
                                 Pageable pageable);

    List<AnalysisResult> findByUserIdAndPaperIdAndAnalysisType(UUID userId, UUID paperId, String analysisType);

    @Query("SELECT a.analysisType, COUNT(a) FROM AnalysisResult a WHERE a.userId = :userId "
            + "AND (:projectId IS NULL OR a.projectId = :projectId) GROUP BY a.analysisType")
    List<Object[]> countByType(@Param("userId") UUID userId, @Param("projectId") UUID projectId);
}
