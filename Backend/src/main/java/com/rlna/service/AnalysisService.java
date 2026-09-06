package com.rlna.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rlna.client.AiServiceClient;
import com.rlna.config.AppProperties;
import com.rlna.dto.AcademicDto;
import com.rlna.dto.AnalysisDto;
import com.rlna.dto.PageResponse;
import com.rlna.dto.SearchDto;
import com.rlna.entity.AnalysisResult;
import com.rlna.entity.Paper;
import com.rlna.exception.ApiException;
import com.rlna.repository.AnalysisResultRepository;
import com.rlna.repository.ConceptRepository;
import com.rlna.repository.PaperRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Every AI-backed analysis, and the cache that stops the same question being
 * paid for twice (Sections 11, 14, 15, 25).
 *
 * <p>The shape is the same in each case: retrieve evidence cheaply (embeddings
 * run locally and cost nothing), fingerprint the request against that evidence,
 * and only call a model when the fingerprint has not been seen before.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final int SUMMARY_TOP_K = 24;
    private static final int COMPARE_TOP_K_PER_PAPER = 12;
    private static final int PROJECT_TOP_K = 30;

    private final AiServiceClient aiServiceClient;
    private final SearchService searchService;
    private final PaperService paperService;
    private final ProjectService projectService;
    private final AcademicSearchService academicSearchService;
    private final PaperRepository paperRepository;
    private final ConceptRepository conceptRepository;
    private final AnalysisResultRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    // ------------------------------------------------------------------
    // Paper-level analyses
    // ------------------------------------------------------------------

    /**
     * Structured paper summary (Section 8.6). Generated once and reused; a fresh
     * one is produced only when the user explicitly asks for it.
     */
    @Transactional
    public AnalysisDto summary(UUID userId, UUID paperId, boolean refresh) {
        Paper paper = requireIndexed(userId, paperId);
        if (reuseStored(refresh) && paper.getSummary() != null) {
            return analysisRepository
                    .findByUserIdAndPaperIdAndAnalysisType(userId, paperId, "summary").stream()
                    .findFirst()
                    .map(a -> AnalysisDto.from(a, true))
                    .orElseGet(() -> storedSummaryAsDto(paper));
        }

        String query = retrievalQueryFor(paper);
        JsonNode retrieval = retrieve(userId, query, null, paperId, "summary", SUMMARY_TOP_K);
        List<UUID> chunkIds = chunkIdsOf(retrieval);
        requireEvidence(retrieval, chunkIds, "There is no indexed text for this paper yet.",
                "This paper's indexed text is too thin to work from. Try reprocessing it.");

        AnalysisDto dto = execute(userId, "summary", null, paperId, query,
                retrieval, chunkIds, "/internal/summarize",
                Map.of("paper_id", paperId.toString(), "user_id", userId.toString(),
                        "chunk_ids", asStrings(chunkIds)), refresh);

        // Mirrored onto the paper so the library can show it without a join.
        paper.setSummary(dto.result());
        paperRepository.save(paper);
        return dto;
    }

    /** Keyword / method / dataset extraction. Uses the fast tier (Section 12.2). */
    @Transactional
    public AnalysisDto concepts(UUID userId, UUID paperId, boolean refresh) {
        Paper paper = requireIndexed(userId, paperId);
        if (reuseStored(refresh) && !conceptRepository.findByPaperId(paperId).isEmpty()) {
            return analysisRepository
                    .findByUserIdAndPaperIdAndAnalysisType(userId, paperId, "concepts").stream()
                    .findFirst()
                    .map(a -> AnalysisDto.from(a, true))
                    .orElseGet(() -> conceptsAsDto(paperId));
        }
        String query = retrievalQueryFor(paper);
        JsonNode retrieval = retrieve(userId, query, null, paperId, "keyword_extraction", SUMMARY_TOP_K);
        List<UUID> chunkIds = chunkIdsOf(retrieval);
        requireEvidence(retrieval, chunkIds, "There is no indexed text for this paper yet.",
                "This paper's indexed text is too thin to work from. Try reprocessing it.");

        return execute(userId, "concepts", paper.getProjectId(), paperId, query,
                retrieval, chunkIds, "/internal/extract-concepts",
                Map.of("paper_id", paperId.toString(), "user_id", userId.toString(),
                        "chunk_ids", asStrings(chunkIds)), refresh);
    }

    /** Grounded question answering over one paper (Section 8.7). */
    @Transactional
    public AnalysisDto askPaper(UUID userId, UUID paperId, AnalysisDto.QuestionRequest request) {
        Paper paper = requireIndexed(userId, paperId);
        return ask(userId, paper.getProjectId(), paperId, request);
    }

    /** Grounded question answering across a whole project. */
    @Transactional
    public AnalysisDto askProject(UUID userId, UUID projectId, AnalysisDto.QuestionRequest request) {
        projectService.require(userId, projectId);
        return ask(userId, projectId, null, request);
    }

    /**
     * Grounded question answering.
     *
     * <p>The relevance floor is applied to a project-wide question but not to one
     * scoped to a single paper. The floor exists to answer "your library does not
     * contain this", which is a real and useful answer across a corpus. Against
     * one paper the user has already chosen, it measures the wrong thing: the
     * embedding model scores by meaning, so a contentless question like "what
     * problem does this paper address?" sits far from every chunk and gets
     * refused even though the paper plainly states its problem.
     *
     * <p>Nothing is loosened by this. The retrieved passages still come only from
     * that paper, the model is still instructed that refusing is a correct
     * answer, it still returns {@code evidence_sufficient}, and claims citing a
     * chunk that was not retrieved are still dropped.
     */
    private AnalysisDto ask(UUID userId, UUID projectId, UUID paperId, AnalysisDto.QuestionRequest request) {
        int topK = request.topK() == null ? 8 : Math.clamp(request.topK(), 1, 20);
        JsonNode retrieval = retrieve(userId, request.question(),
                paperId == null ? projectId : null, paperId, "qa", topK);
        List<UUID> chunkIds = chunkIdsOf(retrieval);

        if (paperId == null) {
            requireEvidence(retrieval, chunkIds,
                    "There is nothing indexed in your library yet. Upload a paper and try again.",
                    "Nothing in your library is close enough to that question to answer it from "
                            + "evidence. Try rewording it, or add papers on the topic.");
        } else if (chunkIds.isEmpty()) {
            throw ApiException.unprocessable("INSUFFICIENT_EVIDENCE",
                    "This paper has no indexed text to answer from.");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("question", request.question());
        payload.put("chunk_ids", asStrings(chunkIds));
        payload.put("user_id", userId.toString());
        return execute(userId, "qa", projectId, paperId, request.question(),
                retrieval, chunkIds, "/internal/question", payload,
                Boolean.TRUE.equals(request.refresh()));
    }

    // ------------------------------------------------------------------
    // Multi-paper analyses
    // ------------------------------------------------------------------

    /** Aligned comparison across two or three papers (Section 8.8). */
    @Transactional
    public AnalysisDto compare(UUID userId, AnalysisDto.CompareRequest request) {
        List<UUID> distinct = request.paperIds().stream().distinct().toList();
        if (distinct.size() < 2) {
            throw ApiException.badRequest("TOO_FEW_PAPERS", "Choose at least two different papers to compare.");
        }
        List<Map<String, Object>> papers = new ArrayList<>();
        Set<UUID> allChunkIds = new LinkedHashSet<>();
        UUID projectId = null;
        String modelId = null;
        int corpusSize = 0;

        for (UUID paperId : distinct) {
            Paper paper = requireIndexed(userId, paperId);
            projectId = projectId == null ? paper.getProjectId() : projectId;
            JsonNode retrieval = retrieve(userId, retrievalQueryFor(paper), null, paperId,
                    "comparison", COMPARE_TOP_K_PER_PAPER);
            List<UUID> chunkIds = chunkIdsOf(retrieval);
            if (chunkIds.isEmpty()) {
                throw ApiException.unprocessable("INSUFFICIENT_EVIDENCE",
                        "\"" + paper.getTitle() + "\" has no indexed text yet, so it cannot be compared.");
            }
            modelId = retrieval.path("model_id").asText(modelId);
            corpusSize += chunkIds.size();
            allChunkIds.addAll(chunkIds);
            papers.add(Map.of(
                    "paper_id", paperId.toString(),
                    "title", paper.getTitle() == null ? "Untitled paper" : paper.getTitle(),
                    "chunk_ids", asStrings(chunkIds)));
        }

        String query = distinct.stream().map(UUID::toString).sorted().reduce("", (a, b) -> a + "|" + b);
        Map<String, Object> payload = Map.of("papers", papers, "user_id", userId.toString());
        return executeWithEvidence(userId, "comparison", projectId, null, query,
                modelId, List.copyOf(allChunkIds), corpusSize, "/internal/compare", payload,
                Boolean.TRUE.equals(request.refresh()));
    }

    /** Research gap identification (Section 14). Weighted to limitations and future work. */
    @Transactional
    public AnalysisDto researchGap(UUID userId, AnalysisDto.ProjectAnalysisRequest request) {
        var project = projectService.require(userId, request.projectId());
        String query = StringUtils.hasText(project.getResearchTopic())
                ? project.getResearchTopic()
                : "limitations, open problems and future work";

        JsonNode retrieval = retrieve(userId, query, request.projectId(), null,
                "research_gap", PROJECT_TOP_K);
        List<UUID> chunkIds = chunkIdsOf(retrieval);
        requireEvidence(retrieval, chunkIds,
                "This project has no indexed material yet. Add papers and try again.",
                "The papers in this project do not discuss limitations or future work close "
                        + "enough to its research topic to derive gaps from. Adjust the topic, "
                        + "or add papers in that area.");

        Map<String, Object> payload = new HashMap<>();
        payload.put("chunk_ids", asStrings(chunkIds));
        payload.put("user_id", userId.toString());
        payload.put("project_id", request.projectId().toString());
        payload.put("corpus_size", paperCount(userId, request.projectId()));
        return execute(userId, "research_gap", request.projectId(), null, query,
                retrieval, chunkIds, "/internal/research-gap", payload,
                Boolean.TRUE.equals(request.refresh()));
    }

    /** Literature synthesis across a project (Section 9.5). */
    @Transactional
    public AnalysisDto literatureReview(UUID userId, AnalysisDto.ProjectAnalysisRequest request) {
        var project = projectService.require(userId, request.projectId());
        String query = StringUtils.hasText(project.getResearchTopic())
                ? project.getResearchTopic()
                : project.getName();

        JsonNode retrieval = retrieve(userId, query, request.projectId(), null,
                "literature_review", PROJECT_TOP_K);
        List<UUID> chunkIds = chunkIdsOf(retrieval);
        requireEvidence(retrieval, chunkIds,
                "This project has no indexed papers yet. Upload a few and try again.",
                "The papers in this project are not a close enough match to \"" + query
                        + "\" to review. Adjust the project's research topic, or add papers "
                        + "on that subject.");

        Map<String, Object> payload = new HashMap<>();
        payload.put("chunk_ids", asStrings(chunkIds));
        payload.put("user_id", userId.toString());
        payload.put("project_id", request.projectId().toString());
        payload.put("topic", query);
        payload.put("corpus_size", paperCount(userId, request.projectId()));
        return execute(userId, "literature_review", request.projectId(), null, query,
                retrieval, chunkIds, "/internal/literature-review", payload,
                Boolean.TRUE.equals(request.refresh()));
    }

    /**
     * Novelty assessment (Section 15).
     *
     * <p>The corpus size travels with the request because the honesty rules
     * depend on it: confidence is lowered for a small corpus, and the result
     * always states how many papers it was drawn from.
     */
    @Transactional
    public AnalysisDto novelty(UUID userId, AnalysisDto.NoveltyRequest request) {
        projectService.require(userId, request.projectId());
        JsonNode retrieval = retrieve(userId, request.idea(), request.projectId(), null,
                "novelty", PROJECT_TOP_K);
        List<UUID> chunkIds = chunkIdsOf(retrieval);

        List<AcademicDto> external = List.of();
        if (!Boolean.FALSE.equals(request.includeAcademicSearch())) {
            external = academicSearchService.searchQuietly(request.idea(), 8);
        }
        if (chunkIds.isEmpty() && external.isEmpty()) {
            throw ApiException.unprocessable("INSUFFICIENT_EVIDENCE",
                    "There is nothing to compare this idea against yet. Add papers to the project "
                            + "or try again when external search is available.");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("idea", request.idea());
        payload.put("chunk_ids", asStrings(chunkIds));
        payload.put("user_id", userId.toString());
        payload.put("project_id", request.projectId().toString());
        payload.put("library_corpus_size", paperCount(userId, request.projectId()));
        payload.put("academic_results", external.stream().map(this::academicPayload).toList());

        // External results are part of the evidence, so they belong in the
        // fingerprint: the same idea checked against different literature is a
        // different question and must not be served from cache.
        List<String> evidenceKeyParts = new ArrayList<>(asStrings(chunkIds));
        external.forEach(a -> evidenceKeyParts.add("ext:" + a.externalId()));

        return executeWithEvidenceKeys(userId, "novelty", request.projectId(), null, request.idea(),
                retrieval.path("model_id").asText(null), chunkIds, evidenceKeyParts,
                paperCount(userId, request.projectId()), "/internal/novelty", payload,
                Boolean.TRUE.equals(request.refresh()));
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<AnalysisDto> history(UUID userId, String type, UUID projectId, UUID paperId,
                                             int page, int size) {
        return PageResponse.from(
                analysisRepository.history(userId, blankToNull(type), projectId, paperId,
                        PageRequest.of(page, Math.min(size, 100))),
                a -> AnalysisDto.from(a, false));
    }

    @Transactional(readOnly = true)
    public AnalysisDto get(UUID userId, UUID analysisId) {
        return AnalysisDto.from(requireAnalysis(userId, analysisId), false);
    }

    @Transactional(readOnly = true)
    public AnalysisResult requireAnalysis(UUID userId, UUID analysisId) {
        return analysisRepository.findByIdAndUserId(analysisId, userId)
                .orElseThrow(() -> ApiException.notFound("analysis"));
    }

    @Transactional
    public void delete(UUID userId, UUID analysisId) {
        analysisRepository.delete(requireAnalysis(userId, analysisId));
    }

    // ------------------------------------------------------------------
    // Shared machinery
    // ------------------------------------------------------------------

    private JsonNode retrieve(UUID userId, String query, UUID projectId, UUID paperId,
                              String task, int topK) {
        return searchService.retrieve(userId, new SearchDto.SemanticRequest(
                query, projectId, paperId, null, null, topK, task));
    }

    private AnalysisDto execute(UUID userId, String type, UUID projectId, UUID paperId, String query,
                                JsonNode retrieval, List<UUID> chunkIds, String endpoint,
                                Map<String, Object> payload, boolean refresh) {
        return executeWithEvidence(userId, type, projectId, paperId, query,
                retrieval.path("model_id").asText(null), chunkIds,
                retrieval.path("corpus_size").asInt(chunkIds.size()), endpoint, payload, refresh);
    }

    private AnalysisDto executeWithEvidence(UUID userId, String type, UUID projectId, UUID paperId,
                                            String query, String modelId, List<UUID> chunkIds,
                                            int corpusSize, String endpoint,
                                            Map<String, Object> payload, boolean refresh) {
        return executeWithEvidenceKeys(userId, type, projectId, paperId, query, modelId, chunkIds,
                asStrings(chunkIds), corpusSize, endpoint, payload, refresh);
    }

    /**
     * The cache-then-call path (Section 25.2).
     *
     * <p>The fingerprint covers the task, the model, the normalized question and
     * the exact evidence. Change any of those and it is a different request;
     * change none of them and no model is called at all.
     */
    private AnalysisDto executeWithEvidenceKeys(UUID userId, String type, UUID projectId, UUID paperId,
                                                String query, String modelId, List<UUID> chunkIds,
                                                Collection<String> evidenceKeys, int corpusSize,
                                                String endpoint, Map<String, Object> payload,
                                                boolean refresh) {
        String cacheKey = cacheKey(type, modelId, query, evidenceKeys);
        boolean useCache = reuseStored(refresh);
        Optional<AnalysisResult> cached = analysisRepository.findByUserIdAndCacheKey(userId, cacheKey);
        if (cached.isPresent() && useCache) {
            log.debug("Cache hit for {} ({})", type, cacheKey);
            return AnalysisDto.from(cached.get(), true);
        }

        Map<String, Object> body = new HashMap<>(payload);
        body.put("corpus_size", corpusSize);
        JsonNode response = aiServiceClient.post(endpoint, body);

        if (!response.path("evidence_sufficient").asBoolean(true)) {
            throw ApiException.unprocessable("INSUFFICIENT_EVIDENCE",
                    "There is not enough in your library to answer this reliably. "
                            + "Try adding related papers.");
        }

        AnalysisResult entity = cached.orElseGet(AnalysisResult::new);
        entity.setUserId(userId);
        entity.setProjectId(projectId);
        entity.setPaperId(paperId);
        entity.setAnalysisType(type);
        entity.setInputQuery(query);
        entity.setResult(response.path("result").isMissingNode() ? response : response.path("result"));
        entity.setEvidenceRefs(evidenceNode(chunkIds, corpusSize));
        entity.setProviderUsed(response.path("provider").asText(null));
        entity.setModelId(response.path("model_id").asText(modelId));
        entity.setConfidence(response.path("confidence").asText(null));
        entity.setCacheKey(cacheKey);

        try {
            return AnalysisDto.from(analysisRepository.saveAndFlush(entity), false);
        } catch (DataIntegrityViolationException e) {
            // Two identical requests raced. The unique fingerprint decided the
            // winner; return the committed result rather than failing the user.
            return analysisRepository.findByUserIdAndCacheKey(userId, cacheKey)
                    .map(a -> AnalysisDto.from(a, true))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Whether a stored result may be served instead of calling a provider.
     *
     * <p>AI_CACHE_ENABLED lets a deployment force every request through to the
     * model, which is what you want while comparing model output. Summary and
     * concepts short-circuit on their own stored output before they ever reach
     * the analysis cache, so the switch has to be honoured in all three places:
     * gating only the cache lookup left two paths still serving a stored answer.
     */
    private boolean reuseStored(boolean refresh) {
        return !refresh && properties.aiService().cacheEnabled();
    }

    static String cacheKey(String type, String modelId, String query, Collection<String> evidenceKeys) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase().replaceAll("\\s+", " ");
        String evidence = evidenceKeys.stream().sorted().reduce("", (a, b) -> a + "," + b);
        String material = type + "|" + (modelId == null ? "" : modelId) + "|" + normalizedQuery + "|" + evidence;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private JsonNode evidenceNode(List<UUID> chunkIds, int corpusSize) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("corpus_size", corpusSize);
        var array = node.putArray("chunk_ids");
        chunkIds.forEach(id -> array.add(id.toString()));
        return node;
    }

    private Paper requireIndexed(UUID userId, UUID paperId) {
        Paper paper = paperService.require(userId, paperId);
        if (!"completed".equals(paper.getProcessingStatus())) {
            throw ApiException.unprocessable("PAPER_NOT_INDEXED",
                    "This paper has not finished indexing yet. Try again once it is ready.");
        }
        return paper;
    }

    private void requireEvidence(JsonNode retrieval, List<UUID> chunkIds, String message) {
        requireEvidence(retrieval, chunkIds, message, message);
    }

    /**
     * Refuses before the model call when the evidence will not support an answer.
     *
     * <p>Refusing is the point: an answer with no evidence behind it is worse
     * than no answer (Section 13.3, rule 7). The two ways of failing get
     * different messages because they need different actions from the reader.
     * Nothing indexed means add papers; papers that exist but do not match the
     * query means change the query, and telling that reader to upload more is
     * advice that cannot work.
     */
    private void requireEvidence(JsonNode retrieval, List<UUID> chunkIds,
                                 String emptyMessage, String irrelevantMessage) {
        if (chunkIds.isEmpty()) {
            throw ApiException.unprocessable("INSUFFICIENT_EVIDENCE", emptyMessage);
        }
        if (!retrieval.path("evidence_sufficient").asBoolean(true)) {
            throw ApiException.unprocessable("INSUFFICIENT_EVIDENCE", irrelevantMessage);
        }
    }

    private static List<UUID> chunkIdsOf(JsonNode retrieval) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode chunk : retrieval.path("chunks")) {
            ids.add(UUID.fromString(chunk.path("chunk_id").asText()));
        }
        return ids;
    }

    private static List<String> asStrings(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    private static String retrievalQueryFor(Paper paper) {
        String title = paper.getTitle() == null ? "" : paper.getTitle();
        String abs = paper.getAbstractText() == null ? "" : paper.getAbstractText();
        String combined = (title + " " + abs).trim();
        return combined.isEmpty() ? "summary of this paper" : combined.substring(0, Math.min(1500, combined.length()));
    }

    private int paperCount(UUID userId, UUID projectId) {
        return (int) paperRepository.countByUserIdAndProjectId(userId, projectId);
    }

    private Map<String, Object> academicPayload(AcademicDto dto) {
        Map<String, Object> map = new HashMap<>();
        map.put("external_id", dto.externalId());
        map.put("title", dto.title());
        map.put("abstract", dto.abstractText());
        map.put("year", dto.year());
        map.put("venue", dto.venue());
        return map;
    }

    private AnalysisDto storedSummaryAsDto(Paper paper) {
        return new AnalysisDto(null, "summary", paper.getProjectId(), paper.getId(), null,
                paper.getSummary(), null, null, true, paper.getUpdatedAt());
    }

    private AnalysisDto conceptsAsDto(UUID paperId) {
        ObjectNode node = objectMapper.createObjectNode();
        var array = node.putArray("concepts");
        conceptRepository.findByPaperId(paperId).forEach(c -> {
            ObjectNode item = array.addObject();
            item.put("concept", c.getConcept());
            item.put("concept_type", c.getConceptType());
            if (c.getConfidence() != null) {
                item.put("confidence", c.getConfidence());
            }
        });
        return new AnalysisDto(null, "concepts", null, paperId, null, node, null, null, true, null);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
