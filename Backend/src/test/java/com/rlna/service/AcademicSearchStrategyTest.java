package com.rlna.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.rlna.dto.AcademicDto;

/**
 * The two query-shaping rules in {@link AcademicSearchService}.
 *
 * <p>Both exist because of behaviour observed against the live API: searching a
 * person's name returned work that merely cited them, and a topic search
 * returned one paper twice under two DOIs.
 */
class AcademicSearchStrategyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Yoshua Bengio", "Geoffrey E. Hinton", "Jean-Baptiste Alayrac",
                            "Kyunghyun Cho", "Ada Lovelace"})
    @DisplayName("names route to the authorship filter")
    void detectsNames(String query) {
        assertThat(AcademicSearchService.looksLikePersonName(query)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "retrieval augmented generation",
            "Attention Is All You Need",
            "Deep Learning",
            "graph neural networks",
            "BERT 2019",
            "transformers",
            "Large Language Model Evaluation"})
    @DisplayName("subjects and titles stay on the text search")
    void rejectsSubjects(String query) {
        assertThat(AcademicSearchService.looksLikePersonName(query)).isFalse();
    }

    private static AcademicDto work(String id, String title, Integer citations) {
        return new AcademicDto(id, title, List.of(), null, 2021, null, null, null, citations);
    }

    @Test
    @DisplayName("a preprint and its published version collapse to the better-cited one")
    void mergesDuplicateTitles() {
        List<AcademicDto> merged = AcademicSearchService.mergeDuplicates(List.of(
                work("W1", "SimCSE: Simple Contrastive Learning of Sentence Embeddings", 4200),
                work("W2", "Sentence-BERT: Sentence Embeddings using Siamese BERT-Networks", 9000),
                work("W3", "simcse: simple contrastive learning of sentence embeddings", 150)));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(AcademicDto::externalId).containsExactly("W1", "W2");
    }

    @Test
    @DisplayName("punctuation and case differences do not defeat the merge")
    void normalisesTitlesBeforeComparing() {
        List<AcademicDto> merged = AcademicSearchService.mergeDuplicates(List.of(
                work("W1", "Attention Is All You Need", 700),
                work("W2", "Attention is all you need!", 90_000)));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).externalId()).isEqualTo("W2");
    }

    @Test
    @DisplayName("distinct papers are never collapsed")
    void keepsDistinctPapers() {
        List<AcademicDto> merged = AcademicSearchService.mergeDuplicates(List.of(
                work("W1", "Attention Is All You Need", 90_000),
                work("W2", "Attention Is All You Need In Speech Separation", 600)));

        assertThat(merged).hasSize(2);
    }

    @Test
    @DisplayName("records without a title survive on their own id rather than merging together")
    void untitledRecordsAreNotMerged() {
        List<AcademicDto> merged = AcademicSearchService.mergeDuplicates(List.of(
                work("W1", null, 5), work("W2", null, 7)));

        assertThat(merged).hasSize(2);
    }
}
