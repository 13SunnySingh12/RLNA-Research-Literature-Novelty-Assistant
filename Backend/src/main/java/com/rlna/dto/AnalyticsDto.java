package com.rlna.dto;

import java.util.List;
import java.util.Map;

/**
 * Dashboard figures (Section 9.8). Everything is computed from stored rows, so
 * opening the dashboard costs no model calls.
 */
public record AnalyticsDto(
        long totalPapers,
        long totalChunks,
        Map<String, Long> papersByStatus,
        List<YearCount> papersByYear,
        List<ConceptCount> topConcepts,
        List<ConceptCount> methodologies,
        List<ConceptTrend> conceptTrends,
        Map<String, Long> analysesByType,
        double processingSuccessRate) {

    public record YearCount(int year, long count) {}

    public record ConceptCount(String concept, String conceptType, long paperCount) {}

    public record ConceptTrend(String concept, List<YearCount> byYear) {}
}
