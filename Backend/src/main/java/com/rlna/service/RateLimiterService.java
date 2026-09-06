package com.rlna.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.rlna.config.AppProperties;
import com.rlna.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-user request budgets for the endpoints that cost money or CPU
 * (Section 26.5).
 *
 * <p>Deliberately in-process. The deployment is a single instance per service,
 * so a shared store would add infrastructure without changing the outcome
 * (Section 34, "Redis / distributed caching"). If the service is ever scaled
 * out, this class is the one place that has to change.
 */
@Slf4j
@Service
public class RateLimiterService {

    public enum Bucket { UPLOAD, SEARCH, ANALYSIS }

    private record Window(AtomicInteger count, Instant resetAt) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AppProperties.RateLimit limits;

    public RateLimiterService(AppProperties properties) {
        this.limits = properties.rateLimit();
    }

    public void check(UUID userId, Bucket bucket) {
        int limit = switch (bucket) {
            case UPLOAD -> limits.uploadPerMinute();
            case SEARCH -> limits.searchPerMinute();
            case ANALYSIS -> limits.analysisPerMinute();
        };
        String key = userId + ":" + bucket;
        Instant now = Instant.now();

        Window window = windows.compute(key, (k, existing) ->
                (existing == null || existing.resetAt().isBefore(now))
                        ? new Window(new AtomicInteger(0), now.plus(Duration.ofMinutes(1)))
                        : existing);

        if (window.count().incrementAndGet() > limit) {
            long seconds = Math.max(1, Duration.between(now, window.resetAt()).getSeconds());
            throw ApiException.tooManyRequests(switch (bucket) {
                case UPLOAD -> "You are uploading too quickly. Try again in " + seconds + " seconds.";
                case SEARCH -> "Too many searches at once. Try again in " + seconds + " seconds.";
                case ANALYSIS -> "You have hit the analysis limit. Try again in " + seconds + " seconds.";
            });
        }
    }

    /** Expired windows are never read again; sweeping them keeps the map bounded. */
    @Scheduled(fixedDelay = 300_000L)
    void evictExpiredWindows() {
        Instant now = Instant.now();
        windows.entrySet().removeIf(e -> e.getValue().resetAt().isBefore(now));
    }
}
