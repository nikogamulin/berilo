"""OpenAI :class:`~berilo.providers.base.LLMClient` implementation.

Default model per the product spec is ``gpt-5-mini``; users may override via
``.env`` / ``--model``. Wraps the official ``openai`` SDK's chat completions
API, with exponential-backoff retry on transient errors and per-call token +
EUR cost accounting.
"""

from __future__ import annotations

import logging

import openai as openai_sdk

from berilo.providers import retry_with_backoff
from berilo.providers.base import CompletionResult, ContentPolicyError, LLMClient
from berilo.providers.pricing import cost_eur

logger = logging.getLogger(__name__)

DEFAULT_MODEL = "gpt-5-mini"

#: Model families that accept the ``reasoning_effort`` parameter.
_REASONING_PREFIXES = ("gpt-5", "o1", "o3", "o4")

#: Transient/retryable OpenAI SDK exceptions: rate limits, timeouts,
#: connection drops, and 5xx server errors. Authentication/bad-request/
#: permission errors are not retried — they won't succeed on retry.
_RETRYABLE_OPENAI_ERRORS: tuple[type[Exception], ...] = (
    openai_sdk.RateLimitError,
    openai_sdk.APIConnectionError,
    openai_sdk.APITimeoutError,
    openai_sdk.InternalServerError,
)


class OpenAIClient(LLMClient):
    """LLM client backed by the OpenAI API.

    Attributes:
        model: Model identifier to use for completions.
    """

    def __init__(
        self,
        api_key: str,
        model: str = DEFAULT_MODEL,
        reasoning_effort: str | None = None,
    ) -> None:
        """Initialize the client.

        Args:
            api_key: The user's own OpenAI API key. Held only by the
                underlying SDK client (``openai.OpenAI``); never stored as a
                plain attribute or logged.
            model: Model identifier to use for completions.
            reasoning_effort: Reasoning effort for reasoning models
                (``gpt-5*``/``o*``): ``"minimal"``/``"low"``/``"medium"``/
                ``"high"``. ``None`` leaves the API default. Ignored for
                non-reasoning models.
        """
        self.model = model
        self.reasoning_effort = reasoning_effort
        self._client = openai_sdk.OpenAI(api_key=api_key)

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        *,
        max_tokens: int | None = None,
        system: str | None = None,
    ) -> CompletionResult:
        """Request a chat completion from the configured OpenAI model.

        Args:
            prompt: A single-turn prompt string. Mutually exclusive with
                ``messages``.
            messages: A chat-style message list (``role``/``content`` dicts).
                Mutually exclusive with ``prompt``.
            max_tokens: Optional cap on completion tokens
                (``max_completion_tokens``); omitted from the request when
                ``None``.
            system: Optional system-message content, prepended to the
                message list.

        Returns:
            The :class:`CompletionResult` for this call, with token counts
            and EUR cost from the response's reported usage.

        Raises:
            ValueError: If both or neither of ``prompt``/``messages`` are
                given.
            openai.OpenAIError: Propagated from the SDK after retries are
                exhausted (or immediately for non-retryable errors).
        """
        if (prompt is None) == (messages is None):
            raise ValueError("complete() requires exactly one of `prompt` or `messages`.")

        chat_messages: list[dict[str, str]] = (
            list(messages) if messages is not None else [{"role": "user", "content": prompt}]
        )
        if system is not None:
            chat_messages = [{"role": "system", "content": system}, *chat_messages]

        def _call() -> object:
            kwargs: dict[str, object] = {"model": self.model, "messages": chat_messages}
            if max_tokens is not None:
                kwargs["max_completion_tokens"] = max_tokens
            if self.reasoning_effort is not None and self.model.startswith(_REASONING_PREFIXES):
                kwargs["reasoning_effort"] = self.reasoning_effort
            return self._client.chat.completions.create(**kwargs)

        try:
            response = retry_with_backoff(
                _call,
                is_retryable=lambda exc: isinstance(exc, _RETRYABLE_OPENAI_ERRORS),
            )
        except openai_sdk.BadRequestError as exc:
            if getattr(exc, "code", None) == "invalid_prompt" or "usage policy" in str(exc):
                raise ContentPolicyError(
                    f"OpenAI flagged the source text for model {self.model}; "
                    "route this batch to a fallback provider."
                ) from exc
            raise

        text = response.choices[0].message.content or ""
        input_tokens = response.usage.prompt_tokens
        output_tokens = response.usage.completion_tokens
        return CompletionResult(
            text=text,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_eur=cost_eur(self.model, input_tokens, output_tokens),
            model=self.model,
        )
