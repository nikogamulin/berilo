"""Tests for the provider layer (S1.4): config, pricing, factory, retry, doctor.

Network-free: vendor SDK client classes (`openai.OpenAI`, `anthropic.Anthropic`)
are monkeypatched with fakes that return canned responses or raise real vendor
exception types constructed with a fake httpx response, never touching the
network. `time.sleep` is patched during retry tests so they run instantly.
"""

from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import anthropic as anthropic_sdk
import httpx
import openai as openai_sdk
import pytest

from berilo.config import Config, load_config
from berilo.providers import create_client, retry_with_backoff
from berilo.providers.anthropic import DEFAULT_MODEL as ANTHROPIC_DEFAULT_MODEL
from berilo.providers.anthropic import AnthropicClient
from berilo.providers.base import CompletionResult
from berilo.providers.doctor import run_doctor
from berilo.providers.openai import DEFAULT_MODEL as OPENAI_DEFAULT_MODEL
from berilo.providers.openai import OpenAIClient
from berilo.providers.pricing import EUR_PER_USD, cost_eur

FAKE_OPENAI_KEY = "test-openai-key-not-a-real-secret-0000000000"
FAKE_ANTHROPIC_KEY = "test-anthropic-key-not-a-real-secret-0000000000"


# --------------------------------------------------------------------------
# Fakes for the vendor SDK surfaces actually touched by our provider code.
# --------------------------------------------------------------------------


def _openai_rate_limit_error() -> openai_sdk.RateLimitError:
    request = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
    response = httpx.Response(429, request=request)
    return openai_sdk.RateLimitError("rate limited", response=response, body=None)


def _openai_auth_error() -> openai_sdk.AuthenticationError:
    request = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
    response = httpx.Response(401, request=request)
    return openai_sdk.AuthenticationError("invalid api key", response=response, body=None)


def _anthropic_rate_limit_error() -> anthropic_sdk.RateLimitError:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    response = httpx.Response(429, request=request)
    return anthropic_sdk.RateLimitError("rate limited", response=response, body=None)


class _FakeOpenAICompletions:
    """Fake for `client.chat.completions`; replays a queue of results."""

    def __init__(self, queue: list[object]) -> None:
        self._queue = list(queue)
        self.calls: list[dict] = []

    def create(self, **kwargs: object) -> object:
        self.calls.append(kwargs)
        item = self._queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class _FakeOpenAISDKClient:
    """Fake for `openai.OpenAI(...)`."""

    def __init__(self, queue: list[object]) -> None:
        self.chat = SimpleNamespace(completions=_FakeOpenAICompletions(queue))


def _openai_response(content: str, prompt_tokens: int, completion_tokens: int) -> SimpleNamespace:
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content))],
        usage=SimpleNamespace(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens),
    )


class _FakeAnthropicMessages:
    """Fake for `client.messages`; replays a queue of results."""

    def __init__(self, queue: list[object]) -> None:
        self._queue = list(queue)
        self.calls: list[dict] = []

    def create(self, **kwargs: object) -> object:
        self.calls.append(kwargs)
        item = self._queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class _FakeAnthropicSDKClient:
    """Fake for `anthropic.Anthropic(...)`."""

    def __init__(self, queue: list[object]) -> None:
        self.messages = _FakeAnthropicMessages(queue)


def _anthropic_response(text: str, input_tokens: int, output_tokens: int) -> SimpleNamespace:
    return SimpleNamespace(
        content=[SimpleNamespace(type="text", text=text)],
        usage=SimpleNamespace(input_tokens=input_tokens, output_tokens=output_tokens),
    )


# --------------------------------------------------------------------------
# pricing.py
# --------------------------------------------------------------------------


def test_cost_eur_known_model_math() -> None:
    """cost_eur multiplies token counts by per-million USD rates, then EUR_PER_USD."""
    cost = cost_eur("gpt-5-mini", input_tokens=1_000_000, output_tokens=1_000_000)
    expected_usd = 0.25 + 2.00
    assert cost == pytest.approx(expected_usd * EUR_PER_USD)


def test_cost_eur_scales_with_partial_million() -> None:
    """Sub-million token counts scale linearly."""
    cost = cost_eur("claude-haiku-4-5", input_tokens=500_000, output_tokens=0)
    assert cost == pytest.approx(0.5 * 1.00 * EUR_PER_USD)


def test_cost_eur_unknown_model_raises_with_known_models_listed() -> None:
    """An unpriced model raises ValueError listing known models (actionable)."""
    with pytest.raises(ValueError, match="gpt-5-mini"):
        cost_eur("totally-unknown-model", input_tokens=100, output_tokens=100)


# --------------------------------------------------------------------------
# config.py
# --------------------------------------------------------------------------


def test_load_config_defaults_when_nothing_set(monkeypatch: pytest.MonkeyPatch) -> None:
    """With no env vars and no overrides, defaults from the module apply."""
    for name in (
        "OPENAI_API_KEY",
        "ANTHROPIC_API_KEY",
        "BERILO_TRANSLATION_MODEL",
        "BERILO_JUDGE_MODEL",
        "BERILO_TARGET_LANG",
    ):
        monkeypatch.delenv(name, raising=False)

    config = load_config()

    assert config.openai_api_key is None
    assert config.anthropic_api_key is None
    assert config.translation_model == "gpt-5-mini"
    assert config.judge_model == "gpt-5-mini"
    assert config.target_lang == "sl"


def test_load_config_env_beats_default(monkeypatch: pytest.MonkeyPatch) -> None:
    """A process environment variable overrides the built-in default."""
    monkeypatch.setenv("BERILO_TARGET_LANG", "de")
    monkeypatch.setenv("OPENAI_API_KEY", FAKE_OPENAI_KEY)

    config = load_config()

    assert config.target_lang == "de"
    assert config.openai_api_key == FAKE_OPENAI_KEY


def test_load_config_override_beats_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """An explicit keyword override wins over both env and default."""
    monkeypatch.setenv("BERILO_TARGET_LANG", "de")

    config = load_config(target_lang="fr")

    assert config.target_lang == "fr"


def test_load_config_reads_env_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Values from an explicit env_file are picked up (python-dotenv path)."""
    monkeypatch.delenv("BERILO_TARGET_LANG", raising=False)
    env_file = tmp_path / ".env.test"
    env_file.write_text(f"BERILO_TARGET_LANG=it\nOPENAI_API_KEY={FAKE_OPENAI_KEY}\n")

    config = load_config(env_file=env_file)

    assert config.target_lang == "it"
    assert config.openai_api_key == FAKE_OPENAI_KEY


def test_config_repr_never_exposes_key_material() -> None:
    """repr(Config) omits API key values (repr=False on both key fields)."""
    config = Config(openai_api_key=FAKE_OPENAI_KEY, anthropic_api_key=FAKE_ANTHROPIC_KEY)

    text = repr(config)

    assert FAKE_OPENAI_KEY not in text
    assert FAKE_ANTHROPIC_KEY not in text


# --------------------------------------------------------------------------
# providers/__init__.py — factory + retry
# --------------------------------------------------------------------------


def test_create_client_routes_openai_prefix() -> None:
    """gpt-* model names route to OpenAIClient."""
    config = Config(openai_api_key=FAKE_OPENAI_KEY)
    client = create_client("gpt-5-mini", config)
    assert isinstance(client, OpenAIClient)
    assert client.model == "gpt-5-mini"


def test_create_client_routes_anthropic_prefix() -> None:
    """claude-* model names route to AnthropicClient."""
    config = Config(anthropic_api_key=FAKE_ANTHROPIC_KEY)
    client = create_client("claude-haiku-4-5", config)
    assert isinstance(client, AnthropicClient)
    assert client.model == "claude-haiku-4-5"


def test_create_client_missing_openai_key_raises_actionable_error() -> None:
    """Selecting an OpenAI model with no key configured raises, naming the env var."""
    config = Config()
    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        create_client("gpt-5-mini", config)


def test_create_client_missing_anthropic_key_raises_actionable_error() -> None:
    """Selecting an Anthropic model with no key configured raises, naming the env var."""
    config = Config()
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        create_client("claude-haiku-4-5", config)


def test_create_client_unknown_prefix_raises() -> None:
    """A model matching neither provider's prefix raises ValueError."""
    config = Config(openai_api_key=FAKE_OPENAI_KEY, anthropic_api_key=FAKE_ANTHROPIC_KEY)
    with pytest.raises(ValueError, match="Unrecognized model"):
        create_client("mistral-large-latest", config)


def test_missing_key_error_does_not_leak_key_material() -> None:
    """Even when a key IS configured for the other provider, no key value leaks."""
    config = Config(anthropic_api_key=FAKE_ANTHROPIC_KEY)
    with pytest.raises(ValueError) as exc_info:
        create_client("gpt-5-mini", config)
    assert FAKE_ANTHROPIC_KEY not in str(exc_info.value)


def test_retry_with_backoff_succeeds_after_two_transient_failures(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Two retryable failures followed by success: 3 attempts total, no real sleep."""
    sleep_calls: list[float] = []
    monkeypatch.setattr("berilo.providers.time.sleep", lambda seconds: sleep_calls.append(seconds))
    monkeypatch.setattr("berilo.providers.random.uniform", lambda a, b: 0.0)

    attempts = {"count": 0}

    def flaky() -> str:
        attempts["count"] += 1
        if attempts["count"] < 3:
            raise RuntimeError("transient")
        return "ok"

    result = retry_with_backoff(flaky, is_retryable=lambda exc: True)

    assert result == "ok"
    assert attempts["count"] == 3
    assert len(sleep_calls) == 2
    # exponential: base * 2**0, base * 2**1 (jitter forced to 0 above)
    assert sleep_calls == [pytest.approx(1.0), pytest.approx(2.0)]


def test_retry_with_backoff_reraises_non_retryable_immediately(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A non-retryable exception is raised on the first attempt, no sleep."""
    monkeypatch.setattr(
        "berilo.providers.time.sleep", lambda seconds: pytest.fail("should not sleep")
    )

    def always_fails() -> None:
        raise ValueError("permanent")

    with pytest.raises(ValueError, match="permanent"):
        retry_with_backoff(always_fails, is_retryable=lambda exc: False)


def test_retry_with_backoff_gives_up_after_max_retries(monkeypatch: pytest.MonkeyPatch) -> None:
    """Retries stop after max_retries and the last exception propagates."""
    monkeypatch.setattr("berilo.providers.time.sleep", lambda seconds: None)

    calls = {"count": 0}

    def always_fails() -> None:
        calls["count"] += 1
        raise RuntimeError("still failing")

    with pytest.raises(RuntimeError, match="still failing"):
        retry_with_backoff(always_fails, is_retryable=lambda exc: True, max_retries=2)

    assert calls["count"] == 3  # initial attempt + 2 retries


# --------------------------------------------------------------------------
# providers/openai.py
# --------------------------------------------------------------------------


def test_openai_client_complete_accounts_tokens_and_cost(monkeypatch: pytest.MonkeyPatch) -> None:
    """A successful completion returns text plus tokens and cost matching pricing.py."""
    fake_sdk_client = _FakeOpenAISDKClient([_openai_response("Pozdravljen svet.", 12, 6)])
    monkeypatch.setattr(
        "berilo.providers.openai.openai_sdk.OpenAI", lambda api_key: fake_sdk_client
    )

    client = OpenAIClient(api_key=FAKE_OPENAI_KEY, model="gpt-5-mini")
    result = client.complete(prompt="Translate: Hello world.")

    assert isinstance(result, CompletionResult)
    assert result.text == "Pozdravljen svet."
    assert result.input_tokens == 12
    assert result.output_tokens == 6
    assert result.model == "gpt-5-mini"
    assert result.cost_eur == pytest.approx(cost_eur("gpt-5-mini", 12, 6))
    assert not hasattr(client, "api_key")


def test_openai_client_requires_exactly_one_of_prompt_or_messages(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Passing both or neither of prompt/messages raises ValueError."""
    monkeypatch.setattr(
        "berilo.providers.openai.openai_sdk.OpenAI", lambda api_key: _FakeOpenAISDKClient([])
    )
    client = OpenAIClient(api_key=FAKE_OPENAI_KEY)

    with pytest.raises(ValueError):
        client.complete()
    with pytest.raises(ValueError):
        client.complete(prompt="hi", messages=[{"role": "user", "content": "hi"}])


def test_openai_client_retries_transient_then_succeeds(monkeypatch: pytest.MonkeyPatch) -> None:
    """A rate-limit error twice, then success on the 3rd attempt — no real delay."""
    monkeypatch.setattr("berilo.providers.time.sleep", lambda seconds: None)
    queue = [
        _openai_rate_limit_error(),
        _openai_rate_limit_error(),
        _openai_response("V redu.", 8, 3),
    ]
    fake_sdk_client = _FakeOpenAISDKClient(queue)
    monkeypatch.setattr(
        "berilo.providers.openai.openai_sdk.OpenAI", lambda api_key: fake_sdk_client
    )

    client = OpenAIClient(api_key=FAKE_OPENAI_KEY)
    result = client.complete(prompt="Translate: OK.")

    assert result.text == "V redu."
    assert len(fake_sdk_client.chat.completions.calls) == 3


def test_openai_client_non_retryable_error_raised_immediately(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """AuthenticationError is not retried; it propagates on the first attempt."""
    monkeypatch.setattr(
        "berilo.providers.time.sleep", lambda seconds: pytest.fail("should not retry/sleep")
    )
    fake_sdk_client = _FakeOpenAISDKClient([_openai_auth_error()])
    monkeypatch.setattr(
        "berilo.providers.openai.openai_sdk.OpenAI", lambda api_key: fake_sdk_client
    )

    client = OpenAIClient(api_key=FAKE_OPENAI_KEY)
    with pytest.raises(openai_sdk.AuthenticationError):
        client.complete(prompt="hi")
    assert len(fake_sdk_client.chat.completions.calls) == 1


# --------------------------------------------------------------------------
# providers/anthropic.py
# --------------------------------------------------------------------------


def test_anthropic_client_complete_accounts_tokens_and_cost(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A successful completion returns text plus tokens and cost matching pricing.py."""
    fake_sdk_client = _FakeAnthropicSDKClient([_anthropic_response("Pozdravljen svet.", 14, 7)])
    monkeypatch.setattr(
        "berilo.providers.anthropic.anthropic_sdk.Anthropic", lambda api_key: fake_sdk_client
    )

    client = AnthropicClient(api_key=FAKE_ANTHROPIC_KEY, model="claude-haiku-4-5")
    result = client.complete(prompt="Translate: Hello world.")

    assert result.text == "Pozdravljen svet."
    assert result.input_tokens == 14
    assert result.output_tokens == 7
    assert result.model == "claude-haiku-4-5"
    assert result.cost_eur == pytest.approx(cost_eur("claude-haiku-4-5", 14, 7))
    assert not hasattr(client, "api_key")


def test_anthropic_client_requires_exactly_one_of_prompt_or_messages(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Passing both or neither of prompt/messages raises ValueError."""
    monkeypatch.setattr(
        "berilo.providers.anthropic.anthropic_sdk.Anthropic",
        lambda api_key: _FakeAnthropicSDKClient([]),
    )
    client = AnthropicClient(api_key=FAKE_ANTHROPIC_KEY)

    with pytest.raises(ValueError):
        client.complete()
    with pytest.raises(ValueError):
        client.complete(prompt="hi", messages=[{"role": "user", "content": "hi"}])


def test_anthropic_client_retries_transient_then_succeeds(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A rate-limit error twice, then success on the 3rd attempt — no real delay."""
    monkeypatch.setattr("berilo.providers.time.sleep", lambda seconds: None)
    queue = [
        _anthropic_rate_limit_error(),
        _anthropic_rate_limit_error(),
        _anthropic_response("V redu.", 9, 4),
    ]
    fake_sdk_client = _FakeAnthropicSDKClient(queue)
    monkeypatch.setattr(
        "berilo.providers.anthropic.anthropic_sdk.Anthropic", lambda api_key: fake_sdk_client
    )

    client = AnthropicClient(api_key=FAKE_ANTHROPIC_KEY)
    result = client.complete(prompt="Translate: OK.")

    assert result.text == "V redu."
    assert len(fake_sdk_client.messages.calls) == 3


# --------------------------------------------------------------------------
# providers/doctor.py
# --------------------------------------------------------------------------


def test_run_doctor_all_providers_succeed_reports_cost_and_returns_zero(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """Doctor succeeds for both configured providers and prints cost > €0."""
    results = {
        OPENAI_DEFAULT_MODEL: CompletionResult(
            text="Knjižnica je bila tiha.",
            input_tokens=20,
            output_tokens=10,
            cost_eur=0.000123,
            model=OPENAI_DEFAULT_MODEL,
        ),
        ANTHROPIC_DEFAULT_MODEL: CompletionResult(
            text="Knjižnica je bila tiha, a vsaka knjiga je vpila.",
            input_tokens=25,
            output_tokens=15,
            cost_eur=0.000456,
            model=ANTHROPIC_DEFAULT_MODEL,
        ),
    }

    class _FakeClient:
        def __init__(self, model: str) -> None:
            self._model = model

        def complete(self, prompt: str | None = None, **_: object) -> CompletionResult:
            return results[self._model]

    def _fake_create_client(model: str, config: Config) -> _FakeClient:
        return _FakeClient(model)

    monkeypatch.setattr("berilo.providers.doctor.create_client", _fake_create_client)

    config = Config(openai_api_key=FAKE_OPENAI_KEY, anthropic_api_key=FAKE_ANTHROPIC_KEY)
    exit_code = run_doctor(config)

    captured = capsys.readouterr()
    assert exit_code == 0
    assert "0.000123" in captured.out
    assert "0.000456" in captured.out
    assert "OpenAI" in captured.out
    assert "Anthropic" in captured.out


def test_run_doctor_no_provider_configured_returns_one(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """No API keys configured at all: doctor returns 1 with an actionable message."""
    exit_code = run_doctor(Config())

    captured = capsys.readouterr()
    assert exit_code == 1
    assert "OPENAI_API_KEY" in captured.out or "ANTHROPIC_API_KEY" in captured.out


def test_run_doctor_one_provider_fails_returns_one(monkeypatch: pytest.MonkeyPatch) -> None:
    """If any configured provider fails, doctor returns 1 even if others succeed."""

    class _FailingClient:
        def complete(self, prompt: str | None = None, **_: object) -> CompletionResult:
            raise RuntimeError("boom")

    class _OkClient:
        def complete(self, prompt: str | None = None, **_: object) -> CompletionResult:
            return CompletionResult(
                text="ok", input_tokens=1, output_tokens=1, cost_eur=0.000001, model="gpt-5-mini"
            )

    def _fake_create_client(model: str, config: Config) -> object:
        return _OkClient() if model == OPENAI_DEFAULT_MODEL else _FailingClient()

    monkeypatch.setattr("berilo.providers.doctor.create_client", _fake_create_client)

    config = Config(openai_api_key=FAKE_OPENAI_KEY, anthropic_api_key=FAKE_ANTHROPIC_KEY)
    exit_code = run_doctor(config)

    assert exit_code == 1


def test_run_doctor_never_prints_key_material(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """Doctor's output never contains the configured API key values."""

    class _OkClient:
        def complete(self, prompt: str | None = None, **_: object) -> CompletionResult:
            return CompletionResult(
                text="ok", input_tokens=1, output_tokens=1, cost_eur=0.000001, model="gpt-5-mini"
            )

    monkeypatch.setattr("berilo.providers.doctor.create_client", lambda model, config: _OkClient())

    config = Config(openai_api_key=FAKE_OPENAI_KEY, anthropic_api_key=FAKE_ANTHROPIC_KEY)
    run_doctor(config)

    captured = capsys.readouterr()
    assert FAKE_OPENAI_KEY not in captured.out
    assert FAKE_ANTHROPIC_KEY not in captured.out
