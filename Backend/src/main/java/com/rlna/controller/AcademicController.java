package com.rlna.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.dto.AcademicDto;
import com.rlna.dto.PaperDto;
import com.rlna.entity.User;
import com.rlna.security.CurrentUser;
import com.rlna.service.AcademicSearchService;
import com.rlna.service.RateLimiterService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Academic search")
@RestController
@RequestMapping("/api/academic")
@RequiredArgsConstructor
public class AcademicController {

    private final AcademicSearchService academicSearchService;
    private final RateLimiterService rateLimiter;

    /**
     * Returns 200 with {@code degraded: true} rather than an error when the
     * external service is unavailable, so the UI can fall back to the local
     * library instead of showing a failure (Section 27).
     */
    @GetMapping("/search")
    @Operation(summary = "Search free academic APIs by topic, title or author")
    public AcademicDto.SearchResults search(@CurrentUser User user,
                                            @RequestParam String q,
                                            @RequestParam(required = false) Integer limit) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.SEARCH);
        return academicSearchService.search(q, limit);
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a discovered paper's metadata into a project")
    public PaperDto importPaper(@CurrentUser User user,
                                @Valid @RequestBody AcademicDto.ImportRequest request) {
        return academicSearchService.importPaper(user.getId(), request);
    }
}
