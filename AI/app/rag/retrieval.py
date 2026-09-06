"""Vector retrieval with section weighting (Sections 9.1, 13.2, 19.3).

Two properties of these queries matter more than anything else in the file.

The owner filter is part of the same statement as the similarity ordering, never
a filter applied to the rows afterwards -- a post-filter would mean another
user's chunks were read, ranked, and only then discarded.

And the section weighting is applied inside the ORDER BY, so a limitations
paragraph can out-rank a slightly closer match from an introduction when the
task is gap analysis. That single idea is what makes gap analysis useful rather
than generic.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from uuid import UUID

from app.core.config import get_settings
from app.core.db import connection
from app.embeddings import encoder
from app.pipelines import sections as S

logger = logging.getLogger(__name__)

# Which sections each task boosts (Section 9.1).
SECTION_WEIGHTS: dict[str, list[str]] = {
    "research_gap": [S.LIMITATIONS, S.FUTURE_WORK, S.DISCUSSION],
    "comparison": [S.METHODOLOGY, S.RESULTS],
    "novelty": [S.ABSTRACT, S.INTRODUCTION, S.CONCLUSION],
    "summary": [S.ABSTRACT, S.INTRODUCTION, S.RESULTS, S.CONCLUSION],
    "literature_review": [S.ABSTRACT, S.METHODOLOGY, S.RESULTS, S.DISCUSSION],
    "keyword_extraction": [S.ABSTRACT, S.INTRODUCTION, S.METHODOLOGY],
    # General question answering is deliberately unweighted: there is no prior
    # about which section holds the answer.
    "qa": [],
}

_SELECT = """
    SELECT c.id                AS chunk_id,
           c.paper_id          AS paper_id,
           c.section_type      AS section_type,
           c.chunk_index       AS chunk_index,
           c.content           AS content,
           p.title             AS paper_title,
           p.publication_year  AS publication_year,
           1 - (c.embedding <=> %(embedding)s::vector) AS similarity,
           (1 - (c.embedding <=> %(embedding)s::vector))
             * CASE WHEN c.section_type = ANY(%(boost)s) THEN %(factor)s ELSE 1.0 END AS score
    FROM paper_chunks c
    JOIN papers p ON p.id = c.paper_id
    WHERE p.user_id = %(user_id)s
      AND c.embedding IS NOT NULL
      AND (%(project_id)s::uuid IS NULL OR p.project_id = %(project_id)s::uuid)
      AND (%(paper_id)s::uuid   IS NULL OR p.id = %(paper_id)s::uuid)
      AND (%(year)s::int        IS NULL OR p.publication_year = %(year)s::int)
      AND (%(tag)s::text        IS NULL OR p.tags @> ARRAY[%(tag)s::text])
    ORDER BY score DESC
    LIMIT %(top_k)s
"""


@dataclass
class Hit:
    chunk_id: UUID
    paper_id: UUID
    paper_title: str | None
    publication_year: int | None
    section_type: str | None
    chunk_index: int | None
    content: str
    similarity: float
    score: float

    def as_dict(self) -> dict:
        return {
            "chunk_id": str(self.chunk_id),
            "paper_id": str(self.paper_id),
            "paper_title": self.paper_title,
            "publication_year": self.publication_year,
            "section_type": self.section_type,
            "chunk_index": self.chunk_index,
            "content": self.content,
            "similarity": round(self.similarity, 4),
            "score": round(self.score, 4),
        }


def search(
    *,
    user_id: UUID,
    query: str,
    project_id: UUID | None = None,
    paper_id: UUID | None = None,
    task: str | None = None,
    top_k: int = 8,
    year: int | None = None,
    tag: str | None = None,
) -> tuple[list[Hit], int]:
    """Returns ranked hits and the size of the corpus they were drawn from."""
    settings = get_settings()
    embedding = encoder.encode_one(query)
    boost = SECTION_WEIGHTS.get(task or "", [])

    params = {
        "embedding": embedding,
        "user_id": str(user_id),
        "project_id": str(project_id) if project_id else None,
        "paper_id": str(paper_id) if paper_id else None,
        "year": year,
        "tag": tag,
        "boost": boost,
        "factor": settings.section_boost_factor,
        "top_k": top_k,
    }

    with connection() as conn, conn.cursor() as cur:
        cur.execute(_SELECT, params)
        rows = cur.fetchall()
        corpus_size = _corpus_size(cur, user_id, project_id, paper_id)

    hits = [
        Hit(
            chunk_id=row["chunk_id"],
            paper_id=row["paper_id"],
            paper_title=row["paper_title"],
            publication_year=row["publication_year"],
            section_type=row["section_type"],
            chunk_index=row["chunk_index"],
            content=row["content"],
            similarity=float(row["similarity"]),
            score=float(row["score"]),
        )
        for row in rows
    ]
    return _deduplicate(hits), corpus_size


def _corpus_size(cur, user_id: UUID, project_id: UUID | None, paper_id: UUID | None) -> int:
    """How many indexed papers the answer could have drawn from.

    Reported alongside every result because a gap found across five papers is
    not the same claim as one found across fifty (Section 14.5).
    """
    cur.execute(
        """
        SELECT COUNT(DISTINCT p.id) AS n
        FROM papers p
        WHERE p.user_id = %(user_id)s
          AND p.processing_status = 'completed'
          AND (%(project_id)s::uuid IS NULL OR p.project_id = %(project_id)s::uuid)
          AND (%(paper_id)s::uuid   IS NULL OR p.id = %(paper_id)s::uuid)
        """,
        {
            "user_id": str(user_id),
            "project_id": str(project_id) if project_id else None,
            "paper_id": str(paper_id) if paper_id else None,
        },
    )
    row = cur.fetchone()
    return int(row["n"]) if row else 0


def _deduplicate(hits: list[Hit]) -> list[Hit]:
    """Drops near-identical chunks (Section 13.3, rule 3).

    Overlapping chunks and boilerplate repeated across a paper otherwise spend
    the context budget saying the same thing several times.
    """
    kept: list[Hit] = []
    seen: list[str] = []
    for hit in hits:
        fingerprint = " ".join(hit.content.lower().split())[:220]
        if any(_close(fingerprint, other) for other in seen):
            continue
        seen.append(fingerprint)
        kept.append(hit)
    return kept


def _close(a: str, b: str) -> bool:
    if a == b:
        return True
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    return len(shorter) > 80 and shorter[:120] == longer[:120]


def evidence_is_sufficient(hits: list[Hit]) -> bool:
    """Whether the best hit clears the relevance floor (Section 13.3, rule 7).

    When it does not, the caller refuses rather than calling a model. The best
    behaviour available to a RAG system is often to decline to answer.
    """
    if not hits:
        return False
    return max(hit.similarity for hit in hits) >= get_settings().retrieval_min_score


def load_chunks(chunk_ids: list[UUID], user_id: UUID) -> list[Hit]:
    """Re-reads chunks by id, scoped to their owner.

    Analysis endpoints receive ids rather than text, so this is where the
    ownership check is repeated. A chunk id belonging to someone else simply
    does not come back.
    """
    if not chunk_ids:
        return []
    with connection() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT c.id AS chunk_id, c.paper_id, c.section_type, c.chunk_index, c.content,
                   p.title AS paper_title, p.publication_year
            FROM paper_chunks c
            JOIN papers p ON p.id = c.paper_id
            WHERE p.user_id = %(user_id)s AND c.id = ANY(%(ids)s::uuid[])
            ORDER BY p.title NULLS LAST, c.chunk_index
            """,
            {"user_id": str(user_id), "ids": [str(cid) for cid in chunk_ids]},
        )
        rows = cur.fetchall()
    return [
        Hit(
            chunk_id=row["chunk_id"],
            paper_id=row["paper_id"],
            paper_title=row["paper_title"],
            publication_year=row["publication_year"],
            section_type=row["section_type"],
            chunk_index=row["chunk_index"],
            content=row["content"],
            similarity=1.0,
            score=1.0,
        )
        for row in rows
    ]


def find_duplicate(user_id: UUID, paper_id: UUID, embedding: list[float]) -> dict | None:
    """Looks for an existing paper whose opening text is nearly identical.

    Cheap, because the vector index already exists, and it catches the same
    paper uploaded twice under a different filename (Section 9.6).
    """
    threshold = get_settings().duplicate_similarity_threshold
    with connection() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT c.paper_id,
                   p.title,
                   MAX(1 - (c.embedding <=> %(embedding)s::vector)) AS similarity
            FROM paper_chunks c
            JOIN papers p ON p.id = c.paper_id
            WHERE p.user_id = %(user_id)s
              AND c.paper_id <> %(paper_id)s
              AND c.chunk_index < 3
              AND c.embedding IS NOT NULL
            GROUP BY c.paper_id, p.title
            ORDER BY similarity DESC
            LIMIT 1
            """,
            {"embedding": embedding, "user_id": str(user_id), "paper_id": str(paper_id)},
        )
        row = cur.fetchone()

    if not row or float(row["similarity"]) < threshold:
        return None
    return {
        "paper_id": str(row["paper_id"]),
        "title": row["title"],
        "similarity": round(float(row["similarity"]), 4),
    }
