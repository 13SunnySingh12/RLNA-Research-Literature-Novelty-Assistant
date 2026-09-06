package com.rlna.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.config.AppProperties;
import com.rlna.dto.AcademicDto;
import com.rlna.entity.Paper;
import com.rlna.exception.ApiException;
import com.rlna.repository.PaperRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Discovery through a free academic API (Section 8.9).
 *
 * <p>OpenAlex is used because it needs no key and its polite pool only asks for
 * a contact address, which keeps the project deployable on free tiers.
 *
 * <p>The service is treated as optional infrastructure throughout: when it is
 * unreachable the caller is told external search is unavailable and the rest of
 * the application carries on with the local library (Section 27).
 */
@Slf4j
@Service
public class AcademicSearchService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_RESULTS = 25;

    /** Words that rule a query out as a person's name, however it is capitalised. */
    private static final java.util.Set<String> SUBJECT_WORDS = java.util.Set.of(
            "learning", "network", "networks", "model", "models", "deep", "neural",
            "attention", "transformer", "transformers", "language", "vision", "graph",
            "data", "search", "analysis", "review", "survey", "study", "method",
            "methods", "system", "systems", "generation", "retrieval", "embedding",
            "embeddings", "large", "machine", "quantum", "climate", "cancer");

    private record CacheEntry(List<AcademicDto> results, Instant expiresAt) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final RestClient restClient;
    private final AppProperties.Academic config;
    private final PaperRepository paperRepository;
    private final ProjectService projectService;

    public AcademicSearchService(AppProperties properties,
                                 PaperRepository paperRepository,
                                 ProjectService projectService) {
        this.config = properties.academic();
        this.paperRepository = paperRepository;
        this.projectService = projectService;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", userAgent(config.mailto()))
                .build();
    }

    public AcademicDto.SearchResults search(String query, Integer limit) {
        if (!StringUtils.hasText(query)) {
            throw ApiException.badRequest("EMPTY_QUERY", "Enter something to search for.");
        }
        int size = limit == null ? 10 : Math.clamp(limit, 1, MAX_RESULTS);
        try {
            return new AcademicDto.SearchResults(query, fetch(query, size), false, null);
        } catch (RuntimeException e) {
            log.warn("Academic search failed for query of length {}: {}", query.length(), e.toString());
            return new AcademicDto.SearchResults(query, List.of(), true,
                    "External search is unavailable right now. Your own library is still searchable.");
        }
    }

    /** Used by novelty assessment, where an outage must degrade rather than fail. */
    public List<AcademicDto> searchQuietly(String query, int limit) {
        try {
            return fetch(query, Math.clamp(limit, 1, MAX_RESULTS));
        } catch (RuntimeException e) {
            log.warn("Academic search unavailable during novelty check: {}", e.toString());
            return List.of();
        }
    }

    private List<AcademicDto> fetch(String query, int size) {
        String cacheKey = size + ":" + query.trim().toLowerCase();
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.results();
        }

        // A person's name is searched by authorship, not by text. OpenAlex's
        // `search` covers title, abstract and full text, so "Yoshua Bengio"
        // returns work that *mentions* him - the top hits included a paper
        // citing his textbook, by someone else - rather than work he wrote.
        List<AcademicDto> results = looksLikePersonName(query)
                ? request(byAuthor(query, size))
                : List.of();
        if (results.isEmpty()) {
            results = request(byText(query, size));
        }

        results = mergeDuplicates(results);
        cache.put(cacheKey, new CacheEntry(results, Instant.now().plus(CACHE_TTL)));
        evictExpired();
        return results;
    }

    private List<AcademicDto> request(String uri) {
        JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        List<AcademicDto> results = new ArrayList<>();
        if (body != null) {
            for (JsonNode work : body.path("results")) {
                results.add(toDto(work));
            }
        }
        return results;
    }

    private String byText(String query, int size) {
        return base(size).queryParam("search", query).build().toUriString();
    }

    private String byAuthor(String name, int size) {
        return base(size)
                .queryParam("filter", "raw_author_name.search:" + name)
                .queryParam("sort", "cited_by_count:desc")
                .build()
                .toUriString();
    }

    private UriComponentsBuilder base(int size) {
        return UriComponentsBuilder.fromPath("/works")
                .queryParam("per-page", size)
                .queryParam("select", "id,display_name,publication_year,doi,cited_by_count,"
                        + "authorships,abstract_inverted_index,primary_location,open_access")
                .queryParamIfPresent("mailto", java.util.Optional.ofNullable(
                        StringUtils.hasText(config.mailto()) ? config.mailto() : null));
    }

    /**
     * Whether the query reads as somebody's name rather than a subject.
     *
     * <p>Deliberately strict: two to four capitalised words with no digits and
     * no research vocabulary. A false positive costs one wasted request and
     * falls back to the text search, so the cautious side is to under-detect.
     */
    static boolean looksLikePersonName(String query) {
        String[] words = query.trim().split("\\s+");
        if (words.length < 2 || words.length > 4) {
            return false;
        }
        for (String word : words) {
            String bare = word.replace(".", "").replace("-", "").replace("'", "");
            if (bare.isEmpty() || !Character.isUpperCase(bare.charAt(0))) {
                return false;
            }
            for (char c : bare.toCharArray()) {
                if (!Character.isLetter(c)) {
                    return false;
                }
            }
            if (SUBJECT_WORDS.contains(bare.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Collapses the same work appearing more than once.
     *
     * <p>OpenAlex indexes a preprint and its published version as separate
     * records, so a search for sentence embeddings returned SimCSE twice under
     * one title with two DOIs. The surviving copy is the better-cited one, which
     * is normally the version of record rather than the preprint.
     */
    static List<AcademicDto> mergeDuplicates(List<AcademicDto> results) {
        Map<String, AcademicDto> best = new java.util.LinkedHashMap<>();
        for (AcademicDto record : results) {
            String key = duplicateKey(record);
            if (key.isEmpty()) {
                best.put("id:" + record.externalId(), record);
                continue;
            }
            AcademicDto existing = best.get(key);
            if (existing == null || citations(record) > citations(existing)) {
                best.put(key, record);
            }
        }
        return List.copyOf(best.values());
    }

    private static String duplicateKey(AcademicDto record) {
        String title = record.title() == null ? "" : record.title();
        StringBuilder normalized = new StringBuilder();
        for (char c : title.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private static int citations(AcademicDto record) {
        return record.citedByCount() == null ? 0 : record.citedByCount();
    }

    private AcademicDto toDto(JsonNode work) {
        List<String> authors = new ArrayList<>();
        for (JsonNode authorship : work.path("authorships")) {
            String name = authorship.path("author").path("display_name").asText(null);
            if (StringUtils.hasText(name)) {
                authors.add(name);
            }
        }
        String venue = work.path("primary_location").path("source").path("display_name").asText(null);
        String oaUrl = work.path("open_access").path("oa_url").asText(null);
        String doi = work.path("doi").asText(null);
        if (doi != null && doi.startsWith("https://doi.org/")) {
            doi = doi.substring("https://doi.org/".length());
        }
        return new AcademicDto(
                work.path("id").asText(null),
                work.path("display_name").asText(null),
                authors,
                reconstructAbstract(work.path("abstract_inverted_index")),
                work.path("publication_year").isInt() ? work.path("publication_year").asInt() : null,
                venue,
                doi,
                oaUrl,
                work.path("cited_by_count").isInt() ? work.path("cited_by_count").asInt() : null);
    }

    /**
     * OpenAlex ships abstracts as an inverted index (word to positions) rather
     * than as text, so the original order has to be rebuilt before the abstract
     * is usable as evidence.
     */
    static String reconstructAbstract(JsonNode invertedIndex) {
        if (invertedIndex == null || !invertedIndex.isObject() || invertedIndex.isEmpty()) {
            return null;
        }
        TreeMap<Integer, String> byPosition = new TreeMap<>();
        invertedIndex.fields().forEachRemaining(entry ->
                entry.getValue().forEach(pos -> byPosition.put(pos.asInt(), entry.getKey())));
        if (byPosition.isEmpty()) {
            return null;
        }
        return String.join(" ", byPosition.values());
    }

    /**
     * Imports a discovered record as a metadata-only paper. There is no PDF yet,
     * so it is marked complete-without-file rather than queued for indexing; a
     * file can be attached later (Section 8.9).
     */
    @Transactional
    public com.rlna.dto.PaperDto importPaper(UUID userId, AcademicDto.ImportRequest request) {
        projectService.require(userId, request.projectId());

        if (StringUtils.hasText(request.externalId())) {
            List<Paper> existing = paperRepository.findByUserIdAndProjectId(userId, request.projectId());
            boolean already = existing.stream()
                    .anyMatch(p -> request.externalId().equals(p.getExternalId()));
            if (already) {
                throw ApiException.conflict("DUPLICATE_PAPER",
                        "This paper is already in your library.");
            }
        }

        Paper paper = new Paper();
        paper.setUserId(userId);
        paper.setProjectId(request.projectId());
        paper.setExternalId(request.externalId());
        paper.setTitle(StringUtils.hasText(request.title()) ? request.title() : "Untitled paper");
        if (request.authors() != null && !request.authors().isEmpty()) {
            paper.setAuthors(request.authors().stream()
                    .filter(StringUtils::hasText)
                    .sorted(Comparator.naturalOrder())
                    .toArray(String[]::new));
        }
        paper.setAbstractText(request.abstractText());
        paper.setPublicationYear(request.year());
        paper.setVenue(request.venue());
        paper.setDoi(request.doi());
        // Searchable by metadata but carrying no chunks. Reported as its own
        // state rather than as queued, which would leave the library polling a
        // job that will never exist.
        paper.setProcessingStatus("metadata_only");
        return com.rlna.dto.PaperDto.from(paperRepository.save(paper));
    }

    private void evictExpired() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private static String userAgent(String mailto) {
        return StringUtils.hasText(mailto) ? "RLNA/1.0 (mailto:" + mailto + ")" : "RLNA/1.0";
    }
}
