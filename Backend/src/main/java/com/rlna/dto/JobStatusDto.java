package com.rlna.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.rlna.entity.ProcessingJob;

/**
 * What the library polls while a paper is being indexed. Everything here comes
 * from the persisted job row, so the answer is identical whether the browser
 * stayed open or the user came back an hour later (Section 24.2).
 */
public record JobStatusDto(
        UUID paperId,
        String status,
        String currentStep,
        int progress,
        String errorMessage,
        int attempts,
        boolean retryable,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt) {

    public static JobStatusDto from(UUID paperId, ProcessingJob job, String paperStatus) {
        if (job == null) {
            return new JobStatusDto(paperId, paperStatus, null,
                    "completed".equals(paperStatus) ? 100 : 0, null, 0,
                    "failed".equals(paperStatus), null, null);
        }
        return new JobStatusDto(paperId, job.getStatus(), job.getCurrentStep(), job.getProgress(),
                job.getErrorMessage(), job.getAttempts(),
                ProcessingJob.STATUS_FAILED.equals(job.getStatus()) && job.isRetryable(),
                job.getStartedAt(), job.getCompletedAt());
    }
}
