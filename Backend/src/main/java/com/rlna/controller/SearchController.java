package com.rlna.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.dto.SearchDto;
import com.rlna.entity.User;
import com.rlna.security.CurrentUser;
import com.rlna.service.RateLimiterService;
import com.rlna.service.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Search")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final RateLimiterService rateLimiter;

    @PostMapping("/semantic")
    @Operation(summary = "Natural-language search across the user's own papers")
    public SearchDto.Results semantic(@CurrentUser User user,
                                      @Valid @RequestBody SearchDto.SemanticRequest request) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.SEARCH);
        return searchService.semantic(user.getId(), request);
    }

    @GetMapping("/keyword")
    @Operation(summary = "Full-text keyword search across the user's own papers")
    public SearchDto.Results keyword(@CurrentUser User user,
                                     @RequestParam String q,
                                     @RequestParam(required = false) UUID projectId,
                                     @RequestParam(required = false) Integer limit) {
        rateLimiter.check(user.getId(), RateLimiterService.Bucket.SEARCH);
        return searchService.keyword(user.getId(), projectId, q, limit);
    }
}
