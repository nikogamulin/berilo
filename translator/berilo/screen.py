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

# Chapter-title substrings that mark back matter (notes/index/bibliography):
# their segments are citation fragments, not body prose, so they are excluded
# from the clean-prose sample. Mirrors the normalizer's back-matter titling.
_BACK_MATTER_TITLE_SUBSTRINGS = (
    "notes",
    "endnotes",
    "index",
    "bibliography",
    "references",
    "acknowledg",
    "about the author",
)

# Chapter-title substrings that mark front matter (cover/title/copyright/TOC):
# not body prose either, and excluded from the clean-prose sample. Mirrors the
# normalizer's front-matter titling (FRONT_MATTER_CHAPTER_TITLE = "Front Matter").
_FRONT_MATTER_TITLE_SUBSTRINGS = (
    "front matter",
    "title page",
    "half title",
    "copyright",
    "contents",
    "dedication",
    "epigraph",
    "praise for",
)

# Deterministic, single-token screening prompt. The model must answer with a
# bare YES/NO so parsing is unambiguous across providers.
#
# v2 (2026-07-24): scoped to EXTRACTION artifacts, matching Rubric T6's
# definition ("inline page numbers, running headers, broken hyphenation").
# Source-inherent OCR character substitutions ("s500" for "$500", "T think"
# for "I think") are the scan's fault, not the pipeline's — a passage carrying
# them still screens YES if the extraction itself is faithful body prose.
SCREEN_PROMPT_VERSION = "v2"
_SCREEN_PROMPT = (
    "You are screening text extracted from a book by an automated PDF "
    "pipeline for EXTRACTION artifacts. Reply with exactly one word, YES or "
    "NO.\n"
    "Answer NO only if the passage shows an extraction defect: a running "
    "header or footer, a page number, a table-of-contents entry, a fragment "
    "cut off mid-sentence, lines from different paragraphs jumbled together, "
    "duplicated text, or unreadable character soup.\n"
    "Answer YES if the passage is body prose that was extracted faithfully — "
    "even if the source scan itself contains OCR typos such as wrong "
    "characters ('s500' for '$500', 'T think' for 'I think'); source typos "
    "are not extraction defects.\n\n"
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


def back_matter_chapter_indices(book: Book) -> set[int]:
    """Return chapter indices whose title names back matter (notes/index/...).

    Segments in these chapters are citation/reference fragments rather than
    body prose, so extraction-quality screening excludes them.

    Args:
        book: The normalized book.

    Returns:
        The set of back-matter chapter indices (possibly empty).
    """
    return _chapter_indices_matching(book, _BACK_MATTER_TITLE_SUBSTRINGS)


def front_matter_chapter_indices(book: Book) -> set[int]:
    """Return chapter indices whose title names front matter (cover/copyright/...).

    Segments in these chapters are cover art, title/copyright pages, and TOC
    entries — not body prose — so extraction-quality screening excludes them.

    Args:
        book: The normalized book.

    Returns:
        The set of front-matter chapter indices (possibly empty).
    """
    return _chapter_indices_matching(book, _FRONT_MATTER_TITLE_SUBSTRINGS)


def _chapter_indices_matching(book: Book, substrings: tuple[str, ...]) -> set[int]:
    """Chapter indices whose title contains any of ``substrings`` (lowercased)."""
    return {
        segment.chapter_index
        for segment in book.segments
        if segment.chapter_title and any(sub in segment.chapter_title.lower() for sub in substrings)
    }


def sample_segments(
    book: Book,
    n: int = DEFAULT_SAMPLE_SIZE,
    seed: int = DEFAULT_SEED,
) -> list[Segment]:
    """Deterministically sample body-prose paragraph segments from a book.

    Sampling is restricted to :attr:`SegmentType.PARAGRAPH` segments (headings,
    captions, and other structural segments are not prose to be screened) and
    excludes back-matter chapters (notes/index/bibliography) and front-matter
    chapters (cover/title/copyright/TOC), whose segments are not body prose. It
    is reproducible for a given ``(n, seed)``: the same book yields the same
    sample every time.

    Args:
        book: The normalized book to sample from.
        n: Desired sample size. If the book has fewer eligible paragraph
            segments, all of them are returned.
        seed: Seed for the deterministic RNG.

    Returns:
        The sampled body-prose paragraph segments, in document order.
    """
    excluded = back_matter_chapter_indices(book) | front_matter_chapter_indices(book)
    paragraphs = [
        seg
        for seg in book.segments
        if seg.type is SegmentType.PARAGRAPH and seg.chapter_index not in excluded
    ]
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
