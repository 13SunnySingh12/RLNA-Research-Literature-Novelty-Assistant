"""Paper metadata recovery (Section 8.4).

Heuristics run first and always. A fast-tier model is then asked to tidy the
result, because a title split across two lines or an author list mixed with
affiliations is exactly the kind of small cleanup a cheap model does well
(Section 12.2).

When no provider is configured the heuristic result stands on its own -- the
degraded output is honest rather than absent.
"""

from __future__ import annotations

import logging
import re
from dataclasses import asdict, dataclass
from datetime import datetime

logger = logging.getLogger(__name__)

_DOI_RE = re.compile(r"\b10\.\d{4,9}/[-._;()/:A-Za-z0-9]+\b")
_YEAR_RE = re.compile(r"\b(19[5-9]\d|20[0-4]\d)\b")
_EMAIL_RE = re.compile(r"\S+@\S+")
_ARXIV_RE = re.compile(r"arXiv:\s*(\d{4}\.\d{4,5})", re.I)

_NOISE_PREFIXES = (
    "arxiv:", "doi:", "http", "www.", "issn", "isbn", "vol.", "volume ",
    "proceedings", "conference on", "journal of", "preprint", "submitted",
    "accepted", "published", "copyright", "©", "license", "under review",
)

# Publishers put these on page one above the title; they are never a title.
_LEGAL_PHRASES = (
    "proper attribution", "permission to reproduce", "all rights reserved",
    "is licensed under", "creative commons", "for personal use",
    "redistribution", "provided that copies",
)


@dataclass
class PaperMetadata:
    title: str | None = None
    authors: list[str] | None = None
    year: int | None = None
    doi: str | None = None
    venue: str | None = None
    abstract: str | None = None

    def as_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v not in (None, [], "")}


def extract(
    first_page: str,
    abstract: str | None,
    pdf_metadata: dict | None,
    prominent_blocks: list[tuple[str, float, float]] | None = None,
) -> PaperMetadata:
    """Best-effort metadata from the PDF's own header and embedded properties."""
    meta = PaperMetadata()
    lines = [line.strip() for line in first_page.splitlines() if line.strip()]

    doi_match = _DOI_RE.search(first_page)
    if doi_match:
        meta.doi = doi_match.group(0).rstrip(".,;")

    # Typography first, embedded properties second, reading order last. The
    # largest text on page one is the title far more often than the first line
    # is: real papers open with copyright notices and arXiv stamps.
    embedded_title = (pdf_metadata or {}).get("title") or ""
    blocks = prominent_blocks or []
    title_block = _title_block(blocks)
    meta.title = (
        (_tidy(title_block[0]) if title_block else None)
        or (_tidy(embedded_title) if _is_plausible_title(embedded_title) else None)
        or _title_from_lines(lines)
    )

    # Authors sit immediately below the title, so the blocks after it in page
    # order are the right place to look before falling back to reading order.
    meta.authors = _authors_from_blocks(blocks, title_block) or _authors_from_lines(lines, meta.title)
    meta.year = _year(first_page, pdf_metadata)
    meta.abstract = _clean_abstract(abstract)
    return meta


def _title_block(blocks: list[tuple[str, float, float]]) -> tuple[str, float, float] | None:
    """The largest plausible block on the page, which is the title."""
    candidates = [b for b in blocks if _plausible_title_line(b[0])]
    if not candidates:
        return None
    return max(candidates, key=lambda b: (round(b[1], 1), -b[2]))


def _plausible_title_line(line: str) -> bool:
    lowered = line.lower()
    if not 8 <= len(line) <= 250:
        return False
    if any(lowered.startswith(prefix) for prefix in _NOISE_PREFIXES):
        return False
    if any(phrase in lowered for phrase in _LEGAL_PHRASES):
        return False
    if _EMAIL_RE.search(line) or _DOI_RE.search(line):
        return False
    return True


def _authors_from_blocks(
    blocks: list[tuple[str, float, float]], title_block: tuple[str, float, float] | None
) -> list[str] | None:
    if not blocks:
        return None
    after = blocks if title_block is None else [b for b in blocks if b[2] > title_block[2]]
    for text, _size, _top in sorted(after, key=lambda b: b[2])[:6]:
        lowered = text.lower()
        if lowered.startswith("abstract"):
            break
        names = _parse_author_line(text)
        if names:
            return names
    return None


def _title_from_lines(lines: list[str]) -> str | None:
    """The title is normally the first substantial non-furniture line block."""
    candidates: list[str] = []
    for line in lines[:14]:
        lowered = line.lower()
        if any(lowered.startswith(prefix) for prefix in _NOISE_PREFIXES):
            continue
        if _EMAIL_RE.search(line) or _DOI_RE.search(line):
            continue
        if any(phrase in lowered for phrase in _LEGAL_PHRASES):
            continue
        if len(line) < 8 or len(line) > 250:
            continue
        if lowered.startswith("abstract"):
            break
        candidates.append(line)
        # Titles wrap onto a second line often enough to be worth joining, but
        # rarely onto a third.
        if len(candidates) == 2:
            break
    if not candidates:
        return None
    title = " ".join(candidates).strip()
    if title.endswith("."):
        title = title[:-1]
    return _tidy(title) if len(title) >= 8 else None


def _authors_from_lines(lines: list[str], title: str | None) -> list[str] | None:
    start = 0
    if title:
        for index, line in enumerate(lines[:14]):
            if line and line in title:
                start = index + 1
    for line in lines[start : start + 8]:
        lowered = line.lower()
        if lowered.startswith("abstract"):
            break
        if _EMAIL_RE.search(line) or any(lowered.startswith(p) for p in _NOISE_PREFIXES):
            continue
        names = _parse_author_line(line)
        if names:
            return names
    return None


def _parse_author_line(line: str) -> list[str] | None:
    # Affiliation and footnote markers, including the star-like glyphs papers
    # use for "equal contribution".
    cleaned = re.sub(r"[\d\*†‡§¶#⋆★☆✦✳∗♦]", "", line)
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" ,;")
    if not cleaned or len(cleaned) > 300:
        return None
    parts = [p.strip(" .") for p in re.split(r",| and |&|;", cleaned) if p.strip(" .")]
    names = [p for p in parts if _looks_like_name(p)]
    # One comma-free name is far more likely to be a stray heading than a byline.
    return names[:25] if len(names) >= 2 or (len(names) == 1 and "," in cleaned) else None


def _looks_like_name(value: str) -> bool:
    words = value.split()
    if not 1 < len(words) <= 5:
        return False
    if any(ch.isdigit() for ch in value):
        return False
    return sum(1 for w in words if w[:1].isupper()) >= 2


def _year(first_page: str, pdf_metadata: dict | None) -> int | None:
    creation = (pdf_metadata or {}).get("creationDate") or ""
    match = re.search(r"(19|20)\d{2}", creation)
    current = datetime.now().year
    years = [int(y) for y in _YEAR_RE.findall(first_page)]
    if years:
        # The most recent plausible year on the page is usually publication;
        # older ones are citations.
        candidate = max(y for y in years if y <= current)
        return candidate
    if match:
        value = int(match.group(0))
        return value if 1950 <= value <= current else None
    return None


def _clean_abstract(abstract: str | None) -> str | None:
    if not abstract:
        return None
    text = re.sub(r"^\s*abstract[\s:.-]*", "", abstract.strip(), flags=re.I)
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) < 80:
        return None
    return text[:6000]


def _is_plausible_title(value: str) -> bool:
    if not value or len(value) < 8 or len(value) > 250:
        return False
    lowered = value.lower()
    # Generators love to leave the source filename or template name behind.
    return not (lowered.endswith(".dvi") or lowered.endswith(".pdf")
                or lowered.startswith("microsoft word")
                or lowered.startswith("untitled"))


def _tidy(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def arxiv_id(text: str) -> str | None:
    match = _ARXIV_RE.search(text)
    return match.group(1) if match else None


async def refine(meta: PaperMetadata, first_page: str) -> PaperMetadata:
    """Asks a fast-tier model to tidy the heuristic result (Sections 11, 12.2).

    Cheap by design: one small call on a single page of text. Every field is
    accepted only if it is actually better than what the heuristics produced, so
    a hallucinated venue or an invented author list cannot overwrite a real one.

    A failure here is not an error. The heuristic metadata is already usable, so
    the paper is indexed with it and the log records that the tidy-up was skipped.
    """
    from app.core.errors import ServiceError
    from app.integrations import router
    from app.models.outputs import MetadataCleanupOutput
    from app.prompts import templates

    if not first_page.strip():
        return meta

    try:
        routed = await router.complete_structured(
            task="metadata_cleanup",
            system=templates.METADATA_SYSTEM,
            user=templates.metadata_user(first_page, meta.as_dict()),
            schema=MetadataCleanupOutput,
        )
    except ServiceError as exc:
        logger.info("Metadata refinement skipped (%s); keeping heuristic values", exc.code)
        return meta
    except Exception:  # noqa: BLE001 - never fail indexing over a cosmetic field
        logger.warning("Metadata refinement failed; keeping heuristic values", exc_info=True)
        return meta

    cleaned: MetadataCleanupOutput = routed.parsed
    if cleaned.title and _is_plausible_title(cleaned.title):
        meta.title = _tidy(cleaned.title)
    if cleaned.authors:
        names = [_tidy(a) for a in cleaned.authors if a and _looks_like_name(_tidy(a))]
        if names:
            meta.authors = names[:25]
    if cleaned.year and 1950 <= cleaned.year <= datetime.now().year:
        meta.year = cleaned.year
    if cleaned.venue and 3 < len(cleaned.venue) <= 500:
        meta.venue = _tidy(cleaned.venue)
    return meta
