"""Unit tests for the pure batch planner (core-spec Surface 3).

These pin the batch-break rules and the wave/snapshot discipline without an LLM
client or a cache, which is the whole reason the planner was extracted: the
rules used to be control flow inside ``translate_book`` and could only be
observed by translating something.
"""

from __future__ import annotations

from berilo.models import Segment, SegmentType
from berilo.plan import build_translation_plan, plan_waves


def _seg(sid: str, text: str, chapter: int = 0, position: int = 0) -> Segment:
    return Segment(
        id=sid,
        type=SegmentType.PARAGRAPH,
        text=text,
        chapter_index=chapter,
        chapter_title=f"Chapter {chapter}",
        position=position,
    )


def _batch_ids(step) -> list[str]:
    return [segment.id for _position, segment in step[1]]


def test_consecutive_segments_group_into_one_batch():
    segments = [_seg(f"c0-{i}", f"text {i}", position=i) for i in range(3)]
    plan = build_translation_plan(segments, lookup=lambda _s: None, batch_size=10)
    assert len(plan.steps) == 1
    assert plan.steps[0][0] == "batch"
    assert _batch_ids(plan.steps[0]) == ["c0-0", "c0-1", "c0-2"]


def test_batch_size_caps_a_batch():
    segments = [_seg(f"c0-{i}", f"text {i}", position=i) for i in range(5)]
    plan = build_translation_plan(segments, lookup=lambda _s: None, batch_size=2)
    assert [_batch_ids(s) for s in plan.steps] == [
        ["c0-0", "c0-1"],
        ["c0-2", "c0-3"],
        ["c0-4"],
    ]


def test_chapter_boundary_breaks_a_batch():
    segments = [
        _seg("c0-0", "a", 0, 0),
        _seg("c0-1", "b", 0, 1),
        _seg("c1-0", "c", 1, 2),
    ]
    plan = build_translation_plan(segments, lookup=lambda _s: None, batch_size=10)
    assert [_batch_ids(s) for s in plan.steps] == [["c0-0", "c0-1"], ["c1-0"]]


def test_cache_hit_becomes_a_pair_step_and_breaks_the_batch():
    segments = [_seg(f"c0-{i}", t, position=i) for i, t in enumerate(["a", "b", "c"])]
    plan = build_translation_plan(
        segments,
        lookup=lambda s: "PREVEDENO" if s.id == "c0-1" else None,
        batch_size=10,
    )
    assert [step[0] for step in plan.steps] == ["batch", "pair", "batch"]
    assert plan.steps[1][1] == ("b", "PREVEDENO")
    assert plan.pair_ids == {1: "c0-1"}
    assert plan.cached_segments == 1
    assert plan.resolved[1] == "PREVEDENO"


def test_empty_and_skipped_segments_resolve_without_a_call():
    segments = [
        _seg("c0-0", "   ", 0, 0),
        _seg("c0-1", "b", 0, 1),
        _seg("c0-2", "c", 0, 2),
    ]
    plan = build_translation_plan(
        segments, lookup=lambda _s: None, skip_segment_ids={"c0-2"}, batch_size=10
    )
    assert [_batch_ids(s) for s in plan.steps] == [["c0-1"]]
    assert plan.empty_segments == 1
    assert plan.skipped_segments == 1
    assert plan.resolved[0] == "   "
    assert plan.resolved[2] == "c"


def test_waves_group_consecutive_batches_up_to_concurrency():
    segments = [_seg(f"c0-{i}", f"t{i}", position=i) for i in range(6)]
    plan = build_translation_plan(segments, lookup=lambda _s: None, batch_size=2)
    waves = plan_waves(plan, concurrency=2, context_pairs=2)
    assert [[[s.id for _p, s in b] for b in w.batches] for w in waves] == [
        [["c0-0", "c0-1"], ["c0-2", "c0-3"]],
        [["c0-4", "c0-5"]],
    ]


def test_a_pair_step_ends_a_wave_so_its_context_reaches_the_next_snapshot():
    segments = [_seg(f"c0-{i}", f"t{i}", position=i) for i in range(5)]
    plan = build_translation_plan(
        segments, lookup=lambda s: "X" if s.id == "c0-2" else None, batch_size=1
    )
    waves = plan_waves(plan, concurrency=4, context_pairs=2)
    # c0-0 and c0-1 batch into wave 1; the c0-2 cache hit ends it.
    assert [[s.id for _p, s in b] for b in waves[0].batches] == [["c0-0"], ["c0-1"]]
    assert waves[0].context_segment_ids == []
    # Wave 2's snapshot sees the two most recent pairs, the cache hit included.
    assert waves[1].context_segment_ids == ["c0-1", "c0-2"]


def test_concurrency_one_makes_every_wave_a_single_batch():
    segments = [_seg(f"c0-{i}", f"t{i}", position=i) for i in range(4)]
    plan = build_translation_plan(segments, lookup=lambda _s: None, batch_size=1)
    waves = plan_waves(plan, concurrency=1, context_pairs=2)
    assert all(len(w.batches) == 1 for w in waves)
    assert len(waves) == 4


def test_zero_context_pairs_remembers_nothing():
    """``context_pairs <= 0`` must remember nothing, not everything."""
    segments = [_seg(f"c0-{i}", f"t{i}", position=i) for i in range(6)]
    plan = build_translation_plan(segments, lookup=lambda _s: None, batch_size=1)
    waves = plan_waves(plan, concurrency=2, context_pairs=0)
    assert all(w.context_segment_ids == [] for w in waves)


def test_snapshot_is_shared_by_a_whole_wave():
    """Every batch in a wave sees one snapshot, so lanes cannot influence it."""
    segments = [_seg(f"c0-{i}", f"t{i}", position=i) for i in range(8)]
    plan = build_translation_plan(segments, lookup=lambda _s: None, batch_size=1)
    waves = plan_waves(plan, concurrency=4, context_pairs=2)
    # Wave 1 runs with no history; wave 2 sees only the last two of wave 1.
    assert waves[0].context_segment_ids == []
    assert waves[1].context_segment_ids == ["c0-2", "c0-3"]
