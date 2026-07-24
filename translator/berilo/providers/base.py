"""Abstract LLM client interface shared by all providers.

Every provider (OpenAI, Anthropic, ...) implements :class:`LLMClient` so the
rest of the pipeline (translate, evaluate) never depends on a specific
vendor SDK. Costs are computed in EUR so the CLI can report a single,
comparable currency regardless of provider billing currency.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class CompletionResult:
    """Result of a single completion call to an LLM provider.

    Attributes:
        text: The completion text returned by the model.
        input_tokens: Number of input (prompt) tokens billed.
        output_tokens: Number of output (completion) tokens billed.
        cost_eur: Estimated cost of this call in EUR.
        model: The model identifier that served the request.
    """

    text: str
    input_tokens: int
    output_tokens: int
    cost_eur: float
    model: str


class LLMClient(ABC):
    """Abstract base for a provider-specific LLM client.

    Implementations wrap a single vendor SDK (OpenAI, Anthropic, ...) behind
    this interface so pipeline code stays provider-agnostic. The user's own
    API key is supplied at construction time; it is never logged or embedded
    in returned results.
    """

    @abstractmethod
    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
    ) -> CompletionResult:
        """Request a completion from the underlying model.

        Args:
            prompt: A single-turn prompt string. Mutually exclusive with
                ``messages``.
            messages: A chat-style message list (``role``/``content`` dicts).
                Mutually exclusive with ``prompt``.

        Returns:
            The :class:`CompletionResult` for this call.

        Raises:
            NotImplementedError: Always, in this stub.
        """
        raise NotImplementedError


class ContentPolicyError(Exception):
    """A provider refused the request on content-policy grounds.

    Raised when a moderation layer flags the *source text* being translated
    (e.g. a history book quoting extremist propaganda verbatim). Callers may
    route the affected batch to a fallback provider; the error is not
    transient and must never be blindly retried against the same provider.
    """
