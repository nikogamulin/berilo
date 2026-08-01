"""The batch planner: which segments share a call, and which calls share a wave.

This module is deliberately **pure** — no cache object, no LLM client, no I/O.
It turns a book's segments into an ordered list of steps and groups those steps
into waves. That purity is the point: batching used to live as control flow
inside :func:`berilo.translate.translate_book`, so there was no value a
conformance vector could be written against, and
``contracts/conformance.md`` §2 duly recorded Surface 3 as gated nowhere. Three
implementations then drifted to 10/1, 20/4 and 10/1 without a single test going
red.

Two step kinds come out of a plan:

* ``("batch", [(position, segment), ...])`` — segments to send in one call.
* ``("pair", (source, target))`` — a segment already in the cache, contributing
  to the rolling context without costing a call.

A batch ends at the first of: a chapter boundary, a skip-list member, an empty
segment, a cache hit, or ``batch_size``.

See ``contracts/core-spec.md`` Surface 3, which this module is the reference
for.
"""

from __future__ import annotations

from collections.abc import Callable, Collection, Sequence
from dataclasses import dataclass, field

from berilo.models import Segment

#: Number of consecutive paragraphs grouped into one completion. Twenty rather
#: than ten because a reasoning model's budget is charged per *call*, not per
#: segment: measured on gpt-5-mini at ``reasoning_effort=low``, ten segments per
#: call billed ~183 output tokens per segment and twenty billed ~69 — ~2.6x
#: faster and ~2.6x cheaper for twice the text per call.
DEFAULT_BATCH_SIZE = 20

#: Batches translated concurrently in one wave. Matches the iOS engine's lane
#: count; measured 3.8x against an identical call count.
DEFAULT_CONCURRENCY = 4

#: Number of previous source/target paragraph pairs prepended to each batch as
#: rolling context (marked "do not retranslate").
DEFAULT_CONTEXT_PAIRS = 2

#: One planned step: ``("batch", [(position, segment), ...])`` or
#: ``("pair", (source, target))``.
PlanStep = tuple[str, object]

#: A batch as the executor receives it: document positions paired with segments,
#: so results can be folded back by position rather than by arrival order.
PositionedBatch = list[tuple[int, Segment]]


@dataclass(frozen=True)
class TranslationPlan:
    """Everything the executor needs, decided before a single call is made.

    Attributes:
        steps: Ordered batch/pair steps.
        resolved: Document position -> text, for every segment needing no API
            call (empty, skipped, or a cache hit). The executor fills in the
            rest as batches return.
        pair_ids: Step index -> segment id, for pair steps only. Held apart
            from the step payload because the payload carries book *text* and a
            conformance vector may record only IDs (``conformance.md`` §1).
        empty_segments: Count of empty/whitespace pass-throughs.
        skipped_segments: Count of skip-list pass-throughs.
        cached_segments: Count of cache hits.
    """

    steps: list[PlanStep] = field(default_factory=list)
    resolved: dict[int, str] = field(default_factory=dict)
    pair_ids: dict[int, str] = field(default_factory=dict)
    empty_segments: int = 0
    skipped_segments: int = 0
    cached_segments: int = 0


@dataclass(frozen=True)
class Wave:
    """One barrier's worth of work.

    Attributes:
        batches: Batches to run concurrently, in plan order.
        context_segment_ids: IDs of the segments whose source/target pairs make
            up this wave's context snapshot, oldest first. Recorded as IDs
            rather than text so a conformance vector can carry them.
        preceding_pairs: ``(source, target)`` pairs from the cache hits sitting
            immediately before this wave, in order. The executor must fold
            these into its rolling context *before* taking the snapshot: a
            cache hit feeds context exactly as a freshly translated segment
            does, which is what makes a resumed run's prompts identical to an
            uninterrupted run's.
    """

    batches: list[PositionedBatch]
    context_segment_ids: list[str]
    preceding_pairs: list[tuple[str, str]] = field(default_factory=list)


def build_translation_plan(
    segments: Sequence[Segment],
    *,
    lookup: Callable[[Segment], str | None],
    skip_segment_ids: Collection[str] = (),
    batch_size: int = DEFAULT_BATCH_SIZE,
) -> TranslationPlan:
    """Walk ``segments`` once and decide every batch boundary.

    Args:
        segments: The book's segments, in document order.
        lookup: Cache probe returning the stored translation, or ``None``.
            Passed as a callable so this module never imports the cache and
            stays testable without one.
        skip_segment_ids: Segment IDs to pass through untranslated.
        batch_size: Maximum consecutive segments per call.

    Returns:
        The :class:`TranslationPlan`.
    """
    skip = set(skip_segment_ids)
    steps: list[PlanStep] = []
    resolved: dict[int, str] = {}
    pair_ids: dict[int, str] = {}
    empty = skipped = cached = 0

    index = 0
    while index < len(segments):
        segment = segments[index]

        if not segment.text.strip():
            resolved[index] = segment.text
            empty += 1
            index += 1
            continue

        if segment.id in skip:
            resolved[index] = segment.text
            skipped += 1
            index += 1
            continue

        hit = lookup(segment)
        if hit is not None:
            resolved[index] = hit
            cached += 1
            # A cache hit still feeds the rolling context, exactly as a freshly
            # translated segment does, so a resumed run's prompts are identical
            # to an uninterrupted run's.
            pair_ids[len(steps)] = segment.id
            steps.append(("pair", (segment.text, hit)))
            index += 1
            continue

        batch: PositionedBatch = [(index, segment)]
        cursor = index + 1
        while cursor < len(segments) and len(batch) < batch_size:
            candidate = segments[cursor]
            if candidate.chapter_index != segment.chapter_index:
                break
            if candidate.id in skip or not candidate.text.strip():
                break
            if lookup(candidate) is not None:
                break
            batch.append((cursor, candidate))
            cursor += 1

        steps.append(("batch", batch))
        index = cursor

    return TranslationPlan(
        steps=steps,
        resolved=resolved,
        pair_ids=pair_ids,
        empty_segments=empty,
        skipped_segments=skipped,
        cached_segments=cached,
    )


def plan_waves(
    plan: TranslationPlan,
    *,
    concurrency: int = DEFAULT_CONCURRENCY,
    context_pairs: int = DEFAULT_CONTEXT_PAIRS,
) -> list[Wave]:
    """Group a plan's steps into waves and resolve each wave's context snapshot.

    Consecutive batch steps group into a wave up to ``concurrency`` wide. A pair
    step ends the wave it meets, so the context that cache hit contributes
    reaches the *next* wave's snapshot in the right order.

    The snapshot is taken **before** the wave runs and is shared by every batch
    in it, so a wave's prompts never depend on which lane finishes first.

    Args:
        plan: The plan to group.
        concurrency: Maximum batches per wave. ``1`` yields one batch per wave,
            which reproduces strictly sequential translation.
        context_pairs: Rolling-context window. ``<= 0`` means no context.

    Returns:
        The waves, in order.
    """
    lanes = max(1, concurrency)
    waves: list[Wave] = []
    remembered: list[str] = []  # segment ids, oldest first

    def remember(segment_id: str) -> None:
        # ``context_pairs <= 0`` means "no rolling context": remember nothing.
        # Appending anyway and skipping the trim would feed every prior pair of
        # the book into each batch — the exact opposite of the intent, and a
        # guaranteed blow-through of the per-batch token ceiling on a full book.
        if context_pairs <= 0:
            return
        remembered.append(segment_id)
        if len(remembered) > context_pairs:
            del remembered[:-context_pairs]

    step = 0
    pending_pairs: list[tuple[str, str]] = []
    while step < len(plan.steps):
        if plan.steps[step][0] == "pair":
            remember(plan.pair_ids[step])
            pending_pairs.append(plan.steps[step][1])  # type: ignore[arg-type]
            step += 1
            continue

        batches: list[PositionedBatch] = []
        cursor = step
        while cursor < len(plan.steps) and len(batches) < lanes:
            if plan.steps[cursor][0] != "batch":
                break
            batches.append(plan.steps[cursor][1])  # type: ignore[arg-type]
            cursor += 1

        # ``remembered`` already includes this wave's preceding cache hits, so
        # the snapshot recorded here is exactly what the executor will hold
        # once it has folded ``preceding_pairs`` in.
        waves.append(
            Wave(
                batches=batches,
                context_segment_ids=list(remembered),
                preceding_pairs=pending_pairs,
            )
        )
        pending_pairs = []

        for batch in batches:
            for _position, segment in batch:
                remember(segment.id)

        step = cursor

    return waves
