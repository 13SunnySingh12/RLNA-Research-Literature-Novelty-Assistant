"""Section detection (Section 8.4).

The section label attached to each chunk is what makes section-weighted
retrieval possible, and section-weighted retrieval is what makes gap analysis
useful rather than generic (Section 9.1). Detection is therefore deliberately
conservative: an unrecognised heading becomes `other` rather than being guessed
into a category that would then be boosted for the wrong task.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# Canonical labels. `references` is detected so it can be excluded from
# chunking: a bibliography retrieves well and answers nothing.
ABSTRACT = "abstract"
INTRODUCTION = "introduction"
RELATED_WORK = "related_work"
METHODOLOGY = "methodology"
RESULTS = "results"
DISCUSSION = "discussion"
LIMITATIONS = "limitations"
CONCLUSION = "conclusion"
FUTURE_WORK = "future_work"
REFERENCES = "references"
OTHER = "other"

# Order matters: the first pattern that matches wins, so more specific phrases
# ("future work") are tested before the general ones ("conclusion").
_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    (ABSTRACT, re.compile(r"^abstract\b", re.I)),
    # A combined "Conclusions and Future Work" heading is labelled future_work:
    # that half is what gap analysis weights on, and labelling the section as a
    # conclusion would hide exactly the material the feature exists to find.
    (FUTURE_WORK, re.compile(
        r"^((conclusions?|discussion|summary)\s+and\s+future\s+work"
        r"|future\s+(work|directions|research)|further\s+work)\b", re.I)),
    (LIMITATIONS, re.compile(r"^(limitations?|threats\s+to\s+validity)\b", re.I)),
    (RELATED_WORK, re.compile(r"^(related\s+work|literature\s+review|background(\s+and\s+related\s+work)?|prior\s+work)\b", re.I)),
    (INTRODUCTION, re.compile(r"^(introduction|motivation)\b", re.I)),
    (METHODOLOGY, re.compile(r"^(methodolog(y|ies)|methods?|approach|proposed\s+(method|approach|model|framework|system)|materials\s+and\s+methods|system\s+(design|architecture)|implementation)\b", re.I)),
    (RESULTS, re.compile(r"^(results?|experiments?|experimental\s+(results|setup|evaluation)|evaluation|findings|performance)\b", re.I)),
    (DISCUSSION, re.compile(r"^(discussions?|analysis)\b", re.I)),
    (CONCLUSION, re.compile(r"^(conclusions?|concluding\s+remarks|summary)\b", re.I)),
    (REFERENCES, re.compile(r"^(references?|bibliography|works\s+cited)\b", re.I)),
]

# A heading is short, is not a sentence, and often carries a section number.
_NUMBER_PREFIX_RE = re.compile(
    r"^\s*(?:\d{1,2}(?:\.\d{1,2}){0,3}|[IVXLC]{1,6}|[A-H])[.)]?\s+", re.I)
_MAX_HEADING_WORDS = 8
_MAX_HEADING_CHARS = 90


@dataclass
class Section:
    section_type: str
    heading: str
    content: str
    order_index: int


def detect(text: str, *, abstract_hint: str | None = None) -> list[Section]:
    """Splits document text into labelled sections, in reading order."""
    lines = text.splitlines()
    boundaries: list[tuple[int, str, str]] = []

    for index, line in enumerate(lines):
        label = _classify_heading(line)
        if label is not None:
            boundaries.append((index, label, line.strip()))

    if not boundaries:
        return _fallback(text, abstract_hint)

    sections: list[Section] = []
    preamble = "\n".join(lines[: boundaries[0][0]]).strip()
    order = 0
    if preamble:
        # Text before the first recognised heading is the title block and, very
        # often, the abstract.
        sections.append(Section(ABSTRACT if _looks_like_abstract(preamble) else OTHER,
                                "Front matter", preamble, order))
        order += 1

    for position, (line_index, label, heading) in enumerate(boundaries):
        end = boundaries[position + 1][0] if position + 1 < len(boundaries) else len(lines)
        body = "\n".join(lines[line_index + 1 : end]).strip()
        if not body:
            continue
        sections.append(Section(label, heading, body, order))
        order += 1

    merged = _merge_adjacent(sections)
    return merged if merged else _fallback(text, abstract_hint)


def _classify_heading(line: str) -> str | None:
    stripped = line.strip()
    if not stripped or len(stripped) > _MAX_HEADING_CHARS:
        return None
    candidate = _NUMBER_PREFIX_RE.sub("", stripped).strip(" :–—-")
    if not candidate or len(candidate.split()) > _MAX_HEADING_WORDS:
        return None
    # A trailing full stop almost always means a sentence, not a heading. This
    # has to be checked before the period is stripped, or the guard never fires
    # and body text starting with a section word ("Background methods were...")
    # becomes a heading, silently swallowing the section above it.
    if candidate.endswith((".", "!", "?", ",", ";", ":")):
        return None
    for label, pattern in _PATTERNS:
        if pattern.match(candidate):
            return label
    return None


def _looks_like_abstract(text: str) -> bool:
    head = text[:400].lower()
    return "abstract" in head or len(text) > 300


def _merge_adjacent(sections: list[Section]) -> list[Section]:
    """Joins consecutive sections carrying the same label.

    Papers routinely split a phase across numbered subsections; keeping them
    apart would fragment retrieval for no benefit.
    """
    merged: list[Section] = []
    for section in sections:
        if merged and merged[-1].section_type == section.section_type and section.section_type != OTHER:
            previous = merged[-1]
            merged[-1] = Section(
                previous.section_type,
                previous.heading,
                f"{previous.content}\n\n{section.heading}\n{section.content}".strip(),
                previous.order_index,
            )
        else:
            merged.append(Section(section.section_type, section.heading, section.content, len(merged)))
    return merged


def _fallback(text: str, abstract_hint: str | None) -> list[Section]:
    """Used when a paper has no headings this parser recognises.

    The document is still indexed -- it is simply labelled `other`, which means
    it participates in ordinary retrieval but never receives a section boost.
    """
    sections: list[Section] = []
    if abstract_hint:
        sections.append(Section(ABSTRACT, "Abstract", abstract_hint, 0))
    sections.append(Section(OTHER, "Document", text, len(sections)))
    return sections
