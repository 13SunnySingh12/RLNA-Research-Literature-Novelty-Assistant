"""Structured output contracts (Section 11.1).

Every model reply is validated against one of these before it can be stored or
rendered. This is what makes "evidence-grounded" a property of the system rather
than a claim in a README: a reply that does not fit is repaired once and then
discarded, and a claim that cites a chunk which was not retrieved is dropped
before it can reach a user.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Confidence = Literal["high", "medium", "low"]


class StrictModel(BaseModel):
    # Unknown keys are dropped rather than accepted, so an injected field cannot
    # ride along into stored output.
    model_config = ConfigDict(extra="ignore", str_strip_whitespace=True)


# --------------------------------------------------------------------------
# Paper-level
# --------------------------------------------------------------------------

class SummaryOutput(StrictModel):
    research_problem: str = Field(default="", max_length=4000)
    approach: str = Field(default="", max_length=4000)
    key_findings: list[str] = Field(default_factory=list, max_length=12)
    limitations: list[str] = Field(default_factory=list, max_length=12)
    takeaway: str = Field(default="", max_length=600)


class ConceptItem(StrictModel):
    concept: str = Field(max_length=255)
    concept_type: Literal["keyword", "method", "dataset", "metric", "domain"] = "keyword"
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)


class ConceptsOutput(StrictModel):
    concepts: list[ConceptItem] = Field(default_factory=list, max_length=40)


class MetadataCleanupOutput(StrictModel):
    title: str | None = Field(default=None, max_length=1000)
    authors: list[str] = Field(default_factory=list, max_length=30)
    year: int | None = Field(default=None, ge=1900, le=2100)
    venue: str | None = Field(default=None, max_length=500)


# --------------------------------------------------------------------------
# Question answering
# --------------------------------------------------------------------------

class Claim(StrictModel):
    statement: str = Field(max_length=2000)
    chunk_id: str | None = None
    paper_id: str | None = None
    section: str | None = None


class QaOutput(StrictModel):
    answer: str = Field(default="", max_length=8000)
    claims: list[Claim] = Field(default_factory=list, max_length=20)
    evidence_sufficient: bool = True
    confidence: Confidence = "medium"
    limitations: str = Field(default="", max_length=2000)


# --------------------------------------------------------------------------
# Comparison
# --------------------------------------------------------------------------

class ComparisonDimension(StrictModel):
    name: str = Field(max_length=120)
    # One value per compared paper, in the order the papers were supplied.
    # "Not reported" is a required answer, never an inferred one (Section 8.8).
    values: list[str] = Field(default_factory=list, max_length=3)


class ComparedPaper(StrictModel):
    paper_id: str
    title: str = Field(max_length=1000)


class ComparisonOutput(StrictModel):
    papers: list[ComparedPaper] = Field(default_factory=list, max_length=3)
    dimensions: list[ComparisonDimension] = Field(default_factory=list, max_length=15)
    confidence: Confidence = "medium"
    limitations: str = Field(default="", max_length=2000)


# --------------------------------------------------------------------------
# Research gaps
# --------------------------------------------------------------------------

class GapEvidence(StrictModel):
    chunk_id: str | None = None
    paper_id: str | None = None
    paper_title: str | None = Field(default=None, max_length=1000)
    section: str | None = None
    quote: str = Field(default="", max_length=1200)


class ResearchGap(StrictModel):
    statement: str = Field(max_length=1500)
    supporting_papers: list[str] = Field(default_factory=list, max_length=30)
    evidence: list[GapEvidence] = Field(default_factory=list, max_length=10)
    why_underexplored: str = Field(default="", max_length=2000)
    confidence: Confidence = "low"


class ResearchGapOutput(StrictModel):
    gaps: list[ResearchGap] = Field(default_factory=list, max_length=10)
    corpus_size: int = 0
    limitations: str = Field(default="", max_length=2000)


# --------------------------------------------------------------------------
# Novelty
# --------------------------------------------------------------------------

class SimilarWork(StrictModel):
    title: str = Field(max_length=1000)
    paper_id: str | None = None
    external_id: str | None = None
    similarity: float | None = Field(default=None, ge=0.0, le=1.0)
    why_similar: str = Field(default="", max_length=1500)


class NoveltyOutput(StrictModel):
    """Note the absence of any single numeric novelty score.

    A percentage would imply a precision this system does not have, so the
    output is structured evidence plus a confidence band instead (Section 34).
    """

    similar_work: list[SimilarWork] = Field(default_factory=list, max_length=15)
    overlap: list[str] = Field(default_factory=list, max_length=12)
    existing_methodologies: list[str] = Field(default_factory=list, max_length=12)
    differences: list[str] = Field(default_factory=list, max_length=12)
    potentially_novel: list[str] = Field(default_factory=list, max_length=12)
    evidence: list[GapEvidence] = Field(default_factory=list, max_length=20)
    confidence: Confidence = "low"
    limitations: str = Field(default="", max_length=2000)


class NoveltyVerification(StrictModel):
    """Result of running two providers over the same evidence (Section 10)."""

    enabled: bool = False
    agreements: list[str] = Field(default_factory=list, max_length=12)
    disagreements: list[str] = Field(default_factory=list, max_length=12)
    second_opinion_available: bool = False


# --------------------------------------------------------------------------
# Literature review
# --------------------------------------------------------------------------

class ReviewTheme(StrictModel):
    name: str = Field(max_length=200)
    description: str = Field(default="", max_length=3000)
    supporting_papers: list[str] = Field(default_factory=list, max_length=30)


class ReviewReference(StrictModel):
    paper_id: str | None = None
    title: str = Field(max_length=1000)
    year: int | None = None


class LiteratureReviewOutput(StrictModel):
    overview: str = Field(default="", max_length=6000)
    themes: list[ReviewTheme] = Field(default_factory=list, max_length=10)
    methodologies: list[str] = Field(default_factory=list, max_length=15)
    datasets: list[str] = Field(default_factory=list, max_length=15)
    agreements: list[str] = Field(default_factory=list, max_length=12)
    contradictions: list[str] = Field(default_factory=list, max_length=12)
    limitations: list[str] = Field(default_factory=list, max_length=12)
    references: list[ReviewReference] = Field(default_factory=list, max_length=60)
    confidence: Confidence = "medium"
