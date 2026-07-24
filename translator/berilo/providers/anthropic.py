"""Anthropic :class:`~berilo.providers.base.LLMClient` implementation (stub).

Users may select any Anthropic model as an alternative to the OpenAI default.
Not yet implemented — see S1.x stories.
"""

from __future__ import annotations

from berilo.providers.base import CompletionResult, LLMClient

DEFAULT_MODEL = "claude-haiku-4-5"


class AnthropicClient(LLMClient):
    """LLM client backed by the Anthropic API.

    Attributes:
        api_key: The user's own Anthropic API key (never logged).
        model: Model identifier to use for completions.
    """

    def __init__(self, api_key: str, model: str = DEFAULT_MODEL) -> None:
        """Initialize the client.

        Args:
            api_key: The user's own Anthropic API key.
            model: Model identifier to use for completions.
        """
        self.api_key = api_key
        self.model = model

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
    ) -> CompletionResult:
        """Not yet implemented.

        Raises:
            NotImplementedError: Always, in this stub.
        """
        raise NotImplementedError("AnthropicClient.complete is not yet implemented")
