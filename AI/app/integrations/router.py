"""Two-tier model routing with automatic fallback (Section 12).

The routing itself is deliberately dumb: a static task-to-tier table, and a
fixed provider order per tier. Nothing is learned or adaptive, because nothing
here needs to be, and a table can be explained in one sentence.

What is not dumb is the failure handling -- bounded retries, per-provider
cooldown after a 429, and a fallback chain that ends in a clean message rather
than a stack trace.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass
from typing import Type, TypeVar

import httpx
from pydantic import BaseModel, ValidationError

from app.core.config import get_settings
from app.core.errors import TransientError
from app.integrations.providers import (
    Completion,
    Provider,
    ProviderRateLimited,
    ProviderUnavailable,
    build_providers,
)

logger = logging.getLogger(__name__)

TModel = TypeVar("TModel", bound=BaseModel)

# Which tier each task runs on (Section 12.2).
TASK_TIER: dict[str, str] = {
    "metadata_cleanup": "fast",
    "keyword_extraction": "fast",
    "classification": "fast",
    "summary": "strong",
    "qa": "strong",
    "comparison": "strong",
    "research_gap": "strong",
    "novelty": "strong",
    "literature_review": "strong",
}

# Order is set by measured free-tier capacity rather than model prestige.
#
# Verified 2026-09-06. Groq publishes gpt-oss-120b at 1,000 requests/day and
# 200,000 tokens/day; Gemini's free tier gives the strong flash model roughly 20
# requests/day, and flash-lite roughly 500. Running the strong tier at Gemini
# first therefore spent the scarcest allowance on every analysis and exhausted it
# within a session, which is exactly what happened during development.
#
# So the workhorse leads the strong tier and Gemini is held behind it: about
# twenty high-quality requests a day are worth far more as the answer when Groq
# is rate limited than as the default. The fast tier inverts this, because
# flash-lite's larger request allowance costs nothing from Groq's tight
# 8,000 tokens/minute ceiling, which the strong tier needs. OpenRouter stays the
# fallback in both.
_DEFAULT_TIER_ORDER: dict[str, tuple[str, ...]] = {
    "fast": ("gemini", "groq", "openrouter"),
    "strong": ("groq", "gemini", "openrouter"),
}


def _tier_order() -> dict[str, tuple[str, ...]]:
    """Provider order per tier, overridable without touching this file."""
    settings = get_settings()
    return {
        "fast": _parse_order(settings.llm_fast_order, _DEFAULT_TIER_ORDER["fast"]),
        "strong": _parse_order(settings.llm_strong_order, _DEFAULT_TIER_ORDER["strong"]),
    }


def _parse_order(configured: str, default: tuple[str, ...]) -> tuple[str, ...]:
    names = tuple(n.strip().lower() for n in (configured or "").split(",") if n.strip())
    known = tuple(n for n in names if n in _DEFAULT_TIER_ORDER["strong"])
    return known or default

_JSON_BLOCK_RE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.S)


@dataclass(frozen=True)
class RoutedResult:
    parsed: BaseModel
    provider: str
    model: str


class _Cooldowns:
    """Circuit-breaker-style suppression of a provider that just rate limited us.

    Retrying into a 429 wastes the retry budget and can extend the ban, so a
    provider that returns one is skipped entirely for a cooldown window.
    """

    def __init__(self) -> None:
        self._until: dict[str, float] = {}

    def trip(self, provider: str, seconds: int) -> None:
        self._until[provider] = time.monotonic() + seconds
        logger.warning("Provider %s cooling down for %ss", provider, seconds)

    def active(self, provider: str) -> bool:
        until = self._until.get(provider)
        if until is None:
            return False
        if time.monotonic() >= until:
            del self._until[provider]
            return False
        return True


_cooldowns = _Cooldowns()
_providers: dict[str, Provider] | None = None

# A provider asking for longer than this is reporting an exhausted daily quota.
# Honouring it literally would park the provider for the rest of the day, so it
# is capped: the cap is long enough to stop the per-minute retry storm that a
# fixed sixty-second cooldown produced, and short enough that a quota which
# resets earlier than advertised is picked up again the same session.
_MAX_COOLDOWN_SECONDS = 15 * 60


def _cooldown_for(exc: ProviderRateLimited, settings) -> int:
    """How long to rest a provider that just rate limited us.

    Prefers the wait the provider asked for over the configured default. Groq
    reports its reset window on every response and Gemini returns a retryDelay,
    so most 429s carry a real number; the default only covers providers that say
    nothing.
    """
    asked = getattr(exc, "retry_after", None)
    if asked is None or asked <= 0:
        return settings.ai_provider_cooldown_seconds
    return int(min(max(asked, settings.ai_provider_cooldown_seconds), _MAX_COOLDOWN_SECONDS))


def _get_providers() -> dict[str, Provider]:
    global _providers
    if _providers is None:
        _providers = build_providers()
    return _providers


def reset_providers() -> None:
    """Rebuilds provider clients. Used by tests after changing configuration."""
    global _providers
    _providers = None
    _cooldowns._until.clear()  # noqa: SLF001 - same module, deliberate


def tier_for(task: str) -> str:
    return TASK_TIER.get(task, "strong")


def model_for(task: str) -> str | None:
    """The model that would answer this task right now.

    Spring Boot folds this into the analysis cache fingerprint, so a model change
    correctly invalidates previously cached answers (Section 25.2).
    """
    tier = tier_for(task)
    for name in _tier_order()[tier]:
        provider = _get_providers()[name]
        if provider.configured() and not _cooldowns.active(name):
            return f"{name}:{provider.model_for(tier)}"
    return None


def available_providers() -> list[str]:
    return [name for name, p in _get_providers().items() if p.configured()]


async def complete_structured(
    *,
    task: str,
    system: str,
    user: str,
    schema: Type[TModel],
) -> RoutedResult:
    """Runs a task through the fallback chain and returns validated output.

    Free-text is never accepted: a response that will not validate against the
    schema is repaired once and then abandoned, which also means a prompt
    injection that hijacks the answer fails validation instead of rendering
    (Sections 11.1 and 26.4).
    """
    settings = get_settings()
    tier = tier_for(task)
    order = [
        name
        for name in _tier_order()[tier]
        if _get_providers()[name].configured() and not _cooldowns.active(name)
    ]
    if not order:
        if not any(p.configured() for p in _get_providers().values()):
            raise TransientError(
                "NO_PROVIDER_CONFIGURED",
                "No AI provider is configured for this deployment.",
            )
        raise TransientError(
            "ALL_PROVIDERS_COOLING_DOWN",
            "AI analysis is temporarily unavailable. Please try again shortly.",
        )

    timeout = httpx.Timeout(settings.ai_request_timeout_seconds, connect=10.0)
    last_error: Exception | None = None

    async with httpx.AsyncClient(timeout=timeout) as client:
        for provider_name in order:
            provider = _get_providers()[provider_name]
            try:
                completion = await _call_with_retries(
                    provider, system=system, user=user, tier=tier, client=client,
                    max_retries=settings.ai_max_retries,
                )
            except ProviderRateLimited as exc:
                _cooldowns.trip(provider_name, _cooldown_for(exc, settings))
                last_error = exc
                _log_failover(task, provider_name, "rate limited")
                continue
            except ProviderUnavailable as exc:
                last_error = exc
                _log_failover(task, provider_name, "unavailable")
                continue

            parsed = await _parse_or_repair(
                completion, provider, system=system, user=user, tier=tier,
                client=client, schema=schema,
            )
            if parsed is None:
                last_error = ValueError(f"{provider_name} produced unusable structured output")
                _log_failover(task, provider_name, "schema validation failed")
                continue
            return RoutedResult(parsed=parsed, provider=provider_name, model=completion.model)

    logger.error("All providers failed for task %s: %s", task, last_error)
    raise TransientError(
        "ALL_PROVIDERS_FAILED",
        "AI analysis is temporarily unavailable. Please try again shortly.",
    )


async def _call_with_retries(
    provider: Provider, *, system: str, user: str, tier: str,
    client: httpx.AsyncClient, max_retries: int,
) -> Completion:
    attempt = 0
    while True:
        try:
            return await provider.complete(system=system, user=user, tier=tier, client=client)
        except ProviderRateLimited:
            # Never retried: the whole point of the cooldown is to route away.
            raise
        except ProviderUnavailable:
            if attempt >= max_retries:
                raise
            delay = 0.5 * (2 ** attempt)
            await asyncio.sleep(delay)
            attempt += 1


async def _parse_or_repair(
    completion: Completion, provider: Provider, *, system: str, user: str, tier: str,
    client: httpx.AsyncClient, schema: Type[TModel],
) -> TModel | None:
    parsed = _try_parse(completion.text, schema)
    if parsed is not None:
        return parsed

    # One repair attempt, then give up and let the chain move on (Section 11.1).
    repair_prompt = (
        f"{user}\n\n"
        "Your previous reply was not valid JSON for the required schema. "
        "Reply again with ONLY a single valid JSON object matching the schema. "
        "No prose, no markdown fences, no explanation."
    )
    try:
        retry = await provider.complete(system=system, user=repair_prompt, tier=tier, client=client)
    except (ProviderRateLimited, ProviderUnavailable):
        return None
    return _try_parse(retry.text, schema)


def _try_parse(text: str, schema: Type[TModel]) -> TModel | None:
    payload = _extract_json(text)
    if payload is None:
        return None
    try:
        return schema.model_validate(payload)
    except ValidationError as exc:
        logger.info("Structured output failed validation: %s", exc.error_count())
        return None


def _extract_json(text: str) -> dict | None:
    """Recovers a JSON object from a reply that may be wrapped in prose or fences."""
    candidate = text.strip()
    fenced = _JSON_BLOCK_RE.search(candidate)
    if fenced:
        candidate = fenced.group(1).strip()
    try:
        value = json.loads(candidate)
        return value if isinstance(value, dict) else None
    except json.JSONDecodeError:
        pass

    start = candidate.find("{")
    end = candidate.rfind("}")
    if start == -1 or end <= start:
        return None
    try:
        value = json.loads(candidate[start : end + 1])
        return value if isinstance(value, dict) else None
    except json.JSONDecodeError:
        return None


def _log_failover(task: str, provider: str, reason: str) -> None:
    # Provider, task and reason only. Never credentials, never document content
    # (Section 12.4).
    logger.warning("Failover: task=%s provider=%s reason=%s", task, provider, reason)
