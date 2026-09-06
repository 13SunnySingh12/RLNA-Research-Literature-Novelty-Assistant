package com.rlna.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Every externally-configurable value in one place, validated at startup so the
 * service fails fast on a missing credential instead of failing mid-request
 * (Section 26.1).
 */
@Validated
@ConfigurationProperties(prefix = "rlna")
public record AppProperties(
        @Valid @NotNull Auth auth,
        @Valid @NotNull Storage storage,
        @Valid @NotNull AiService aiService,
        @Valid @NotNull Upload upload,
        @Valid @NotNull Academic academic,
        @NotNull List<String> allowedOrigins,
        @Valid @NotNull RateLimit rateLimit) {

    public record Auth(@NotBlank String jwksUrl, String issuer) {}

    public record Storage(
            @NotBlank String bucket,
            String endpoint,
            String region,
            String accessKeyId,
            String secretAccessKey,
            @Min(30) long signedUrlTtlSeconds,
            boolean forcePathStyle) {

        /**
         * Storage is optional at boot so the rest of the application stays
         * testable without credentials. A region is required whenever the rest
         * is present: Backblaze B2 signs requests over it, so a blank one fails
         * to authenticate rather than failing to route.
         */
        public boolean isConfigured() {
            return endpoint != null && !endpoint.isBlank()
                    && region != null && !region.isBlank()
                    && accessKeyId != null && !accessKeyId.isBlank()
                    && secretAccessKey != null && !secretAccessKey.isBlank();
        }

        /**
         * The B2 console shows the endpoint as a bare host
         * ({@code s3.us-west-004.backblazeb2.com}), and a URI without a scheme
         * fails deep inside the SDK rather than at configuration time. Assume
         * HTTPS when none is given.
         */
        public String endpointUri() {
            if (endpoint == null || endpoint.isBlank()) {
                return endpoint;
            }
            String trimmed = endpoint.trim();
            return trimmed.contains("://") ? trimmed : "https://" + trimmed;
        }
    }

    public record AiService(@NotBlank String baseUrl, @NotBlank String internalToken,
                           int timeoutSeconds, boolean cacheEnabled) {}

    public record Upload(@Min(1) int maxSizeMb) {
        public long maxSizeBytes() {
            return (long) maxSizeMb * 1024L * 1024L;
        }
    }

    public record Academic(@NotBlank String baseUrl, String mailto, int rateLimitPerMinute) {}

    /** Per-user request budgets for the endpoints that cost money or CPU (Section 26.5). */
    public record RateLimit(
            @Min(1) int uploadPerMinute,
            @Min(1) int searchPerMinute,
            @Min(1) int analysisPerMinute) {}
}
