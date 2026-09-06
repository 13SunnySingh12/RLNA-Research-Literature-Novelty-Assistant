package com.rlna.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "papers")
@Getter
@Setter
@NoArgsConstructor
public class Paper {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "project_id", columnDefinition = "uuid")
    private UUID projectId;

    private String title;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "authors", columnDefinition = "text[]")
    private String[] authors;

    /** {@code abstract} is a Java keyword, so the field is renamed and mapped. */
    @Column(name = "abstract", columnDefinition = "text")
    private String abstractText;

    @Column(name = "publication_year")
    private Integer publicationYear;

    private String venue;

    private String doi;

    @Column(name = "external_id")
    private String externalId;

    /** Pointer into object storage. The PDF bytes never enter Postgres. */
    @Column(name = "storage_object_key")
    private String storageObjectKey;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_hash")
    private String contentHash;

    /** Set by the indexing pipeline when this paper closely matches an existing one. */
    @Column(name = "duplicate_of", columnDefinition = "uuid")
    private UUID duplicateOf;

    @Column(name = "duplicate_similarity")
    private Float duplicateSimilarity;

    @Column(name = "processing_status", nullable = false)
    private String processingStatus = "queued";

    /**
     * The indexing strategy behind this paper's chunks. Below
     * {@code PaperIndexingWorker.CURRENT_INDEX_VERSION} means the chunks predate
     * the current chunking rules and the paper is a re-index candidate.
     */
    @Column(name = "index_version", nullable = false)
    private int indexVersion = 0;

    @Column(name = "processing_error", columnDefinition = "text")
    private String processingError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb")
    private JsonNode summary;

    @Column(name = "reading_status", nullable = false)
    private String readingStatus = "to_read";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags = new String[0];

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
