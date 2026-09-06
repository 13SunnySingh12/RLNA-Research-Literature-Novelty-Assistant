package com.rlna.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.client.AiServiceClient;
import com.rlna.dto.SearchDto;
import com.rlna.repository.PaperChunkRepository;

import lombok.RequiredArgsConstructor;

/**
 * Retrieval (Section 13.2).
 *
 * <p>Semantic search is delegated to the AI service, which owns the embedding
 * model and the pgvector query. Keyword search stays here because it is pure
 * SQL and needs no model at all.
 *
 * <p>In both paths the owner filter is part of the same statement as the
 * ranking, never a filter applied to results afterwards (Section 13.3, rule 2).
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_KEYWORD_RESULTS = 50;

    private final AiServiceClient aiServiceClient;
    private final PaperChunkRepository chunkRepository;
    private final ProjectService projectService;
    private final PaperService paperService;

    @Transactional(readOnly = true)
    public SearchDto.Results semantic(UUID userId, SearchDto.SemanticRequest request) {
        if (request.projectId() != null) {
            projectService.require(userId, request.projectId());
        }
        if (request.paperId() != null) {
            paperService.require(userId, request.paperId());
        }
        JsonNode response = aiServiceClient.post("/internal/retrieve", retrievalRequest(userId, request));
        return new SearchDto.Results(request.query(), parseHits(response),
                response.path("corpus_size").asInt(),
                response.path("evidence_sufficient").asBoolean(true));
    }

    /**
     * The retrieval payload used by both search and analysis. {@code task}
     * selects the section weighting profile applied to the similarity score
     * (Section 9.1).
     */
    Map<String, Object> retrievalRequest(UUID userId, SearchDto.SemanticRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("user_id", userId.toString());
        body.put("project_id", request.projectId() == null ? null : request.projectId().toString());
        body.put("paper_id", request.paperId() == null ? null : request.paperId().toString());
        body.put("query", request.query());
        body.put("top_k", request.topKOrDefault(DEFAULT_TOP_K));
        body.put("task", request.task());
        body.put("year", request.year());
        body.put("tag", request.tag());
        return body;
    }

    JsonNode retrieve(UUID userId, SearchDto.SemanticRequest request) {
        return aiServiceClient.post("/internal/retrieve", retrievalRequest(userId, request));
    }

    static List<SearchDto.Hit> parseHits(JsonNode response) {
        List<SearchDto.Hit> hits = new ArrayList<>();
        for (JsonNode node : response.path("chunks")) {
            hits.add(new SearchDto.Hit(
                    UUID.fromString(node.path("chunk_id").asText()),
                    UUID.fromString(node.path("paper_id").asText()),
                    node.path("paper_title").asText(null),
                    node.path("publication_year").isInt() ? node.path("publication_year").asInt() : null,
                    node.path("section_type").asText(null),
                    node.path("chunk_index").isInt() ? node.path("chunk_index").asInt() : null,
                    node.path("content").asText(""),
                    node.path("score").asDouble()));
        }
        return hits;
    }

    @Transactional(readOnly = true)
    public SearchDto.Results keyword(UUID userId, UUID projectId, String query, Integer limit) {
        if (projectId != null) {
            projectService.require(userId, projectId);
        }
        int cap = limit == null ? 20 : Math.min(limit, MAX_KEYWORD_RESULTS);
        List<SearchDto.Hit> hits = new ArrayList<>();
        for (Object[] row : chunkRepository.keywordSearch(userId, projectId, query, cap)) {
            hits.add(new SearchDto.Hit(
                    (UUID) row[0],
                    (UUID) row[1],
                    (String) row[6],
                    null,
                    (String) row[2],
                    row[3] == null ? null : ((Number) row[3]).intValue(),
                    (String) row[4],
                    ((Number) row[5]).doubleValue()));
        }
        return new SearchDto.Results(query, hits, hits.size(), !hits.isEmpty());
    }
}
