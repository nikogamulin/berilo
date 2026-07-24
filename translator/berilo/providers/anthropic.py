"""Anthropic :class:`~berilo.providers.base.LLMClient` implementation.

Users may select any Anthropic model as an alternative to the OpenAI default.
Wraps the official ``anthropic`` SDK's messages API, with exponential-backoff
retry on transient errors and per-call token + EUR cost accounting.
"""

from __future__ import annotations

import logging

import anthropic as anthropic_sdk

from berilo.providers import retry_with_backoff
from berilo.providers.base import CompletionResult, LLMClient
from berilo.providers.pricing import cost_eur

logger = logging.getLogger(__name__)

DEFAULT_MODEL = "claude-haiku-4-5"

#: The Anthropic messages API requires ``max_tokens``; this default is
#: generous enough for a translated paragraph or a judge-score response.
DEFAULT_MAX_TOKENS = 1024

#: Transient/retryable Anthropic SDK exceptions: rate limits, timeouts,
#: connection drops, overloaded/5xx server errors. Authentication/bad-request
#: /permission errors are not retried — they won't succeed on retry.
_RETRYABLE_ANTHROPIC_ERRORS: tuple[type[Exception], ...] = (
    anthropic_sdk.RateLimitError,
    anthropic_sdk.APIConnectionError,
    anthropic_sdk.APITimeoutError,
    anthropic_sdk.InternalServerError,
    anthropic_sdk.OverloadedError,
)


class AnthropicClient(LLMClient):
    """LLM client backed by the Anthropic API.

    Attributes:
        model: Model identifier to use for completions.
    """

    def __init__(self, api_key: str, model: str = DEFAULT_MODEL) -> None:
        """Initialize the client.

        Args:
            api_key: The user's own Anthropic API key. Held only by the
                underlying SDK client (``anthropic.Anthropic``); never stored
                as a plain attribute or logged.
            model: Model identifier to use for completions.
        """
        self.model = model
        self._client = anthropic_sdk.Anthropic(api_key=api_key)

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        *,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        system: str | None = None,
    ) -> CompletionResult:
        """Request a message completion from the configured Anthropic model.

        Args:
            prompt: A single-turn prompt string. Mutually exclusive with
                ``messages``.
            messages: A chat-style message list (``role``/``content`` dicts).
                Mutually exclusive with ``prompt``.
            max_tokens: Cap on completion tokens; the Anthropic API requires
                this parameter on every request.
            system: Optional system-prompt string, passed via the API's
                dedicated ``system`` parameter (not injected into
                ``messages``).

        Returns:
            The :class:`CompletionResult` for this call, with token counts
            and EUR cost from the response's reported usage.

        Raises:
            ValueError: If both or neither of ``prompt``/``messages`` are
                given.
            anthropic.AnthropicError: Propagated from the SDK after retries
                are exhausted (or immediately for non-retryable errors).
        """
        if (prompt is None) == (messages is None):
            raise ValueError("complete() requires exactly one of `prompt` or `messages`.")

        chat_messages: list[dict[str, str]] = (
            list(messages) if messages is not None else [{"role": "user", "content": prompt}]
        )

        def _call() -> object:
            kwargs: dict[str, object] = {
                "model": self.model,
                "messages": chat_messages,
                "max_tokens": max_tokens,
            }
            if system is not None:
                kwargs["system"] = system
            return self._client.messages.create(**kwargs)

        response = retry_with_backoff(
            _call,
            is_retryable=lambda exc: isinstance(exc, _RETRYABLE_ANTHROPIC_ERRORS),
        )

        text = "".join(
            block.text for block in response.content if getattr(block, "type", None) == "text"
        )
        input_tokens = response.usage.input_tokens
        output_tokens = response.usage.output_tokens
        return CompletionResult(
            text=text,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_eur=cost_eur(self.model, input_tokens, output_tokens),
            model=self.model,
        )
