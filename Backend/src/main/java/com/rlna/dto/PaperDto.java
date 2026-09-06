package com.rlna.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.entity.Paper;

import jakarta.validation.constraints.Size;

public record PaperDto(
        UUID id,
        UUID projectId,
        String title,
        List<String> authors,
        String abstractText,
        Integer publicationYear,
        String venue,
        String doi,
        String externalId,
        Long fileSize,
        boolean hasFile,
        String processingStatus,
        String processingError,
        JsonNode summary,
        String readingStatus,
        List<String> tags,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static PaperDto from(Paper p) {
        return new PaperDto(
                p.getId(), p.getProjectId(), p.getTitle(),
                p.getAuthors() == null ? List.of() : List.of(p.getAuthors()),
                p.getAbstractText(), p.getPublicationYear(), p.getVenue(), p.getDoi(),
                p.getExternalId(), p.getFileSize(), p.getStorageObjectKey() != null,
                p.getProcessingStatus(), p.getProcessingError(), p.getSummary(),
                p.getReadingStatus(),
                p.getTags() == null ? List.of() : List.of(p.getTags()),
                p.getNotes(), p.getCreatedAt(), p.getUpdatedAt());
    }

    /** Library rows do not need the abstract, the summary, or the notes. */
    public static PaperDto summaryOf(Paper p) {
        return new PaperDto(
                p.getId(), p.getProjectId(), p.getTitle(),
                p.getAuthors() == null ? List.of() : List.of(p.getAuthors()),
                null, p.getPublicationYear(), p.getVenue(), p.getDoi(),
                p.getExternalId(), p.getFileSize(), p.getStorageObjectKey() != null,
                p.getProcessingStatus(), p.getProcessingError(), null,
                p.getReadingStatus(),
                p.getTags() == null ? List.of() : List.of(p.getTags()),
                null, p.getCreatedAt(), p.getUpdatedAt());
    }

    public record Detail(PaperDto paper, List<SectionDto> sections, List<ConceptDto> concepts, long chunkCount) {}

    public record SectionDto(UUID id, String sectionType, String heading, int orderIndex, int characterCount) {}

    public record ConceptDto(String concept, String conceptType, Float confidence) {}

    /** Only the fields a user may edit on their own paper. */
    public record UpdateRequest(
            @Size(max = 1000) String title,
            @Size(max = 20, message = "A paper can carry at most 20 tags.") List<@Size(max = 60) String> tags,
            String readingStatus,
            @Size(max = 20000) String notes,
            UUID projectId) {}

    /** Returned when an upload matches a paper already in the library (Section 9.6). */
    public record DuplicateWarning(UUID existingPaperId, String existingTitle, String reason, Double similarity) {}

    public record UploadResult(PaperDto paper, DuplicateWarning duplicateOf) {}
}
