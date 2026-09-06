package com.rlna.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;

import com.rlna.exception.ApiException;
import com.rlna.service.StorageService;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Exercises the real storage round trip: upload, presigned download, delete.
 *
 * <p>Runs against whatever S3-compatible endpoint {@code B2_*} points at, which
 * is Backblaze B2 in production and MinIO locally. The code path is identical
 * either way, which is the point of the substitution.
 *
 * <p>Skipped when no endpoint is configured, so the suite stays runnable on a
 * machine with no storage credentials.
 */
@EnabledIfEnvironmentVariable(named = "B2_ENDPOINT", matches = ".+")
class StorageServiceIntegrationTest {

    private static AppProperties.Storage config() {
        return new AppProperties.Storage(
                env("B2_BUCKET_NAME", "rlna-papers"),
                env("B2_ENDPOINT", null),
                env("B2_REGION", "us-east-005"),
                env("B2_KEY_ID", null),
                env("B2_APPLICATION_KEY", null),
                300,
                Boolean.parseBoolean(env("B2_FORCE_PATH_STYLE", "false")));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static StorageService storageService() {
        AppProperties properties = new AppProperties(
                new AppProperties.Auth("https://example.test/jwks", "https://example.test"),
                config(),
                new AppProperties.AiService("http://ai.test", "token", 60, true),
                new AppProperties.Upload(25),
                new AppProperties.Academic("https://api.test", "a@b.c", 60),
                java.util.List.of("http://localhost:5173"),
                new AppProperties.RateLimit(20, 60, 20));

        StorageConfig storageConfig = new StorageConfig();
        S3Client client = storageConfig.s3Client(properties);
        S3Presigner presigner = storageConfig.s3Presigner(properties);
        return new StorageService(provider(client), provider(presigner), properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(instance);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(instance);
        return provider;
    }

    @Test
    @DisplayName("a PDF round-trips through object storage and is then deleted")
    void roundTrip() throws Exception {
        StorageService storage = storageService();
        assertThat(storage.isAvailable()).isTrue();

        UUID userId = UUID.randomUUID();
        UUID paperId = UUID.randomUUID();
        String key = StorageService.paperKey(userId, paperId);
        byte[] content = "%PDF-1.7\nintegration test payload\n%%EOF".getBytes(StandardCharsets.UTF_8);

        try {
            // Upload. On B2 this is the call that fails outright if the SDK's
            // default checksum headers are left enabled.
            storage.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");

            // Read back with server-side credentials, the way the AI service does.
            assertThat(storage.get(key)).isEqualTo(content);

            // Presigned download, the way a browser does.
            String url = storage.presignedGetUrl(key);
            assertThat(url).contains(key);
            assertThat(downloadStatus(url)).isEqualTo(200);

            storage.deleteQuietly(key);

            // Gone, and reported as a missing file rather than as an outage.
            assertThatThrownBy(() -> storage.get(key))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).contains("NOT_FOUND"));
        } finally {
            storage.deleteQuietly(key);
        }
    }

    @Test
    @DisplayName("a presigned URL stops working once the object is deleted")
    void presignedUrlDiesWithTheObject() throws Exception {
        StorageService storage = storageService();
        String key = StorageService.paperKey(UUID.randomUUID(), UUID.randomUUID());
        byte[] content = "%PDF-1.7\ntransient\n%%EOF".getBytes(StandardCharsets.UTF_8);

        storage.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");
        String url = storage.presignedGetUrl(key);
        assertThat(downloadStatus(url)).isEqualTo(200);

        storage.deleteQuietly(key);

        // The signature is still valid; the object is not. Deleting a paper
        // therefore revokes any link already handed out.
        assertThat(downloadStatus(url)).isIn(403, 404);
    }

    @Test
    @DisplayName("reading an object that was never written is a not-found, not a server error")
    void missingObjectIsNotFound() {
        StorageService storage = storageService();
        String key = StorageService.paperKey(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> storage.get(key))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    private static int downloadStatus(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
