package com.rlna.client;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.config.AppProperties;
import com.rlna.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * The only path from the application backend into the AI service.
 *
 * <p>Requests carry the shared internal token and already-authorized ids. The
 * AI service performs no authorization of its own (Section 22.2, rule 4), which
 * is precisely why it is never exposed to the browser.
 *
 * <p>Errors are translated here: the caller learns that analysis is unavailable,
 * never which provider failed or why.
 */
@Slf4j
@Component
public class AiServiceClient {

    private final RestClient restClient;

    public AiServiceClient(AppProperties properties) {
        AppProperties.AiService config = properties.aiService();
        Duration timeout = Duration.ofSeconds(config.timeoutSeconds());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Generous read timeout: indexing a long PDF legitimately takes a while,
        // and the AI service applies its own per-provider timeouts inside.
        factory.setReadTimeout(timeout.plusSeconds(30));

        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.internalToken())
                // Connection reuse is switched off deliberately. The underlying
                // HttpURLConnection pool keeps sockets alive without ever
                // checking whether the peer is still there, so the first call
                // after the AI service restarts reads from a dead socket and
                // comes back as an unparseable "application/octet-stream" body.
                // Free-tier instances sleep and restart routinely, which makes
                // that a normal condition rather than an edge case. These are a
                // handful of long-running internal calls, so a fresh connection
                // each time costs nothing measurable.
                .defaultHeader(HttpHeaders.CONNECTION, "close")
                .build();
    }

    public JsonNode post(String path, Object body) {
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String detail = readDetail(response.getBody());
                        log.warn("AI service {} returned {}: {}", path, response.getStatusCode(), detail);
                        throw translate(response.getStatusCode(), detail);
                    })
                    .body(JsonNode.class);
        } catch (ApiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            log.error("AI service unreachable at {}", path, e);
            throw ApiException.unavailable("AI_UNAVAILABLE",
                    "AI analysis is temporarily unavailable. Please try again shortly.");
        } catch (RuntimeException e) {
            log.error("AI service call to {} failed", path, e);
            throw ApiException.unavailable("AI_UNAVAILABLE",
                    "AI analysis is temporarily unavailable. Please try again shortly.");
        }
    }

    public boolean isHealthy() {
        try {
            Map<?, ?> body = restClient.get().uri("/health").retrieve().body(Map.class);
            return body != null && "ok".equals(body.get("status"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ApiException translate(HttpStatusCode status, String detail) {
        if (status.value() == 429) {
            return ApiException.tooManyRequests(
                    "You have hit the analysis limit. Try again in a few minutes.");
        }
        if (status.value() == 422 && detail != null && detail.contains("INSUFFICIENT_EVIDENCE")) {
            return ApiException.unprocessable("INSUFFICIENT_EVIDENCE",
                    "There is not enough in your library to answer this. Try adding related papers.");
        }
        if (status.value() == 400 || status.value() == 422) {
            // The AI service classifies why it refused - a password-protected
            // PDF, a scan with no text layer, a document beyond the page limit.
            // Flattening every 4xx to one code threw that away and left callers
            // unable to tell the user which of those happened, or that retrying
            // could not help. The code travels; the wording stays with whoever
            // shows it.
            String upstream = codeFrom(detail);
            return ApiException.unprocessable(
                    upstream != null ? upstream : "AI_REQUEST_REJECTED",
                    "That request could not be processed. Please adjust it and try again.");
        }
        return ApiException.unavailable("AI_UNAVAILABLE",
                "AI analysis is temporarily unavailable. Please try again shortly.");
    }

    /** Reads {"error":{"code":...}} from the AI service's error envelope. */
    private String codeFrom(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            JsonNode code = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(detail).path("error").path("code");
            return code.isTextual() && !code.asText().isBlank() ? code.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String readDetail(java.io.InputStream body) {
        try {
            return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
