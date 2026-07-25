"""A/B prompt-variant harness: paired, cluster-bootstrapped translation experiments.

Rubric T3 (fluency) is flat at 12.4–13.5/20 across every book translated so far
(``docs/findings.md``), and testing one hypothesis about it used to mean a
full-book re-translation (~€0.6–1.2) plus an eval (~€0.07). This module makes a
hypothesis cost ~€0.07 and a few minutes, *without* making the measurement
easier than reality. Three design constraints carry that:

* **Production conditions, not isolated paragraphs.** The variant is produced by
  the real :func:`berilo.translate.translate_book` — production batch size,
  production rolling context, and the book's own cached glossary — over
  *contiguous runs* of body prose. A prompt that only looks good on a lone
  paragraph would otherwise sail through as a false positive. Each run is
  preceded in the scratch book by the segments that really precede it, pre-seeded
  into the scratch cache with their existing translations, so the batch is fed
  genuine rolling context at zero extra cost.
* **Paired judging.** Control (the translation already in the translated EPUB)
  and variant are judged on the *same* segments by the *same* :class:`Judge`
  prompts Rubric T uses, so the delta is within-segment and the between-segment
  variance — which dwarfs the effect — cancels.
* **The resampling unit is the RUN, not the segment.** Segments inside a run
  share a batch, a rolling context and a prompt, so their scores are correlated.
  Resampling segments would treat ``runs × run_length`` correlated observations
  as independent, shrink the CI by roughly ``sqrt(run_length)`` and manufacture
  significance for an effect that is really one draw per run. Every CI reported
  here comes from :func:`cluster_bootstrap_ci`, which resamples whole runs.
  ``tests/test_experiment.py`` pins a synthetic case where the two disagree.

Cache safety: the variant is translated into a **separate scratch database**
(:data:`DEFAULT_EXPERIMENT_CACHE_PATH`), never the production cache, and control
and variant rows are additionally keyed apart by ``prompt_version`` (S1.10).
Asking for a variant whose version equals the control's is refused outright —
that combination would serve the control text back at €0 and report a delta of
exactly zero, a null result indistinguishable from a real one.
"""

from __future__ import annotations

import dataclasses
import json
import logging
import random
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path

from berilo.cache import (
    DEFAULT_CACHE_DIR,
    CallRecord,
    SegmentTranslation,
    TranslationCache,
    book_hash,
    segment_hash,
)
from berilo.eval import sampling
from berilo.eval.judge import Judge, load_prompt
from berilo.eval.rubric_t import (
    JUDGE_MAX,
    WEIGHTS,
    align,
    excluded_chapter_indices,
    restrict_to_body,
)
from berilo.glossary import Glossary
from berilo.models import Book, Segment, SegmentType
from berilo.prompts import TranslationStyle
from berilo.providers.base import LLMClient
from berilo.providers.pricing import cost_eur
from berilo.translate import (
    CHARS_PER_TOKEN,
    DEFAULT_BATCH_SIZE,
    DEFAULT_CONTEXT_PAIRS,
    REASONING_BILLING_MODEL_PREFIXES,
    REASONING_TOKENS_PER_CALL,
    TranslationStats,
    build_book_context,
    estimate_cost,
    translate_book,
)

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------
# Tunables (named constants — no magic numbers).
# --------------------------------------------------------------------------

#: Scratch cache for A/B runs. Deliberately a different file from the production
#: cache: an experiment can never write a variant translation where a real
#: ``berilo translate`` would read it. Persistent (not a temp file) so re-running
#: the same variant on the same runs re-bills nothing.
DEFAULT_EXPERIMENT_CACHE_PATH = DEFAULT_CACHE_DIR / "experiments.db"

#: Contiguous body-prose segments per run. Defaults to the production batch size
#: so one run is exactly one production-shaped batch.
DEFAULT_RUN_LENGTH = DEFAULT_BATCH_SIZE

#: Number of runs (clusters) sampled per experiment. 3 × 10 = 30 paired
#: judgments, the minimum the fluency-uplift plan's G2 gate asks for, at roughly
#: €0.07 — inside the plan's €0.10-per-hypothesis budget.
DEFAULT_RUNS = 3

#: Seed for run selection and the cluster bootstrap.
DEFAULT_SEED = 42

#: Below this many clusters the percentile bootstrap is coarse: ``R`` clusters
#: admit only ``C(2R-1, R)`` distinct resamples — 10 at R=3, 35 at R=4, 126 at
#: R=5 — so a 2.5 % tail is placed more by the discreteness of the resample
#: space than by the data, and the design has little power against an effect
#: that varies *between* runs. Judge cost scales with judged segments, not with
#: runs, so the trade is real and belongs to the operator: it is reported as a
#: caveat on every affected run rather than silently absorbed.
MIN_INFORMATIVE_CLUSTERS = 5

#: Judge calls per judged segment: meaning + fluency, for control and variant.
JUDGE_CALLS_PER_SEGMENT = 4

#: Dimensions this harness measures, in report order (ids match Rubric T).
AB_DIMENSIONS: tuple[tuple[str, str], ...] = (
    ("T2", "Meaning preservation"),
    ("T3", "Fluency / naturalness"),
)

#: Visible output tokens in a judge reply (``SCORE: n`` plus a short rationale),
#: used only by the dry-run estimator.
JUDGE_OUTPUT_TOKENS = 8

#: Words per unit for the reported €/1k-words figure.
WORDS_PER_COST_UNIT = 1_000

#: Rubric T7 awards full marks at or below this EUR-per-100k-words rate; the
#: report converts the variant's rate into the same unit so a costly variant is
#: visibly costly against the gate rather than only in the abstract.
T7_FULL_EUR_PER_100K_WORDS = 1.5
_WORDS_PER_T7_UNIT = 100_000

#: Segment types eligible for a run (prose only — the same restriction Rubric
#: T2/T3 applies to its judged pool).
_ELIGIBLE_TYPES = (SegmentType.PARAGRAPH,)


class ExperimentError(RuntimeError):
    """Raised when an A/B experiment cannot be set up or trusted.

    Loud by design: every condition that would silently produce a *plausible but
    meaningless* delta (a variant that collides with the control in the cache,
    a book with too little body prose to form runs) raises rather than degrades.
    """


# --------------------------------------------------------------------------
# Run (cluster) selection.
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class ProseRun:
    """One contiguous run of body-prose segments — the experiment's cluster.

    Attributes:
        chapter_index: Chapter the whole run belongs to.
        chapter_title: Title of that chapter, if known.
        start_position: Index of the run's first segment in the source book's
            segment list (so a report can point at where a run came from).
        segments: The judged source segments, contiguous and in document order.
        control_texts: Existing translations of :attr:`segments`, aligned 1:1.
        lead_in: ``(segment, existing_translation)`` pairs for the segments that
            really precede the run in the source book. They are pre-seeded into
            the scratch cache so the variant's first batch is fed the same
            rolling context production would have given it, at zero cost, and
            are never judged.
    """

    chapter_index: int
    chapter_title: str | None
    start_position: int
    segments: tuple[Segment, ...]
    control_texts: tuple[str, ...]
    lead_in: tuple[tuple[Segment, str], ...]

    @property
    def source_words(self) -> int:
        """Total whitespace-delimited source words across the judged segments."""
        return sum(len(segment.text.split()) for segment in self.segments)


def _control_map(source: Book, translated: Book) -> dict[str, str]:
    """Map source segment id -> existing translation, via Rubric T's alignment.

    Args:
        source: The normalized source book.
        translated: The normalized translated book (the control arm).

    Returns:
        Only entries whose translation exists and is non-empty.

    Raises:
        AlignmentError: Propagated from :func:`berilo.eval.rubric_t.align` when
            the two books cannot be trusted to correspond.
    """
    alignment = align(source, translated)
    return {
        src.id: tgt.text
        for src, tgt in alignment.pairs
        if tgt is not None and tgt.text.strip() and src.text.strip()
    }


def _is_eligible(segment: Segment, controls: dict[str, str]) -> bool:
    """Whether a segment can sit inside a run (prose, non-empty, translated)."""
    return segment.type in _ELIGIBLE_TYPES and bool(segment.text.strip()) and segment.id in controls


def _eligible_blocks(source: Book, controls: dict[str, str]) -> list[list[int]]:
    """Group source indices into maximal same-chapter blocks of eligible segments.

    A block is broken by anything that would also break a production batch: an
    ineligible segment (heading, caption, empty, untranslated) or a chapter
    boundary. Runs are cut from blocks, so a run can never straddle either.
    """
    blocks: list[list[int]] = []
    current: list[int] = []
    for index, segment in enumerate(source.segments):
        if not _is_eligible(segment, controls):
            if current:
                blocks.append(current)
                current = []
            continue
        if current and segment.chapter_index != source.segments[current[-1]].chapter_index:
            blocks.append(current)
            current = []
        current.append(index)
    if current:
        blocks.append(current)
    return blocks


def _lead_in_for(
    source: Book,
    start: int,
    controls: dict[str, str],
    context_pairs: int,
    forbidden_hashes: set[str],
) -> tuple[tuple[Segment, str], ...]:
    """Collect the up-to-``context_pairs`` real predecessors of ``start``.

    A predecessor qualifies only if it is in the same chapter, has text, already
    has a translation to seed, and does not share a source-text hash with any
    judged segment — a shared hash would make the seeded context row satisfy a
    judged segment's cache lookup and silently leave it un-translated.
    """
    collected: list[tuple[Segment, str]] = []
    chapter = source.segments[start].chapter_index
    for index in range(start - 1, max(start - context_pairs, 0) - 1, -1):
        candidate = source.segments[index]
        if candidate.chapter_index != chapter or not candidate.text.strip():
            break
        control = controls.get(candidate.id)
        if control is None or segment_hash(candidate.text) in forbidden_hashes:
            break
        collected.append((candidate, control))
    return tuple(reversed(collected))


def candidate_runs(
    source: Book,
    translated: Book,
    *,
    run_length: int = DEFAULT_RUN_LENGTH,
    context_pairs: int = DEFAULT_CONTEXT_PAIRS,
) -> list[ProseRun]:
    """Tile the book into non-overlapping candidate runs of body prose.

    Within each maximal block of eligible segments the runs are laid out with a
    ``context_pairs``-wide gap between them, so no segment is ever both a judged
    segment of one run and the rolling-context lead-in of another. That gap is
    also what keeps the clusters independent: two runs never share a batch, a
    prompt, or a context window.

    Args:
        source: The normalized source book.
        translated: The normalized translated book (supplies control text).
        run_length: Contiguous judged segments per run.
        context_pairs: Rolling-context pairs each run is primed with.

    Returns:
        Every candidate run, in document order (front/back matter NOT yet
        excluded — callers restrict the pool via
        :func:`berilo.eval.rubric_t.restrict_to_body`).
    """
    if run_length <= 0:
        raise ExperimentError(f"run_length must be positive, got {run_length}.")
    controls = _control_map(source, translated)
    stride = run_length + max(context_pairs, 0)

    runs: list[ProseRun] = []
    for block in _eligible_blocks(source, controls):
        for offset in range(0, len(block) - run_length + 1, stride):
            indices = block[offset : offset + run_length]
            segments = tuple(source.segments[i] for i in indices)
            hashes = {segment_hash(seg.text) for seg in segments}
            runs.append(
                ProseRun(
                    chapter_index=segments[0].chapter_index,
                    chapter_title=segments[0].chapter_title,
                    start_position=indices[0],
                    segments=segments,
                    control_texts=tuple(controls[seg.id] for seg in segments),
                    lead_in=_lead_in_for(source, indices[0], controls, context_pairs, hashes),
                )
            )
    return runs


def select_runs(pool: Sequence[ProseRun], count: int, seed: int) -> list[ProseRun]:
    """Pick ``count`` runs from ``pool`` deterministically (same seed → same runs)."""
    indices = sampling.select_indices(len(pool), count, seed)
    return [pool[i] for i in indices]


# --------------------------------------------------------------------------
# Cluster bootstrap — the harness's central statistical guarantee.
# --------------------------------------------------------------------------


def cluster_bootstrap_ci(
    clusters: Sequence[Sequence[float]],
    *,
    seed: int = DEFAULT_SEED,
    n_resamples: int = sampling.DEFAULT_RESAMPLES,
    ci: float = sampling.DEFAULT_CI,
) -> tuple[float, float]:
    """Percentile bootstrap CI of the pooled mean, **resampling whole clusters**.

    Each resample draws ``len(clusters)`` clusters *with replacement* and takes
    the mean over every observation in the drawn clusters. This is the only
    correct unit here: the observations inside one cluster (one contiguous run)
    were produced by a single batch under a single prompt and a single rolling
    context, so they are correlated. Resampling the observations individually
    would pretend there are ``clusters × cluster_size`` independent draws,
    narrow the interval by roughly ``sqrt(cluster_size)``, and turn a
    3-run coin-flip into a "significant" result.

    Args:
        clusters: One sequence of observations per cluster.
        seed: Seed for reproducible resampling.
        n_resamples: Number of bootstrap resamples (the rubric requires >= 1000).
        ci: Central confidence level.

    Returns:
        The ``(low, high)`` bounds. ``(0.0, 0.0)`` when there is nothing to
        resample.
    """
    non_empty = [list(cluster) for cluster in clusters if len(cluster) > 0]
    if not non_empty:
        return (0.0, 0.0)

    rng = random.Random(seed)
    n_clusters = len(non_empty)
    stats: list[float] = []
    for _ in range(n_resamples):
        pooled: list[float] = []
        for _ in range(n_clusters):
            pooled.extend(non_empty[rng.randrange(n_clusters)])
        stats.append(sampling.mean(pooled))
    return sampling.percentile_ci(stats, ci)


# --------------------------------------------------------------------------
# Results.
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class JudgedPair:
    """One segment judged in both arms (the paired unit).

    Attributes:
        run_index: Index of the run (cluster) this segment belongs to.
        segment_id: Source segment id.
        chapter_index: Chapter index.
        source: Source text.
        control: Existing translation (control arm).
        variant: Freshly produced translation (variant arm).
        control_meaning: T2 verdict for the control, 1-5.
        variant_meaning: T2 verdict for the variant, 1-5.
        control_fluency: T3 verdict for the control, 1-5.
        variant_fluency: T3 verdict for the variant, 1-5.
    """

    run_index: int
    segment_id: str
    chapter_index: int
    source: str
    control: str
    variant: str
    control_meaning: int
    variant_meaning: int
    control_fluency: int
    variant_fluency: int


@dataclass(frozen=True)
class DimensionDelta:
    """Paired control→variant delta on one dimension, with a cluster-bootstrap CI.

    Attributes:
        key: Rubric dimension id (``"T2"`` / ``"T3"``).
        name: Human-readable dimension name.
        weight: The dimension's Rubric T weight, used to express the delta in
            rubric points as well as on the judge's 1-5 scale.
        control_mean: Mean control verdict (1-5).
        variant_mean: Mean variant verdict (1-5).
        delta: ``variant_mean - control_mean`` (1-5 scale).
        ci: 95 % cluster-bootstrap CI of :attr:`delta`.
        clusters: Number of runs resampled (the effective sample size).
        pairs: Number of judged segments behind the estimate.
    """

    key: str
    name: str
    weight: int
    control_mean: float
    variant_mean: float
    delta: float
    ci: tuple[float, float]
    clusters: int
    pairs: int

    @property
    def points_delta(self) -> float:
        """The delta expressed in Rubric T points for this dimension."""
        return self.delta / JUDGE_MAX * self.weight

    @property
    def points_ci(self) -> tuple[float, float]:
        """:attr:`ci` expressed in Rubric T points."""
        return (self.ci[0] / JUDGE_MAX * self.weight, self.ci[1] / JUDGE_MAX * self.weight)

    @property
    def verdict(self) -> str:
        """``"win"`` only when the CI lower bound clears zero.

        A variant "wins" a dimension when the whole interval is above zero,
        "regresses" when it is entirely below, and is otherwise inconclusive —
        an interval straddling zero is *not* a small win.
        """
        if self.ci[0] > 0:
            return "win"
        if self.ci[1] < 0:
            return "regression"
        return "inconclusive"


@dataclass(frozen=True)
class ExperimentPlan:
    """Everything a run would do, computed without any API call.

    Attributes:
        book_title: Source book title.
        source_format: Source format (``"pdf"``/``"epub"``/…).
        control_version: Prompt version attributed to the existing translation.
        variant_name: Registry name of the variant style.
        variant_version: Cache-keying version of the variant style.
        model: Translation model the variant will be produced with.
        judge_model: Model used for the paired judging.
        target_lang: Target language code.
        seed: Seed for run selection and the bootstrap.
        batch_size: Production batch size the re-translation will use.
        context_pairs: Production rolling-context depth.
        run_length: Contiguous judged segments per run.
        requested_runs: Runs asked for.
        candidate_pool: Candidate runs available after body-prose restriction.
        runs: The selected runs.
        fell_back_to_full_pool: Whether front/back matter had to be re-included
            because the body-prose pool was too small.
        glossary_terms: Terms in the book's cached glossary, or ``None`` when the
            production cache holds no glossary for this book/model/lang.
        judge_calls: Judge API calls a real run would make.
        estimated_translation_cost_eur: Estimated variant-translation spend.
        estimated_judge_cost_eur: Estimated judging spend.
        reasoning_surcharge_eur: Share of the estimate that is the fixed
            per-call reasoning-token additive (see :class:`JudgeCostEstimate`).
    """

    book_title: str
    source_format: str
    control_version: str
    variant_name: str
    variant_version: str
    model: str
    judge_model: str
    target_lang: str
    seed: int
    batch_size: int
    context_pairs: int
    run_length: int
    requested_runs: int
    candidate_pool: int
    runs: tuple[ProseRun, ...]
    fell_back_to_full_pool: bool
    glossary_terms: int | None
    judge_calls: int
    estimated_translation_cost_eur: float
    estimated_judge_cost_eur: float
    reasoning_surcharge_eur: float

    @property
    def judged_segments(self) -> int:
        """Total segments judged in both arms."""
        return sum(len(run.segments) for run in self.runs)

    @property
    def source_words(self) -> int:
        """Total source words across the judged segments."""
        return sum(run.source_words for run in self.runs)

    @property
    def estimated_cost_eur(self) -> float:
        """Estimated total spend of a real run."""
        return self.estimated_translation_cost_eur + self.estimated_judge_cost_eur


@dataclass(frozen=True)
class ExperimentResult:
    """Outcome of one A/B experiment.

    Attributes:
        plan: The plan that was executed.
        pairs: Every judged segment, in run order.
        deltas: Per-dimension paired deltas with cluster-bootstrap CIs.
        translation_cost_eur: EUR actually spent producing the variant.
        judge_cost_eur: EUR actually spent judging both arms.
        translation_api_calls: Translation API calls actually made.
        revision_failures: Batches whose revision pass could not be applied
            (relevant only to revising styles; the un-revised draft was kept).
    """

    plan: ExperimentPlan
    pairs: tuple[JudgedPair, ...]
    deltas: tuple[DimensionDelta, ...]
    translation_cost_eur: float
    judge_cost_eur: float
    translation_api_calls: int
    revision_failures: int

    @property
    def total_cost_eur(self) -> float:
        """EUR actually spent by this experiment."""
        return self.translation_cost_eur + self.judge_cost_eur

    @property
    def eur_per_1k_words(self) -> float:
        """Variant translation spend per 1 000 source words (0.0 for an empty run).

        Only the *translation* arm counts: judging is a measurement expense, not
        something a reader's translation would pay. Per-book fixed costs
        (glossary, style memo) are amortized over the whole book in production
        and are excluded here, so this figure tracks the marginal per-word rate.
        """
        words = self.plan.source_words
        if words <= 0:
            return 0.0
        return self.translation_cost_eur / (words / WORDS_PER_COST_UNIT)

    @property
    def eur_per_100k_words(self) -> float:
        """The same rate in Rubric T7's unit (full marks at ≤ €1.50/100k words)."""
        return self.eur_per_1k_words * (_WORDS_PER_T7_UNIT / WORDS_PER_COST_UNIT)


# --------------------------------------------------------------------------
# Planning (zero API calls).
# --------------------------------------------------------------------------


def _is_reasoning_model(model: str) -> bool:
    """Whether ``model`` bills hidden reasoning tokens as output."""
    return model.startswith(REASONING_BILLING_MODEL_PREFIXES)


@dataclass(frozen=True)
class JudgeCostEstimate:
    """Estimated judging spend, with the reasoning surcharge broken out.

    Attributes:
        calls: Judge API calls a real run would make.
        total_eur: Estimated EUR cost of those calls.
        surcharge_eur: The share of :attr:`total_eur` that is the fixed
            per-call reasoning-token additive. Broken out because on short
            judge replies that additive dominates the estimate *and*
            ``docs/findings.md`` records it as an over-estimate at
            ``reasoning_effort=low`` — an operator sizing a budget needs to see
            how much of the number is that one conservative assumption.
    """

    calls: int
    total_eur: float
    surcharge_eur: float


def estimate_judge_cost(runs: Sequence[ProseRun], judge_model: str) -> JudgeCostEstimate:
    """Estimate the judging spend for both arms, making no API call.

    Deliberately conservative: it charges the full per-call reasoning surcharge
    (:data:`berilo.translate.REASONING_TOKENS_PER_CALL`) for reasoning-billing
    models. Over-estimating a budget is the safe direction; the surcharge is
    reported separately so the estimate can be read with that in mind.

    Args:
        runs: The selected runs (the variant's length is approximated by the
            control's, which is not yet translated at planning time).
        judge_model: Model the judge will use.

    Returns:
        The :class:`JudgeCostEstimate`.
    """
    meaning_chars = len(load_prompt("meaning_v1"))
    fluency_chars = len(load_prompt("fluency_v1"))
    reasoning = REASONING_TOKENS_PER_CALL if _is_reasoning_model(judge_model) else 0

    input_tokens = 0
    calls = 0
    for run in runs:
        for segment, control in zip(run.segments, run.control_texts):
            # Two meaning calls (source + each arm) and two fluency calls.
            per_meaning = (meaning_chars + len(segment.text) + len(control)) // CHARS_PER_TOKEN
            per_fluency = (fluency_chars + len(control)) // CHARS_PER_TOKEN
            input_tokens += 2 * per_meaning + 2 * per_fluency
            calls += JUDGE_CALLS_PER_SEGMENT
    output_tokens = calls * (JUDGE_OUTPUT_TOKENS + reasoning)
    return JudgeCostEstimate(
        calls=calls,
        total_eur=cost_eur(judge_model, input_tokens, output_tokens),
        surcharge_eur=cost_eur(judge_model, 0, calls * reasoning),
    )


def build_plan(
    source: Book,
    translated: Book,
    *,
    style: TranslationStyle,
    control_version: str,
    model: str,
    judge_model: str,
    target_lang: str,
    glossary: Glossary | None,
    runs: int = DEFAULT_RUNS,
    run_length: int = DEFAULT_RUN_LENGTH,
    seed: int = DEFAULT_SEED,
    batch_size: int = DEFAULT_BATCH_SIZE,
    context_pairs: int = DEFAULT_CONTEXT_PAIRS,
) -> ExperimentPlan:
    """Select the runs and price the experiment. Makes **zero** API calls.

    Args:
        source: The normalized source book.
        translated: The normalized translated book (the control arm).
        style: The variant translation style.
        control_version: Prompt version attributed to the existing translation.
        model: Translation model for the variant.
        judge_model: Model used for judging.
        target_lang: Target language code.
        glossary: The book's cached glossary, or ``None``.
        runs: Number of runs (clusters) to sample.
        run_length: Contiguous judged segments per run.
        seed: Seed for run selection.
        batch_size: Production batch size (kept explicit so the report can show
            that the experiment ran under production conditions).
        context_pairs: Production rolling-context depth.

    Returns:
        The :class:`ExperimentPlan`.

    Raises:
        ExperimentError: If the variant would collide with the control in the
            cache, or the book yields no candidate run.
    """
    if style.version == control_version:
        raise ExperimentError(
            f"Variant style {style.name!r} has prompt version {style.version!r}, which is "
            f"also the control's. Control and variant would share every cache row, so the "
            f"variant would be served the control's text at €0 and the measured delta would "
            f"be exactly zero — a null result indistinguishable from a real one. Pick a "
            f"different --variant (or correct --control)."
        )

    pool = candidate_runs(source, translated, run_length=run_length, context_pairs=context_pairs)
    if not pool:
        raise ExperimentError(
            f"No contiguous run of {run_length} translated body-prose segments exists in "
            f"'{source.title}' — lower --run-length or check the translated EPUB."
        )
    excluded = excluded_chapter_indices(source)
    body_pool, fell_back = restrict_to_body(
        pool, lambda run: run.chapter_index, excluded, runs, "A/B runs"
    )
    selected = tuple(select_runs(body_pool, runs, seed))

    judged_book = dataclasses.replace(
        source, segments=[seg for run in selected for seg in run.segments]
    )
    translation_estimate = estimate_cost(
        judged_book,
        model=model,
        target_lang=target_lang,
        batch_size=batch_size,
        glossary=False,
        style=style,
    )
    judge_estimate = estimate_judge_cost(selected, judge_model)
    return ExperimentPlan(
        book_title=source.title,
        source_format=source.source_format,
        control_version=control_version,
        variant_name=style.name,
        variant_version=style.version,
        model=model,
        judge_model=judge_model,
        target_lang=target_lang,
        seed=seed,
        batch_size=batch_size,
        context_pairs=context_pairs,
        run_length=run_length,
        requested_runs=runs,
        candidate_pool=len(body_pool),
        runs=selected,
        fell_back_to_full_pool=fell_back,
        glossary_terms=None if glossary is None else len(glossary.terms),
        judge_calls=judge_estimate.calls,
        estimated_translation_cost_eur=translation_estimate.cost_eur,
        estimated_judge_cost_eur=judge_estimate.total_eur,
        reasoning_surcharge_eur=judge_estimate.surcharge_eur
        + cost_eur(model, 0, translation_estimate.reasoning_tokens),
    )


# --------------------------------------------------------------------------
# Execution.
# --------------------------------------------------------------------------


def build_scratch_book(source: Book, runs: Sequence[ProseRun]) -> Book:
    """Assemble the mini-book the variant is translated from.

    Each run contributes its lead-in segments followed by its judged segments,
    in document order. The lead-ins are seeded into the scratch cache before
    translation (see :func:`seed_control_translations`), so
    :func:`~berilo.translate.translate_book` serves them from cache — which both
    costs nothing and makes them the batch's rolling context — and then breaks
    the batch at the next run's lead-in. One run therefore becomes exactly one
    production-shaped batch.
    """
    segments: list[Segment] = []
    for run in runs:
        segments.extend(segment for segment, _ in run.lead_in)
        segments.extend(run.segments)
    return dataclasses.replace(source, segments=segments)


def seed_control_translations(
    cache: TranslationCache,
    scratch_hash: str,
    runs: Sequence[ProseRun],
    *,
    model: str,
    target_lang: str,
    control_version: str,
    variant_version: str,
) -> None:
    """Write the existing translations into the scratch cache.

    Two different jobs, deliberately keyed apart:

    * Judged segments are stored under ``control_version`` only. They document
      the control arm and prove the two arms coexist — the variant's lookup uses
      ``variant_version`` and therefore misses, as it must.
    * Lead-in segments are additionally stored under ``variant_version`` so the
      variant run reads them from cache. They are context, never judged; using
      the existing translation for them is what makes the batch's rolling
      context real prose rather than a truncated prefix.

    Args:
        cache: The scratch cache (never the production cache).
        scratch_hash: Book hash of the scratch mini-book.
        runs: The selected runs.
        model: Translation model keying the rows.
        target_lang: Target language code.
        control_version: Prompt version of the existing translation.
        variant_version: Prompt version the variant will be written under.
    """
    no_cost = CallRecord(kind="ab_seed", input_tokens=0, output_tokens=0, cost_eur=0.0)

    judged = [
        SegmentTranslation(segment_hash=segment_hash(segment.text), text=control, cost_eur=0.0)
        for run in runs
        for segment, control in zip(run.segments, run.control_texts)
    ]
    lead_in = [
        SegmentTranslation(segment_hash=segment_hash(segment.text), text=control, cost_eur=0.0)
        for run in runs
        for segment, control in run.lead_in
    ]
    if judged:
        cache.store_batch(scratch_hash, model, target_lang, judged, no_cost, control_version)
    if lead_in:
        cache.store_batch(scratch_hash, model, target_lang, lead_in, no_cost, control_version)
        cache.store_batch(scratch_hash, model, target_lang, lead_in, no_cost, variant_version)


def _prime_book_context(
    source: Book,
    scratch_hash: str,
    *,
    client: LLMClient,
    style: TranslationStyle,
    cache: TranslationCache,
    model: str,
    target_lang: str,
) -> float:
    """Derive the style memo from the FULL book and attach it to the scratch book.

    A book-context style computes its memo from the book's opening. Left to
    itself, ``translate_book`` would compute it from the scratch mini-book's
    opening — a random slice of chapter 12 — and the variant would be measured
    with a memo it would never have in production. So the memo is derived here
    against the real book (memoized, so repeat experiments are free) and stored
    under the scratch book's hash, where ``translate_book`` finds it as a hit.

    Returns:
        EUR spent deriving the memo (0.0 on a cache hit or a memo-less style).
    """
    if style.book_context_system is None:
        return 0.0
    memo, result = build_book_context(
        source,
        client=client,
        style=style,
        target_lang=target_lang,
        model=model,
        cache=cache,
    )
    if memo:
        cache.store_book_context(scratch_hash, model, target_lang, style.version, memo)
    return result.cost_eur if result is not None else 0.0


def _judge_pairs(
    runs: Sequence[ProseRun],
    variant_texts: dict[str, str],
    judge: Judge,
) -> list[JudgedPair]:
    """Judge every segment in both arms with the Rubric T judge prompts."""
    pairs: list[JudgedPair] = []
    for run_index, run in enumerate(runs):
        for segment, control in zip(run.segments, run.control_texts):
            variant = variant_texts[segment.id]
            pairs.append(
                JudgedPair(
                    run_index=run_index,
                    segment_id=segment.id,
                    chapter_index=segment.chapter_index,
                    source=segment.text,
                    control=control,
                    variant=variant,
                    control_meaning=judge.meaning(segment.text, control),
                    variant_meaning=judge.meaning(segment.text, variant),
                    control_fluency=judge.fluency(control),
                    variant_fluency=judge.fluency(variant),
                )
            )
    return pairs


#: Per-dimension ``(control accessor, variant accessor)`` over a judged pair.
_DIMENSION_ACCESSORS: dict[str, tuple[Callable[[JudgedPair], int], Callable[[JudgedPair], int]]] = {
    "T2": (lambda pair: pair.control_meaning, lambda pair: pair.variant_meaning),
    "T3": (lambda pair: pair.control_fluency, lambda pair: pair.variant_fluency),
}


def _dimension_delta(
    key: str,
    name: str,
    pairs: Sequence[JudgedPair],
    control_of: Callable[[JudgedPair], int],
    variant_of: Callable[[JudgedPair], int],
    *,
    n_clusters: int,
    seed: int,
    n_resamples: int,
) -> DimensionDelta:
    """Compute one dimension's paired delta with a cluster-bootstrap CI."""
    clusters: list[list[float]] = [[] for _ in range(n_clusters)]
    for pair in pairs:
        clusters[pair.run_index].append(float(variant_of(pair) - control_of(pair)))
    control_scores = [float(control_of(pair)) for pair in pairs]
    variant_scores = [float(variant_of(pair)) for pair in pairs]
    return DimensionDelta(
        key=key,
        name=name,
        weight=WEIGHTS[key],
        control_mean=sampling.mean(control_scores),
        variant_mean=sampling.mean(variant_scores),
        delta=sampling.mean([value for cluster in clusters for value in cluster]),
        ci=cluster_bootstrap_ci(clusters, seed=seed, n_resamples=n_resamples),
        clusters=sum(1 for cluster in clusters if cluster),
        pairs=len(pairs),
    )


def run_experiment(
    source: Book,
    *,
    plan: ExperimentPlan,
    style: TranslationStyle,
    client: LLMClient,
    judge: Judge,
    scratch_cache: TranslationCache,
    glossary: Glossary | None,
    fallback_client: LLMClient | None = None,
    n_resamples: int = sampling.DEFAULT_RESAMPLES,
) -> ExperimentResult:
    """Produce the variant through the production path and judge it against control.

    The variant is produced by :func:`berilo.translate.translate_book` — not by
    a bespoke one-shot call — with the plan's production ``batch_size`` and
    ``context_pairs`` and the book's own cached glossary, so a variant that only
    flatters isolated paragraphs cannot pass.

    Args:
        source: The normalized source book (supplies metadata and, for
            book-context styles, the opening excerpt behind the style memo).
        plan: The plan produced by :func:`build_plan`.
        style: The variant translation style.
        client: LLM client for the variant translation.
        judge: Judge used for both arms (the same prompts Rubric T uses).
        scratch_cache: Scratch cache — never the production cache.
        glossary: The book's cached glossary, or ``None``.
        fallback_client: Optional second-provider client for content-policy
            refusals, matching the production translate path.
        n_resamples: Bootstrap resamples per dimension.

    Returns:
        The :class:`ExperimentResult`.

    Raises:
        ExperimentError: If the variant translation does not come back 1:1 with
            the judged segments.
    """
    runs = plan.runs
    scratch_book = build_scratch_book(source, runs)
    scratch_hash = book_hash(scratch_book)

    seed_control_translations(
        scratch_cache,
        scratch_hash,
        runs,
        model=plan.model,
        target_lang=plan.target_lang,
        control_version=plan.control_version,
        variant_version=plan.variant_version,
    )
    memo_cost = _prime_book_context(
        source,
        scratch_hash,
        client=client,
        style=style,
        cache=scratch_cache,
        model=plan.model,
        target_lang=plan.target_lang,
    )

    captured: dict[str, TranslationStats] = {}

    def _capture(stats: TranslationStats) -> None:
        captured["stats"] = stats

    translated_scratch = translate_book(
        scratch_book,
        client=client,
        target_lang=plan.target_lang,
        cache=scratch_cache,
        glossary=glossary,
        batch_size=plan.batch_size,
        context_pairs=plan.context_pairs,
        on_progress=_capture,
        fallback_client=fallback_client,
        style=style,
    )
    stats = captured.get("stats")

    variant_texts = {segment.id: segment.text for segment in translated_scratch.segments}
    missing = [
        segment.id for run in runs for segment in run.segments if segment.id not in variant_texts
    ]
    if missing:  # defensive: translate_book guarantees 1:1, so this must never fire
        raise ExperimentError(
            f"{len(missing)} judged segment(s) have no variant translation — the 1:1 "
            f"source↔target mapping was broken; refusing to report a delta."
        )

    pairs = tuple(_judge_pairs(runs, variant_texts, judge))
    deltas = tuple(
        _dimension_delta(
            key,
            name,
            pairs,
            *_DIMENSION_ACCESSORS[key],
            n_clusters=len(runs),
            seed=plan.seed,
            n_resamples=n_resamples,
        )
        for key, name in AB_DIMENSIONS
    )
    return ExperimentResult(
        plan=plan,
        pairs=pairs,
        deltas=deltas,
        translation_cost_eur=(stats.cost_eur if stats is not None else 0.0) + memo_cost,
        judge_cost_eur=judge.total_cost_eur,
        translation_api_calls=(stats.api_calls if stats is not None else 0)
        + (1 if memo_cost > 0 else 0),
        revision_failures=stats.revision_failures if stats is not None else 0,
    )


# --------------------------------------------------------------------------
# Reporting.
# --------------------------------------------------------------------------


def _run_label(run: ProseRun) -> str:
    """Short human locator for one run."""
    title = run.chapter_title or f"chapter {run.chapter_index}"
    return f"ch {run.chapter_index} ({title[:34]}) @ segment {run.start_position}"


def _low_power_caveat(clusters: int) -> str | None:
    """Warn when the cluster count is too small for the CI to carry weight.

    Returns ``None`` when there are enough clusters. Never suppressed: a coarse
    interval that quietly reads "inconclusive" is exactly how a real effect gets
    discarded, or a null result over-trusted.
    """
    if clusters >= MIN_INFORMATIVE_CLUSTERS:
        return None
    return (
        f"! Only {clusters} clusters: the bootstrap admits few distinct resamples, so this "
        f"CI is coarse and has little power against an effect that varies between runs. "
        f"Read 'inconclusive' as 'not measured', not as 'no effect'. Judge cost scales with "
        f"judged segments, not with runs — {MIN_INFORMATIVE_CLUSTERS} runs x N segments "
        f"costs the same as N runs x {MIN_INFORMATIVE_CLUSTERS} segments, so --runs can be "
        f"raised (keeping --run-length at the production batch size costs proportionally "
        f"more; shortening it trades batch fidelity for clusters)."
    )


def format_plan(plan: ExperimentPlan) -> str:
    """Render the dry-run plan. Describes a run that has NOT happened."""
    lines = [
        "DRY RUN — no translation calls, no judge calls, no cost.",
        f"Book: {plan.book_title} ({plan.source_format}).",
        f"Control: prompt version '{plan.control_version}' (already in the translated EPUB).",
        f"Variant: '{plan.variant_name}' (prompt version '{plan.variant_version}').",
        f"Runs: {len(plan.runs)} of {plan.requested_runs} requested, "
        f"{plan.run_length} contiguous body-prose segments each "
        f"({plan.judged_segments} paired judgments, {plan.source_words} source words), "
        f"drawn seed {plan.seed} from {plan.candidate_pool} candidate runs.",
    ]
    for index, run in enumerate(plan.runs):
        lines.append(f"  run {index}: {_run_label(run)}, {len(run.lead_in)} context lead-in(s)")
    lines.extend(
        [
            f"Production conditions: translate_book, batch size {plan.batch_size}, "
            f"{plan.context_pairs} rolling-context pairs, "
            + (
                f"cached glossary of {plan.glossary_terms} terms."
                if plan.glossary_terms is not None
                else "NO cached glossary for this book/model/lang (production would have one)."
            ),
            f"Judge calls: {plan.judge_calls} "
            f"({JUDGE_CALLS_PER_SEGMENT} per segment = meaning + fluency, control + variant), "
            f"model {plan.judge_model}.",
            f"Estimated cost: €{plan.estimated_cost_eur:.4f} "
            f"(translation €{plan.estimated_translation_cost_eur:.4f} + "
            f"judging €{plan.estimated_judge_cost_eur:.4f}).",
            f"  Of that, €{plan.reasoning_surcharge_eur:.4f} is the fixed per-call "
            "reasoning-token surcharge, which docs/findings.md records as an over-estimate "
            "at reasoning_effort=low — read the total as an upper bound.",
            f"CIs will be cluster-bootstrapped over {len(plan.runs)} runs, not "
            f"{plan.judged_segments} segments.",
        ]
    )
    caveat = _low_power_caveat(len(plan.runs))
    if caveat:
        lines.append(f"  {caveat}")
    if plan.fell_back_to_full_pool:
        lines.append(
            "  ! Body-prose run pool was smaller than the requested run count — "
            "front/back-matter chapters were re-included."
        )
    return "\n".join(lines)


def format_result(result: ExperimentResult) -> str:
    """Render the human-readable A/B report."""
    plan = result.plan
    lines = [
        f"A/B — {plan.book_title}",
        f"control '{plan.control_version}'  vs  variant '{plan.variant_name}' "
        f"({plan.variant_version}), model {plan.model}, judge {plan.judge_model}",
        f"{len(plan.runs)} contiguous runs x {plan.run_length} segments "
        f"= {plan.judged_segments} paired judgments (seed {plan.seed})",
        "",
        f"{'dim':<4}{'name':<26}{'control':>9}{'variant':>9}{'delta':>9}"
        f"{'95% CI (cluster)':>22}  verdict",
    ]
    for delta in result.deltas:
        ci = f"[{delta.ci[0]:+.2f}, {delta.ci[1]:+.2f}]"
        lines.append(
            f"{delta.key:<4}{delta.name:<26}{delta.control_mean:>9.2f}"
            f"{delta.variant_mean:>9.2f}{delta.delta:>+9.2f}{ci:>22}  {delta.verdict}"
        )
    lines.append("")
    for delta in result.deltas:
        points_ci = delta.points_ci
        lines.append(
            f"  {delta.key} in rubric points: {delta.points_delta:+.2f}/{delta.weight} "
            f"[{points_ci[0]:+.2f}, {points_ci[1]:+.2f}]"
        )
    lines.extend(
        [
            "",
            f"  Resampling unit: the contiguous RUN ({result.deltas[0].clusters} clusters), "
            "not the segment — segments inside a run share a batch, a prompt and a "
            "rolling context, so a segment-level bootstrap would understate these CIs.",
            "  A variant wins a dimension only when the CI lower bound is above zero.",
            f"  Cost actually spent: €{result.total_cost_eur:.4f} "
            f"(variant translation €{result.translation_cost_eur:.4f} over "
            f"{result.translation_api_calls} calls + judging €{result.judge_cost_eur:.4f}).",
            f"  Variant translation rate: €{result.eur_per_1k_words:.4f}/1k source words "
            f"(= €{result.eur_per_100k_words:.2f}/100k words; "
            f"Rubric T7 full marks at ≤ €{T7_FULL_EUR_PER_100K_WORDS:.2f}).",
        ]
    )
    caveat = _low_power_caveat(result.deltas[0].clusters if result.deltas else 0)
    if caveat:
        lines.append(f"  {caveat}")
    if result.revision_failures:
        lines.append(
            f"  ! {result.revision_failures} batch(es) lost their revision pass — those "
            "segments carry un-revised quality, so the variant is measured pessimistically."
        )
    if plan.glossary_terms is None:
        lines.append(
            "  ! No cached glossary was available — production translation would have had "
            "one, so terminology conditions differ from production."
        )
    return "\n".join(lines)


def result_to_dict(result: ExperimentResult) -> dict[str, object]:
    """Render the result as a JSON-serializable dict (for ``--json``)."""
    plan = result.plan
    return {
        "book": plan.book_title,
        "control_version": plan.control_version,
        "variant": plan.variant_name,
        "variant_version": plan.variant_version,
        "model": plan.model,
        "judge_model": plan.judge_model,
        "target_lang": plan.target_lang,
        "seed": plan.seed,
        "runs": len(plan.runs),
        "run_length": plan.run_length,
        "batch_size": plan.batch_size,
        "context_pairs": plan.context_pairs,
        "judged_segments": plan.judged_segments,
        "source_words": plan.source_words,
        "resampling_unit": "run",
        "clusters": len(plan.runs),
        "low_power": len(plan.runs) < MIN_INFORMATIVE_CLUSTERS,
        "glossary_terms": plan.glossary_terms,
        "deltas": {
            delta.key: {
                "name": delta.name,
                "control_mean": round(delta.control_mean, 4),
                "variant_mean": round(delta.variant_mean, 4),
                "delta": round(delta.delta, 4),
                "ci": [round(delta.ci[0], 4), round(delta.ci[1], 4)],
                "points_delta": round(delta.points_delta, 4),
                "points_ci": [round(value, 4) for value in delta.points_ci],
                "clusters": delta.clusters,
                "pairs": delta.pairs,
                "verdict": delta.verdict,
            }
            for delta in result.deltas
        },
        "cost_eur": {
            "translation": round(result.translation_cost_eur, 6),
            "judge": round(result.judge_cost_eur, 6),
            "total": round(result.total_cost_eur, 6),
            "per_1k_source_words": round(result.eur_per_1k_words, 6),
            "per_100k_source_words": round(result.eur_per_100k_words, 4),
        },
        "translation_api_calls": result.translation_api_calls,
        "revision_failures": result.revision_failures,
        "runs_detail": [
            {
                "chapter_index": run.chapter_index,
                "chapter_title": run.chapter_title,
                "start_position": run.start_position,
                "segments": len(run.segments),
                "lead_in": len(run.lead_in),
            }
            for run in plan.runs
        ],
    }


def write_pairs_dump(result: ExperimentResult, dump_path: str | Path) -> int:
    """Write one JSON row per judged pair (source, both arms, all four verdicts).

    Args:
        result: A completed experiment.
        dump_path: Destination JSONL path; parent directories are created.

    Returns:
        The number of rows written.
    """
    path = Path(dump_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for pair in result.pairs:
            handle.write(
                json.dumps(dataclasses.asdict(pair), ensure_ascii=False) + "\n",
            )
    return len(result.pairs)
