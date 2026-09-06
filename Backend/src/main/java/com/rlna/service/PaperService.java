package com.rlna.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.rlna.config.AppProperties;
import com.rlna.dto.JobStatusDto;
import com.rlna.dto.PageResponse;
import com.rlna.dto.PaperDto;
import com.rlna.entity.Paper;
import com.rlna.entity.PaperSection;
import com.rlna.entity.ProcessingJob;
import com.rlna.exception.ApiException;
import com.rlna.repository.ConceptRepository;
import com.rlna.repository.PaperChunkRepository;
import com.rlna.repository.PaperRepository;
import com.rlna.repository.PaperSectionRepository;
import com.rlna.repository.ProcessingJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperService {

    private static final String PDF_MAGIC = "%PDF-";
    private static final int MAGIC_SEARCH_WINDOW = 1024;
    private static final double TITLE_DUPLICATE_THRESHOLD = 0.72;
    private static final List<String> READING_STATUSES = List.of("to_read", "reading", "read");

    private final PaperRepository paperRepository;
    private final PaperSectionRepository sectionRepository;
    private final PaperChunkRepository chunkRepository;
    private final ConceptRepository conceptRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProjectService projectService;
    private final StorageService storageService;
    private final PaperIndexingWorker indexingWorker;
    private final AppProperties properties;

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    /**
     * Validates, stores, and queues one PDF.
     *
     * <p>Validation happens entirely before anything is written, so a rejected
     * file never reaches storage (Section 29.5, case 2). The database row is
     * created first only because the object key is derived from the paper id;
     * if the upload then fails, the row is removed rather than left pointing at
     * an object that does not exist.
     */
    @Transactional
    public PaperDto.UploadResult upload(UUID userId, UUID projectId, MultipartFile file) {
        if (projectId != null) {
            projectService.require(userId, projectId);
        }
        byte[] bytes = validateAndRead(file);
        String contentHash = sha256(bytes);

        Optional<Paper> exact = paperRepository.findByUserIdAndContentHash(userId, contentHash);
        if (exact.isPresent()) {
            throw ApiException.conflict("DUPLICATE_PAPER",
                    "This paper is already in your library: " + displayTitle(exact.get()));
        }

        String title = titleFromFilename(file.getOriginalFilename());
        PaperDto.DuplicateWarning warning = paperRepository
                .findSimilarTitle(userId, title, TITLE_DUPLICATE_THRESHOLD)
                .map(p -> new PaperDto.DuplicateWarning(p.getId(), displayTitle(p),
                        "A paper with a very similar title is already in your library.", null))
                .orElse(null);

        Paper paper = new Paper();
        paper.setUserId(userId);
        paper.setProjectId(projectId);
        paper.setTitle(title);
        paper.setFileSize((long) bytes.length);
        paper.setContentHash(contentHash);
        paper.setProcessingStatus("queued");
        paper = paperRepository.saveAndFlush(paper);

        String key = StorageService.paperKey(userId, paper.getId());
        try {
            storageService.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
        } catch (RuntimeException e) {
            paperRepository.delete(paper);
            throw e;
        }
        paper.setStorageObjectKey(key);
        paper = paperRepository.save(paper);

        ProcessingJob job = createJob(paper.getId());
        enqueueAfterCommit(paper.getId(), job.getId());

        return new PaperDto.UploadResult(PaperDto.from(paper), warning);
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "That file is empty.");
        }
        long maxBytes = properties.upload().maxSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new ApiException("FILE_TOO_LARGE", org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Maximum file size is " + properties.upload().maxSizeMb() + " MB.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("UPLOAD_READ_FAILED", "We could not read that upload. Please try again.");
        }
        if (bytes.length > maxBytes) {
            throw new ApiException("FILE_TOO_LARGE", org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Maximum file size is " + properties.upload().maxSizeMb() + " MB.");
        }
        // The declared content type and the file extension are both attacker
        // controlled, so the actual bytes decide (Section 26.3).
        if (!looksLikePdf(bytes)) {
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Only PDF files are supported.");
        }
        return bytes;
    }

    static boolean looksLikePdf(byte[] bytes) {
        int window = Math.min(bytes.length, MAGIC_SEARCH_WINDOW);
        if (window < PDF_MAGIC.length()) {
            return false;
        }
        // The header is permitted a small amount of leading junk by the PDF
        // specification, so the marker is searched for rather than required at
        // offset zero.
        return new String(bytes, 0, window, StandardCharsets.ISO_8859_1).contains(PDF_MAGIC);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<PaperDto> list(UUID userId, UUID projectId, String status, Integer year,
                                       String readingStatus, String tag, String query,
                                       String sort, int page, int size) {
        if (StringUtils.hasText(tag)) {
            // Array containment cannot be expressed in the shared JPQL query, so
            // the tag filter takes its own path.
            List<PaperDto> items = paperRepository.findByTag(userId, tag).stream()
                    .map(PaperDto::summaryOf)
                    .toList();
            return PageResponse.of(items);
        }
        Page<Paper> result = paperRepository.search(userId, projectId,
                blankToNull(status), year, blankToNull(readingStatus),
                StringUtils.hasText(query) ? query.trim() : "",
                PageRequest.of(page, Math.min(size, 100), sortOf(sort)));
        return PageResponse.from(result, PaperDto::summaryOf);
    }

    @Transactional(readOnly = true)
    public PaperDto.Detail detail(UUID userId, UUID paperId) {
        Paper paper = require(userId, paperId);
        List<PaperDto.SectionDto> sections = sectionRepository.findByPaperIdOrderByOrderIndex(paperId).stream()
                .map(s -> new PaperDto.SectionDto(s.getId(), s.getSectionType(), s.getHeading(),
                        s.getOrderIndex(), lengthOf(s)))
                .toList();
        List<PaperDto.ConceptDto> concepts = conceptRepository.findByPaperId(paperId).stream()
                .map(c -> new PaperDto.ConceptDto(c.getConcept(), c.getConceptType(), c.getConfidence()))
                .toList();
        return new PaperDto.Detail(PaperDto.from(paper), sections, concepts,
                chunkRepository.countByPaperId(paperId));
    }

    /** Loads a paper or throws 404. The single place paper ownership is proven. */
    @Transactional(readOnly = true)
    public Paper require(UUID userId, UUID paperId) {
        return paperRepository.findByIdAndUserId(paperId, userId)
                .orElseThrow(() -> ApiException.notFound("paper"));
    }

    @Transactional(readOnly = true)
    public String fileUrl(UUID userId, UUID paperId) {
        Paper paper = require(userId, paperId);
        if (paper.getStorageObjectKey() == null) {
            throw ApiException.notFound("file");
        }
        return storageService.presignedGetUrl(paper.getStorageObjectKey());
    }

    public long signedUrlTtlSeconds() {
        return storageService.signedUrlTtlSeconds();
    }

    @Transactional(readOnly = true)
    public JobStatusDto status(UUID userId, UUID paperId) {
        Paper paper = require(userId, paperId);
        return JobStatusDto.from(paperId,
                jobRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId).orElse(null),
                paper.getProcessingStatus());
    }

    @Transactional(readOnly = true)
    public List<String> tags(UUID userId) {
        return paperRepository.findDistinctTags(userId);
    }

    @Transactional(readOnly = true)
    public PaperDto.DuplicateWarning duplicateWarning(UUID userId, UUID paperId) {
        Paper paper = require(userId, paperId);
        if (paper.getDuplicateOf() == null) {
            return null;
        }
        return paperRepository.findByIdAndUserId(paper.getDuplicateOf(), userId)
                .map(other -> new PaperDto.DuplicateWarning(other.getId(), displayTitle(other),
                        "The content of this paper closely matches one already in your library.",
                        paper.getDuplicateSimilarity() == null ? null
                                : paper.getDuplicateSimilarity().doubleValue()))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public PaperDto update(UUID userId, UUID paperId, PaperDto.UpdateRequest request) {
        Paper paper = require(userId, paperId);
        if (StringUtils.hasText(request.title())) {
            paper.setTitle(request.title().trim());
        }
        if (request.tags() != null) {
            paper.setTags(request.tags().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toArray(String[]::new));
        }
        if (request.readingStatus() != null) {
            if (!READING_STATUSES.contains(request.readingStatus())) {
                throw ApiException.badRequest("INVALID_READING_STATUS",
                        "Reading status must be one of: to read, reading, read.");
            }
            paper.setReadingStatus(request.readingStatus());
        }
        if (request.notes() != null) {
            paper.setNotes(request.notes());
        }
        if (request.projectId() != null) {
            projectService.require(userId, request.projectId());
            paper.setProjectId(request.projectId());
        }
        return PaperDto.from(paperRepository.save(paper));
    }

    /**
     * Removes a paper completely. Sections, chunks, concepts and jobs go with it
     * through the database cascade; the stored object is deleted afterwards
     * because a storage hiccup must not leave the rows half-deleted
     * (Section 18.3).
     */
    @Transactional
    public void delete(UUID userId, UUID paperId) {
        Paper paper = require(userId, paperId);
        String key = paper.getStorageObjectKey();
        paperRepository.delete(paper);
        paperRepository.flush();
        storageService.deleteQuietly(key);
    }

    /**
     * Attaches a PDF to a record imported from an academic API (Section 8.9).
     * The metadata already gathered is kept; only the file and the index are new.
     */
    @Transactional
    public PaperDto.UploadResult attachFile(UUID userId, UUID paperId, MultipartFile file) {
        Paper paper = require(userId, paperId);
        if (paper.getStorageObjectKey() != null) {
            throw ApiException.conflict("FILE_ALREADY_ATTACHED",
                    "This paper already has a PDF. Delete it and upload again to replace the file.");
        }
        byte[] bytes = validateAndRead(file);
        String contentHash = sha256(bytes);

        Optional<Paper> exact = paperRepository.findByUserIdAndContentHash(userId, contentHash);
        if (exact.isPresent()) {
            throw ApiException.conflict("DUPLICATE_PAPER",
                    "That PDF is already in your library: " + displayTitle(exact.get()));
        }

        String key = StorageService.paperKey(userId, paperId);
        storageService.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
        paper.setStorageObjectKey(key);
        paper.setFileSize((long) bytes.length);
        paper.setContentHash(contentHash);
        paper.setProcessingStatus("queued");
        paper.setProcessingError(null);
        paper = paperRepository.save(paper);

        ProcessingJob job = createJob(paperId);
        enqueueAfterCommit(paperId, job.getId());
        return new PaperDto.UploadResult(PaperDto.from(paper), null);
    }

    /**
     * Re-indexes every paper still on a superseded chunking strategy.
     *
     * <p>Safe to run repeatedly. Papers already on the current version are not
     * selected, a paper being processed right now is skipped rather than queued
     * twice, and the version stamp is written only when indexing succeeds - so
     * an interrupted run leaves the remainder outdated and the next run finishes
     * them. Indexing replaces a paper's chunks inside one transaction, so the
     * old index stays intact if the rebuild fails.
     *
     * @param limit how many to enqueue this run, keeping a large library from
     *              flooding the worker pool in one go.
     */
    @Transactional
    public ReindexReport reindexOutdated(UUID userId, int limit) {
        List<Paper> outdated = paperRepository.findOutdatedIndexes(
                userId, PaperIndexingWorker.CURRENT_INDEX_VERSION);
        int queued = 0;
        int busy = 0;
        for (Paper paper : outdated) {
            if (queued >= limit) {
                break;
            }
            if (jobRepository.countActiveForPaper(paper.getId()) > 0) {
                busy++;
                continue;
            }
            paper.setProcessingStatus("queued");
            paper.setProcessingError(null);
            paperRepository.save(paper);
            ProcessingJob job = createJob(paper.getId());
            enqueueAfterCommit(paper.getId(), job.getId());
            queued++;
        }
        int remaining = Math.max(0, outdated.size() - queued);
        log.info("Re-index: {} outdated, {} queued, {} already processing", outdated.size(), queued, busy);
        return new ReindexReport(outdated.size(), queued, busy, remaining);
    }

    /** Outcome of one re-index sweep. */
    public record ReindexReport(int outdated, int queued, int alreadyProcessing, int remaining) {}

    /** Re-runs indexing for a paper whose job failed, without re-uploading it. */
    @Transactional
    public JobStatusDto reprocess(UUID userId, UUID paperId) {
        Paper paper = require(userId, paperId);
        if (paper.getStorageObjectKey() == null) {
            throw ApiException.unprocessable("NO_FILE",
                    "This record has no PDF attached yet, so there is nothing to index.");
        }
        if (jobRepository.countActiveForPaper(paperId) > 0) {
            throw ApiException.conflict("ALREADY_PROCESSING",
                    "This paper is already being processed.");
        }
        paper.setProcessingStatus("queued");
        paper.setProcessingError(null);
        paperRepository.save(paper);

        ProcessingJob job = createJob(paperId);
        enqueueAfterCommit(paperId, job.getId());
        return JobStatusDto.from(paperId, job, "queued");
    }

    /**
     * Hands the job to the background worker only once this transaction has
     * committed. Dispatching inside the transaction would let the worker start
     * before the paper row is visible to it, which surfaces as a phantom
     * "paper not found" under load.
     */
    private void enqueueAfterCommit(UUID paperId, UUID jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexingWorker.process(paperId, jobId);
                }
            });
        } else {
            indexingWorker.process(paperId, jobId);
        }
    }

    private ProcessingJob createJob(UUID paperId) {
        ProcessingJob job = new ProcessingJob();
        job.setPaperId(paperId);
        job.setJobType("pdf_processing");
        job.setStatus(ProcessingJob.STATUS_QUEUED);
        job.setCurrentStep("Queued");
        return jobRepository.saveAndFlush(job);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int lengthOf(PaperSection section) {
        return section.getContent() == null ? 0 : section.getContent().length();
    }

    private static String displayTitle(Paper paper) {
        return StringUtils.hasText(paper.getTitle()) ? paper.getTitle() : "Untitled paper";
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static Sort sortOf(String sort) {
        if (sort == null) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return switch (sort) {
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            case "year" -> Sort.by(Sort.Direction.DESC, "publicationYear");
            case "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "updated" -> Sort.by(Sort.Direction.DESC, "updatedAt");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    /**
     * A provisional title so the library has something to show before the real
     * one is extracted. The filename is never used to build a storage key.
     */
    static String titleFromFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "Untitled paper";
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase().endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            return "Untitled paper";
        }
        return name.length() > 300 ? name.substring(0, 300) : name;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
