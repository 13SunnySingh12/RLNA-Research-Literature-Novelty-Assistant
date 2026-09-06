"""RLNA AI service.

The internal API. Every route is authenticated by a shared token and receives
requests that Spring Boot has already authorized (Section 21.2). Nothing here is
reachable from a browser, and nothing here decides who may see what.
"""

from __future__ import annotations

import base64
import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from starlette.requests import Request

from app.core import db
from app.core.config import get_settings
from app.core.errors import ServiceError
from app.core.security import require_internal_token
from app.embeddings import encoder
from app.integrations import router as model_router
from app.integrations import storage
from app.models.requests import (
    CompareRequest,
    ConceptsRequest,
    EmbedRequest,
    LiteratureReviewRequest,
    NoveltyRequest,
    ProcessPdfRequest,
    QuestionRequest,
    RenderPdfRequest,
    ResearchGapRequest,
    RetrieveRequest,
    SummarizeRequest,
)
from app.pipelines import analysis, indexing, rendering
from app.pipelines import metadata as metadata_refinement
from app.pipelines.metadata import PaperMetadata
from app.rag import retrieval

logging.basicConfig(
    level=get_settings().log_level.upper(),
    format="%(asctime)s %(levelname)-5s %(name)s - %(message)s",
)
logger = logging.getLogger("rlna.ai")


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings = get_settings()
    logger.info(
        "Starting AI service | storage=%s providers=%s",
        "configured" if settings.storage_configured else "not configured",
        ",".join(model_router.available_providers()) or "none",
    )
    db.get_pool()
    # Loading the embedding model here means the first user request is not the
    # one that pays the several-second load cost.
    await run_in_threadpool(encoder.warm_up)
    yield
    db.close_pool()


app = FastAPI(
    title="RLNA AI Service",
    version="1.0.0",
    description="Internal document and AI service. Not publicly routable.",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)

_auth = [Depends(require_internal_token)]


@app.exception_handler(ServiceError)
async def service_error_handler(_: Request, exc: ServiceError) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})


@app.exception_handler(Exception)
async def unexpected_error_handler(_: Request, exc: Exception) -> JSONResponse:
    logger.error("Unhandled error in AI service", exc_info=exc)
    return JSONResponse(
        status_code=500,
        content={"error": {"code": "INTERNAL_ERROR", "message": "The AI service failed."}},
    )


# --------------------------------------------------------------------------
# Health
# --------------------------------------------------------------------------

@app.get("/health")
async def health() -> dict:
    """Unauthenticated so the platform can reach it, and free of anything sensitive."""
    settings = get_settings()
    return {
        "status": "ok",
        "service": "rlna-ai",
        "dependencies": {
            "database": "up" if await run_in_threadpool(db.healthy) else "unreachable",
            "storage": "up" if storage.available() else "not configured",
            "providers": model_router.available_providers() or "none configured",
        },
        "embedding_model": settings.embedding_model,
    }


# --------------------------------------------------------------------------
# Indexing
# --------------------------------------------------------------------------

@app.post("/internal/process-pdf", dependencies=_auth)
async def process_pdf(request: ProcessPdfRequest) -> dict:
    """Extract, section, chunk, embed and store one paper.

    Runs off the event loop: PDF parsing and embedding are CPU-bound, and holding
    the loop would stall every other request on the instance.
    """
    result = await run_in_threadpool(indexing.run, request)

    # Extraction is CPU work and refinement is a network call, so they are
    # separated: the heavy pass finishes off the event loop, then a single
    # fast-tier call tidies the bibliographic fields it recovered.
    first_page = result.pop("_first_page", "")
    heuristic = PaperMetadata(**result.get("metadata", {}))
    refined = await metadata_refinement.refine(heuristic, first_page)
    result["metadata"] = refined.as_dict()
    return result


@app.post("/internal/embed", dependencies=_auth)
async def embed(request: EmbedRequest) -> dict:
    vectors = await run_in_threadpool(encoder.encode, request.texts)
    return {"model": encoder.model_name(), "dimension": len(vectors[0]) if vectors else 0,
            "vectors": vectors}


# --------------------------------------------------------------------------
# Retrieval
# --------------------------------------------------------------------------

@app.post("/internal/retrieve", dependencies=_auth)
async def retrieve(request: RetrieveRequest) -> dict:
    hits, corpus_size = await run_in_threadpool(
        retrieval.search,
        user_id=request.user_id,
        query=request.query,
        project_id=request.project_id,
        paper_id=request.paper_id,
        task=request.task,
        top_k=request.top_k,
        year=request.year,
        tag=request.tag,
    )
    return {
        "chunks": [hit.as_dict() for hit in hits],
        "corpus_size": corpus_size,
        "evidence_sufficient": retrieval.evidence_is_sufficient(hits),
        "section_boost": retrieval.SECTION_WEIGHTS.get(request.task or "", []),
        # Returned so the caller can fingerprint the request against the model
        # that would answer it, without a second round trip (Section 25.2).
        "model_id": model_router.model_for(request.task or "qa"),
    }


# --------------------------------------------------------------------------
# Analysis
# --------------------------------------------------------------------------

@app.post("/internal/summarize", dependencies=_auth)
async def summarize(request: SummarizeRequest) -> dict:
    return await analysis.summarize(request)


@app.post("/internal/extract-concepts", dependencies=_auth)
async def extract_concepts(request: ConceptsRequest) -> dict:
    return await analysis.extract_concepts(request)


@app.post("/internal/question", dependencies=_auth)
async def question(request: QuestionRequest) -> dict:
    return await analysis.answer_question(request)


@app.post("/internal/compare", dependencies=_auth)
async def compare(request: CompareRequest) -> dict:
    return await analysis.compare(request)


@app.post("/internal/research-gap", dependencies=_auth)
async def research_gap(request: ResearchGapRequest) -> dict:
    return await analysis.research_gap(request)


@app.post("/internal/novelty", dependencies=_auth)
async def novelty(request: NoveltyRequest) -> dict:
    return await analysis.novelty(request)


@app.post("/internal/literature-review", dependencies=_auth)
async def literature_review(request: LiteratureReviewRequest) -> dict:
    return await analysis.literature_review(request)


# --------------------------------------------------------------------------
# Export rendering
# --------------------------------------------------------------------------

@app.post("/internal/render-pdf", dependencies=_auth)
async def render_pdf(request: RenderPdfRequest) -> dict:
    pdf_bytes = await run_in_threadpool(rendering.markdown_to_pdf, request.markdown, request.title)
    return {"pdf_base64": base64.b64encode(pdf_bytes).decode("ascii")}
