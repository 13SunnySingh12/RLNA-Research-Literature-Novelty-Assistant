package com.rlna.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.rlna.config.AppProperties;
import com.rlna.exception.ApiException;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * All object-storage access, against Backblaze B2 over its S3-compatible API.
 *
 * <p>Object keys are built here from ids the server already trusts. A
 * user-supplied filename never reaches a key, so a crafted name cannot escape
 * its prefix or collide with another user's object.
 *
 * <p>The bucket is private. Objects are only ever reachable through a short-lived
 * presigned URL issued after an ownership check, so nothing here is served
 * publicly.
 */
@Slf4j
@Service
public class StorageService {

    private final ObjectProvider<S3Client> s3ClientProvider;
    private final ObjectProvider<S3Presigner> presignerProvider;
    private final AppProperties.Storage config;

    public StorageService(ObjectProvider<S3Client> s3ClientProvider,
                          ObjectProvider<S3Presigner> presignerProvider,
                          AppProperties properties) {
        this.s3ClientProvider = s3ClientProvider;
        this.presignerProvider = presignerProvider;
        this.config = properties.storage();
    }

    public boolean isAvailable() {
        return config.isConfigured() && s3ClientProvider.getIfAvailable() != null;
    }

    /** Server-generated, and the only shape of key this application writes. */
    public static String paperKey(UUID userId, UUID paperId) {
        return "papers/" + userId + "/" + paperId + "/original.pdf";
    }

    public void put(String key, InputStream content, long contentLength, String contentType) {
        S3Client client = requireClient();
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(config.bucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength(contentLength)
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
        } catch (NoSuchBucketException e) {
            // A misconfigured bucket name looks like an outage from the outside,
            // so it is called out separately in the log.
            log.error("Storage bucket '{}' does not exist at {}", config.bucket(), config.endpoint());
            throw storageUnavailable("STORAGE_WRITE_FAILED",
                    "We could not save that file right now. Please try again.");
        } catch (S3Exception e) {
            logS3Failure("write", key, e);
            throw storageUnavailable("STORAGE_WRITE_FAILED",
                    "We could not save that file right now. Please try again.");
        } catch (SdkClientException e) {
            log.error("Storage unreachable while writing {}: {}", key, e.getMessage());
            throw storageUnavailable("STORAGE_WRITE_FAILED",
                    "We could not save that file right now. Please try again.");
        }
    }

    public byte[] get(String key) {
        S3Client client = requireClient();
        try (InputStream in = client.getObject(GetObjectRequest.builder()
                .bucket(config.bucket()).key(key).build())) {
            return in.readAllBytes();
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("file");
        } catch (S3Exception e) {
            logS3Failure("read", key, e);
            throw storageUnavailable("STORAGE_READ_FAILED",
                    "We could not read that file right now. Please try again.");
        } catch (SdkClientException | IOException e) {
            log.error("Storage unreachable while reading {}: {}", key, e.getMessage());
            throw storageUnavailable("STORAGE_READ_FAILED",
                    "We could not read that file right now. Please try again.");
        }
    }

    /**
     * Short-lived download URL, issued only after the caller's ownership of the
     * paper has already been established. The browser never sees a credential,
     * and the link stops working once the TTL expires.
     */
    public String presignedGetUrl(String key) {
        S3Presigner presigner = presignerProvider.getIfAvailable();
        if (presigner == null) {
            throw storageUnavailable("STORAGE_UNAVAILABLE",
                    "File storage is not available right now. Please try again shortly.");
        }
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofSeconds(config.signedUrlTtlSeconds()))
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(config.bucket()).key(key).build())
                            .build())
                    .url()
                    .toString();
        } catch (RuntimeException e) {
            log.error("Could not presign a download URL for {}: {}", key, e.getMessage());
            throw storageUnavailable("STORAGE_UNAVAILABLE",
                    "File storage is not available right now. Please try again shortly.");
        }
    }

    public long signedUrlTtlSeconds() {
        return config.signedUrlTtlSeconds();
    }

    /**
     * Best-effort delete. A paper's database rows are already gone by the time
     * this runs, so a storage failure must not resurrect them: it is logged as
     * an orphaned object instead of failing the user's delete.
     */
    public void deleteQuietly(String key) {
        if (key == null || !isAvailable()) {
            return;
        }
        try {
            s3ClientProvider.getObject().deleteObject(
                    DeleteObjectRequest.builder().bucket(config.bucket()).key(key).build());
        } catch (RuntimeException e) {
            log.warn("Orphaned object left in storage: {} ({})", key, e.getMessage());
        }
    }

    private S3Client requireClient() {
        S3Client client = s3ClientProvider.getIfAvailable();
        if (client == null) {
            throw storageUnavailable("STORAGE_UNAVAILABLE",
                    "File storage is not available right now. Please try again shortly.");
        }
        return client;
    }

    /**
     * Logs the provider's status and error code, never the response body. B2
     * returns the bucket name and request details in its error payloads, and a
     * rejected header name is the signal that matters for diagnosis.
     */
    private void logS3Failure(String operation, String key, S3Exception e) {
        int status = e.statusCode();
        String code = e.awsErrorDetails() == null ? "unknown" : e.awsErrorDetails().errorCode();
        if (status == 401 || status == 403) {
            log.error("Storage {} denied for {} (status {}, code {}). Check the B2 application key "
                    + "and that it grants access to bucket '{}'.", operation, key, status, code,
                    config.bucket());
        } else {
            log.error("Storage {} failed for {} (status {}, code {})", operation, key, status, code);
        }
    }

    private ApiException storageUnavailable(String code, String message) {
        return ApiException.unavailable(code, message);
    }
}
