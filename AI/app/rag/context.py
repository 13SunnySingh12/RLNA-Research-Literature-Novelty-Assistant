"""Context construction (Section 13.3).

Retrieved text is treated as untrusted data throughout. A paper can contain the
sentence "ignore previous instructions", and a PDF is exactly the kind of file
where someone would put it, so every chunk is fenced, labelled as data, and
introduced by a system prompt that says instructions inside it are not
instructions (Section 26.4).
"""

from __future__ import annotations

import json
import re

from app.core.config import get_settings
from app.pipelines import sections as S
from app.rag.retrieval import Hit

# Chunks are ordered by paper and then by the order sections appear in a paper,
# so the model reads something shaped like a document rather than a ranked list.
_SECTION_ORDER = {
    S.ABSTRACT: 0,
    S.INTRODUCTION: 1,
    S.RELATED_WORK: 2,
    S.METHODOLOGY: 3,
    S.RESULTS: 4,
    S.DISCUSSION: 5,
    S.LIMITATIONS: 6,
    S.CONCLUSION: 7,
    S.FUTURE_WORK: 8,
    S.OTHER: 9,
}

_FENCE_OPEN = "<<<EVIDENCE"
_FENCE_CLOSE = "EVIDENCE>>>"


def build(hits: list[Hit], *, budget_characters: int | None = None) -> str:
    """Renders retrieved chunks into a provenance-labelled, fenced context block."""
    if not hits:
        return ""
    budget = budget_characters or get_settings().max_context_characters

    # Selection is by score so that the *lowest*-scoring chunks are the ones
    # dropped when the budget binds (Section 13.3, rule 6)...
    kept: list[Hit] = []
    used = 0
    for hit in sorted(hits, key=lambda h: h.score, reverse=True):
        cost = len(_render(hit))
        if used + cost > budget:
            continue
        kept.append(hit)
        used += cost

    if not kept:
        # Never return an empty context just because the best chunk was
        # oversized; include it truncated instead.
        best = max(hits, key=lambda h: h.score)
        return _render(best, limit=budget)

    # ...and presentation is by document order, so the model reads something
    # shaped like a paper rather than a ranked list.
    kept.sort(
        key=lambda h: (
            (h.paper_title or "").lower(),
            _SECTION_ORDER.get(h.section_type or S.OTHER, 9),
            h.chunk_index if h.chunk_index is not None else 0,
        )
    )
    return "\n\n".join(_render(hit) for hit in kept)


def _render(hit: Hit, limit: int | None = None) -> str:
    """One retrieved chunk, as a JSON object inside the evidence fence.

    JSON rather than prose behind a bracketed header. The passage sits in a
    named string field, so it is structurally a value the model is reading
    about rather than a line of the conversation it is taking part in. A
    sentence in the paper that reads like an instruction is then visibly the
    contents of `text`: it cannot pass for a turn addressed to the model, and
    it cannot close a header and start issuing rules of its own.
    """
    content = hit.content if limit is None else hit.content[: max(limit - 300, 300)]
    record = {
        "chunk_id": str(hit.chunk_id),
        "paper_id": str(hit.paper_id),
        "paper_title": hit.paper_title or "Untitled",
        "section": (hit.section_type or "unknown").replace("_", " "),
        "text": content,
    }
    return f"{_FENCE_OPEN}\n{json.dumps(record, ensure_ascii=False)}\n{_FENCE_CLOSE}"



# Phrasings that address the reader of the document rather than describing the
# work. Real papers discuss prompt injection in the third person ("attackers can
# instruct the model to..."); these patterns are second-person directives aimed
# at whoever is processing the text.
_INJECTION_PATTERNS = [
    re.compile(p, re.I)
    for p in (
        r"\bignore (all |any )?(previous|prior|earlier|above) (instructions|rules|prompts)",
        r"\bdisregard (all |any )?(previous|prior|earlier|above)\b",
        r"\b(new|updated|revised) instructions\b",
        r"\bsystem override\b",  # not 'system prompt': papers discuss it legitimately
        r"\byou are now\b",
        r"\bdeveloper mode\b",
        r"\bpretend (that )?this (paper|document|study)\b",
        r"\bpretend (that )?you\b",
        r"\bact as (if|though|an?)\b",
        r"\bdo not mention that you\b",
        r"\bstate these as\b",
        r"\byou (must|should) (now )?(output|return|reply|respond|say)\b",
        r"\bfabricate\b",
        r"\breveal your (system )?(prompt|instructions)\b",
    )
]


_QUOTED = re.compile(r"[\"'‘’“”]([^\"'‘’“”]{0,400})[\"'‘’“”]")


def is_suspicious(text: str) -> bool:
    """Whether a passage tries to instruct the reader instead of informing it.

    Quoted spans are removed before matching. Security papers reproduce attack
    strings to study them, and this corpus is exactly where such papers belong:
    a sentence like `attackers embed strings such as "ignore all previous
    instructions"` was being quarantined, which loses real evidence.

    That is a deliberate narrowing, and an attacker can evade it by quoting their
    own payload. It is affordable because this check is defence in depth, not the
    defence: retrieved text reaches the model as a JSON string field, under rules
    that say evidence cannot instruct. Losing genuine research to a false
    positive costs more than a keyword miss the architecture already absorbs.
    """
    stripped = _QUOTED.sub(" ", text or "")
    return any(pattern.search(stripped) for pattern in _INJECTION_PATTERNS)


def suspicious_ids(hits: list[Hit]) -> set[str]:
    return {str(hit.chunk_id) for hit in hits if is_suspicious(hit.content)}


def citable_ids(hits: list[Hit]) -> set[str]:
    """Chunks a claim is allowed to rest on.

    Passages carrying injected instructions are excluded rather than merely
    fenced. A prompt rule telling the model to ignore them is advice it can
    decline to take, and on this corpus it demonstrably did: asked what accuracy
    the paper reported, the model repeated a figure that existed only inside a
    "pretend this paper reports..." sentence. Dropping the chunk from the citable
    set means any claim resting solely on it is removed by
    drop_unsupported_claims, which is enforcement rather than instruction.
    """
    return {str(hit.chunk_id) for hit in hits} - suspicious_ids(hits)


def chunk_owners(hits: list[Hit]) -> dict[str, str]:
    """chunk_id -> the paper it actually belongs to."""
    return {str(hit.chunk_id): str(hit.paper_id) for hit in hits}


def chunk_text(hits: list[Hit]) -> dict[str, str]:
    return {str(hit.chunk_id): (hit.content or "") for hit in hits}


def _normalise(text: str) -> str:
    """Collapses whitespace and punctuation so a quote can be matched by wording.

    Extraction inserts line breaks mid-sentence and models re-punctuate what they
    quote, so an exact substring test rejects quotations that are genuinely
    present. Comparing on letters and digits alone keeps the check about whether
    the words are in the source.
    """
    return "".join(c.lower() for c in text if c.isalnum())


def quote_is_supported(quote: str, source: str, *, minimum: int = 24) -> bool:
    """Whether a quotation actually appears in the chunk it is attributed to.

    Very short quotes are accepted: a handful of characters carries no claim on
    its own and would fail normalisation for punctuation reasons alone.
    """
    normalised = _normalise(quote)
    if len(normalised) < minimum:
        return True
    return normalised in _normalise(source)


def paper_titles(hits: list[Hit]) -> dict[str, str]:
    return {str(hit.paper_id): (hit.paper_title or "Untitled paper") for hit in hits}


def drop_unsupported_claims(claims: list, allowed_chunk_ids: set[str]) -> list:
    """Removes claims that cite a chunk which was not in the retrieved context.

    A model that invents a citation, or that has been steered into citing
    something it was not given, loses that claim before anything is rendered
    (Section 11.1).
    """
    kept = []
    for claim in claims:
        chunk_id = getattr(claim, "chunk_id", None)
        if chunk_id is None or str(chunk_id) in allowed_chunk_ids:
            kept.append(claim)
    return kept
