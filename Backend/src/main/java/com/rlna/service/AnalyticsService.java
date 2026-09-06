package com.rlna.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rlna.dto.AnalyticsDto;
import com.rlna.repository.AnalysisResultRepository;
import com.rlna.repository.ConceptRepository;
import com.rlna.repository.PaperChunkRepository;
import com.rlna.repository.PaperRepository;

import lombok.RequiredArgsConstructor;

/**
 * Dashboard figures (Section 9.8).
 *
 * <p>Every number is aggregated from rows the system already has. Opening the
 * dashboard costs no model calls, which is what makes it safe to leave on the
 * landing screen.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int TOP_CONCEPT_LIMIT = 15;
    private static final int TREND_CONCEPT_LIMIT = 5;

    private final PaperRepository paperRepository;
    private final PaperChunkRepository chunkRepository;
    private final ConceptRepository conceptRepository;
    private final AnalysisResultRepository analysisRepository;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public AnalyticsDto forUser(UUID userId, UUID projectId) {
        if (projectId != null) {
            projectService.require(userId, projectId);
        }

        Map<String, Long> byStatus = toCountMap(paperRepository.countByStatus(userId, projectId));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        List<AnalyticsDto.YearCount> byYear = paperRepository.countByPublicationYear(userId, projectId).stream()
                .map(row -> new AnalyticsDto.YearCount(((Number) row[0]).intValue(), ((Number) row[1]).longValue()))
                .toList();

        List<AnalyticsDto.ConceptCount> allConcepts = conceptRepository.topConcepts(userId, projectId).stream()
                .map(row -> new AnalyticsDto.ConceptCount(
                        (String) row[0], (String) row[1], ((Number) row[2]).longValue()))
                .toList();

        List<AnalyticsDto.ConceptCount> topConcepts = allConcepts.stream()
                .limit(TOP_CONCEPT_LIMIT)
                .toList();
        List<AnalyticsDto.ConceptCount> methodologies = allConcepts.stream()
                .filter(c -> "method".equals(c.conceptType()))
                .limit(TOP_CONCEPT_LIMIT)
                .toList();

        return new AnalyticsDto(
                total,
                chunkRepository.countForUser(userId, projectId),
                byStatus,
                byYear,
                topConcepts,
                methodologies,
                trends(userId, projectId, topConcepts),
                toCountMap(analysisRepository.countByType(userId, projectId)),
                successRate(byStatus));
    }

    /**
     * Frequency of the leading concepts across publication years -- the chart
     * that answers "which topics are rising" rather than just "which are common".
     */
    private List<AnalyticsDto.ConceptTrend> trends(UUID userId, UUID projectId,
                                                   List<AnalyticsDto.ConceptCount> topConcepts) {
        List<String> tracked = topConcepts.stream()
                .filter(c -> "keyword".equals(c.conceptType()) || "method".equals(c.conceptType()))
                .limit(TREND_CONCEPT_LIMIT)
                .map(AnalyticsDto.ConceptCount::concept)
                .toList();
        if (tracked.isEmpty()) {
            return List.of();
        }

        Map<String, Map<Integer, Long>> grouped = new LinkedHashMap<>();
        tracked.forEach(c -> grouped.put(c, new LinkedHashMap<>()));
        for (Object[] row : conceptRepository.conceptsByYear(userId, projectId)) {
            String concept = (String) row[1];
            if (!grouped.containsKey(concept)) {
                continue;
            }
            grouped.get(concept).merge(((Number) row[0]).intValue(), ((Number) row[2]).longValue(), Long::sum);
        }

        List<AnalyticsDto.ConceptTrend> trends = new ArrayList<>();
        grouped.forEach((concept, years) -> {
            List<AnalyticsDto.YearCount> counts = years.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new AnalyticsDto.YearCount(e.getKey(), e.getValue()))
                    .toList();
            if (!counts.isEmpty()) {
                trends.add(new AnalyticsDto.ConceptTrend(concept, counts));
            }
        });
        return trends;
    }

    /**
     * Share of indexing attempts that succeeded. Papers still queued or holding
     * metadata only are excluded: they have not succeeded or failed yet, and
     * counting them either way would misreport the pipeline's reliability.
     */
    private static double successRate(Map<String, Long> byStatus) {
        long completed = byStatus.getOrDefault("completed", 0L);
        long failed = byStatus.getOrDefault("failed", 0L);
        long finished = completed + failed;
        return finished == 0 ? 1.0 : (double) completed / finished;
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }
}
