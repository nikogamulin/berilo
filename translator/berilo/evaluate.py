"""Verify/evaluate stage (stub). See S1.x.

Checks translation completeness (no dropped/empty segments — hard fail),
LLM-judge sample scoring, and glossary consistency. Backs the
``berilo eval`` CLI subcommand and Rubric T scoring.
"""

from __future__ import annotations

from berilo.models import Book
from berilo.providers.base import LLMClient


def evaluate_book(
    source: Book,
    translated: Book,
    client: LLMClient,
    sample_size: int = 40,
    seed: int = 42,
) -> dict[str, float]:
    """Score a translated book against its source.

    Args:
        source: The original (untranslated) book.
        translated: The translated book to evaluate.
        client: The LLM client used for judge scoring.
        sample_size: Number of segments to sample for judge scoring.
        seed: Random seed for sample selection (reproducibility).

    Returns:
        A mapping of metric name to score.

    Raises:
        NotImplementedError: Always, in this stub.
    """
    raise NotImplementedError("evaluate_book is not yet implemented")
