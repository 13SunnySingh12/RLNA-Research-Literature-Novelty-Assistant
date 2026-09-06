package com.rlna.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A record returned by the external academic API (Section 8.9). */
public record AcademicDto(
        String externalId,
        String title,
        List<String> authors,
        String abstractText,
        Integer year,
        String venue,
        String doi,
        String openAccessUrl,
        Integer citedByCount) {

    public record ImportRequest(
            @NotNull(message = "Choose a project to import into.") UUID projectId,
            @NotBlank String externalId,
            @Size(max = 1000) String title,
            List<String> authors,
            String abstractText,
            Integer year,
            @Size(max = 500) String venue,
            @Size(max = 255) String doi) {}

    public record SearchResults(String query, List<AcademicDto> results, boolean degraded, String notice) {}
}
