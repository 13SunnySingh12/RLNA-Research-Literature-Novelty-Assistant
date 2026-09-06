"""Internal API surface.

The shared token is the entire trust boundary for this service, so it is tested
directly (Sections 21.3 and 22.2 rule 4).
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app

# Whatever the service is actually configured with, rather than a literal.
# conftest supplies a default with setdefault, so an ALLOWED_CALLER_TOKEN
# already in the environment wins -- which is exactly what CI does. Hard-coding
# the value here meant the suite presented one token while the app expected
# another, and the authenticated request came back 401 instead of reaching
# validation. It failed for any developer with the variable exported, too.
TOKEN = get_settings().allowed_caller_token

INTERNAL_ROUTES = [
    "/internal/process-pdf", "/internal/embed", "/internal/retrieve",
    "/internal/summarize", "/internal/extract-concepts", "/internal/question",
    "/internal/compare", "/internal/research-gap", "/internal/novelty",
    "/internal/literature-review", "/internal/render-pdf",
]


@pytest.fixture
def client():
    # Constructed without the context manager so the lifespan hook does not run:
    # these tests are about routing and authentication, not about loading an
    # embedding model or opening a connection pool.
    return TestClient(app, raise_server_exceptions=False)


@pytest.mark.parametrize("path", INTERNAL_ROUTES)
def test_every_internal_route_requires_the_token(client, path):
    assert client.post(path, json={}).status_code == 401


@pytest.mark.parametrize("path", INTERNAL_ROUTES)
def test_a_wrong_token_is_rejected(client, path):
    response = client.post(path, json={}, headers={"Authorization": "Bearer wrong-token-value"})
    assert response.status_code == 401


def test_a_non_bearer_scheme_is_rejected(client):
    response = client.post("/internal/embed", json={"texts": ["x"]},
                           headers={"Authorization": f"Basic {TOKEN}"})
    assert response.status_code == 401


def test_the_rejection_never_echoes_the_expected_token(client):
    body = client.post("/internal/embed", json={}, headers={"Authorization": "Bearer nope"}).text
    assert TOKEN not in body


def test_an_authenticated_request_gets_past_auth_to_validation(client):
    """A valid token but an invalid body must fail validation, not authentication."""
    response = client.post("/internal/retrieve", json={"query": "x"},
                           headers={"Authorization": f"Bearer {TOKEN}"})
    assert response.status_code == 422


def test_interactive_docs_are_not_exposed(client):
    """The service is internal; publishing a schema browser widens its surface."""
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404


def test_health_is_reachable_without_a_token(client):
    # The platform's health probe cannot present a credential.
    assert client.get("/health").status_code == 200


def test_pdf_rendering_produces_a_real_pdf():
    from app.pipelines import rendering

    markdown = (
        "# Novelty assessment\n\n"
        "## Overlap\n\n- Existing work covers X\n\n"
        "> Quoted evidence from a paper.\n\n"
        "| Dimension | Paper A |\n|---|---|\n| Dataset | Not reported |\n\n"
        "---\n\n**Confidence:** low\n"
    )
    pdf_bytes = rendering.markdown_to_pdf(markdown, "Novelty assessment")
    assert pdf_bytes.startswith(b"%PDF-")
    assert len(pdf_bytes) > 1000


def test_rendered_markup_from_model_output_cannot_inject_html():
    from app.pipelines import rendering

    html = rendering._to_html("<script>alert(1)</script> and **bold**", "T")
    assert "<script>" not in html
    assert "&lt;script&gt;" in html
    assert "<b>bold</b>" in html
