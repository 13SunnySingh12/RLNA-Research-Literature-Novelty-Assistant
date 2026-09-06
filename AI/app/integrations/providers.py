"""LLM provider clients.

Each client turns a prompt into raw model text and normalizes failures into two
kinds: rate limited, or unavailable. The router above them cares about nothing
else, which is what keeps adding a fourth provider a matter of writing one class
rather than touching the calling code.
"""

from __future__ import annotations

import logging
import re
from email.utils import parsedate_to_datetime
from datetime import datetime, timezone
from abc import ABC, abstractmethod
from dataclasses import dataclass

import httpx

from app.core.config import get_settings

logger = logging.getLogger(__name__)


class ProviderRateLimited(Exception):
    """The provider refused the call and will keep refusing for a while.

    Carries the wait the provider itself asked for, when it said. A fixed
    cooldown guesses, and guessing short is expensive: a provider reporting a
    exhausted daily quota was being retried a minute later, every minute.
    """

    def __init__(self, message: str, retry_after: float | None = None) -> None:
        super().__init__(message)
        self.retry_after = retry_after


def _seconds_from_duration(value: str) -> float | None:
    """Parses the shapes providers use for a wait: `30`, `1m26.4s`, `3.51s`."""
    text = (value or "").strip().lower()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        pass
    match = re.fullmatch(r"(?:(\d+(?:\.\d+)?)m)?(?:(\d+(?:\.\d+)?)s)?", text)
    if not match or not any(match.groups()):
        return None
    minutes = float(match.group(1) or 0)
    seconds = float(match.group(2) or 0)
    return minutes * 60 + seconds


def retry_after_seconds(response: httpx.Response) -> float | None:
    """How long the provider says to wait, from whichever channel it used.

    Groq sends `x-ratelimit-reset-*` on every response; Gemini puts a
    `retryDelay` in the error body; the HTTP `Retry-After` header is the common
    fallback. Returns None when the provider said nothing, leaving the caller to
    apply its own default.
    """
    header = response.headers.get("retry-after")
    if header:
        seconds = _seconds_from_duration(header)
        if seconds is not None:
            return seconds
        try:  # RFC 7231 allows an HTTP date instead of a delta.
            when = parsedate_to_datetime(header)
            return max(0.0, (when - datetime.now(timezone.utc)).total_seconds())
        except (TypeError, ValueError):
            pass

    for name in ("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens"):
        seconds = _seconds_from_duration(response.headers.get(name, ""))
        if seconds is not None:
            return seconds

    try:  # Gemini: {"error": {"details": [{"@type": ...RetryInfo, "retryDelay": "57s"}]}}
        for detail in response.json().get("error", {}).get("details", []) or []:
            seconds = _seconds_from_duration(str(detail.get("retryDelay", "")))
            if seconds is not None:
                return seconds
    except (ValueError, AttributeError, TypeError):
        pass
    return None


class ProviderUnavailable(Exception):
    """The provider failed in a way another provider might not."""


@dataclass(frozen=True)
class Completion:
    text: str
    provider: str
    model: str


class Provider(ABC):
    name: str

    @abstractmethod
    def model_for(self, tier: str) -> str: ...

    @abstractmethod
    def configured(self) -> bool: ...

    @abstractmethod
    async def complete(
        self, *, system: str, user: str, tier: str, client: httpx.AsyncClient
    ) -> Completion: ...


class OpenAICompatibleProvider(Provider):
    """Groq and OpenRouter both expose the OpenAI chat completions shape."""

    def __init__(self, name: str, base_url: str, api_key: str, model: str, fast_model: str,
                 extra_headers: dict[str, str] | None = None) -> None:
        self.name = name
        self._base_url = base_url
        self._api_key = api_key
        self._model = model
        self._fast_model = fast_model
        self._extra_headers = extra_headers or {}

    def configured(self) -> bool:
        return bool(self._api_key)

    def model_for(self, tier: str) -> str:
        return self._fast_model if tier == "fast" else self._model

    async def complete(self, *, system: str, user: str, tier: str,
                       client: httpx.AsyncClient) -> Completion:
        model = self.model_for(tier)
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            # Structured output is a hard requirement, not a preference
            # (Section 11.1).
            "response_format": {"type": "json_object"},
            "temperature": 0.2,
        }
        headers = {"Authorization": f"Bearer {self._api_key}", **self._extra_headers}
        try:
            response = await client.post(self._base_url, json=payload, headers=headers)
        except httpx.HTTPError as exc:
            raise ProviderUnavailable(f"{self.name} transport failure") from exc

        if response.status_code == 429:
            raise ProviderRateLimited(f"{self.name} rate limited", retry_after_seconds(response))
        if response.status_code >= 400:
            # The body may contain the request payload back; log the status only.
            logger.warning("%s returned HTTP %s", self.name, response.status_code)
            raise ProviderUnavailable(f"{self.name} returned {response.status_code}")

        data = response.json()
        try:
            text = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise ProviderUnavailable(f"{self.name} returned an unusable response") from exc
        if not text:
            raise ProviderUnavailable(f"{self.name} returned an empty response")
        return Completion(text=text, provider=self.name, model=model)


class GeminiProvider(Provider):
    name = "gemini"

    def __init__(self, api_key: str, model: str, fast_model: str) -> None:
        self._api_key = api_key
        self._model = model
        self._fast_model = fast_model

    def configured(self) -> bool:
        return bool(self._api_key)

    def model_for(self, tier: str) -> str:
        return self._fast_model if tier == "fast" else self._model

    async def complete(self, *, system: str, user: str, tier: str,
                       client: httpx.AsyncClient) -> Completion:
        model = self.model_for(tier)
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        payload = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2},
        }
        try:
            response = await client.post(
                url, json=payload, headers={"x-goog-api-key": self._api_key}
            )
        except httpx.HTTPError as exc:
            raise ProviderUnavailable("gemini transport failure") from exc

        if response.status_code == 429:
            raise ProviderRateLimited("gemini rate limited", retry_after_seconds(response))
        if response.status_code >= 400:
            logger.warning("gemini returned HTTP %s", response.status_code)
            raise ProviderUnavailable(f"gemini returned {response.status_code}")

        data = response.json()
        try:
            parts = data["candidates"][0]["content"]["parts"]
            text = "".join(part.get("text", "") for part in parts)
        except (KeyError, IndexError, TypeError) as exc:
            raise ProviderUnavailable("gemini returned an unusable response") from exc
        if not text:
            # Usually a safety block or an exhausted token budget. Either way the
            # answer is unusable and another provider may do better.
            raise ProviderUnavailable("gemini returned an empty response")
        return Completion(text=text, provider=self.name, model=model)


def build_providers() -> dict[str, Provider]:
    settings = get_settings()
    return {
        "gemini": GeminiProvider(
            settings.gemini_api_key, settings.gemini_model, settings.gemini_fast_model
        ),
        "groq": OpenAICompatibleProvider(
            "groq",
            "https://api.groq.com/openai/v1/chat/completions",
            settings.groq_api_key,
            settings.groq_model,
            settings.groq_fast_model,
        ),
        "openrouter": OpenAICompatibleProvider(
            "openrouter",
            "https://openrouter.ai/api/v1/chat/completions",
            settings.openrouter_api_key,
            settings.openrouter_model,
            settings.openrouter_model,
            extra_headers={
                "HTTP-Referer": "https://github.com/rlna",
                "X-Title": "RLNA",
            },
        ),
    }
