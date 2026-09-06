package com.rlna.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.client.AiServiceClient;
import com.rlna.entity.Paper;
import com.rlna.entity.ProcessingJob;
import com.rlna.exception.ApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The background half of paper indexing (Section 24).
 *
 * <p>Nothing here depends on the browser. The request that triggered the work
 * returned 202 long before this runs, progress is written to
 * {@code processing_jobs} rather than held in memory, and jobs left unfinished
 * by a restart are picked up again on the next boot. A user can close the tab,
 * refresh, or lose connectivity without changing the outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperIndexingWorker {

    static final int MAX_ATTEMPTS = 3;

    /**
     * Bumped whenever chunking or embedding changes in a way that makes existing
     * chunks worse than freshly built ones. Papers below this are re-index
     * candidates; see {@code PaperService.reindexOutdated}.
     *
     * <p>1 - chunk size clamped to the embedding model's input window.
     */
    public static final int CURRENT_INDEX_VERSION = 1;
    private static final long RETRY_BASE_DELAY_MS = 2_000L;

    private final ProcessingJobStore jobStore;
    private final AiServiceClient aiServiceClient;

    @Async("paperProcessingExecutor")
    public void process(UUID paperId, UUID jobId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                runOnce(paperId, jobId, attempt);
                return;
            } catch (ApiException e) {
                boolean transientFailure = e.getStatus().is5xxServerError()
                        || e.getStatus() == HttpStatus.TOO_MANY_REQUESTS;
                if (!transientFailure || attempt == MAX_ATTEMPTS) {
                    jobStore.fail(paperId, jobId, userMessageFor(e), retryableAfter(e));
                    return;
                }
                log.warn("Indexing attempt {} for paper {} failed ({}); retrying", attempt, paperId, e.getCode());
                if (!backoff(attempt)) {
                    jobStore.fail(paperId, jobId, "Indexing was interrupted. You can retry it.");
                    return;
                }
            } catch (RuntimeException e) {
                log.error("Indexing paper {} failed unexpectedly", paperId, e);
                jobStore.fail(paperId, jobId, "Indexing failed unexpectedly. You can retry it.");
                return;
            }
        }
    }

    /**
     * One indexing attempt. The AI service reads the PDF from storage with its
     * own credentials, and writes the sections, chunks and embeddings it derives
     * along with per-step progress. This method owns the paper's metadata and
     * the job's terminal state, nothing else (Section 7 responsibility boundary).
     */
    private void runOnce(UUID paperId, UUID jobId, int attempt) {
        Paper paper = jobStore.load(paperId);
        if (paper == null) {
            log.info("Paper {} was deleted before indexing started; dropping job {}", paperId, jobId);
            return;
        }
        jobStore.markRunning(paperId, jobId, attempt);

        Map<String, Object> request = new HashMap<>();
        request.put("job_id", jobId.toString());
        request.put("paper_id", paperId.toString());
        request.put("user_id", paper.getUserId().toString());
        request.put("project_id", paper.getProjectId() == null ? null : paper.getProjectId().toString());
        request.put("object_key", paper.getStorageObjectKey());
        request.put("fallback_title", paper.getTitle());

        JsonNode response = aiServiceClient.post("/internal/process-pdf", request);
        jobStore.complete(paperId, jobId, response);
    }

    /**
     * Free instances sleep and restart, which can strand a job mid-flight.
     * Because progress lives in the database, those jobs are resumed here rather
     * than leaving a paper stuck at "processing" forever.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedJobs() {
        List<ProcessingJob> stranded = jobStore.findUnfinished();
        if (stranded.isEmpty()) {
            return;
        }
        log.info("Resuming {} unfinished indexing job(s) after restart", stranded.size());
        for (ProcessingJob job : stranded) {
            if (job.getAttempts() >= MAX_ATTEMPTS) {
                jobStore.fail(job.getPaperId(), job.getId(),
                        "Indexing did not finish after several attempts. You can retry it.");
            } else {
                process(job.getPaperId(), job.getId());
            }
        }
    }

    /**
     * Provider and infrastructure detail is deliberately absent from every
     * branch: the user is told what happened and what to do next, nothing about
     * how the system is built (Section 27, principle 3).
     */
    /**
     * Whether another attempt could plausibly succeed.
     *
     * <p>A password-protected PDF, a scan with no text layer and an
     * over-limit document fail the same way every time. Offering a retry there
     * wastes the reader's time and hides the real fix, which is a different
     * file.
     */
    private static boolean retryableAfter(ApiException e) {
        return switch (e.getCode()) {
            case "PDF_UNREADABLE", "PDF_ENCRYPTED", "NO_EXTRACTABLE_TEXT", "PDF_TOO_COMPLEX" -> false;
            default -> true;
        };
    }

    private static String userMessageFor(ApiException e) {
        return switch (e.getCode()) {
            case "PDF_UNREADABLE" -> "We could not read this PDF. The file may be damaged.";
            case "PDF_ENCRYPTED" ->
                    "This PDF is password protected. Remove the password and upload it again.";
            case "NO_EXTRACTABLE_TEXT" ->
                    "This looks like a scanned PDF. Text extraction is not supported yet.";
            case "PDF_TOO_COMPLEX" -> "This PDF is too large or too complex to index safely.";
            case "STORAGE_READ_FAILED", "FILE_NOT_FOUND" ->
                    "We could not read the stored file for this paper. Try re-uploading it.";
            case "AI_UNAVAILABLE" -> "Indexing is temporarily unavailable. Retry in a few minutes.";
            default -> "Indexing failed. You can retry it.";
        };
    }

    private static boolean backoff(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_DELAY_MS * (long) Math.pow(2, attempt - 1));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
