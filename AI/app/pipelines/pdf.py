"""PDF text extraction and cleaning (Section 8.4).

Uploaded PDFs are untrusted input, so extraction is bounded in pages and in
characters and every parser failure is contained rather than allowed to take the
worker down.
"""

from __future__ import annotations

import logging
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass

import fitz  # PyMuPDF

from app.core.config import get_settings
from app.core.errors import PermanentError

logger = logging.getLogger(__name__)

# PyMuPDF already resolves most ligatures, but scanned-to-text and older
# generators still emit the precomposed glyphs.
_LIGATURES = {
    "ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl",
    "ﬅ": "st", "ﬆ": "st",
}

_PAGE_NUMBER_RE = re.compile(r"^\s*(?:page\s+)?[-–—\[(]?\s*\d{1,4}\s*(?:/\s*\d{1,4})?[-–—\])]?\s*$", re.I)
_HYPHEN_BREAK_RE = re.compile(r"(\w)[-­]\s*\n\s*(\w)")
_MULTI_NEWLINE_RE = re.compile(r"\n{3,}")
_MULTI_SPACE_RE = re.compile(r"[ \t ]{2,}")
_URL_ONLY_RE = re.compile(r"^\s*(?:https?://|www\.)\S+\s*$", re.I)
# Everything in C0 except tab, newline and carriage return, plus the C1 block.
_CONTROL_CHARS_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")


@dataclass(frozen=True)
class ExtractedDocument:
    text: str
    page_count: int
    first_page_text: str
    # First-page text blocks as (text, font size, vertical position). On an
    # academic paper the title is set larger than everything around it, which
    # identifies it far more reliably than "the first substantial line" -- that
    # heuristic happily returns a publisher's legal notice.
    prominent_blocks: list[tuple[str, float, float]]


def extract(pdf_bytes: bytes) -> ExtractedDocument:
    """Extracts cleaned text from a PDF, or raises a permanent error."""
    settings = get_settings()
    try:
        document = fitz.open(stream=pdf_bytes, filetype="pdf")
    except Exception as exc:  # noqa: BLE001 - any parser failure is the same outcome
        logger.info("PDF could not be opened: %s", exc)
        raise PermanentError("PDF_UNREADABLE", "This PDF could not be read.") from exc

    try:
        if document.needs_pass:
            raise PermanentError(
                "PDF_ENCRYPTED", "This PDF is password protected, so its text cannot be read."
            )
        if document.page_count > settings.max_pdf_pages:
            # A page count far beyond any real paper is the cheapest signal that
            # a file is not what it claims to be.
            raise PermanentError(
                "PDF_TOO_COMPLEX",
                f"This PDF has {document.page_count} pages, beyond the {settings.max_pdf_pages} page limit.",
            )

        pages: list[str] = []
        prominent: list[tuple[str, float, float]] = []
        total_characters = 0
        for page in document:
            try:
                page_text = page.get_text("text") or ""
                if page.number == 0:
                    prominent = _prominent_blocks(page)
            except Exception:  # noqa: BLE001 - one bad page must not lose the rest
                logger.warning("Skipping unreadable page %s", page.number)
                page_text = ""
            total_characters += len(page_text)
            if total_characters > settings.max_pdf_characters:
                raise PermanentError(
                    "PDF_TOO_COMPLEX", "This PDF contains more text than can be indexed safely."
                )
            pages.append(page_text)
        page_count = document.page_count
    finally:
        document.close()

    pages = _strip_running_headers(pages)
    text = _clean("\n\n".join(pages))

    if len(text.strip()) < 200:
        # A text-layer-free PDF extracts to almost nothing. Saying so precisely
        # is far more useful than a generic failure (Section 27).
        raise PermanentError(
            "NO_EXTRACTABLE_TEXT",
            "No text could be extracted. This looks like a scanned PDF.",
        )

    first_page = _clean(pages[0]) if pages else ""
    return ExtractedDocument(
        text=text,
        page_count=page_count,
        first_page_text=first_page,
        prominent_blocks=[
            (_clean(text), size, top) for text, size, top in prominent if _clean(text)
        ],
    )


def _prominent_blocks(page) -> list[tuple[str, float, float]]:
    """First-page text blocks as (text, font size, vertical position).

    Blocks rather than lines, because PyMuPDF has already grouped adjacent lines
    that belong together: a title wrapped over two lines arrives as one block,
    while an unrelated copyright notice set at the same size stays separate.
    Joining by font size alone would splice the two together.
    """
    try:
        blocks = page.get_text("dict").get("blocks", [])
    except Exception:  # noqa: BLE001 - fall back to the text heuristics
        return []

    collected: list[tuple[str, float, float]] = []
    for block in blocks:
        lines = block.get("lines", [])
        if not lines:
            continue
        parts: list[str] = []
        sizes: list[float] = []
        for line in lines:
            spans = [s for s in line.get("spans", []) if (s.get("text") or "").strip()]
            if not spans:
                continue
            parts.append("".join(s["text"] for s in spans).strip())
            sizes.append(max(float(s.get("size", 0)) for s in spans))
        text = " ".join(parts).strip()
        if not text or not sizes or len(text) < 4:
            continue
        collected.append((re.sub(r"\s+", " ", text), max(sizes),
                          float(block.get("bbox", (0, 0, 0, 0))[1])))
    return collected


def _strip_running_headers(pages: list[str]) -> list[str]:
    """Removes the repeated first/last lines that carry journal furniture.

    A line is treated as furniture only when it recurs on a large share of a
    multi-page document, so a genuine heading that happens to appear twice
    survives.
    """
    if len(pages) < 4:
        return pages

    first_lines: Counter[str] = Counter()
    last_lines: Counter[str] = Counter()
    for page in pages:
        lines = [line.strip() for line in page.splitlines() if line.strip()]
        if not lines:
            continue
        first_lines[lines[0]] += 1
        last_lines[lines[-1]] += 1

    threshold = max(3, int(len(pages) * 0.5))
    repeated = {line for line, count in first_lines.items() if count >= threshold}
    repeated |= {line for line, count in last_lines.items() if count >= threshold}

    cleaned: list[str] = []
    for page in pages:
        kept = [
            line
            for line in page.splitlines()
            if line.strip() not in repeated
            and not _PAGE_NUMBER_RE.match(line)
            and not _URL_ONLY_RE.match(line)
        ]
        cleaned.append("\n".join(kept))
    return cleaned


def _clean(text: str) -> str:
    # Real PDFs do carry NUL and other C0 control bytes in their text layer, and
    # PostgreSQL rejects them outright in a text column. Stripping them here,
    # once, keeps every downstream writer from having to know that.
    text = _CONTROL_CHARS_RE.sub("", text)
    text = unicodedata.normalize("NFKC", text)
    for ligature, replacement in _LIGATURES.items():
        text = text.replace(ligature, replacement)
    # Words split across a line break lose their hyphen; without this every such
    # word becomes two useless half-tokens in the index.
    text = _HYPHEN_BREAK_RE.sub(r"\1\2", text)
    text = text.replace("­", "")
    text = "\n".join(line.rstrip() for line in text.splitlines())
    text = _MULTI_SPACE_RE.sub(" ", text)
    text = _MULTI_NEWLINE_RE.sub("\n\n", text)
    return text.strip()
