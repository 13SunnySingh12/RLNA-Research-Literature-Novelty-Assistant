"""The indexing pipeline (Sections 8.4 and 13.1).

Download, extract, clean, detect sections, chunk, embed, store. Progress is
written to `processing_jobs` at each step, which is what lets the library show
"chunking" rather than a spinner, and what lets a user close the tab without
losing sight of where their upload got to.
"""

from __future__ import annotations

import logging
from uuid import UUID

import fitz
import psycopg

from app.core.config import get_settings
from app.core.errors import PermanentError
from app.core.db import connection
from app.embeddings import encoder
from app.integrations import storage
from app.models.requests import ProcessPdfRequest
from app.pipelines import chunking, metadata as metadata_extraction, pdf, sections as section_detection
from app.rag import retrieval

logger = logging.getLogger(__name__)


def set_progress(job_id: UUID, step: str, progress: int) -> None:
    """Records a step against the job.

    Deliberately its own committed write: progress that is only visible once the
    whole pipeline finishes is not progress.
    """
    try:
        with connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                UPDATE processing_jobs
                SET current_step = %(step)s, progress = %(progress)s,
                    status = 'running', updated_at = now()
                WHERE id = %(job_id)s
                """,
                {"step": step, "progress": progress, "job_id": str(job_id)},
            )
    except Exception:  # noqa: BLE001 - a progress write must never fail the job
        logger.warning("Could not record progress for job %s", job_id, exc_info=True)


def run(request: ProcessPdfRequest) -> dict:
    settings = get_settings()

    set_progress(request.job_id, "Downloading file", 10)
    pdf_bytes = storage.download(request.object_key)

    set_progress(request.job_id, "Extracting text", 25)
    document = pdf.extract(pdf_bytes)
    pdf_properties = _pdf_properties(pdf_bytes)

    set_progress(request.job_id, "Detecting sections", 40)
    detected = section_detection.detect(document.text)
    abstract_text = next(
        (s.content for s in detected if s.section_type == section_detection.ABSTRACT), None
    )

    paper_metadata = metadata_extraction.extract(
        document.first_page_text, abstract_text, pdf_properties, document.prominent_blocks
    )
    if not paper_metadata.title and request.fallback_title:
        paper_metadata.title = request.fallback_title

    set_progress(request.job_id, "Chunking", 55)
    # all-MiniLM-L6-v2 reads 256 tokens; the configured 512 would be halved on
    # encode, leaving the back of every chunk unsearchable. Clamping keeps the
    # stored chunk and the vector that finds it describing the same text.
    chunk_size = min(settings.chunk_size_tokens, encoder.max_sequence_tokens())
    if chunk_size < settings.chunk_size_tokens:
        _warn_chunk_clamped(settings.chunk_size_tokens, chunk_size)

    chunks = chunking.build(
        detected,
        count_tokens=encoder.count_tokens,
        chunk_size=chunk_size,
        overlap=min(settings.chunk_overlap_tokens, max(1, chunk_size // 4)),
    )
    if not chunks:
        raise PermanentError(
            "NO_EXTRACTABLE_TEXT",
            "No indexable text was found in this PDF once headers and references were removed.",
        )

    set_progress(request.job_id, "Generating embeddings", 70)
    vectors = encoder.encode([chunk.content for chunk in chunks])

    set_progress(request.job_id, "Storing index", 90)
    try:
        _store(request.paper_id, detected, chunks, vectors)
    except psycopg.DataError as exc:
        # The text this PDF produced is something PostgreSQL will not accept.
        # Retrying cannot change that, so it is reported as permanent rather
        # than burning the retry budget on an identical failure.
        logger.error("Paper %s produced text PostgreSQL rejected: %s", request.paper_id, exc)
        raise PermanentError(
            "PDF_UNREADABLE", "The text extracted from this PDF could not be stored."
        ) from exc

    duplicate = None
    if vectors:
        # Compared on the opening chunks, which carry the title and abstract:
        # the same paper under a different filename looks identical there.
        duplicate = retrieval.find_duplicate(request.user_id, request.paper_id, vectors[0])
        if duplicate:
            logger.info(
                "Paper %s looks like a duplicate of %s (%.3f)",
                request.paper_id, duplicate["paper_id"], duplicate["similarity"],
            )

    return {
        "paper_id": str(request.paper_id),
        "chunk_count": len(chunks),
        "section_count": len(detected),
        "page_count": document.page_count,
        "metadata": paper_metadata.as_dict(),
        "duplicate_of": duplicate,
        "embedding_model": encoder.model_name(),
        # Consumed by the metadata refinement pass in the endpoint and removed
        # before the response is returned; the caller never sees it.
        "_first_page": document.first_page_text,
    }


def _pdf_properties(pdf_bytes: bytes) -> dict:
    try:
        with fitz.open(stream=pdf_bytes, filetype="pdf") as document:
            return dict(document.metadata or {})
    except Exception:  # noqa: BLE001 - embedded properties are a bonus, never required
        return {}


def _store(
    paper_id: UUID,
    detected: list[section_detection.Section],
    chunks: list[chunking.Chunk],
    vectors: list[list[float]],
) -> None:
    """Writes sections and chunks in one transaction.

    Re-indexing replaces what was there rather than adding to it, so a retried
    job cannot leave a paper with two copies of every chunk. Either the whole
    new index lands or the old one stays.
    """
    model_name = encoder.model_name()
    with connection() as conn:
        with conn.transaction(), conn.cursor() as cur:
            cur.execute("DELETE FROM paper_chunks WHERE paper_id = %s", (str(paper_id),))
            cur.execute("DELETE FROM paper_sections WHERE paper_id = %s", (str(paper_id),))

            section_ids: dict[int, str] = {}
            for section in detected:
                cur.execute(
                    """
                    INSERT INTO paper_sections (paper_id, section_type, heading, content, order_index)
                    VALUES (%s, %s, %s, %s, %s)
                    RETURNING id
                    """,
                    (
                        str(paper_id),
                        section.section_type,
                        section.heading[:500] if section.heading else None,
                        section.content,
                        section.order_index,
                    ),
                )
                section_ids[section.order_index] = cur.fetchone()["id"]

            cur.executemany(
                """
                INSERT INTO paper_chunks
                    (paper_id, section_id, section_type, chunk_index, content,
                     token_count, embedding, embedding_model)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                """,
                [
                    (
                        str(paper_id),
                        section_ids.get(chunk.section_index),
                        chunk.section_type,
                        chunk.chunk_index,
                        chunk.content,
                        chunk.token_count,
                        vector,
                        model_name,
                    )
                    for chunk, vector in zip(chunks, vectors, strict=True)
                ],
            )


def store_concepts(paper_id: UUID, concepts: list) -> int:
    """Replaces a paper's extracted concepts.

    The unique constraint on (paper, concept, type) means a re-run cannot
    duplicate rows even if the model returns the same term twice.
    """
    if not concepts:
        return 0
    with connection() as conn:
        with conn.transaction(), conn.cursor() as cur:
            cur.execute("DELETE FROM concepts WHERE paper_id = %s", (str(paper_id),))
            cur.executemany(
                """
                INSERT INTO concepts (paper_id, concept, concept_type, confidence)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (paper_id, concept, concept_type) DO NOTHING
                """,
                [
                    (str(paper_id), item.concept[:255], item.concept_type, item.confidence)
                    for item in concepts
                ],
            )
    return len(concepts)


_chunk_clamp_warned = False


def _warn_chunk_clamped(configured: int, effective: int) -> None:
    """Warns once per process rather than once per paper."""
    global _chunk_clamp_warned
    if _chunk_clamp_warned:
        return
    _chunk_clamp_warned = True
    logger.warning(
        "CHUNK_SIZE_TOKENS is %d but %s only reads %d tokens; chunking at %d so that "
        "no indexed text is invisible to search.",
        configured, encoder.model_name(), effective, effective,
    )
