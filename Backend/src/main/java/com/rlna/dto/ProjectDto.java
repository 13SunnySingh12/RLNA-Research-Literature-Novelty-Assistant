package com.rlna.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.rlna.entity.Project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectDto(
        UUID id,
        String name,
        String description,
        String researchTopic,
        long paperCount,
        Map<String, Long> paperStatusCounts,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProjectDto from(Project p, long paperCount, Map<String, Long> statusCounts) {
        return new ProjectDto(p.getId(), p.getName(), p.getDescription(), p.getResearchTopic(),
                paperCount, statusCounts, p.getCreatedAt(), p.getUpdatedAt());
    }

    public record CreateRequest(
            @NotBlank(message = "Give the project a name.")
            @Size(max = 255, message = "Project names are limited to 255 characters.")
            String name,

            @Size(max = 5000) String description,
            @Size(max = 500) String researchTopic) {}

    public record UpdateRequest(
            @Size(min = 1, max = 255, message = "Project names are limited to 255 characters.")
            String name,
            @Size(max = 5000) String description,
            @Size(max = 500) String researchTopic) {}
}
