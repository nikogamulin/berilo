"""LLM provider abstraction (OpenAI, Anthropic) behind a single interface.

Exposes :func:`create_client`, a factory that picks the right
:class:`~berilo.providers.base.LLMClient` implementation for a given model
name, and :func:`retry_with_backoff`, a small hand-rolled retry helper shared
by both provider implementations for transient/rate-limit errors.
"""

from __future__ import annotations

import random
import time
from collections.abc import Callable
from typing import TYPE_CHECKING, TypeVar

if TYPE_CHECKING:
    from berilo.config import Config
    from berilo.providers.base import LLMClient

#: Maximum number of retry attempts after the initial call, on transient
#: provider errors (rate limits, timeouts, connection issues, 5xx).
MAX_RETRIES = 5

#: Base delay (seconds) for exponential backoff; actual delay per attempt is
#: ``BASE_DELAY_S * 2 ** attempt`` plus a random jitter in ``[0, BASE_DELAY_S)``.
BASE_DELAY_S = 1.0

# Model-name prefixes routed to each provider. Anthropic is checked first
# since its prefix is unambiguous; OpenAI prefixes (gpt-*, o*) are checked
# second.
_ANTHROPIC_MODEL_PREFIXES = ("claude-",)
_OPENAI_MODEL_PREFIXES = ("gpt-", "o")

_T = TypeVar("_T")


def create_client(model: str, config: Config) -> LLMClient:
    """Instantiate the provider client that serves ``model``.

    Routing is by model-name prefix: ``gpt-*``/``o*`` go to OpenAI,
    ``claude-*`` goes to Anthropic.

    Args:
        model: Model identifier (e.g. ``"gpt-5-mini"``, ``"claude-haiku-4-5"``).
        config: Resolved configuration holding provider API keys.

    Returns:
        A ready-to-use :class:`~berilo.providers.base.LLMClient`.

    Raises:
        ValueError: If ``model`` doesn't match a known provider prefix, or the
            API key required to serve it is not configured.
    """
    if model.startswith(_ANTHROPIC_MODEL_PREFIXES):
        if not config.anthropic_api_key:
            raise ValueError(
                f"Model '{model}' requires ANTHROPIC_API_KEY to be set (in .env or "
                "the environment) — no Anthropic key is configured."
            )
        from berilo.providers.anthropic import AnthropicClient

        return AnthropicClient(api_key=config.anthropic_api_key, model=model)

    if model.startswith(_OPENAI_MODEL_PREFIXES):
        if not config.openai_api_key:
            raise ValueError(
                f"Model '{model}' requires OPENAI_API_KEY to be set (in .env or "
                "the environment) — no OpenAI key is configured."
            )
        from berilo.providers.openai import OpenAIClient

        return OpenAIClient(
            api_key=config.openai_api_key,
            model=model,
            reasoning_effort=config.reasoning_effort,
        )

    raise ValueError(
        f"Unrecognized model '{model}': expected an OpenAI model (gpt-*, o*) "
        "or an Anthropic model (claude-*)."
    )


def retry_with_backoff(
    func: Callable[[], _T],
    *,
    is_retryable: Callable[[Exception], bool],
    max_retries: int = MAX_RETRIES,
    base_delay_s: float = BASE_DELAY_S,
) -> _T:
    """Call ``func``, retrying transient failures with exponential backoff + jitter.

    Args:
        func: Zero-argument callable to invoke (typically a closure over the
            actual SDK call).
        is_retryable: Predicate deciding whether a raised exception should be
            retried (e.g. rate limits, timeouts) vs. re-raised immediately
            (e.g. authentication, bad request).
        max_retries: Maximum number of retry attempts after the first call.
        base_delay_s: Base delay in seconds for the exponential backoff.

    Returns:
        Whatever ``func()`` returns on its first successful attempt.

    Raises:
        Exception: The last exception raised by ``func``, once retries are
            exhausted or ``is_retryable`` returns False.
    """
    attempt = 0
    while True:
        try:
            return func()
        except Exception as exc:  # noqa: BLE001 - re-raised below when not retryable
            attempt += 1
            if attempt > max_retries or not is_retryable(exc):
                raise
            delay = base_delay_s * (2 ** (attempt - 1)) + random.uniform(0, base_delay_s)
            time.sleep(delay)
