"""Generate the batching conformance vectors for core-spec Surface 3.

Surface 3 was titled "Markers and batching" and specified only markers, so
`conformance.md` §2 recorded it as gated in neither port. The consequence was a
10x spread that nothing could see: the Python reference and the Kotlin port ran
one batch of ten at a time while the Swift port ran four batches of twenty.

Batching was never un-gateable. It is a pure function of `(segments, cache
state, skip list, batch_size, concurrency, context_pairs)` — `berilo.plan` —
and this script records what that function returns for a synthetic book across
the cases a port is most likely to get wrong: the default shape, the strictly
sequential shape, a skip list, a partially resumed cache, and a fully cached
book.

Each vector records the ordered waves -> batches -> **segment IDs**, plus each
wave's context-snapshot provenance, also as IDs. No book text is written:
`conformance.md` §1 rule 3 permits only derived values, because `data/` is
copyrighted and vectors are committed. The synthetic book defined here is the
declared exception and the only place literal text belongs.

Run from the repository root::

    PYTHONPATH=translator python3 contracts/gen/generate_batch_plan_vectors.py

Regenerate whenever `berilo/plan.py` changes; the port gates then fail until
the ports follow. A change that alters any vector's bytes is a **version bump**
— a new `vectors/v<N+1>/` directory, never an edit in place (`conformance.md`
§3).
"""

from __future__ import annotations

import json
from pathlib import Path

from berilo.models import Book, Segment, SegmentType
from berilo.plan import build_translation_plan, plan_waves

#: Vectors directory integer. Bumping this means a new directory.
VECTORS_VERSION = 2

#: Where the vectors land.
OUT_DIR = Path(__file__).resolve().parents[1] / "vectors" / f"v{VECTORS_VERSION}" / "batch_plan"

#: Chapters of the synthetic book, as ``(chapter_index, paragraph_count)``.
#: Chapter 1 is deliberately longer than ``batch_size`` so batches split inside
#: a chapter; chapter 2 is shorter, so the final batch is a partial one.
_SHAPE = ((0, 7), (1, 25), (2, 3))

#: Coordinates of the one empty paragraph, which must break a batch without
#: costing a call.
_EMPTY_AT = (1, 4)


def _synthetic_book() -> Book:
    """Build a book shaped to exercise every batch-break rule at once."""
    segments: list[Segment] = []
    position = 0
    for chapter, count in _SHAPE:
        for index in range(count):
            text = "" if (chapter, index) == _EMPTY_AT else f"Chapter {chapter} paragraph {index}."
            segments.append(
                Segment(
                    id=f"c{chapter}-p{index}",
                    type=SegmentType.PARAGRAPH,
                    text=text,
                    chapter_index=chapter,
                    chapter_title=f"Chapter {chapter}",
                    position=position,
                )
            )
            position += 1
    return Book(
        title="Synthetic Batching Book",
        authors=["Berilo Conformance"],
        language="en",
        source_path="synthetic.epub",
        source_format="epub",
        segments=segments,
    )


#: ``(name, batch_size, concurrency, context_pairs, skip_ids, cached_ids)``.
#: ``cached_ids`` may be the sentinel ``"ALL"``, meaning a fully cached book.
CASES: tuple[tuple[str, int, int, int, tuple[str, ...], object], ...] = (
    ("default", 20, 4, 2, (), ()),
    ("sequential", 20, 1, 2, (), ()),
    ("batch_10_lanes_4", 10, 4, 2, (), ()),
    ("no_context", 20, 4, 0, (), ()),
    ("with_skips", 10, 4, 2, ("c1-p0", "c1-p1"), ()),
    ("resumed", 10, 4, 2, (), ("c0-p0", "c0-p1", "c1-p9", "c1-p10")),
    ("fully_cached", 20, 4, 2, (), "ALL"),
)


def case_vector(book: Book, case) -> dict:
    """Return the vector payload for one case.

    Args:
        book: The synthetic book to plan over.
        case: One entry of :data:`CASES`.

    Returns:
        The JSON-ready vector.
    """
    name, batch_size, concurrency, context_pairs, skip_ids, cached_ids = case
    cached = {segment.id for segment in book.segments} if cached_ids == "ALL" else set(cached_ids)
    plan = build_translation_plan(
        book.segments,
        lookup=lambda segment: "CACHED" if segment.id in cached else None,
        skip_segment_ids=set(skip_ids),
        batch_size=batch_size,
    )
    waves = plan_waves(plan, concurrency=concurrency, context_pairs=context_pairs)
    return {
        "name": name,
        "inputs": {
            "batch_size": batch_size,
            "concurrency": concurrency,
            "context_pairs": context_pairs,
            "skip_segment_ids": sorted(skip_ids),
            "cached_segment_ids": sorted(cached),
        },
        "counts": {
            "empty_segments": plan.empty_segments,
            "skipped_segments": plan.skipped_segments,
            "cached_segments": plan.cached_segments,
            "batches": sum(len(wave.batches) for wave in waves),
            "waves": len(waves),
        },
        "waves": [
            {
                "context_segment_ids": wave.context_segment_ids,
                "preceding_cache_hit_ids": [
                    segment_id for segment_id in wave.context_segment_ids if segment_id in cached
                ],
                "batches": [[segment.id for _position, segment in batch] for batch in wave.batches],
            }
            for wave in waves
        ],
    }


def _inventory(book: Book) -> dict:
    """Return the book's segment inventory, so a port builds the same input."""
    return {
        "book": book.title,
        "segments": [
            {
                "id": segment.id,
                "chapter_index": segment.chapter_index,
                "position": segment.position,
                "empty": not segment.text.strip(),
            }
            for segment in book.segments
        ],
    }


def _write(path: Path, payload: dict) -> None:
    """Write ``payload`` deterministically (sorted keys, trailing newline)."""
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    """Write one vector per case, the segment inventory, and the manifest."""
    book = _synthetic_book()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    _write(OUT_DIR / "synthetic.inventory.json", _inventory(book))
    for case in CASES:
        vector = case_vector(book, case)
        _write(OUT_DIR / f"synthetic.{vector['name']}.json", vector)

    _write(
        OUT_DIR.parent / "manifest.json",
        {
            "vectors_version": VECTORS_VERSION,
            "surfaces": ["batch_plan"],
            "generator": "contracts/gen/generate_batch_plan_vectors.py",
        },
    )
    print(f"Wrote {len(CASES) + 1} vectors to {OUT_DIR}")


if __name__ == "__main__":
    main()
