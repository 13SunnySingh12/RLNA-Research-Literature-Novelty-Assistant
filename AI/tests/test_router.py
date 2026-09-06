"""Two-tier routing, the fallback chain, and structured output handling.

These are the reliability behaviours the project claims (Section 12.4), so they
are tested against mocked providers rather than asserted in a README. No test in
this file makes a real API call.
"""

from __future__ import annotations

import json

import httpx
import pytest
import respx

from app.core.errors import TransientError
from app.integrations import providers, router
from app.models.outputs import SummaryOutput
from tests.conftest import GEMINI_URL, GROQ_URL, OPENROUTER_URL, gemini_body, openai_body

VALID = json.dumps({
    "research_problem": "p", "approach": "a",
    "key_findings": ["f"], "limitations": ["l"], "takeaway": "t",
})


async def _summarize():
    return await router.complete_structured(
        task="summary", system="sys", user="usr", schema=SummaryOutput
    )


# --------------------------------------------------------------------- tiers

def test_task_tier_table_matches_the_spec():
    assert router.tier_for("metadata_cleanup") == "fast"
    assert router.tier_for("keyword_extraction") == "fast"
    assert router.tier_for("classification") == "fast"
    for reasoning_task in ("summary", "qa", "comparison", "research_gap", "novelty"):
        assert router.tier_for(reasoning_task) == "strong"


def test_unknown_task_defaults_to_the_strong_tier():
    # Defaulting down would silently degrade a new reasoning task.
    assert router.tier_for("something_new") == "strong"


def test_model_id_reflects_the_tier(all_providers):
    strong = router.model_for("summary")
    fast = router.model_for("keyword_extraction")
    assert strong and fast and strong != fast
    # Order follows measured free-tier capacity: Groq's 1,000 requests/day leads
    # the strong tier, and Gemini flash-lite's larger request allowance leads the
    # fast tier so small calls do not spend Groq's tight token budget.
    assert strong.startswith("groq:")
    assert fast.startswith("gemini:")


# ------------------------------------------------------------------ fallback

@respx.mock
async def test_falls_back_to_the_second_provider_on_server_error(all_providers):
    respx.post(url__startswith=GEMINI_URL).mock(return_value=httpx.Response(500))
    groq = respx.post(GROQ_URL).mock(return_value=httpx.Response(200, json=openai_body(VALID)))

    result = await _summarize()

    assert result.provider == "groq"
    assert groq.called


@respx.mock
async def test_rate_limit_fails_over_and_cools_the_provider_down(all_providers):
    groq = respx.post(GROQ_URL).mock(return_value=httpx.Response(429))
    gemini = respx.post(url__startswith=GEMINI_URL).mock(
        return_value=httpx.Response(200, json=gemini_body(VALID))
    )

    first = await _summarize()
    assert first.provider == "gemini"
    assert groq.call_count == 1

    # The cooled-down provider must be skipped entirely, not retried into its
    # own rate limit.
    second = await _summarize()
    assert second.provider == "gemini"
    assert groq.call_count == 1
    assert gemini.call_count == 2


@respx.mock
async def test_rate_limits_are_never_retried(all_providers):
    """A 429 is a routing signal, not a transient blip."""
    groq = respx.post(GROQ_URL).mock(return_value=httpx.Response(429))
    respx.post(url__startswith=GEMINI_URL).mock(
        return_value=httpx.Response(200, json=gemini_body(VALID))
    )

    await _summarize()

    assert groq.call_count == 1


@respx.mock
async def test_reaches_openrouter_only_after_both_primaries_fail(all_providers):
    respx.post(url__startswith=GEMINI_URL).mock(return_value=httpx.Response(503))
    respx.post(GROQ_URL).mock(return_value=httpx.Response(503))
    fallback = respx.post(OPENROUTER_URL).mock(
        return_value=httpx.Response(200, json=openai_body(VALID))
    )

    result = await _summarize()

    assert result.provider == "openrouter"
    assert fallback.called


@respx.mock
async def test_all_providers_failing_raises_a_clean_transient_error(all_providers):
    respx.post(url__startswith=GEMINI_URL).mock(return_value=httpx.Response(503))
    respx.post(GROQ_URL).mock(return_value=httpx.Response(503))
    respx.post(OPENROUTER_URL).mock(return_value=httpx.Response(503))

    with pytest.raises(TransientError) as exc:
        await _summarize()

    assert exc.value.code == "ALL_PROVIDERS_FAILED"
    # The user-facing message must not name a provider or a model.
    message = exc.value.detail["message"].lower()
    assert "gemini" not in message and "groq" not in message and "openrouter" not in message


@respx.mock
async def test_timeout_is_treated_as_a_failover(all_providers):
    respx.post(url__startswith=GEMINI_URL).mock(side_effect=httpx.ReadTimeout("too slow"))
    respx.post(GROQ_URL).mock(return_value=httpx.Response(200, json=openai_body(VALID)))

    result = await _summarize()

    assert result.provider == "groq"


async def test_no_configured_provider_reports_that_specifically(monkeypatch):
    for key in ("GEMINI_API_KEY", "GROQ_API_KEY", "OPENROUTER_API_KEY"):
        monkeypatch.setenv(key, "")
    from app.core.config import get_settings

    get_settings.cache_clear()
    router.reset_providers()

    with pytest.raises(TransientError) as exc:
        await _summarize()
    assert exc.value.code == "NO_PROVIDER_CONFIGURED"


# ----------------------------------------------------- structured output

@respx.mock
async def test_output_wrapped_in_markdown_fences_is_still_accepted(all_providers):
    respx.post(GROQ_URL).mock(
        return_value=httpx.Response(200, json=openai_body(f"```json\n{VALID}\n```"))
    )
    result = await _summarize()
    assert result.parsed.takeaway == "t"


@respx.mock
async def test_output_surrounded_by_prose_is_recovered(all_providers):
    respx.post(GROQ_URL).mock(
        return_value=httpx.Response(
            200, json=openai_body(f"Sure! Here you go:\n{VALID}\nHope that helps.")
        )
    )
    result = await _summarize()
    assert result.parsed.research_problem == "p"


@respx.mock
async def test_invalid_output_triggers_exactly_one_repair_attempt(all_providers):
    route = respx.post(GROQ_URL).mock(
        side_effect=[
            httpx.Response(200, json=openai_body("not json at all")),
            httpx.Response(200, json=openai_body(VALID)),
        ]
    )
    result = await _summarize()

    assert route.call_count == 2
    assert result.provider == "groq"


@respx.mock
async def test_a_provider_that_cannot_produce_valid_output_is_abandoned(all_providers):
    """Two bad replies is enough; the chain moves on rather than looping."""
    groq = respx.post(GROQ_URL).mock(
        return_value=httpx.Response(200, json=openai_body("still not json"))
    )
    respx.post(url__startswith=GEMINI_URL).mock(
        return_value=httpx.Response(200, json=gemini_body(VALID))
    )

    result = await _summarize()

    assert groq.call_count == 2  # original plus one repair
    assert result.provider == "gemini"  # then abandoned, and the next provider answered


@respx.mock
async def test_an_empty_reply_is_a_failure_not_an_empty_answer(all_providers):
    """A safety block or exhausted budget returns empty text; that is not an answer."""
    respx.post(url__startswith=GEMINI_URL).mock(
        return_value=httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": ""}]}}]})
    )
    respx.post(GROQ_URL).mock(return_value=httpx.Response(200, json=openai_body(VALID)))

    result = await _summarize()

    assert result.provider == "groq"


@respx.mock
async def test_json_requested_explicitly_from_every_provider(all_providers):
    captured = {}

    def record(request):
        captured.update(json.loads(request.content))
        return httpx.Response(200, json=openai_body(VALID))

    respx.post(url__startswith=GEMINI_URL).mock(return_value=httpx.Response(503))
    respx.post(GROQ_URL).mock(side_effect=record)

    await _summarize()

    assert captured["response_format"] == {"type": "json_object"}


def test_provider_clients_report_configuration_honestly(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "")
    monkeypatch.setenv("GROQ_API_KEY", "k")
    monkeypatch.setenv("OPENROUTER_API_KEY", "")
    from app.core.config import get_settings

    get_settings.cache_clear()
    built = providers.build_providers()

    assert not built["gemini"].configured()
    assert built["groq"].configured()
    assert not built["openrouter"].configured()


# ------------------------------------------------- provider-signalled cooldown

def test_duration_parsing_covers_the_shapes_providers_send():
    from app.integrations.providers import _seconds_from_duration

    assert _seconds_from_duration("30") == 30            # Retry-After, plain seconds
    assert _seconds_from_duration("57s") == 57           # Gemini retryDelay
    assert _seconds_from_duration("1m26.4s") == 86.4     # Groq reset-requests
    assert _seconds_from_duration("3.51s") == 3.51       # Groq reset-tokens
    assert _seconds_from_duration("2m") == 120
    assert _seconds_from_duration("") is None
    assert _seconds_from_duration("later") is None


def test_retry_after_read_from_each_channel_providers_use():
    from app.integrations.providers import retry_after_seconds

    assert retry_after_seconds(httpx.Response(429, headers={"retry-after": "45"})) == 45
    assert retry_after_seconds(
        httpx.Response(429, headers={"x-ratelimit-reset-requests": "1m26.4s"})
    ) == 86.4
    assert retry_after_seconds(
        httpx.Response(429, json={"error": {"details": [{"retryDelay": "57s"}]}})
    ) == 57
    # Nothing said: the caller falls back to its configured default.
    assert retry_after_seconds(httpx.Response(429)) is None


def test_cooldown_honours_the_wait_the_provider_asked_for():
    from app.core.config import get_settings
    from app.integrations.providers import ProviderRateLimited
    from app.integrations.router import _MAX_COOLDOWN_SECONDS, _cooldown_for

    settings = get_settings()
    default = settings.ai_provider_cooldown_seconds

    # A silent provider keeps the configured default.
    assert _cooldown_for(ProviderRateLimited("x"), settings) == default
    # A longer request is honoured rather than ignored.
    assert _cooldown_for(ProviderRateLimited("x", default + 300), settings) == default + 300
    # A shorter one never drops below the default, so a provider cannot talk us
    # into hammering it.
    assert _cooldown_for(ProviderRateLimited("x", 1), settings) == default
    # An exhausted daily quota is capped rather than parking it all day.
    assert _cooldown_for(ProviderRateLimited("x", 86_400), settings) == _MAX_COOLDOWN_SECONDS


@respx.mock
async def test_a_429_with_retry_after_rests_the_provider_for_that_long(all_providers):
    """The point of the change: a long wait is not retried again in sixty seconds."""
    import time as _time

    from app.integrations import router as router_module

    respx.post(GROQ_URL).mock(
        return_value=httpx.Response(429, headers={"retry-after": "600"})
    )
    respx.post(url__startswith=GEMINI_URL).mock(
        return_value=httpx.Response(200, json=gemini_body(VALID))
    )

    await _summarize()

    resting = router_module._cooldowns._until["groq"] - _time.monotonic()  # noqa: SLF001
    assert 500 < resting <= router_module._MAX_COOLDOWN_SECONDS
