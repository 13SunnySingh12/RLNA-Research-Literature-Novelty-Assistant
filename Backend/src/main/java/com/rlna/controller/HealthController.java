package com.rlna.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rlna.client.AiServiceClient;
import com.rlna.service.StorageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Platform health check (Section 31.2).
 *
 * <p>Reports each dependency separately so a degraded deployment is visible
 * without reading logs. The endpoint stays 200 while the service itself is up:
 * a sleeping AI instance is a degraded state, not a dead one, and returning 503
 * would make the platform recycle a healthy container.
 */
@Tag(name = "Health")
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final AiServiceClient aiServiceClient;
    private final StorageService storageService;

    @GetMapping("/health")
    @Operation(summary = "Service and dependency health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "rlna-backend");

        Map<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("database", databaseStatus());
        dependencies.put("aiService", aiServiceClient.isHealthy() ? "up" : "unreachable");
        dependencies.put("storage", storageService.isAvailable() ? "up" : "not configured");
        body.put("dependencies", dependencies);
        return body;
    }

    private String databaseStatus() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "up";
        } catch (RuntimeException e) {
            return "unreachable";
        }
    }
}
