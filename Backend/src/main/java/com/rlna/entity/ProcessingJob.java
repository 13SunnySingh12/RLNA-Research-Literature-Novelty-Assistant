package com.rlna.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

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

/**
 * Durable state for a long-running operation (Section 24.2).
 *
 * <p>Progress lives in the database rather than in the worker thread, so a
 * restart, a browser refresh, or a closed tab cannot lose it.
 */
@Entity
@Table(name = "processing_jobs")
@Getter
@Setter
@NoArgsConstructor
public class ProcessingJob {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "paper_id", nullable = false, columnDefinition = "uuid")
    private UUID paperId;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(nullable = false)
    private String status = STATUS_QUEUED;

    @Column(name = "current_step")
    private String currentStep;

    @Column(nullable = false)
    private Integer progress = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private Integer attempts = 0;

    /** False when the failure is inherent to the file, so retrying cannot help. */
    @Column(nullable = false)
    private boolean retryable = true;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

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
