"""The analysis tasks (Sections 11, 14, 15).

Each function follows the same shape: load the evidence Spring Boot already
retrieved and authorized, build a fenced context from it, run the task through
the router, then post-process the validated output so that only claims backed by
that evidence survive.

The post-processing is the part that matters. A model can be wrong, or steered,
and a claim citing a chunk that was never in the context is dropped here rather
than rendered to a user.
"""

from __future__ import annotations

import logging
from uuid import UUID

from starlette.concurrency import run_in_threadpool

from app.core.errors import InsufficientEvidence
from app.integrations import router
from app.models import outputs
from app.models.requests import (
    CompareRequest,
    ConceptsRequest,
    LiteratureReviewRequest,
    NoveltyRequest,
    QuestionRequest,
    ResearchGapRequest,
    SummarizeRequest,
)
from app.prompts import templates
from app.rag import context as context_builder
from app.rag import retrieval

logger = logging.getLogger(__name__)


async def _load_evidence(chunk_ids: list[UUID], user_id: UUID) -> list[retrieval.Hit]:
    # psycopg's pool is synchronous, so this has to leave the event loop. It runs
    # inside async handlers that also make provider calls, and a slow database
    # round trip here would otherwise stall every other request on the instance
    # rather than just this one.
    hits = await run_in_threadpool(retrieval.load_chunks, chunk_ids, user_id)
    if not hits:
        raise InsufficientEvidence(
            "The evidence for this request is no longer available. Try running it again."
        )
    return hits


def _envelope(result, routed: router.RoutedResult, corpus_size: int, confidence: str | None = None) -> dict:
    return {
        "result": result.model_dump(),
        "provider": routed.provider,
        "model_id": routed.model,
        "confidence": confidence or getattr(result, "confidence", None),
        "corpus_size": corpus_size,
        "evidence_sufficient": True,
    }


# --------------------------------------------------------------------------

async def summarize(request: SummarizeRequest) -> dict:
    hits = await _load_evidence(request.chunk_ids, request.user_id)
    title = next((h.paper_title for h in hits if h.paper_title), "Untitled paper")
    routed = await router.complete_structured(
        task="summary",
        system=templates.SUMMARY_SYSTEM,
        user=templates.summary_user(title, context_builder.build(hits)),
        schema=outputs.SummaryOutput,
    )
    return _envelope(routed.parsed, routed, request.corpus_size, confidence="medium")


async def extract_concepts(request: ConceptsRequest) -> dict:
    from app.pipelines import indexing

    hits = await _load_evidence(request.chunk_ids, request.user_id)
    title = next((h.paper_title for h in hits if h.paper_title), "Untitled paper")
    routed = await router.complete_structured(
        task="keyword_extraction",
        system=templates.CONCEPTS_SYSTEM,
        user=templates.concepts_user(title, context_builder.build(hits)),
        schema=outputs.ConceptsOutput,
    )
    result: outputs.ConceptsOutput = routed.parsed
    stored = await run_in_threadpool(indexing.store_concepts, request.paper_id, result.concepts)
    envelope = _envelope(result, routed, request.corpus_size, confidence="medium")
    envelope["stored_concepts"] = stored
    return envelope


async def answer_question(request: QuestionRequest) -> dict:
    hits = await _load_evidence(request.chunk_ids, request.user_id)
    if not retrieval.evidence_is_sufficient(hits):
        # Reached only when Spring Boot's own check was borderline. Refusing is
        # the correct behaviour, not a failure (Section 13.3, rule 7).
        raise InsufficientEvidence()

    routed = await router.complete_structured(
        task="qa",
        system=templates.QA_SYSTEM,
        user=templates.qa_user(request.question, context_builder.build(hits)),
        schema=outputs.QaOutput,
    )
    result: outputs.QaOutput = routed.parsed

    allowed = context_builder.citable_ids(hits)
    quarantined = context_builder.suspicious_ids(hits)
    result.claims = context_builder.drop_unsupported_claims(result.claims, allowed)
    # Titles travel alongside the claims so the UI can label a citation without
    # a second lookup.
    titles = context_builder.paper_titles(hits)
    if not result.claims and result.evidence_sufficient:
        # Every claim was unsupported, so the answer is not grounded whatever the
        # model asserted about itself.
        result.evidence_sufficient = False
        result.confidence = "low"
        if quarantined:
            # Dropping the claims is not enough on its own: the prose still
            # carries whatever the injected text talked the model into saying,
            # and that is the part a reader would believe.
            result.answer = (
                "This paper contains text written to instruct an assistant rather than to "
                "report research, so an answer drawn from it cannot be trusted. Nothing here "
                "is reliable enough to answer from."
            )
            result.limitations = (
                "Passages in this document attempt to direct the assistant's output. They were "
                "excluded from the evidence."
            )

    envelope = _envelope(result, routed, request.corpus_size)
    envelope["evidence_sufficient"] = result.evidence_sufficient
    envelope["paper_titles"] = titles
    return envelope


async def compare(request: CompareRequest) -> dict:
    from app.core.config import get_settings

    context_by_paper: dict[str, str] = {}
    papers: list[dict] = []
    all_hits: list[retrieval.Hit] = []
    per_paper_budget = get_settings().max_context_characters // max(1, len(request.papers))

    for paper in request.papers:
        hits = await run_in_threadpool(retrieval.load_chunks, paper.chunk_ids, request.user_id)
        if not hits:
            raise InsufficientEvidence(
                f'"{paper.title or "A selected paper"}" has no indexed text to compare.'
            )
        all_hits.extend(hits)
        title = paper.title or next((h.paper_title for h in hits if h.paper_title), "Untitled paper")
        papers.append({"paper_id": str(paper.paper_id), "title": title})
        # Each paper gets an equal slice of the one budget, so a long paper
        # cannot crowd the others out and the whole prompt still fits inside the
        # limit the providers actually enforce.
        context_by_paper[str(paper.paper_id)] = context_builder.build(
            hits, budget_characters=per_paper_budget
        )

    routed = await router.complete_structured(
        task="comparison",
        system=templates.COMPARISON_SYSTEM,
        user=templates.comparison_user(papers, context_by_paper),
        schema=outputs.ComparisonOutput,
    )
    result: outputs.ComparisonOutput = routed.parsed
    if not result.papers:
        result.papers = [outputs.ComparedPaper(**p) for p in papers]
    # A short row would silently misalign the table columns.
    for dimension in result.dimensions:
        while len(dimension.values) < len(papers):
            dimension.values.append("Not reported")
    return _envelope(result, routed, request.corpus_size)


async def research_gap(request: ResearchGapRequest) -> dict:
    hits = await _load_evidence(request.chunk_ids, request.user_id)
    topic = ""
    routed = await router.complete_structured(
        task="research_gap",
        system=templates.RESEARCH_GAP_SYSTEM,
        user=templates.research_gap_user(topic, request.corpus_size, context_builder.build(hits)),
        schema=outputs.ResearchGapOutput,
    )
    result: outputs.ResearchGapOutput = routed.parsed

    allowed = context_builder.citable_ids(hits)
    titles = context_builder.paper_titles(hits)
    owners = context_builder.chunk_owners(hits)
    sources = context_builder.chunk_text(hits)
    kept: list[outputs.ResearchGap] = []
    for gap in result.gaps:
        gap.evidence = [
            e for e in gap.evidence
            if e.chunk_id and str(e.chunk_id) in allowed
            and context_builder.quote_is_supported(e.quote, sources.get(str(e.chunk_id), ""))
        ]
        if not gap.evidence:
            # A gap with no evidence behind it is discarded, not displayed
            # (Section 14.5).
            continue
        for evidence in gap.evidence:
            # The chunk decides which paper it came from. A model that cites a
            # real passage against the wrong paper is fabricating provenance
            # just as much as one inventing the passage.
            owner = owners.get(str(evidence.chunk_id))
            if owner:
                evidence.paper_id = owner
            evidence.paper_title = titles.get(str(evidence.paper_id))
        gap.confidence = _bounded_confidence(gap.confidence, request.corpus_size)
        kept.append(gap)

    result.gaps = kept
    result.corpus_size = request.corpus_size
    result.limitations = (
        result.limitations
        or f"Based only on the {request.corpus_size} paper(s) indexed in this project."
    )
    overall = "low" if not kept else _bounded_confidence(kept[0].confidence, request.corpus_size)
    return _envelope(result, routed, request.corpus_size, confidence=overall)


async def literature_review(request: LiteratureReviewRequest) -> dict:
    hits = await _load_evidence(request.chunk_ids, request.user_id)
    routed = await router.complete_structured(
        task="literature_review",
        system=templates.LITERATURE_REVIEW_SYSTEM,
        user=templates.literature_review_user(
            request.topic, request.corpus_size, context_builder.build(hits)
        ),
        schema=outputs.LiteratureReviewOutput,
    )
    result: outputs.LiteratureReviewOutput = routed.parsed

    # Only papers that actually contributed evidence may be cited (Section 9.5).
    contributed = context_builder.paper_titles(hits)
    result.references = [
        ref for ref in result.references if ref.paper_id and str(ref.paper_id) in contributed
    ]
    if not result.references:
        result.references = [
            outputs.ReviewReference(paper_id=pid, title=title)
            for pid, title in contributed.items()
        ]
    result.confidence = _bounded_confidence(result.confidence, request.corpus_size)
    return _envelope(result, routed, request.corpus_size)


async def novelty(request: NoveltyRequest) -> dict:
    hits = await run_in_threadpool(retrieval.load_chunks, request.chunk_ids, request.user_id)
    if not hits and not request.academic_results:
        raise InsufficientEvidence("There is nothing to compare this idea against yet.")

    corpus_size = request.library_corpus_size
    user_prompt = templates.novelty_user(
        request.idea, corpus_size, context_builder.build(hits), request.academic_results
    )
    routed = await router.complete_structured(
        task="novelty",
        system=templates.NOVELTY_SYSTEM,
        user=user_prompt,
        schema=outputs.NoveltyOutput,
    )
    result: outputs.NoveltyOutput = routed.parsed

    allowed = context_builder.citable_ids(hits)
    owners = context_builder.chunk_owners(hits)
    sources = context_builder.chunk_text(hits)
    result.evidence = [
        e for e in result.evidence
        if e.chunk_id and str(e.chunk_id) in allowed
        and context_builder.quote_is_supported(e.quote, sources.get(str(e.chunk_id), ""))
    ]
    for evidence in result.evidence:
        owner = owners.get(str(evidence.chunk_id))
        if owner:
            evidence.paper_id = owner
    result.confidence = _bounded_confidence(result.confidence, corpus_size, hits)
    result.limitations = _novelty_limitations(result.limitations, corpus_size, len(request.academic_results))

    envelope = _envelope(result, routed, corpus_size)
    envelope["verification"] = (await _verify_novelty(user_prompt, result)).model_dump()
    return envelope


async def _verify_novelty(
    user_prompt: str, first: outputs.NoveltyOutput
) -> outputs.NoveltyVerification:
    """Optional second opinion from a different provider (Section 10).

    Deliberately limited to novelty assessment and off by default: it doubles
    tokens and latency, so it has to be a deliberate choice for the single
    highest-value task rather than a default for ordinary requests.
    """
    from app.core.config import get_settings

    if not get_settings().dual_model_verification:
        return outputs.NoveltyVerification(enabled=False)
    if len(router.available_providers()) < 2:
        return outputs.NoveltyVerification(enabled=True, second_opinion_available=False)

    try:
        second = await router.complete_structured(
            task="novelty",
            system=templates.NOVELTY_SYSTEM,
            user=user_prompt,
            schema=outputs.NoveltyOutput,
        )
        comparison = await router.complete_structured(
            task="classification",
            system=templates.NOVELTY_VERIFY_SYSTEM,
            user=templates.novelty_verify_user(
                first.model_dump_json(), second.parsed.model_dump_json()
            ),
            schema=outputs.NoveltyVerification,
        )
        verification: outputs.NoveltyVerification = comparison.parsed
        verification.enabled = True
        verification.second_opinion_available = True
        return verification
    except Exception:  # noqa: BLE001 - a failed second opinion must not fail the first
        logger.warning("Dual-model verification failed; returning the primary assessment only")
        return outputs.NoveltyVerification(enabled=True, second_opinion_available=False)


def _bounded_confidence(
    stated: str, corpus_size: int, hits: list[retrieval.Hit] | None = None
) -> str:
    """Caps the model's self-reported confidence against the evidence available.

    A model asked how sure it is will usually say "high". Confidence here is
    driven by corpus size and retrieval strength instead, and can only ever be
    lowered (Sections 14.5 and 15.4).
    """
    ceiling = "high"
    if corpus_size < 5:
        ceiling = "low"
    elif corpus_size < 15:
        ceiling = "medium"

    if hits:
        best = max((h.similarity for h in hits), default=0.0)
        if best < 0.5:
            ceiling = "low"
        elif best < 0.65 and ceiling == "high":
            ceiling = "medium"

    order = {"low": 0, "medium": 1, "high": 2}
    return stated if order.get(stated, 1) <= order[ceiling] else ceiling


def _novelty_limitations(existing: str, corpus_size: int, external_count: int) -> str:
    base = (
        f"Based on {corpus_size} paper(s) retrieved from your library"
        + (f" and {external_count} record(s) from academic search" if external_count else "")
        + ". This is not a proof of novelty: the absence of similar work in this corpus does "
        "not mean none exists."
    )
    return f"{existing.strip()} {base}".strip() if existing else base
