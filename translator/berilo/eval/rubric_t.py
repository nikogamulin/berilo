"""Rubric T scoring: dimension scorers, alignment, weighting, bootstrap total.

Implements ``docs/rubric.md`` §"Rubric T" end to end. Dimensions:

* **T1 Completeness** (20) — exact, no sampling; ``< 100 %`` caps the total at 40.
* **T2 Meaning** (30) — LLM judge over a seeded sample of pairs.
* **T3 Fluency** (20) — LLM judge over the same sample.
* **T4 Terminology** (10) — glossary-rendering hit rate (cache glossary).
* **T5 Structure** (10) — automated source/target structural diff.
* **T6 Extraction cleanliness** (5) — PDF sources only, else automatic full.
* **T7 Cost efficiency** (5) — from the translation cache's ``calls`` table.

Dimensions that cannot be measured (no glossary, no cost data) score ``None``
and are dropped from the weighted total, which is then renormalized over the
weights that remain — never silently treated as zero. Sampled dimensions and
the weighted total are reported with 95 % bootstrap CIs.
"""

from __future__ import annotations

import difflib
import logging
import random
import re
from collections import Counter
from dataclasses import dataclass, field

from berilo.eval import sampling
from berilo.eval.judge import Judge
from berilo.models import Book, Segment, SegmentType

logger = logging.getLogger(__name__)

#: Rubric revision recorded in every score row.
RUBRIC_VERSION = "1.0"

#: Dimension weights (sum to 100), keyed by dimension id.
WEIGHTS: dict[str, int] = {
    "T1": 20,
    "T2": 30,
    "T3": 20,
    "T4": 10,
    "T5": 10,
    "T6": 5,
    "T7": 5,
}

DIMENSION_NAMES: dict[str, str] = {
    "T1": "Completeness",
    "T2": "Meaning preservation",
    "T3": "Fluency / naturalness",
    "T4": "Terminology consistency",
    "T5": "Structural fidelity",
    "T6": "Extraction cleanliness",
    "T7": "Cost efficiency",
}

#: Judge score scale (1-5) for meaning/fluency dimensions.
JUDGE_MAX = 5

#: Segment types eligible for meaning/fluency judging (prose only).
_JUDGEABLE_TYPES = (SegmentType.PARAGRAPH,)

#: T1 completeness below this fraction caps the whole rubric total.
COMPLETE_FRACTION = 1.0
INCOMPLETE_CAP = 40.0

#: T5 sub-weights (sum to the T5 weight of 10).
_T5_CHAPTERS = 2.5
_T5_HEADINGS = 2.5
_T5_BLOCKS = 2.5
_T5_EMPHASIS = 2.5
_EMPHASIS_RETENTION_TARGET = 0.95

#: T6 sample size (paragraphs screened for artifacts).
T6_SAMPLE = 20

#: T4 considers the most frequent glossary terms up to this many.
T4_TOP_TERMS = 20

#: T7 cost thresholds (EUR per 100k source words): full score at/below FULL,
#: linear to zero at ZERO. Ties to the "<= €1.50 / 100k words" rubric clause;
#: the dry-run estimate is not persisted, so the estimate-ratio clause cannot be
#: evaluated here (see module note / findings).
_T7_FULL_EUR_PER_100K = 1.5
_T7_ZERO_EUR_PER_100K = 4.5
_WORDS_PER_UNIT = 100_000

#: Open inline-emphasis tags counted for T5 emphasis retention.
_EMPHASIS_OPEN_RE = re.compile(r"<(?:em|strong|i|b)>", re.IGNORECASE)


class AlignmentError(RuntimeError):
    """Raised when source and translated structure cannot be trusted to align.

    A structural *scramble* (segments reordered, foreign content inserted, or
    heading/paragraph types not corresponding) makes positional pairing
    meaningless — so we refuse to score rather than silently pair mismatched
    text. A pure *truncation* (missing translations) is NOT an error: it is
    reported through T1 completeness instead.
    """


@dataclass(frozen=True)
class Alignment:
    """Result of aligning a source book to its translation.

    Attributes:
        pairs: 1:1 source-to-target pairs in source order; the target is
            ``None`` where a source segment has no corresponding translation.
        source_count: Number of source segments.
        target_count: Number of translated segments.
    """

    pairs: list[tuple[Segment, Segment | None]]
    source_count: int
    target_count: int

    @property
    def translated_pairs(self) -> list[tuple[Segment, Segment]]:
        """Pairs whose target exists and has non-empty text."""
        return [(s, t) for s, t in self.pairs if t is not None and t.text.strip()]


def _signature(segment: Segment) -> tuple[int, str, int]:
    """Structural fingerprint used for alignment (position-independent within it).

    Combines chapter index, segment type, and heading level so that a
    reordering or a type change registers as a mismatch, while identical prose
    paragraphs in the same chapter share a fingerprint (as they must for a
    subsequence match to find real drops).
    """
    return (segment.chapter_index, segment.type.value, segment.heading_level or 0)


def align(source: Book, translated: Book) -> Alignment:
    """Align ``translated`` to ``source`` by structural subsequence matching.

    Uses :class:`difflib.SequenceMatcher` over per-segment structural
    fingerprints (not raw text, which differs by language) to find the real
    correspondence — never a naive prefix zip. Missing translations surface as
    ``(source, None)`` pairs (→ T1 < 100 %). Foreign or reordered structure
    (``insert``/``replace`` opcodes) raises :class:`AlignmentError` loudly.

    Args:
        source: The normalized source book.
        translated: The normalized translated book.

    Returns:
        The :class:`Alignment`.

    Raises:
        AlignmentError: On a structural scramble (reorder / inserted / foreign
            content) that cannot be positionally trusted.
    """
    sig_s = [_signature(s) for s in source.segments]
    sig_t = [_signature(s) for s in translated.segments]
    matcher = difflib.SequenceMatcher(a=sig_s, b=sig_t, autojunk=False)

    pairs: list[tuple[Segment, Segment | None]] = []
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            for offset in range(i2 - i1):
                pairs.append((source.segments[i1 + offset], translated.segments[j1 + offset]))
        elif tag == "delete":
            for index in range(i1, i2):
                pairs.append((source.segments[index], None))
        elif tag == "insert":
            raise AlignmentError(
                f"Translation has {j2 - j1} segment(s) with no structural "
                f"counterpart in the source at source index {i1} "
                f"(target index {j1}); alignment cannot be trusted."
            )
        else:  # replace
            raise AlignmentError(
                f"Structural mismatch at source segments [{i1}:{i2}] vs "
                f"translated [{j1}:{j2}] — segment types/chapters do not "
                f"correspond; alignment cannot be trusted."
            )

    return Alignment(
        pairs=pairs,
        source_count=len(source.segments),
        target_count=len(translated.segments),
    )


@dataclass
class DimensionScore:
    """One scored rubric dimension.

    Attributes:
        key: Dimension id (``"T1"`` … ``"T7"``).
        name: Human-readable dimension name.
        weight: Dimension weight (out of 100).
        fraction: Achieved fraction in ``[0, 1]``, or ``None`` if unmeasurable.
        ci_points: ``(low, high)`` CI in points, or ``None`` (deterministic /
            unavailable dimensions carry no CI).
        detail: Short human note (e.g. why a dimension is ``None``).
    """

    key: str
    name: str
    weight: float
    fraction: float | None
    ci_points: tuple[float, float] | None = None
    detail: str = ""

    @property
    def available(self) -> bool:
        """Whether this dimension contributes to the weighted total."""
        return self.fraction is not None

    @property
    def points(self) -> float | None:
        """Achieved points (``fraction × weight``), or ``None`` if unavailable."""
        return None if self.fraction is None else self.fraction * self.weight


@dataclass
class RubricTResult:
    """Full Rubric T scoring result.

    Attributes:
        dimensions: The seven :class:`DimensionScore` entries in T1…T7 order.
        total: Weighted total in ``[0, 100]`` (renormalized over available
            dimensions; capped at 40 when T1 < 100 %).
        total_ci: 95 % bootstrap CI of the total, ``(low, high)``.
        complete: Whether completeness (T1) is exactly 100 %.
        sample_ids: Source segment ids of the meaning/fluency sample (stable
            under a fixed seed).
        notes: Human-readable notes (renormalization, cap, cost limitations).
        cost_eur: EUR actually spent on judge calls in this eval run.
    """

    dimensions: list[DimensionScore]
    total: float
    total_ci: tuple[float, float]
    complete: bool
    sample_ids: list[str]
    notes: list[str] = field(default_factory=list)
    cost_eur: float = 0.0

    def by_key(self) -> dict[str, DimensionScore]:
        """Return the dimensions keyed by id."""
        return {dim.key: dim for dim in self.dimensions}


# --------------------------------------------------------------------------
# Deterministic (non-judge) dimension scorers.
# --------------------------------------------------------------------------


def score_completeness(alignment: Alignment) -> DimensionScore:
    """T1: fraction of source segments with a non-empty translation (exact)."""
    translated = sum(
        1 for _, target in alignment.pairs if target is not None and target.text.strip()
    )
    fraction = translated / alignment.source_count if alignment.source_count else 1.0
    detail = f"{translated}/{alignment.source_count} source segments translated"
    if alignment.source_count != alignment.target_count:
        detail += f" (source {alignment.source_count} vs translated {alignment.target_count})"
    return DimensionScore("T1", DIMENSION_NAMES["T1"], WEIGHTS["T1"], fraction, detail=detail)


def _ratio(a: int, b: int) -> float:
    """Symmetric count-agreement ratio ``min/max`` (``1.0`` when both zero)."""
    if a == 0 and b == 0:
        return 1.0
    return min(a, b) / max(a, b)


def _heading_levels(book: Book) -> Counter:
    """Counter of heading levels present in ``book``."""
    return Counter(
        seg.heading_level or 0 for seg in book.segments if seg.type == SegmentType.HEADING
    )


def _type_count(book: Book, seg_type: SegmentType) -> int:
    """Number of segments of ``seg_type`` in ``book``."""
    return sum(1 for seg in book.segments if seg.type == seg_type)


def _emphasis_count(book: Book) -> int:
    """Total inline emphasis open-tags across every segment's text."""
    return sum(len(_EMPHASIS_OPEN_RE.findall(seg.text)) for seg in book.segments)


def score_structure(source: Book, translated: Book) -> DimensionScore:
    """T5: automated structural diff (chapters, headings, lists/quotes, emphasis)."""
    # Chapters.
    chapter_score = _ratio(source.chapter_count, translated.chapter_count) * _T5_CHAPTERS

    # Heading count + levels.
    src_headings, tgt_headings = _heading_levels(source), _heading_levels(translated)
    matched = sum((src_headings & tgt_headings).values())
    total_headings = max(sum(src_headings.values()), sum(tgt_headings.values()))
    heading_score = (matched / total_headings if total_headings else 1.0) * _T5_HEADINGS

    # Lists + blockquotes.
    list_ratio = _ratio(
        _type_count(source, SegmentType.LIST_ITEM), _type_count(translated, SegmentType.LIST_ITEM)
    )
    quote_ratio = _ratio(
        _type_count(source, SegmentType.BLOCKQUOTE),
        _type_count(translated, SegmentType.BLOCKQUOTE),
    )
    block_score = (list_ratio + quote_ratio) / 2 * _T5_BLOCKS

    # Emphasis retention.
    src_emph, tgt_emph = _emphasis_count(source), _emphasis_count(translated)
    retention = 1.0 if src_emph == 0 else min(tgt_emph, src_emph) / src_emph
    emphasis_score = (
        _T5_EMPHASIS if retention >= _EMPHASIS_RETENTION_TARGET else retention * _T5_EMPHASIS
    )

    achieved = chapter_score + heading_score + block_score + emphasis_score
    fraction = achieved / WEIGHTS["T5"]
    detail = (
        f"chapters {source.chapter_count}/{translated.chapter_count}; "
        f"headings {sum(src_headings.values())}/{sum(tgt_headings.values())}; "
        f"emphasis retention {retention:.0%}"
    )
    return DimensionScore("T5", DIMENSION_NAMES["T5"], WEIGHTS["T5"], fraction, detail=detail)


def _stem(word: str) -> str:
    """Declension-tolerant stem: drop up to two trailing letters, keep >= 3."""
    lowered = word.lower()
    return lowered[: max(3, len(lowered) - 2)]


def _rendering_matches(rendering: str, text: str) -> bool:
    """Whether ``text`` contains ``rendering`` prefix-tolerantly (per word).

    Case-insensitive; each whitespace-delimited word of ``rendering`` must
    appear in ``text`` as a stem prefix, tolerating Slovenian declension
    suffixes.
    """
    text_lower = text.lower()
    return all(_stem(word) in text_lower for word in rendering.split() if word)


def _term_occurrences(term: str, text_lower: str) -> int:
    """Count case-insensitive occurrences of ``term`` in already-lowered text."""
    if not term:
        return 0
    return text_lower.count(term.lower())


def score_terminology(alignment: Alignment, glossary: dict[str, str] | None) -> DimensionScore:
    """T4: glossary-rendering hit rate over the most frequent glossary terms."""
    if not glossary:
        return DimensionScore(
            "T4",
            DIMENSION_NAMES["T4"],
            WEIGHTS["T4"],
            None,
            detail="no glossary in cache — dimension dropped, weights renormalized",
        )

    pairs = [(s, t) for s, t in alignment.pairs if t is not None]
    lowered = [(s.text.lower(), t.text) for s, t in pairs]

    # Rank glossary terms by total occurrence in the source.
    freq = {
        term: sum(_term_occurrences(term, src_lower) for src_lower, _ in lowered)
        for term in glossary
    }
    top_terms = [
        term for term, count in sorted(freq.items(), key=lambda kv: (-kv[1], kv[0])) if count > 0
    ][:T4_TOP_TERMS]

    total = matched = 0
    for term in top_terms:
        rendering = glossary[term]
        for src_lower, tgt_text in lowered:
            occ = _term_occurrences(term, src_lower)
            if occ == 0:
                continue
            total += occ
            if _rendering_matches(rendering, tgt_text):
                matched += occ

    if total == 0:
        return DimensionScore(
            "T4",
            DIMENSION_NAMES["T4"],
            WEIGHTS["T4"],
            1.0,
            detail="glossary terms did not occur in sampled source — vacuously consistent",
        )
    fraction = matched / total
    detail = f"{matched}/{total} occurrences of top {len(top_terms)} terms rendered consistently"
    return DimensionScore("T4", DIMENSION_NAMES["T4"], WEIGHTS["T4"], fraction, detail=detail)


def score_cost(actual_cost_eur: float | None, source_word_count: int) -> DimensionScore:
    """T7: cost efficiency from the cache ``calls`` table (per-100k-words clause)."""
    if actual_cost_eur is None:
        return DimensionScore(
            "T7",
            DIMENSION_NAMES["T7"],
            WEIGHTS["T7"],
            None,
            detail="no cost data in cache — dimension dropped, weights renormalized",
        )
    if source_word_count <= 0:
        return DimensionScore(
            "T7", DIMENSION_NAMES["T7"], WEIGHTS["T7"], 1.0, detail="empty book — cost vacuous"
        )
    per_100k = actual_cost_eur / (source_word_count / _WORDS_PER_UNIT)
    if per_100k <= _T7_FULL_EUR_PER_100K:
        fraction = 1.0
    elif per_100k >= _T7_ZERO_EUR_PER_100K:
        fraction = 0.0
    else:
        span = _T7_ZERO_EUR_PER_100K - _T7_FULL_EUR_PER_100K
        fraction = (_T7_ZERO_EUR_PER_100K - per_100k) / span
    detail = (
        f"€{actual_cost_eur:.4f} actual ≈ €{per_100k:.2f}/100k words "
        f"(dry-run estimate not persisted; ratio clause not evaluated)"
    )
    return DimensionScore("T7", DIMENSION_NAMES["T7"], WEIGHTS["T7"], fraction, detail=detail)


# --------------------------------------------------------------------------
# Judge (sampled) dimension scorers.
# --------------------------------------------------------------------------


def _bootstrap_points(
    scores: list[int], weight: float, seed: int
) -> tuple[float, tuple[float, float]]:
    """Point estimate + bootstrap CI (in points) for a judged 1-5 dimension."""
    fraction = sampling.mean(scores) / JUDGE_MAX if scores else 0.0

    def statistic(indices: list[int]) -> float:
        return sampling.mean([scores[i] for i in indices]) / JUDGE_MAX * weight

    ci = sampling.bootstrap_ci(statistic, len(scores), seed=seed)
    return fraction, ci


def score_extraction(
    source_format: str,
    translated: Book,
    judge: Judge,
    *,
    sample_size: int,
    seed: int,
) -> tuple[DimensionScore, list[int]]:
    """T6: extraction cleanliness. Automatic full for non-PDF sources.

    Returns the dimension and the per-paragraph 0/1 screen scores (empty for
    non-PDF sources) so the total's bootstrap can resample them.
    """
    if source_format != "pdf":
        return (
            DimensionScore(
                "T6",
                DIMENSION_NAMES["T6"],
                WEIGHTS["T6"],
                1.0,
                detail=f"{source_format} source — automatic full",
            ),
            [],
        )

    paragraphs = [
        seg.text
        for seg in translated.segments
        if seg.type == SegmentType.PARAGRAPH and seg.text.strip()
    ]
    indices = sampling.select_indices(len(paragraphs), sample_size, seed)
    scores = [judge.screen(paragraphs[i]) for i in indices]
    fraction = sampling.mean(scores) if scores else 1.0

    def statistic(resample: list[int]) -> float:
        return sampling.mean([scores[i] for i in resample]) * WEIGHTS["T6"]

    ci = sampling.bootstrap_ci(statistic, len(scores), seed=seed) if scores else None
    detail = f"{sum(scores)}/{len(scores)} screened paragraphs clean" if scores else "no paragraphs"
    return (
        DimensionScore(
            "T6", DIMENSION_NAMES["T6"], WEIGHTS["T6"], fraction, ci_points=ci, detail=detail
        ),
        scores,
    )


# --------------------------------------------------------------------------
# Total: renormalization, cap, bootstrap.
# --------------------------------------------------------------------------


def _weighted_total(dims: list[DimensionScore], *, complete: bool) -> float:
    """Renormalized weighted total over available dimensions, with the T1 cap."""
    available = [d for d in dims if d.available]
    weight_sum = sum(d.weight for d in available)
    if weight_sum == 0:
        return 0.0
    points = sum(d.points for d in available)  # type: ignore[misc]
    total = points / weight_sum * 100.0
    if not complete:
        total = min(total, INCOMPLETE_CAP)
    return total


def _bootstrap_total(
    dims: list[DimensionScore],
    meaning: list[int],
    fluency: list[int],
    screen: list[int],
    *,
    complete: bool,
    seed: int,
    n_resamples: int = sampling.DEFAULT_RESAMPLES,
) -> tuple[float, float]:
    """Bootstrap CI of the weighted total via paired resampling of judged samples.

    The meaning and fluency arrays are resampled jointly (same indices — they
    come from the same segment sample); the T6 screen array is resampled
    independently. Deterministic dimensions are held at their point estimates.
    """
    fixed_points = 0.0
    fixed_weight = 0.0
    judged_weight = 0.0
    for dim in dims:
        if not dim.available:
            continue
        if dim.key in {"T2", "T3", "T6"}:
            judged_weight += dim.weight
        else:
            fixed_points += dim.points  # type: ignore[misc]
            fixed_weight += dim.weight

    total_weight = fixed_weight + judged_weight
    if total_weight == 0:
        point = _weighted_total(dims, complete=complete)
        return (point, point)

    t6_available = any(d.key == "T6" and d.available for d in dims)
    n_pair = len(meaning)
    n_screen = len(screen)
    rng = random.Random(seed)

    stats: list[float] = []
    for _ in range(n_resamples):
        points = fixed_points
        if meaning:
            idx = [rng.randrange(n_pair) for _ in range(n_pair)]
            points += sampling.mean([meaning[i] for i in idx]) / JUDGE_MAX * WEIGHTS["T2"]
            points += sampling.mean([fluency[i] for i in idx]) / JUDGE_MAX * WEIGHTS["T3"]
        if t6_available:
            if screen:
                sidx = [rng.randrange(n_screen) for _ in range(n_screen)]
                points += sampling.mean([screen[i] for i in sidx]) * WEIGHTS["T6"]
            else:
                points += WEIGHTS["T6"]  # non-PDF automatic full
        total = points / total_weight * 100.0
        if not complete:
            total = min(total, INCOMPLETE_CAP)
        stats.append(total)

    return sampling.percentile_ci(stats)


def score_book(
    source: Book,
    translated: Book,
    judge: Judge,
    *,
    sample_size: int,
    seed: int,
    glossary: dict[str, str] | None = None,
    actual_cost_eur: float | None = None,
) -> RubricTResult:
    """Score ``translated`` against ``source`` on all of Rubric T.

    Args:
        source: The normalized source book.
        translated: The normalized translated book.
        judge: The judge used for T2/T3/T6 (mocked in tests).
        sample_size: Segment-pair sample size for T2/T3 (and paragraph count for T6).
        seed: Seed for all sampling and bootstraps.
        glossary: The per-book glossary from the cache, or ``None`` (→ T4 dropped).
        actual_cost_eur: Total translation cost from the cache, or ``None`` (→ T7 dropped).

    Returns:
        The :class:`RubricTResult`.

    Raises:
        AlignmentError: On a structural scramble (see :func:`align`).
    """
    alignment = align(source, translated)

    t1 = score_completeness(alignment)
    complete = t1.fraction is not None and t1.fraction >= COMPLETE_FRACTION

    # Sample judgeable prose pairs (deterministic).
    eligible = [
        (s, t)
        for s, t in alignment.pairs
        if t is not None and s.type in _JUDGEABLE_TYPES and s.text.strip() and t.text.strip()
    ]
    sample_indices = sampling.select_indices(len(eligible), sample_size, seed)
    sample_pairs = [eligible[i] for i in sample_indices]
    sample_ids = [s.id for s, _ in sample_pairs]

    meaning_scores = [judge.meaning(s.text, t.text) for s, t in sample_pairs]
    fluency_scores = [judge.fluency(t.text) for _, t in sample_pairs]

    m_fraction, m_ci = _bootstrap_points(meaning_scores, WEIGHTS["T2"], seed)
    f_fraction, f_ci = _bootstrap_points(fluency_scores, WEIGHTS["T3"], seed)
    t2 = DimensionScore(
        "T2",
        DIMENSION_NAMES["T2"],
        WEIGHTS["T2"],
        m_fraction if meaning_scores else None,
        ci_points=m_ci if meaning_scores else None,
        detail=(
            f"{len(meaning_scores)} pairs judged (mean {sampling.mean(meaning_scores):.2f}/5)"
            if meaning_scores
            else "no judgeable prose pairs"
        ),
    )
    t3 = DimensionScore(
        "T3",
        DIMENSION_NAMES["T3"],
        WEIGHTS["T3"],
        f_fraction if fluency_scores else None,
        ci_points=f_ci if fluency_scores else None,
        detail=(
            f"{len(fluency_scores)} segments judged (mean {sampling.mean(fluency_scores):.2f}/5)"
            if fluency_scores
            else "no judgeable prose"
        ),
    )

    t4 = score_terminology(alignment, glossary)
    t5 = score_structure(source, translated)
    t6, screen_scores = score_extraction(
        source.source_format, translated, judge, sample_size=T6_SAMPLE, seed=seed + 1
    )
    word_count = sum(len(seg.text.split()) for seg in source.segments)
    t7 = score_cost(actual_cost_eur, word_count)

    dims = [t1, t2, t3, t4, t5, t6, t7]
    total = _weighted_total(dims, complete=complete)
    total_ci = _bootstrap_total(
        dims, meaning_scores, fluency_scores, screen_scores, complete=complete, seed=seed
    )

    notes: list[str] = []
    if not complete:
        notes.append(
            f"INCOMPLETE: T1 {t1.fraction:.1%} < 100% — total capped at {INCOMPLETE_CAP:.0f}."
        )
    dropped = [d.key for d in dims if not d.available]
    if dropped:
        kept_weight = sum(d.weight for d in dims if d.available)
        notes.append(
            f"Dropped {', '.join(dropped)} (unmeasurable); total renormalized over "
            f"{kept_weight} weight."
        )

    return RubricTResult(
        dimensions=dims,
        total=total,
        total_ci=total_ci,
        complete=complete,
        sample_ids=sample_ids,
        notes=notes,
        cost_eur=judge.total_cost_eur,
    )
