package com.rlna.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.dto.AnalyticsDto;
import com.rlna.entity.User;
import com.rlna.security.CurrentUser;
import com.rlna.service.AnalyticsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Analytics")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping
    @Operation(summary = "Dashboard figures across the library, optionally scoped to a project")
    public AnalyticsDto analytics(@CurrentUser User user,
                                  @RequestParam(required = false) UUID projectId) {
        return analyticsService.forUser(user.getId(), projectId);
    }
}
