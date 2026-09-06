package com.rlna.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A chunk as the application layer sees it.
 *
 * <p>The {@code embedding} column is deliberately not mapped. Vector generation
 * and vector retrieval belong to the AI service (Section 7); Spring Boot only
 * ever needs a chunk's text and provenance in order to render evidence.
 */
@Entity
@Table(name = "paper_chunks")
@Getter
@Setter
@NoArgsConstructor
public class PaperChunk {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "paper_id", nullable = false, columnDefinition = "uuid")
    private UUID paperId;

    @Column(name = "section_id", columnDefinition = "uuid")
    private UUID sectionId;

    @Column(name = "section_type")
    private String sectionType;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "embedding_model")
    private String embeddingModel;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
