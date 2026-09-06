package com.rlna.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rlna.dto.JobStatusDto;
import com.rlna.dto.PageResponse;
import com.rlna.dto.PaperDto;
import com.rlna.entity.User;
import com.rlna.exception.ApiException;
import com.rlna.security.CurrentUser;
import com.rlna.service.PaperService;
import com.rlna.service.RateLimiterService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Papers")
@RestController
@RequestMapping("/api/papers")
@RequiredArgsConstructor
public class PaperController {

    /** One entry per uploaded file, so a rejected file does not hide the accepted ones. */
    public record UploadOutcome(String filename, boolean accepted, PaperDto paper,
                                PaperDto.DuplicateWarning duplicateOf, String errorCode, String errorMessage) {}

    private final PaperService paperService;
    private final RateLimiterService rateLimiter;

    @GetMapping
    @Operation(summary = "List papers with filters")
    public PageResponse<PaperDto> list(@CurrentUser User user,
                                       @RequestParam(required = false) UUID projectId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Integer year,
                                       @RequestParam(required = false) String readingStatus,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return paperService.list(user.getId(), projectId, status, year, readingStatus, tag, q,
                sort, Math.max(page, 0), Math.max(size, 1));
    }

    /**
     * Uploads one or more PDFs.
     *
     * <p>Returns 202: the files are stored and queued, and indexing continues on
     * the server whatever the browser does next (Section 24.1). Each file is
     * handled independently so one bad file does not discard a good batch.
     */
    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "Upload PDFs into a project")
    public ResponseEntity<List<UploadOutcome>> upload(@CurrentUser User user,
                                                      @RequestPart("files") MultipartFile[] files,
                                                      @RequestParam(required = false) UUID projectId) {
        if (files == null || files.length == 0) {
            throw ApiException.badRequest("NO_FILES", "Choose at least one PDF to upload.");
        }
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.UPLOAD);

        List<UploadOutcome> outcomes = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename();
            try {
                PaperDto.UploadResult result = paperService.upload(user.getId(), projectId, file);
                outcomes.add(new UploadOutcome(name, true, result.paper(), result.duplicateOf(), null, null));
            } catch (ApiException e) {
                outcomes.add(new UploadOutcome(name, false, null, null, e.getCode(), e.getMessage()));
            }
        }
        boolean anyAccepted = outcomes.stream().anyMatch(UploadOutcome::accepted);
        return ResponseEntity.status(anyAccepted ? HttpStatus.ACCEPTED : HttpStatus.BAD_REQUEST)
                .body(outcomes);
    }

    @PostMapping(path = "/{id}/file", consumes = "multipart/form-data")
    @Operation(summary = "Attach a PDF to a metadata-only paper")
    public ResponseEntity<PaperDto.UploadResult> attach(@CurrentUser User user, @PathVariable UUID id,
                                                        @RequestPart("file") MultipartFile file) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.UPLOAD);
        return ResponseEntity.accepted().body(paperService.attachFile(user.getId(), id, file));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Paper detail with sections and concepts")
    public PaperDto.Detail get(@CurrentUser User user, @PathVariable UUID id) {
        return paperService.detail(user.getId(), id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update tags, reading status, notes or project")
    public PaperDto update(@CurrentUser User user, @PathVariable UUID id,
                           @Valid @RequestBody PaperDto.UpdateRequest request) {
        return paperService.update(user.getId(), id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a paper, its index, and its stored file")
    public ResponseEntity<Void> delete(@CurrentUser User user, @PathVariable UUID id) {
        paperService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * A short-lived signed URL, issued only after ownership is proven. The
     * storage credential never reaches the browser (Section 20.3).
     */
    @GetMapping("/{id}/file")
    @Operation(summary = "Signed download URL for the original PDF")
    public Map<String, Object> file(@CurrentUser User user, @PathVariable UUID id) {
        return Map.of("url", paperService.fileUrl(user.getId(), id),
                "expiresInSeconds", paperService.signedUrlTtlSeconds());
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Live indexing progress")
    public JobStatusDto status(@CurrentUser User user, @PathVariable UUID id) {
        return paperService.status(user.getId(), id);
    }

    @GetMapping("/{id}/duplicate")
    @Operation(summary = "Duplicate warning found during indexing, if any")
    public ResponseEntity<PaperDto.DuplicateWarning> duplicate(@CurrentUser User user, @PathVariable UUID id) {
        PaperDto.DuplicateWarning warning = paperService.duplicateWarning(user.getId(), id);
        return warning == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(warning);
    }

    @PostMapping("/{id}/reprocess")
    @Operation(summary = "Retry indexing for a failed paper")
    public ResponseEntity<JobStatusDto> reprocess(@CurrentUser User user, @PathVariable UUID id) {
        return ResponseEntity.accepted().body(paperService.reprocess(user.getId(), id));
    }

    /**
     * Rebuilds any paper still indexed under a superseded chunking strategy.
     *
     * <p>Exposed rather than run on startup: it costs a full re-embed per paper,
     * so it belongs where someone chooses to spend that, and it reports what it
     * did instead of working silently.
     */
    @PostMapping("/reindex-outdated")
    @Operation(summary = "Re-index papers whose chunks predate the current strategy")
    public PaperService.ReindexReport reindexOutdated(
            @CurrentUser User user,
            @RequestParam(defaultValue = "25") int limit) {
        return paperService.reindexOutdated(user.getId(), Math.clamp(limit, 1, 200));
    }

    @GetMapping("/tags")
    @Operation(summary = "Distinct tags used across the library")
    public List<String> tags(@CurrentUser User user) {
        return paperService.tags(user.getId());
    }
}
