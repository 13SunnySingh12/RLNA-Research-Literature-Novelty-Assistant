package com.rlna.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchDto() {

    public record SemanticRequest(
            @NotBlank(message = "Enter something to search for.")
            @Size(max = 2000, message = "Search queries are limited to 2000 characters.")
            String query,

            UUID projectId,
            UUID paperId,
            Integer year,
            String tag,

            @Min(1) @Max(50)
            Integer topK,

            /**
             * Task label that selects a section weighting profile
             * (Section 9.1). Null means unweighted.
             */
            String task) {

        public int topKOrDefault(int fallback) {
            return topK == null ? fallback : topK;
        }
    }

    /** One retrieved chunk, with everything needed to show where it came from. */
    public record Hit(
            UUID chunkId,
            UUID paperId,
            String paperTitle,
            Integer publicationYear,
            String sectionType,
            Integer chunkIndex,
            String content,
            double score) {}

    public record Results(String query, List<Hit> hits, int corpusSize, boolean sufficientEvidence) {}
}
