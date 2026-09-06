"""Shared test configuration.

Tests never touch a real provider or a real database. Provider behaviour is
driven through respx so that failure paths -- rate limits, outages, malformed
output -- can be exercised deterministically (Section 29.2).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

# The service is imported as `app.*`, so the AI directory has to be importable.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ.setdefault("ALLOWED_CALLER_TOKEN", "test-token-that-is-long-enough-to-pass")
os.environ.setdefault("DATABASE_URL", "postgresql://test:test@localhost:5432/test")
os.environ.setdefault("AI_MAX_RETRIES", "1")
os.environ.setdefault("AI_PROVIDER_COOLDOWN_SECONDS", "60")

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"


@pytest.fixture(autouse=True)
def _reset_settings_and_providers():
    """Each test gets a clean settings cache and a clean provider/cooldown state."""
    from app.core.config import get_settings
    from app.integrations import router

    get_settings.cache_clear()
    router.reset_providers()
    yield
    get_settings.cache_clear()
    router.reset_providers()


@pytest.fixture
def all_providers(monkeypatch):
    """Configures all three providers so the fallback chain has somewhere to go."""
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.setenv("GROQ_API_KEY", "test-groq-key")
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-openrouter-key")
    from app.core.config import get_settings
    from app.integrations import router

    get_settings.cache_clear()
    router.reset_providers()
    return None


def openai_body(content: str) -> dict:
    return {"choices": [{"message": {"content": content}}]}


def gemini_body(content: str) -> dict:
    return {"candidates": [{"content": {"parts": [{"text": content}]}}]}
