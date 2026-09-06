package com.rlna.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.entity.Paper;
import com.rlna.entity.ProcessingJob;
import com.rlna.repository.PaperRepository;
import com.rlna.repository.ProcessingJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Durable state transitions for an indexing job.
 *
 * <p>Separate from the worker on purpose. Each method runs in its own
 * transaction so a step is committed the moment it happens, which is what lets
 * a polling client see honest progress and what lets a restart resume from the
 * last committed step rather than from the beginning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingJobStore {

    private final PaperRepository paperRepository;
    private final ProcessingJobRepository jobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID paperId, UUID jobId, int attempt) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ProcessingJob.STATUS_RUNNING);
            job.setAttempts(attempt);
            job.setCurrentStep("Preparing");
            job.setProgress(5);
            job.setErrorMessage(null);
            if (job.getStartedAt() == null) {
                job.setStartedAt(OffsetDateTime.now());
            }
            jobRepository.save(job);
        });
        paperRepository.findById(paperId).ifPresent(paper -> {
            paper.setProcessingStatus("processing");
            paper.setProcessingError(null);
            paperRepository.save(paper);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Paper load(UUID paperId) {
        return paperRepository.findById(paperId).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID paperId, UUID jobId, JsonNode response) {
        Paper paper = paperRepository.findById(paperId).orElse(null);
        if (paper == null) {
            return;
        }
        applyMetadata(paper, response.path("metadata"));
        applyDuplicate(paper, response.path("duplicate_of"));
        paper.setProcessingStatus("completed");
        paper.setProcessingError(null);
        // Stamped only on success, so an interrupted re-index leaves the paper
        // marked outdated and the migration picks it up again next run.
        paper.setIndexVersion(PaperIndexingWorker.CURRENT_INDEX_VERSION);
        paperRepository.save(paper);

        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ProcessingJob.STATUS_COMPLETED);
            job.setCurrentStep("Complete");
            job.setProgress(100);
            job.setCompletedAt(OffsetDateTime.now());
            job.setErrorMessage(null);
            jobRepository.save(job);
        });
        log.info("Indexed paper {} into {} chunk(s)", paperId, response.path("chunk_count").asInt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID paperId, UUID jobId, String message) {
        fail(paperId, jobId, message, true);
    }

    /**
     * @param retryable whether another attempt could plausibly succeed. False for
     *                  failures inherent to the file itself, where retrying only
     *                  reproduces the same result.
     */
    public void fail(UUID paperId, UUID jobId, String message, boolean retryable) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ProcessingJob.STATUS_FAILED);
            job.setErrorMessage(message);
            job.setRetryable(retryable);
            job.setCompletedAt(OffsetDateTime.now());
            jobRepository.save(job);
        });
        // The stored PDF is deliberately left in place: a failed index must never
        // cost the user their upload (Section 27, principle 1).
        paperRepository.findById(paperId).ifPresent(paper -> {
            paper.setProcessingStatus("failed");
            paper.setProcessingError(message);
            paperRepository.save(paper);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<ProcessingJob> findUnfinished() {
        return jobRepository.findUnfinished();
    }

    private void applyMetadata(Paper paper, JsonNode metadata) {
        if (hasText(metadata.path("title"))) {
            paper.setTitle(trim(metadata.path("title").asText(), 1000));
        }
        JsonNode authors = metadata.path("authors");
        if (authors.isArray() && !authors.isEmpty()) {
            List<String> names = new ArrayList<>();
            authors.forEach(a -> {
                if (a.isTextual() && !a.asText().isBlank()) {
                    names.add(trim(a.asText(), 255));
                }
            });
            if (!names.isEmpty()) {
                paper.setAuthors(names.toArray(String[]::new));
            }
        }
        if (metadata.path("year").isInt()) {
            paper.setPublicationYear(metadata.path("year").asInt());
        }
        if (hasText(metadata.path("doi"))) {
            paper.setDoi(trim(metadata.path("doi").asText(), 255));
        }
        if (hasText(metadata.path("venue"))) {
            paper.setVenue(trim(metadata.path("venue").asText(), 500));
        }
        if (hasText(metadata.path("abstract"))) {
            paper.setAbstractText(metadata.path("abstract").asText());
        }
    }

    private void applyDuplicate(Paper paper, JsonNode duplicate) {
        if (duplicate.isObject() && hasText(duplicate.path("paper_id"))) {
            try {
                paper.setDuplicateOf(UUID.fromString(duplicate.path("paper_id").asText()));
                paper.setDuplicateSimilarity((float) duplicate.path("similarity").asDouble());
            } catch (IllegalArgumentException e) {
                log.warn("AI service returned an unusable duplicate reference for paper {}", paper.getId());
            }
        }
    }

    private static boolean hasText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
