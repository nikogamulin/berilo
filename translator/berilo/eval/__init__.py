"""Rubric T evaluation harness (S1.7).

``berilo eval`` scores a translated EPUB against its source on Rubric T
(``docs/rubric.md``): exact completeness (T1), seeded LLM-judge meaning (T2) and
fluency (T3) with bootstrap CIs, glossary terminology consistency (T4),
automated structural diff (T5), extraction-cleanliness screening (T6, PDF
sources), and cost efficiency (T7). Sub-modules:

* :mod:`berilo.eval.sampling` — seeded sampling + percentile bootstrap CI.
* :mod:`berilo.eval.judge` — versioned LLM-judge prompts + strict verdict parsing.
* :mod:`berilo.eval.rubric_t` — dimension scorers, alignment, weighted total.
* :mod:`berilo.eval.runner` — normalize → score → report → persist orchestration.
"""

from __future__ import annotations

from berilo.eval.judge import Judge, JudgeError
from berilo.eval.rubric_t import (
    RUBRIC_VERSION,
    AlignmentError,
    RubricTResult,
    align,
    score_book,
)

__all__ = [
    "Judge",
    "JudgeError",
    "AlignmentError",
    "RubricTResult",
    "RUBRIC_VERSION",
    "align",
    "score_book",
]
