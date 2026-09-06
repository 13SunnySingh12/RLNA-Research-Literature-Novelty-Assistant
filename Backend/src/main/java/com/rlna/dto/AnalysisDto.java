package com.rlna.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.entity.AnalysisResult;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AnalysisDto(
        UUID id,
        String analysisType,
        UUID projectId,
        UUID paperId,
        String inputQuery,
        JsonNode result,
        JsonNode evidenceRefs,
        String confidence,
        boolean fromCache,
        OffsetDateTime createdAt) {

    /**
     * Note what is absent: {@code providerUsed} and {@code modelId} are stored
     * for operational analysis but never leave the server. The user is not
     * shown which provider answered (Section 12.4).
     */
    public static AnalysisDto from(AnalysisResult a, boolean fromCache) {
        return new AnalysisDto(a.getId(), a.getAnalysisType(), a.getProjectId(), a.getPaperId(),
                a.getInputQuery(), a.getResult(), a.getEvidenceRefs(), a.getConfidence(),
                fromCache, a.getCreatedAt());
    }

    public record QuestionRequest(
            @NotBlank(message = "Type a question first.")
            @Size(max = 2000, message = "Questions are limited to 2000 characters.")
            String question,
            Integer topK,
            Boolean refresh) {}

    public record CompareRequest(
            @NotNull @Size(min = 2, max = 3, message = "Choose two or three papers to compare.")
            List<UUID> paperIds,
            Boolean refresh) {}

    public record ProjectAnalysisRequest(
            @NotNull(message = "Choose a project.") UUID projectId,
            Boolean refresh) {}

    public record NoveltyRequest(
            @NotNull(message = "Choose a project.") UUID projectId,

            @NotBlank(message = "Describe the idea you want checked.")
            @Size(min = 20, max = 4000,
                  message = "Describe the idea in at least 20 and at most 4000 characters.")
            String idea,

            /** Also search the free academic APIs, not just the local library. */
            Boolean includeAcademicSearch,
            Boolean refresh) {}

    public record SummaryRequest(Boolean refresh) {}
}
