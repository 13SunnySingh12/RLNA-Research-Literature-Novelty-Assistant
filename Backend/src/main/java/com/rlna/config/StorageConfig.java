package com.rlna.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Object storage: Backblaze B2 through its S3-compatible API.
 *
 * <p>Three settings here are not cosmetic. Getting any of them wrong produces an
 * error that names an HTTP header rather than the cause.
 *
 * <ol>
 *   <li><b>Checksums are only sent when the operation requires them.</b> Since
 *       2.30, the AWS SDK adds {@code x-amz-sdk-checksum-algorithm} and
 *       {@code x-amz-checksum-mode} to every request by default. B2 rejects both
 *       with "Unsupported header ... received for this API call", so every
 *       upload fails until this is turned down.</li>
 *   <li><b>Chunked encoding is off.</b> B2 does not accept the
 *       {@code aws-chunked} streaming payload signature, so the body is signed
 *       in one piece.</li>
 *   <li><b>The region is real.</b> B2 derives it from the endpoint host
 *       ({@code s3.us-west-004.backblazeb2.com} means {@code us-west-004}), and
 *       the SigV4 signature is computed over it, so a placeholder region fails
 *       to authenticate rather than failing to route.</li>
 * </ol>
 *
 * <p>The same beans drive MinIO for local development, which is why path-style
 * addressing stays configurable.
 */
@Slf4j
@Configuration
public class StorageConfig {

    @Bean
    S3Client s3Client(AppProperties properties) {
        AppProperties.Storage s = properties.storage();
        if (!s.isConfigured()) {
            log.warn("Object storage is not configured. Upload and download endpoints will report "
                    + "storage as unavailable until Backblaze B2 credentials are supplied.");
            return null;
        }
        log.info("Object storage configured: bucket '{}' at {} (region {})",
                s.bucket(), s.endpoint(), s.region());
        return S3Client.builder()
                .region(Region.of(s.region()))
                .endpointOverride(URI.create(s.endpointUri()))
                .credentialsProvider(credentials(s))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s.forcePathStyle())
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(AppProperties properties) {
        AppProperties.Storage s = properties.storage();
        if (!s.isConfigured()) {
            return null;
        }
        return S3Presigner.builder()
                .region(Region.of(s.region()))
                .endpointOverride(URI.create(s.endpointUri()))
                .credentialsProvider(credentials(s))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s.forcePathStyle())
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    /**
     * A B2 application key pair: the key id is the access key, the application
     * key is the secret. Both arrive from the environment and are never logged.
     */
    private StaticCredentialsProvider credentials(AppProperties.Storage s) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s.accessKeyId(), s.secretAccessKey()));
    }
}
