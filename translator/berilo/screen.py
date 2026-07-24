"""Segment-quality screen for extraction QA.

After normalization, ``berilo inspect --screen`` samples a handful of
paragraph segments and asks an LLM whether each is clean book-body prose (as
opposed to a leaked running header, page number, table-of-contents entry, or
garbled OCR). The result quantifies extraction cleanliness — the offline
projection of the S1.2 Verify line: *≥95% of 30 randomly sampled segments
(seed 42) are clean prose*.

The screen depends only on the abstract :class:`~berilo.providers.base.LLMClient`
so it is provider-agnostic and cheaply mockable in tests; the paid live run is
performed by the Supervisor at landing.
"""

from __future__ import annotations

import logging
import random
from dataclasses import dataclass, field

from berilo.models import Book, Segment, SegmentType
from berilo.providers.base import LLMClient

logger = logging.getLogger(__name__)

# Default sample size / seed for the screen, matching the S1.2 Verify line.
DEFAULT_SAMPLE_SIZE = 30
DEFAULT_SEED = 42

# Deterministic, single-token screening prompt. The model must answer with a
# bare YES/NO so parsing is unambiguous across providers.
_SCREEN_PROMPT = (
    "You are screening text extracted from a book by an automated PDF "
    "pipeline. Reply with exactly one word, YES or NO.\n"
    "Answer YES if the passage is clean prose from the body of the book. "
    "Answer NO if it is a running header, footer, page number, "
    "table-of-contents entry, or garbled / broken text.\n\n"
    "PASSAGE:\n{text}\n\nAnswer (YES or NO):"
)


@dataclass(frozen=True)
class SegmentVerdict:
    """One segment's screen result.

    Attributes:
        segment: The screened segment.
        is_clean: True if the model judged it clean book-body prose.
        raw_response: The model's raw reply text (for auditing).
    """

    segment: Segment
    is_clean: bool
    raw_response: str


@dataclass(frozen=True)
class ScreenReport:
    """Aggregate result of screening a batch of segments.

    Attributes:
        verdicts: Per-segment verdicts in the order screened.
        cost_eur: Total EUR cost accumulated across all screen calls.
    """

    verdicts: list[SegmentVerdict] = field(default_factory=list)
    cost_eur: float = 0.0

    @property
    def total(self) -> int:
        """Number of segments screened."""
        return len(self.verdicts)

    @property
    def clean_count(self) -> int:
        """Number of segments judged clean."""
        return sum(1 for v in self.verdicts if v.is_clean)

    @property
    def clean_fraction(self) -> float:
        """Fraction of screened segments judged clean (0.0 if none screened)."""
        return self.clean_count / self.total if self.total else 0.0

    @property
    def flagged(self) -> list[SegmentVerdict]:
        """Verdicts for segments judged *not* clean."""
        return [v for v in self.verdicts if not v.is_clean]


def sample_segments(
    book: Book,
    n: int = DEFAULT_SAMPLE_SIZE,
    seed: int = DEFAULT_SEED,
) -> list[Segment]:
    """Deterministically sample paragraph segments from a book.

    Sampling is restricted to :attr:`SegmentType.PARAGRAPH` segments (headings
    and other structural segments are not prose to be screened) and is
    reproducible for a given ``(n, seed)``: the same book yields the same
    sample every time.

    Args:
        book: The normalized book to sample from.
        n: Desired sample size. If the book has fewer paragraph segments, all
            of them are returned.
        seed: Seed for the deterministic RNG.

    Returns:
        The sampled paragraph segments, in document order.
    """
    paragraphs = [seg for seg in book.segments if seg.type is SegmentType.PARAGRAPH]
    if n >= len(paragraphs):
        return paragraphs
    rng = random.Random(seed)
    chosen = rng.sample(paragraphs, n)
    chosen.sort(key=lambda seg: seg.position)
    return chosen


def _is_yes(response_text: str) -> bool:
    """Return True if a model reply affirms (starts with 'yes')."""
    return response_text.strip().lower().startswith("yes")


def screen_segments(segments: list[Segment], client: LLMClient) -> ScreenReport:
    """Screen segments for clean-prose quality via an LLM.

    One completion call is made per segment. Costs reported by the client are
    summed into the report so the caller can surface actual spend.

    Args:
        segments: Segments to screen (typically from :func:`sample_segments`).
        client: The LLM client used to judge each segment.

    Returns:
        A :class:`ScreenReport` with per-segment verdicts, clean fraction, and
        total cost.
    """
    verdicts: list[SegmentVerdict] = []
    total_cost = 0.0
    for segment in segments:
        result = client.complete(prompt=_SCREEN_PROMPT.format(text=segment.text))
        total_cost += result.cost_eur
        verdicts.append(
            SegmentVerdict(
                segment=segment,
                is_clean=_is_yes(result.text),
                raw_response=result.text,
            )
        )
    report = ScreenReport(verdicts=verdicts, cost_eur=total_cost)
    logger.info(
        "screened %d segments: %.1f%% clean, cost €%.4f",
        report.total,
        report.clean_fraction * 100,
        report.cost_eur,
    )
    return report
