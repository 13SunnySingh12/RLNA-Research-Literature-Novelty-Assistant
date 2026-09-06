package com.rlna.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.rlna.client.AiServiceClient;
import com.rlna.dto.ExportDto;
import com.rlna.entity.AnalysisResult;
import com.rlna.entity.Paper;
import com.rlna.exception.ApiException;
import com.rlna.repository.PaperChunkRepository;
import com.rlna.repository.PaperRepository;

import lombok.RequiredArgsConstructor;

/**
 * Export of a stored analysis to Markdown, PDF or BibTeX (Section 9.9).
 *
 * <p>Markdown is rendered here from the stored structured result, so an export
 * never re-runs a model. PDF rendering is delegated to the AI service, which
 * already carries a PDF library; adding a second one to this service would be
 * a dependency with no new capability behind it.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    public record Export(String filename, String contentType, byte[] content) {}

    private final AnalysisService analysisService;
    private final PaperRepository paperRepository;
    private final PaperChunkRepository chunkRepository;
    private final AiServiceClient aiServiceClient;

    @Transactional(readOnly = true)
    public Export export(UUID userId, ExportDto.Request request) {
        AnalysisResult analysis = analysisService.requireAnalysis(userId, request.analysisId());
        String slug = slug(analysis.getAnalysisType()) + "-" + analysis.getId().toString().substring(0, 8);

        return switch (request.format()) {
            case "markdown" -> new Export(slug + ".md", "text/markdown; charset=utf-8",
                    toMarkdown(analysis).getBytes(StandardCharsets.UTF_8));
            case "bibtex" -> new Export(slug + ".bib", "application/x-bibtex; charset=utf-8",
                    toBibtex(userId, analysis).getBytes(StandardCharsets.UTF_8));
            case "pdf" -> new Export(slug + ".pdf", "application/pdf",
                    toPdf(toMarkdown(analysis), titleFor(analysis)));
            default -> throw ApiException.badRequest("UNSUPPORTED_FORMAT",
                    "Supported formats are markdown, pdf and bibtex.");
        };
    }

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    String toMarkdown(AnalysisResult analysis) {
        JsonNode result = analysis.getResult();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(titleFor(analysis)).append("\n\n");
        if (StringUtils.hasText(analysis.getInputQuery()) && !"comparison".equals(analysis.getAnalysisType())) {
            md.append("> ").append(analysis.getInputQuery().replace("\n", " ")).append("\n\n");
        }
        md.append("*Generated ").append(analysis.getCreatedAt()).append("*\n\n");

        switch (analysis.getAnalysisType()) {
            case "summary" -> appendSummary(md, result);
            case "qa" -> appendQa(md, result);
            case "comparison" -> appendComparison(md, result);
            case "research_gap" -> appendGaps(md, result);
            case "novelty" -> appendNovelty(md, result);
            case "literature_review" -> appendReview(md, result);
            default -> md.append("```json\n").append(result.toPrettyString()).append("\n```\n");
        }

        appendHonesty(md, result, analysis);
        return md.toString();
    }

    private void appendSummary(StringBuilder md, JsonNode r) {
        section(md, "Research problem", r.path("research_problem").asText(null));
        section(md, "Approach", r.path("approach").asText(null));
        list(md, "Key findings", r.path("key_findings"));
        list(md, "Limitations", r.path("limitations"));
        section(md, "Takeaway", r.path("takeaway").asText(null));
    }

    private void appendQa(StringBuilder md, JsonNode r) {
        section(md, "Answer", r.path("answer").asText(null));
        if (r.path("claims").isArray() && !r.path("claims").isEmpty()) {
            md.append("## Evidence\n\n");
            for (JsonNode claim : r.path("claims")) {
                md.append("- ").append(claim.path("statement").asText(""));
                String source = claim.path("paper_title").asText(null);
                String sec = claim.path("section").asText(null);
                if (source != null) {
                    md.append("  \n  *(").append(source);
                    if (sec != null) {
                        md.append(", ").append(sec.replace('_', ' '));
                    }
                    md.append(")*");
                }
                md.append("\n");
            }
            md.append("\n");
        }
    }

    private void appendComparison(StringBuilder md, JsonNode r) {
        JsonNode papers = r.path("papers");
        JsonNode dimensions = r.path("dimensions");
        if (!papers.isArray() || !dimensions.isArray() || papers.isEmpty()) {
            md.append("```json\n").append(r.toPrettyString()).append("\n```\n");
            return;
        }
        md.append("| Dimension |");
        papers.forEach(p -> md.append(' ').append(escapeCell(p.path("title").asText("Paper"))).append(" |"));
        md.append("\n|---|");
        papers.forEach(p -> md.append("---|"));
        md.append("\n");

        for (JsonNode dim : dimensions) {
            md.append("| **").append(escapeCell(dim.path("name").asText(""))).append("** |");
            JsonNode values = dim.path("values");
            for (int i = 0; i < papers.size(); i++) {
                String value = values.path(i).asText("");
                md.append(' ').append(escapeCell(value.isBlank() ? "Not reported" : value)).append(" |");
            }
            md.append("\n");
        }
        md.append("\n");
    }

    private void appendGaps(StringBuilder md, JsonNode r) {
        for (JsonNode gap : r.path("gaps")) {
            md.append("## Potential research gap\n\n");
            md.append(gap.path("statement").asText("")).append("\n\n");
            if (StringUtils.hasText(gap.path("why_underexplored").asText(null))) {
                md.append("**Why it looks underexplored:** ")
                        .append(gap.path("why_underexplored").asText()).append("\n\n");
            }
            if (gap.path("evidence").isArray() && !gap.path("evidence").isEmpty()) {
                md.append("**Supporting evidence**\n\n");
                for (JsonNode ev : gap.path("evidence")) {
                    md.append("> ").append(ev.path("quote").asText("").replace("\n", " ")).append("\n>\n");
                    md.append("> -- ").append(ev.path("paper_title").asText("Unknown paper"))
                            .append(", ").append(ev.path("section").asText("").replace('_', ' '))
                            .append("\n\n");
                }
            }
            md.append("*Confidence: ").append(gap.path("confidence").asText("unknown")).append("*\n\n");
        }
    }

    private void appendNovelty(StringBuilder md, JsonNode r) {
        list(md, "Similar existing work", r.path("similar_work"), "title");
        list(md, "Areas of overlap", r.path("overlap"));
        list(md, "Existing methodologies", r.path("existing_methodologies"));
        list(md, "Differences", r.path("differences"));
        list(md, "Potentially novel aspects", r.path("potentially_novel"));
    }

    private void appendReview(StringBuilder md, JsonNode r) {
        section(md, "Overview", r.path("overview").asText(null));
        for (JsonNode theme : r.path("themes")) {
            md.append("## ").append(theme.path("name").asText("Theme")).append("\n\n");
            md.append(theme.path("description").asText("")).append("\n\n");
        }
        list(md, "Methodological approaches", r.path("methodologies"));
        list(md, "Datasets and evaluation", r.path("datasets"));
        list(md, "Points of agreement", r.path("agreements"));
        list(md, "Contradictions", r.path("contradictions"));
        list(md, "Reported limitations", r.path("limitations"));

        if (r.path("references").isArray() && !r.path("references").isEmpty()) {
            md.append("## References\n\n");
            int i = 1;
            for (JsonNode ref : r.path("references")) {
                md.append(i++).append(". ").append(ref.path("title").asText(""));
                if (ref.path("year").isInt()) {
                    md.append(" (").append(ref.path("year").asInt()).append(")");
                }
                md.append("\n");
            }
            md.append("\n");
        }
    }

    /**
     * Confidence and limitations are appended to every export, not just shown in
     * the UI. An exported document that dropped them would misrepresent the
     * result the moment it left the app (Sections 14.5 and 15.4).
     */
    private void appendHonesty(StringBuilder md, JsonNode result, AnalysisResult analysis) {
        md.append("---\n\n");
        if (StringUtils.hasText(analysis.getConfidence())) {
            md.append("**Confidence:** ").append(analysis.getConfidence()).append("\n\n");
        }
        String limitations = result.path("limitations").isTextual()
                ? result.path("limitations").asText() : null;
        int corpusSize = analysis.getEvidenceRefs() == null ? 0
                : analysis.getEvidenceRefs().path("corpus_size").asInt();
        if (corpusSize > 0) {
            md.append("**Evidence base:** ").append(corpusSize)
                    .append(" paper(s) from your library.\n\n");
        }
        if (StringUtils.hasText(limitations)) {
            md.append("**Limitations:** ").append(limitations).append("\n\n");
        }
        if ("novelty".equals(analysis.getAnalysisType())) {
            md.append("> This is not a proof of novelty. The absence of similar work in this "
                    + "corpus does not mean none exists.\n\n");
        }
        md.append("*Generated by RLNA from your own indexed library.*\n");
    }

    // ------------------------------------------------------------------
    // BibTeX
    // ------------------------------------------------------------------

    /**
     * A reference list for the papers the analysis actually used. Papers that
     * contributed no evidence are not cited (Section 9.5).
     */
    String toBibtex(UUID userId, AnalysisResult analysis) {
        Set<UUID> paperIds = new LinkedHashSet<>();
        JsonNode refs = analysis.getEvidenceRefs();
        if (refs != null && refs.path("chunk_ids").isArray() && !refs.path("chunk_ids").isEmpty()) {
            List<UUID> chunkIds = new ArrayList<>();
            refs.path("chunk_ids").forEach(id -> chunkIds.add(UUID.fromString(id.asText())));
            // Resolved through the ownership-checked lookup, so a stale or
            // tampered evidence reference simply yields no citation.
            chunkRepository.findAllOwnedByIds(chunkIds, userId)
                    .forEach(chunk -> paperIds.add(chunk.getPaperId()));
        }
        if (paperIds.isEmpty() && analysis.getPaperId() != null) {
            paperIds.add(analysis.getPaperId());
        }
        if (paperIds.isEmpty() && analysis.getProjectId() != null) {
            paperRepository.findCompletedInProject(userId, analysis.getProjectId())
                    .forEach(p -> paperIds.add(p.getId()));
        }

        StringBuilder bib = new StringBuilder();
        Set<String> usedKeys = new LinkedHashSet<>();
        // One query rather than one per paper: a project export can cite every
        // paper in the project, and round trips to a managed database dominate
        // the cost of building the bibliography.
        Map<UUID, Paper> byId = paperRepository.findByIdInAndUserId(paperIds, userId).stream()
                .collect(Collectors.toMap(Paper::getId, paper -> paper));
        for (UUID paperId : paperIds) {
            Paper paper = byId.get(paperId);
            if (paper != null) {
                bib.append(entryFor(paper, usedKeys)).append("\n");
            }
        }
        if (bib.isEmpty()) {
            throw ApiException.unprocessable("NOTHING_TO_EXPORT",
                    "This analysis has no cited papers to export as references.");
        }
        return bib.toString();
    }

    private String entryFor(Paper paper, Set<String> usedKeys) {
        String key = citationKey(paper, usedKeys);
        StringBuilder e = new StringBuilder("@article{").append(key).append(",\n");
        e.append("  title   = {").append(bibEscape(orDefault(paper.getTitle(), "Untitled"))).append("},\n");
        if (paper.getAuthors() != null && paper.getAuthors().length > 0) {
            e.append("  author  = {").append(bibEscape(String.join(" and ", paper.getAuthors()))).append("},\n");
        }
        if (paper.getPublicationYear() != null) {
            e.append("  year    = {").append(paper.getPublicationYear()).append("},\n");
        }
        if (StringUtils.hasText(paper.getVenue())) {
            e.append("  journal = {").append(bibEscape(paper.getVenue())).append("},\n");
        }
        if (StringUtils.hasText(paper.getDoi())) {
            e.append("  doi     = {").append(bibEscape(paper.getDoi())).append("},\n");
        }
        return e.append("}\n").toString();
    }

    private String citationKey(Paper paper, Set<String> usedKeys) {
        String author = paper.getAuthors() != null && paper.getAuthors().length > 0
                ? paper.getAuthors()[0].replaceAll("[^A-Za-z]", "")
                : "anon";
        if (author.isEmpty()) {
            author = "anon";
        }
        String base = author.toLowerCase(Locale.ROOT)
                + (paper.getPublicationYear() == null ? "" : paper.getPublicationYear());
        String key = base;
        int suffix = 1;
        while (!usedKeys.add(key)) {
            key = base + (char) ('a' + suffix++ - 1);
        }
        return key;
    }

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    private byte[] toPdf(String markdown, String title) {
        JsonNode response = aiServiceClient.post("/internal/render-pdf",
                Map.of("markdown", markdown, "title", title));
        String encoded = response.path("pdf_base64").asText(null);
        if (!StringUtils.hasText(encoded)) {
            throw ApiException.unavailable("EXPORT_FAILED",
                    "We could not build the PDF right now. Try Markdown, or try again shortly.");
        }
        return Base64.getDecoder().decode(encoded);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void section(StringBuilder md, String heading, String body) {
        if (StringUtils.hasText(body)) {
            md.append("## ").append(heading).append("\n\n").append(body).append("\n\n");
        }
    }

    private static void list(StringBuilder md, String heading, JsonNode array) {
        list(md, heading, array, null);
    }

    private static void list(StringBuilder md, String heading, JsonNode array, String objectField) {
        if (!array.isArray() || array.isEmpty()) {
            return;
        }
        md.append("## ").append(heading).append("\n\n");
        List<String> lines = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isTextual()) {
                lines.add(item.asText());
            } else if (objectField != null && item.path(objectField).isTextual()) {
                lines.add(item.path(objectField).asText());
            } else if (item.path("statement").isTextual()) {
                lines.add(item.path("statement").asText());
            } else if (item.path("text").isTextual()) {
                lines.add(item.path("text").asText());
            }
        }
        lines.forEach(l -> md.append("- ").append(l).append("\n"));
        md.append("\n");
    }

    private static String titleFor(AnalysisResult analysis) {
        return switch (analysis.getAnalysisType()) {
            case "summary" -> "Paper summary";
            case "qa" -> "Question and answer";
            case "comparison" -> "Paper comparison";
            case "research_gap" -> "Potential research gaps";
            case "novelty" -> "Novelty assessment";
            case "literature_review" -> "Literature review draft";
            default -> "Analysis";
        };
    }

    private static String escapeCell(String value) {
        return value.replace("|", "\\|").replace("\n", " ").trim();
    }

    private static String bibEscape(String value) {
        return value.replace("{", "\\{").replace("}", "\\}").replace("\\", "\\\\");
    }

    private static String orDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String slug(String value) {
        return value == null ? "analysis" : value.replaceAll("[^a-z0-9]+", "-");
    }
}
