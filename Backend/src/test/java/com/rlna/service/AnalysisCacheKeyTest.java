package com.rlna.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The analysis fingerprint (Section 25.2).
 *
 * <p>This is what turns caching from an optimisation into a cost control, so
 * the properties that matter are tested explicitly: the same request must
 * produce the same key, and any change to the task, the model, the question or
 * the evidence must produce a different one.
 */
class AnalysisCacheKeyTest {

    private static final List<String> EVIDENCE = List.of("chunk-a", "chunk-b", "chunk-c");

    private static String key(String type, String model, String query, List<String> evidence) {
        return AnalysisService.cacheKey(type, model, query, evidence);
    }

    @Test
    @DisplayName("the same request always produces the same key")
    void deterministic() {
        assertThat(key("qa", "m1", "What is X?", EVIDENCE))
                .isEqualTo(key("qa", "m1", "What is X?", EVIDENCE));
    }

    @Test
    @DisplayName("the key is a SHA-256 hex digest")
    void shape() {
        assertThat(key("qa", "m1", "q", EVIDENCE)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("evidence order does not matter, but evidence content does")
    void evidenceIsOrderInsensitive() {
        // Retrieval can legitimately return the same chunks in a different
        // order; that is the same question over the same evidence.
        assertThat(key("qa", "m1", "q", List.of("c", "a", "b")))
                .isEqualTo(key("qa", "m1", "q", List.of("a", "b", "c")));
        assertThat(key("qa", "m1", "q", List.of("a", "b")))
                .isNotEqualTo(key("qa", "m1", "q", List.of("a", "b", "c")));
    }

    @Test
    @DisplayName("questions differing only in whitespace or case share a key")
    void queryIsNormalized() {
        assertThat(key("qa", "m1", "  What   is X? ", EVIDENCE))
                .isEqualTo(key("qa", "m1", "what is x?", EVIDENCE));
    }

    @Test
    @DisplayName("a different question produces a different key")
    void queryMatters() {
        assertThat(key("qa", "m1", "What is X?", EVIDENCE))
                .isNotEqualTo(key("qa", "m1", "What is Y?", EVIDENCE));
    }

    @Test
    @DisplayName("changing the model invalidates the cached answer")
    void modelMatters() {
        // Otherwise a model upgrade would keep serving the old model's answers.
        assertThat(key("qa", "gemini:a", "q", EVIDENCE))
                .isNotEqualTo(key("qa", "gemini:b", "q", EVIDENCE));
    }

    @Test
    @DisplayName("the same evidence used for a different task is a different key")
    void taskMatters() {
        assertThat(key("qa", "m1", "q", EVIDENCE))
                .isNotEqualTo(key("novelty", "m1", "q", EVIDENCE));
    }

    @Test
    @DisplayName("external evidence participates in the fingerprint")
    void externalEvidenceMatters() {
        // A novelty check against different external literature is a different
        // question, even when the idea and the local corpus are unchanged.
        assertThat(key("novelty", "m1", "idea", List.of("chunk-a", "ext:W1")))
                .isNotEqualTo(key("novelty", "m1", "idea", List.of("chunk-a", "ext:W2")));
    }

    @Test
    @DisplayName("a null model id is handled rather than throwing")
    void nullModelIsSafe() {
        assertThat(key("qa", null, "q", EVIDENCE)).hasSize(64);
    }

    @Test
    @DisplayName("empty evidence still yields a usable key")
    void emptyEvidence() {
        assertThat(key("qa", "m1", "q", List.of())).hasSize(64);
    }
}
