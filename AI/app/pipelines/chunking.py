"""Section-aware chunking (Section 19.2).

Two rules matter here. A chunk never spans two sections, because the section
label is what retrieval weights on and a chunk straddling a boundary would carry
a label that is only half true. And chunks overlap, because a claim split across
a boundary would otherwise be retrievable from neither side.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Iterable

from app.pipelines.sections import REFERENCES, Section

MIN_CHUNK_TOKENS = 50

_SENTENCE_END_RE = re.compile(r"(?<=[.!?])\s+(?=[A-Z(\[])")


@dataclass(frozen=True)
class Chunk:
    section_index: int
    section_type: str
    chunk_index: int
    content: str
    token_count: int


def build(
    sections: Iterable[Section],
    *,
    count_tokens: Callable[[str], int],
    chunk_size: int,
    overlap: int,
) -> list[Chunk]:
    """Turns labelled sections into overlapping, section-bounded chunks."""
    if overlap >= chunk_size:
        raise ValueError("Chunk overlap must be smaller than chunk size")

    chunks: list[Chunk] = []
    index = 0
    for section in sections:
        # A bibliography retrieves well on surface similarity and answers
        # nothing, so it is labelled and then left out of the index.
        if section.section_type == REFERENCES:
            continue
        for text in _split_section(section.content, count_tokens, chunk_size, overlap):
            token_count = count_tokens(text)
            chunks.append(
                Chunk(
                    section_index=section.order_index,
                    section_type=section.section_type,
                    chunk_index=index,
                    content=text,
                    token_count=token_count,
                )
            )
            index += 1
    return chunks


def _split_section(
    content: str,
    count_tokens: Callable[[str], int],
    chunk_size: int,
    overlap: int,
) -> list[str]:
    text = content.strip()
    if not text:
        return []
    if count_tokens(text) <= chunk_size:
        return [text]

    sentences = _sentences(text)
    chunks: list[str] = []
    current: list[str] = []
    current_tokens = 0

    for sentence in sentences:
        sentence_tokens = count_tokens(sentence)
        # A single sentence longer than the window (a table dumped as prose, a
        # long equation) is split on words so it is not silently dropped.
        if sentence_tokens > chunk_size:
            if current:
                chunks.append(" ".join(current))
                current, current_tokens = [], 0
            chunks.extend(_split_long(sentence, count_tokens, chunk_size))
            continue

        if current_tokens + sentence_tokens > chunk_size and current:
            chunks.append(" ".join(current))
            carry = _tail_within(current, count_tokens, overlap)
            current = list(carry)
            current_tokens = count_tokens(" ".join(current)) if current else 0

        current.append(sentence)
        current_tokens += sentence_tokens

    if current:
        chunks.append(" ".join(current))

    # The accumulator above sums sentences counted individually, which
    # under-reads the joined text: subwords merge across sentence boundaries. A
    # handful of chunks therefore land slightly over budget, so the invariant is
    # enforced once here rather than approximated per sentence, which would cost
    # a re-tokenisation of the whole candidate on every append.
    bounded: list[str] = []
    for chunk in chunks:
        if count_tokens(chunk) > chunk_size:
            bounded.extend(_split_long(chunk, count_tokens, chunk_size))
        else:
            bounded.append(chunk)

    return _merge_tiny(bounded, count_tokens, chunk_size)


def _sentences(text: str) -> list[str]:
    parts: list[str] = []
    for paragraph in text.split("\n\n"):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        parts.extend(s.strip() for s in _SENTENCE_END_RE.split(paragraph) if s.strip())
    return parts or [text]


def _tail_within(
    sentences: list[str], count_tokens: Callable[[str], int], overlap: int
) -> list[str]:
    """Takes the trailing sentences that fit inside the overlap budget."""
    carry: list[str] = []
    budget = 0
    for sentence in reversed(sentences):
        tokens = count_tokens(sentence)
        if budget + tokens > overlap:
            break
        carry.insert(0, sentence)
        budget += tokens
    return carry


def _split_long(
    sentence: str, count_tokens: Callable[[str], int], chunk_size: int
) -> list[str]:
    words = sentence.split()
    pieces: list[str] = []
    current: list[str] = []
    for word in words:
        candidate = current + [word]
        if current and count_tokens(" ".join(candidate)) > chunk_size:
            pieces.append(" ".join(current))
            current = [word]
        else:
            current = candidate
    if current:
        pieces.append(" ".join(current))
    return pieces


def _merge_tiny(
    chunks: list[str], count_tokens: Callable[[str], int], chunk_size: int
) -> list[str]:
    """Folds sub-minimum fragments into their neighbour.

    A 12-token chunk embeds to something almost meaningless and pollutes results;
    it belongs with the text around it (Section 19.2).

    A merge that would push its neighbour past the budget is skipped: overshooting
    puts the tail of the chunk beyond what the embedding model reads, which is a
    worse outcome than leaving one short chunk in place.
    """
    if len(chunks) < 2:
        return chunks

    def joins_within_budget(left: str, right: str) -> bool:
        return count_tokens(f"{left} {right}".strip()) <= chunk_size

    merged: list[str] = []
    for chunk in chunks:
        if (
            merged
            and count_tokens(chunk) < MIN_CHUNK_TOKENS
            and joins_within_budget(merged[-1], chunk)
        ):
            merged[-1] = f"{merged[-1]} {chunk}".strip()
        else:
            merged.append(chunk)
    # The first chunk can still be short if the section itself was short.
    if (
        len(merged) > 1
        and count_tokens(merged[0]) < MIN_CHUNK_TOKENS
        and joins_within_budget(merged[0], merged[1])
    ):
        merged[1] = f"{merged[0]} {merged[1]}".strip()
        merged.pop(0)
    return merged
