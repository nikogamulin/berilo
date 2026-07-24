"""Verify/evaluate stage — thin shim over :mod:`berilo.eval` (S1.7).

The real Rubric T implementation lives in the :mod:`berilo.eval` package
(dimension scorers, seeded LLM-judge scoring, bootstrap CIs). This module is
kept as a stable import surface: ``berilo.evaluate.evaluate_book`` scores a
normalized source/translated :class:`~berilo.models.Book` pair and returns the
weighted total plus per-dimension points.
"""

from __future__ import annotations

from berilo.eval import score_book
from berilo.eval.judge import Judge
from berilo.eval.rubric_t import RubricTResult
from berilo.models import Book
from berilo.providers.base import LLMClient

__all__ = ["evaluate_book", "RubricTResult"]


def evaluate_book(
    source: Book,
    translated: Book,
    client: LLMClient,
    sample_size: int = 40,
    seed: int = 42,
    *,
    glossary: dict[str, str] | None = None,
    actual_cost_eur: float | None = None,
) -> RubricTResult:
    """Score a translated book against its source on Rubric T.

    Args:
        source: The original (untranslated) book.
        translated: The translated book to evaluate.
        client: The LLM client used for judge scoring.
        sample_size: Number of segment pairs to sample for judge scoring.
        seed: Random seed for sample selection (reproducibility).
        glossary: Optional per-book glossary (``source term -> rendering``); when
            omitted, the terminology dimension (T4) is dropped and weights
            renormalized.
        actual_cost_eur: Optional total translation cost; when omitted, the cost
            dimension (T7) is dropped and weights renormalized.

    Returns:
        The :class:`~berilo.eval.rubric_t.RubricTResult`.

    Raises:
        AlignmentError: If source and translated structure cannot be aligned.
    """
    return score_book(
        source,
        translated,
        Judge(client),
        sample_size=sample_size,
        seed=seed,
        glossary=glossary,
        actual_cost_eur=actual_cost_eur,
    )
