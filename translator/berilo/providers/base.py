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


class EmptyCompletionError(Exception):
    """A provider billed a call but returned no completion text.

    Raised when a response carries no usable text despite reporting
    non-zero tokens and cost — e.g. a reasoning model exhausting its token
    budget on hidden reasoning, or a response whose content blocks contain
    no text block at all. Segment integrity (CLAUDE.md §2) requires this be
    loud; a silently empty, billed translation must never be cached or
    written into the output book.

    A caller that can degrade to a smaller unit of work (a stricter retry,
    a per-segment fallback) should catch this rather than let it abort the
    whole run — only a caller with no smaller unit left should let it
    propagate. ``result`` carries the (unusable-text) :class:`CompletionResult`
    the provider had already billed before raising, so a degrading caller can
    still fold its cost into run accounting instead of losing it silently.

    Attributes:
        result: The billed, unusable-text completion, if the provider could
            construct one before raising; ``None`` otherwise.
    """

    def __init__(self, message: str, *, result: CompletionResult | None = None) -> None:
        """Initialize the error.

        Args:
            message: Human-readable description of the failure.
            result: The billed completion whose text was unusable, if known.
        """
        super().__init__(message)
        self.result = result


class TruncatedCompletionError(Exception):
    """A provider cut a completion off before it finished, still billed.

    Raised when the response's stop/finish reason reports that the token
    budget was exhausted mid-generation (OpenAI ``finish_reason="length"``,
    Anthropic ``stop_reason="max_tokens"``) — indistinguishable from a
    complete response without checking that field. A partial batch
    translation must never be trusted silently.

    Same degrade-vs-propagate contract as :class:`EmptyCompletionError`: a
    caller with a smaller retry unit available should catch and degrade;
    a caller with none left (single-segment translation) should let it
    surface as a loud failure.

    Attributes:
        result: The billed, partial-text completion, if the provider could
            construct one before raising; ``None`` otherwise.
    """

    def __init__(self, message: str, *, result: CompletionResult | None = None) -> None:
        """Initialize the error.

        Args:
            message: Human-readable description of the failure.
            result: The billed, truncated completion, if known.
        """
        super().__init__(message)
        self.result = result
