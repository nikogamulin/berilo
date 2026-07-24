"""OpenAI :class:`~berilo.providers.base.LLMClient` implementation (stub).

Default model per the product spec is ``gpt-5-mini``; users may override via
``.env`` / ``--model``. Not yet implemented — see S1.x stories.
"""

from __future__ import annotations

from berilo.providers.base import CompletionResult, LLMClient

DEFAULT_MODEL = "gpt-5-mini"


class OpenAIClient(LLMClient):
    """LLM client backed by the OpenAI API.

    Attributes:
        api_key: The user's own OpenAI API key (never logged).
        model: Model identifier to use for completions.
    """

    def __init__(self, api_key: str, model: str = DEFAULT_MODEL) -> None:
        """Initialize the client.

        Args:
            api_key: The user's own OpenAI API key.
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
        raise NotImplementedError("OpenAIClient.complete is not yet implemented")
