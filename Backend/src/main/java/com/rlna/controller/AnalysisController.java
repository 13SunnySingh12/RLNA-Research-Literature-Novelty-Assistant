package com.rlna.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.dto.AnalysisDto;
import com.rlna.dto.PageResponse;
import com.rlna.entity.User;
import com.rlna.security.CurrentUser;
import com.rlna.service.AnalysisService;
import com.rlna.service.RateLimiterService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Every model-backed endpoint. All of them are rate limited, all of them go
 * through the result cache, and none of them reveal which provider or model
 * produced the answer.
 */
@Tag(name = "Analysis")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final RateLimiterService rateLimiter;

    @PostMapping("/papers/{id}/summary")
    @Operation(summary = "Structured summary of one paper")
    public AnalysisDto summary(@CurrentUser User user, @PathVariable UUID id,
                               @RequestBody(required = false) AnalysisDto.SummaryRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        boolean refresh = request != null && Boolean.TRUE.equals(request.refresh());
        return analysisService.summary(user.getId(), id, refresh);
    }

    @PostMapping("/papers/{id}/concepts")
    @Operation(summary = "Extract keywords, methods, datasets and metrics")
    public AnalysisDto concepts(@CurrentUser User user, @PathVariable UUID id,
                                @RequestBody(required = false) AnalysisDto.SummaryRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        boolean refresh = request != null && Boolean.TRUE.equals(request.refresh());
        return analysisService.concepts(user.getId(), id, refresh);
    }

    @PostMapping("/papers/{id}/question")
    @Operation(summary = "Ask a question about one paper")
    public AnalysisDto askPaper(@CurrentUser User user, @PathVariable UUID id,
                                @Valid @RequestBody AnalysisDto.QuestionRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        return analysisService.askPaper(user.getId(), id, request);
    }

    @PostMapping("/projects/{id}/question")
    @Operation(summary = "Ask a question across a whole project")
    public AnalysisDto askProject(@CurrentUser User user, @PathVariable UUID id,
                                  @Valid @RequestBody AnalysisDto.QuestionRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        return analysisService.askProject(user.getId(), id, request);
    }

    @PostMapping("/analysis/compare")
    @Operation(summary = "Compare two or three papers across fixed dimensions")
    public AnalysisDto compare(@CurrentUser User user, @Valid @RequestBody AnalysisDto.CompareRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        return analysisService.compare(user.getId(), request);
    }

    @PostMapping("/analysis/research-gap")
    @Operation(summary = "Identify potential research gaps from what authors themselves reported")
    public AnalysisDto researchGap(@CurrentUser User user,
                                   @Valid @RequestBody AnalysisDto.ProjectAnalysisRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        return analysisService.researchGap(user.getId(), request);
    }

    @PostMapping("/analysis/novelty")
    @Operation(summary = "Assess an idea against the retrieved literature")
    public AnalysisDto novelty(@CurrentUser User user, @Valid @RequestBody AnalysisDto.NoveltyRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        return analysisService.novelty(user.getId(), request);
    }

    @PostMapping("/analysis/literature-review")
    @Operation(summary = "Draft a literature review across a project")
    public AnalysisDto literatureReview(@CurrentUser User user,
                                        @Valid @RequestBody AnalysisDto.ProjectAnalysisRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.ANALYSIS);
        return analysisService.literatureReview(user.getId(), request);
    }

    @GetMapping("/analysis/history")
    @Operation(summary = "Past analyses")
    public PageResponse<AnalysisDto> history(@CurrentUser User user,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) UUID projectId,
                                             @RequestParam(required = false) UUID paperId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return analysisService.history(user.getId(), type, projectId, paperId,
                Math.max(page, 0), Math.max(size, 1));
    }

    @GetMapping("/analysis/{id}")
    @Operation(summary = "Re-open one stored analysis")
    public AnalysisDto get(@CurrentUser User user, @PathVariable UUID id) {
        return analysisService.get(user.getId(), id);
    }

    @DeleteMapping("/analysis/{id}")
    @Operation(summary = "Delete a stored analysis")
    public ResponseEntity<Void> delete(@CurrentUser User user, @PathVariable UUID id) {
        analysisService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
