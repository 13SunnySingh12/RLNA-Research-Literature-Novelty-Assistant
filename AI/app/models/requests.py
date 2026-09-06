"""Request bodies for the internal API.

Every field the caller sends is validated here. Spring Boot has already checked
authorization, but the ids it sends are still typed and bounded on arrival --
validation and trust are different things.
"""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class InternalRequest(BaseModel):
    model_config = ConfigDict(extra="ignore", str_strip_whitespace=True)


class ProcessPdfRequest(InternalRequest):
    job_id: UUID
    paper_id: UUID
    user_id: UUID
    project_id: UUID | None = None
    object_key: str = Field(min_length=1, max_length=500)
    fallback_title: str | None = Field(default=None, max_length=1000)


class RetrieveRequest(InternalRequest):
    user_id: UUID
    project_id: UUID | None = None
    paper_id: UUID | None = None
    query: str = Field(min_length=1, max_length=4000)
    top_k: int = Field(default=8, ge=1, le=50)
    task: str | None = None
    year: int | None = None
    tag: str | None = Field(default=None, max_length=60)


class EvidenceRequest(InternalRequest):
    """Base for analyses that operate on chunks already retrieved by Spring Boot."""

    user_id: UUID
    chunk_ids: list[UUID] = Field(default_factory=list, max_length=200)
    corpus_size: int = 0


class SummarizeRequest(EvidenceRequest):
    paper_id: UUID


class ConceptsRequest(EvidenceRequest):
    paper_id: UUID


class QuestionRequest(EvidenceRequest):
    question: str = Field(min_length=1, max_length=2000)


class ComparePaper(InternalRequest):
    paper_id: UUID
    title: str = Field(default="", max_length=1000)
    chunk_ids: list[UUID] = Field(default_factory=list, max_length=80)


class CompareRequest(InternalRequest):
    user_id: UUID
    papers: list[ComparePaper] = Field(min_length=2, max_length=3)
    corpus_size: int = 0


class ResearchGapRequest(EvidenceRequest):
    project_id: UUID


class LiteratureReviewRequest(EvidenceRequest):
    project_id: UUID
    topic: str = Field(default="", max_length=500)


class AcademicResult(InternalRequest):
    external_id: str | None = None
    title: str | None = Field(default=None, max_length=1000)
    abstract: str | None = None
    year: int | None = None
    venue: str | None = Field(default=None, max_length=500)


class NoveltyRequest(EvidenceRequest):
    project_id: UUID
    idea: str = Field(min_length=10, max_length=4000)
    library_corpus_size: int = 0
    academic_results: list[AcademicResult] = Field(default_factory=list, max_length=25)


class EmbedRequest(InternalRequest):
    texts: list[str] = Field(min_length=1, max_length=64)


class RenderPdfRequest(InternalRequest):
    title: str = Field(default="Export", max_length=300)
    markdown: str = Field(min_length=1, max_length=500_000)
