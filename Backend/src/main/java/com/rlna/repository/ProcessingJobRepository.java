package com.rlna.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rlna.entity.ProcessingJob;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {

    Optional<ProcessingJob> findFirstByPaperIdOrderByCreatedAtDesc(UUID paperId);

    List<ProcessingJob> findByPaperId(UUID paperId);

    /**
     * Jobs left mid-flight when the instance stopped. A free Render instance
     * sleeps and restarts routinely, so recovering these on boot is what keeps
     * a paper from being stuck at "processing" forever (Section 24.2).
     */
    @Query("SELECT j FROM ProcessingJob j WHERE j.status IN ('queued', 'running') ORDER BY j.createdAt")
    List<ProcessingJob> findUnfinished();

    @Query("SELECT COUNT(j) FROM ProcessingJob j WHERE j.paperId = :paperId AND j.status IN ('queued', 'running')")
    long countActiveForPaper(@Param("paperId") UUID paperId);
}
