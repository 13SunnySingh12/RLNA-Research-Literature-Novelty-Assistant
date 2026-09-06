"""Chunking, section detection and text cleaning.

These are pure functions over text, so they are tested directly rather than
through the service (Section 29.2).
"""

from __future__ import annotations

import pytest

from app.pipelines import chunking, metadata, pdf
from app.pipelines import sections as S


def words(n: int, token: str = "alpha") -> str:
    return " ".join([token] * n) + "."


def count(text: str) -> int:
    """A deterministic stand-in for the model tokenizer: one token per word."""
    return len(text.split())


def section(section_type: str, content: str, order: int = 0) -> S.Section:
    return S.Section(section_type, section_type.title(), content, order)


# ------------------------------------------------------------------ chunking

def test_a_chunk_never_spans_two_sections():
    built = chunking.build(
        [section(S.METHODOLOGY, words(400), 0), section(S.RESULTS, words(400), 1)],
        count_tokens=count, chunk_size=512, overlap=64,
    )
    for chunk in built:
        assert chunk.section_type in (S.METHODOLOGY, S.RESULTS)
    # Section boundaries are hard boundaries, so the two sections cannot have
    # been merged into one chunk even though together they fit the window.
    assert {c.section_type for c in built} == {S.METHODOLOGY, S.RESULTS}


def test_chunks_respect_the_configured_size():
    built = chunking.build(
        [section(S.RESULTS, " ".join(words(60) for _ in range(30)))],
        count_tokens=count, chunk_size=200, overlap=20,
    )
    assert len(built) > 1
    for chunk in built:
        # A small overshoot is possible because chunks break on sentences, never
        # mid-sentence; a large one would mean the budget is not being honoured.
        assert count(chunk.content) <= 260


def test_chunks_overlap_so_meaning_is_not_lost_at_a_boundary():
    sentences = " ".join(f"Sentence number {i} says something distinct." for i in range(60))
    built = chunking.build(
        [section(S.INTRODUCTION, sentences)],
        count_tokens=count, chunk_size=80, overlap=25,
    )
    assert len(built) >= 2
    tail = set(built[0].content.split()[-12:])
    head = set(built[1].content.split()[:25])
    assert tail & head, "consecutive chunks share no text, so the overlap is not working"


def test_references_are_labelled_but_never_indexed():
    built = chunking.build(
        [section(S.RESULTS, words(300), 0), section(S.REFERENCES, words(900), 1)],
        count_tokens=count, chunk_size=200, overlap=20,
    )
    # A bibliography matches on surface similarity and answers nothing.
    assert all(c.section_type != S.REFERENCES for c in built)
    assert built


def test_fragments_below_the_minimum_are_merged_into_a_neighbour():
    built = chunking.build(
        [section(S.RESULTS, " ".join(words(70) for _ in range(3)) + " Tiny.")],
        count_tokens=count, chunk_size=75, overlap=10,
    )
    assert built
    assert all(count(c.content) >= chunking.MIN_CHUNK_TOKENS for c in built[1:])


def test_a_single_oversized_sentence_is_split_rather_than_dropped():
    monster = "word " * 500
    built = chunking.build(
        [section(S.METHODOLOGY, monster)], count_tokens=count, chunk_size=100, overlap=10
    )
    assert len(built) > 1
    assert sum(count(c.content) for c in built) >= 400


def test_chunk_indexes_are_contiguous_across_sections():
    built = chunking.build(
        [section(S.INTRODUCTION, words(500), 0), section(S.RESULTS, words(500), 1)],
        count_tokens=count, chunk_size=150, overlap=20,
    )
    assert [c.chunk_index for c in built] == list(range(len(built)))


def test_overlap_must_be_smaller_than_the_chunk():
    with pytest.raises(ValueError):
        chunking.build([section(S.RESULTS, words(100))],
                       count_tokens=count, chunk_size=50, overlap=50)


# --------------------------------------------------------- section detection

def test_detects_the_standard_academic_sections():
    text = "\n".join([
        "Abstract", "We present a method.",
        "1 Introduction", "Background text here.",
        "2 Related Work", "Prior approaches did other things.",
        "3 Methodology", "We trained a model.",
        "4 Results", "It worked well.",
        "5 Limitations", "The dataset was small.",
        "6 Future Work", "More data is needed.",
        "References", "[1] Someone.",
    ])
    found = {s.section_type for s in S.detect(text)}
    assert {S.ABSTRACT, S.INTRODUCTION, S.RELATED_WORK, S.METHODOLOGY,
            S.RESULTS, S.LIMITATIONS, S.FUTURE_WORK, S.REFERENCES} <= found


def test_future_work_wins_over_conclusion_when_both_could_match():
    detected = S.detect("Conclusions and Future Work\nWe will extend this later.")
    # Order matters: matching "conclusion" first would mislabel the section that
    # gap analysis depends on most.
    assert detected[0].section_type == S.FUTURE_WORK


def test_roman_and_lettered_headings_are_recognised():
    detected = S.detect("III. METHODOLOGY\nWe describe the approach.")
    assert any(s.section_type == S.METHODOLOGY for s in detected)


def test_a_sentence_that_starts_with_a_section_word_is_not_a_heading():
    text = "Introduction to this topic is beyond the scope of this short note, sadly."
    assert all(s.section_type != S.INTRODUCTION for s in S.detect(text))


def test_a_paper_with_no_recognised_headings_is_still_indexable():
    detected = S.detect("Some prose with no headings at all. " * 20)
    assert detected
    # Labelled `other`, so it takes part in retrieval but never gets a boost.
    assert detected[-1].section_type == S.OTHER


def test_repeated_subsections_of_one_phase_are_merged():
    text = "\n".join([
        "3.1 Methodology", "First half of the method.",
        "3.2 Methods", "Second half of the method.",
    ])
    detected = [s for s in S.detect(text) if s.section_type == S.METHODOLOGY]
    assert len(detected) == 1
    assert "First half" in detected[0].content and "Second half" in detected[0].content


# ---------------------------------------------------------------- cleaning

def test_nul_and_control_bytes_are_stripped():
    """PostgreSQL rejects NUL in a text column, and real PDFs do emit them."""
    cleaned = pdf._clean("Valid\x00text\x07with\x1fcontrol bytes")
    assert "\x00" not in cleaned and "\x07" not in cleaned
    assert "Valid" in cleaned and "text" in cleaned


def test_words_hyphenated_across_a_line_break_are_rejoined():
    assert "federated" in pdf._clean("This uses feder-\nated learning.")


def test_ligatures_are_normalised():
    cleaned = pdf._clean("The classiﬁer was efﬁcient.")
    assert "classifier" in cleaned and "efficient" in cleaned


def test_running_headers_are_removed_only_when_they_actually_repeat():
    pages = ["Journal of Testing\nReal content one.",
             "Journal of Testing\nReal content two.",
             "Journal of Testing\nReal content three.",
             "Journal of Testing\nReal content four."]
    cleaned = pdf._strip_running_headers(pages)
    assert all("Journal of Testing" not in page for page in cleaned)
    assert all("Real content" in page for page in cleaned)


def test_a_heading_appearing_twice_is_not_mistaken_for_furniture():
    pages = ["Results\nA.", "Discussion\nB.", "Method\nC.", "Results\nD."]
    cleaned = pdf._strip_running_headers(pages)
    assert any("Results" in page for page in cleaned)


# ---------------------------------------------------------------- metadata

def test_title_comes_from_the_largest_text_not_the_first_line():
    blocks = [
        ("Provided proper attribution is provided, permission to reproduce", 9.0, 40.0),
        ("Attention Is All You Need", 17.0, 120.0),
        ("Ashish Vaswani, Noam Shazeer, Niki Parmar", 11.0, 160.0),
    ]
    result = metadata.extract("", None, {}, blocks)
    assert result.title == "Attention Is All You Need"


def test_authors_are_read_from_the_block_below_the_title():
    blocks = [
        ("A Study of Things", 17.0, 100.0),
        ("Jane Doe, John Smith and Ada Lovelace", 11.0, 140.0),
    ]
    result = metadata.extract("", None, {}, blocks)
    assert result.authors == ["Jane Doe", "John Smith", "Ada Lovelace"]


def test_footnote_markers_are_stripped_from_author_names():
    blocks = [("A Study Of Retrieval", 17.0, 100.0),
              ("Patrick Lewis⋆, Ethan Perez†", 11.0, 140.0)]
    result = metadata.extract("", None, {}, blocks)
    assert result.authors == ["Patrick Lewis", "Ethan Perez"]


def test_a_doi_is_recovered_from_the_first_page():
    result = metadata.extract("Published at doi:10.1145/3292500.3330701 in 2019.", None, {}, [])
    assert result.doi == "10.1145/3292500.3330701"


def test_a_generator_placeholder_title_is_rejected():
    result = metadata.extract("Real Title Here\nSome authors", None,
                              {"title": "Microsoft Word - draft7.docx"}, [])
    assert result.title != "Microsoft Word - draft7.docx"


def test_token_counting_is_safe_from_several_threads():
    """The fast tokenizer is one shared Rust object and panics if shared.

    Indexing runs in a thread pool, so two concurrent uploads encoded at the
    same time and raised "Already borrowed", surfacing as a 500 on upload.
    """
    import concurrent.futures as cf

    from app.embeddings import encoder

    text = ("Sparse attention reduces the quadratic cost of self-attention "
            "while preserving long-range dependencies. ") * 40

    with cf.ThreadPoolExecutor(max_workers=8) as pool:
        counts = list(pool.map(lambda _: encoder.count_tokens(text), range(64)))

    assert len(set(counts)) == 1, "concurrent counts disagreed"
    assert counts[0] > 0
