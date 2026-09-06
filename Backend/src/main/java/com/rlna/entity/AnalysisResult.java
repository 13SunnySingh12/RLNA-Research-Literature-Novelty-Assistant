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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stored AI result. Doubles as the analysis history and as the result cache:
 * {@code cacheKey} is a fingerprint of the request, so an identical request
 * finds this row instead of paying for the model call again (Section 25.2).
 */
@Entity
@Table(name = "analysis_results")
@Getter
@Setter
@NoArgsConstructor
public class AnalysisResult {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "project_id", columnDefinition = "uuid")
    private UUID projectId;

    @Column(name = "paper_id", columnDefinition = "uuid")
    private UUID paperId;

    @Column(name = "analysis_type", nullable = false)
    private String analysisType;

    @Column(name = "input_query", columnDefinition = "text")
    private String inputQuery;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", columnDefinition = "jsonb")
    private JsonNode evidenceRefs;

    @Column(name = "provider_used")
    private String providerUsed;

    @Column(name = "model_id")
    private String modelId;

    private String confidence;

    @Column(name = "cache_key", nullable = false, length = 64)
    private String cacheKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
