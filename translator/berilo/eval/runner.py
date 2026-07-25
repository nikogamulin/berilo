"""Orchestration for ``berilo eval``: normalize, score, report, persist.

Wires the pieces together: normalize the source and translated books, pull the
per-book glossary and actual translation cost from the shared translation cache
(read-only), run :func:`berilo.eval.rubric_t.score_book`, render a human table
or JSON, and append a score row to ``loops/build/rubric_scores.jsonl``.
"""

from __future__ import annotations

import datetime as _dt
import json
import logging
import sqlite3
import subprocess
from pathlib import Path

from berilo.cache import book_hash
from berilo.eval import rubric_t
from berilo.eval.judge import (
    FLUENCY_PROMPT,
    MEANING_PROMPT,
    SCREEN_PROMPT,
    Judge,
)
from berilo.eval.rubric_t import RubricTResult
from berilo.models import Book

logger = logging.getLogger(__name__)

#: Default score-row destination (relative to the CLI's working directory).
DEFAULT_SCORES_FILE = "loops/build/rubric_scores.jsonl"

#: Source-file extensions tried, in order, when auto-discovering the source next
#: to a translated ``<stem>.<lang>.epub`` output.
_SOURCE_EXTENSIONS = (".epub", ".pdf", ".mobi", ".azw3")


def discover_source(translated_path: str | Path, lang: str) -> Path | None:
    """Locate the source file for a translated ``<stem>.<lang>.epub`` output.

    The translate stage writes ``<stem>.<lang>.epub`` next to the source, so a
    translated book named e.g. ``book.sl.epub`` implies a source ``book.epub`` /
    ``book.pdf`` / ``book.mobi`` in the same directory.

    Args:
        translated_path: Path to the translated EPUB.
        lang: Target language code embedded in the output name.

    Returns:
        The discovered source path, or ``None`` if none is found.
    """
    path = Path(translated_path)
    stem = path.stem  # e.g. "book.sl"
    suffix = f".{lang}"
    base = stem[: -len(suffix)] if stem.endswith(suffix) else stem
    for ext in _SOURCE_EXTENSIONS:
        candidate = path.with_name(f"{base}{ext}")
        if candidate.exists() and candidate != path:
            return candidate
    return None


def read_cache_facts(
    db_path: str | Path, source: Book, model: str, lang: str
) -> tuple[dict[str, str] | None, float | None]:
    """Read the per-book glossary and total actual cost from the cache (read-only).

    Args:
        db_path: Path to the translation cache database.
        source: The source book (its hash keys the cache rows).
        model: Translation model the glossary/cost were recorded under.
        lang: Target language code.

    Returns:
        ``(glossary_or_None, total_cost_eur_or_None)``. Both are ``None`` when
        the cache file is absent or holds no matching rows.
    """
    path = Path(db_path)
    if not path.exists():
        return (None, None)

    bhash = book_hash(source)
    try:
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    except sqlite3.Error as exc:  # pragma: no cover - defensive
        logger.warning("Could not open cache %s read-only: %s", path, exc)
        return (None, None)
    try:
        conn.row_factory = sqlite3.Row
        glossary: dict[str, str] | None = None
        row = conn.execute(
            "SELECT terms_json FROM glossaries WHERE book_hash = ? AND model = ? AND lang = ?",
            (bhash, model, lang),
        ).fetchone()
        if row is not None:
            loaded = json.loads(row["terms_json"])
            glossary = {str(k): str(v) for k, v in loaded.items()}

        cost_row = conn.execute(
            "SELECT SUM(cost_eur) AS total, COUNT(*) AS n FROM calls "
            "WHERE book_hash = ? AND lang = ?",
            (bhash, lang),
        ).fetchone()
        cost = float(cost_row["total"]) if cost_row and cost_row["n"] else None
    except sqlite3.Error as exc:  # pragma: no cover - defensive
        logger.warning("Cache read failed: %s", exc)
        return (None, None)
    finally:
        conn.close()
    return (glossary, cost)


def _git_commit() -> str:
    """Return the current git commit sha, or ``"unknown"`` if unavailable."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
            timeout=5,
        )
        return out.stdout.strip() or "unknown"
    except (subprocess.SubprocessError, OSError):  # pragma: no cover - env dependent
        return "unknown"


def prompt_versions() -> dict[str, str]:
    """Return the judge prompt versions recorded in every score row."""
    return {"meaning": MEANING_PROMPT, "fluency": FLUENCY_PROMPT, "screen": SCREEN_PROMPT}


def score_row(result: RubricTResult, *, seed: int, sample: int) -> dict[str, object]:
    """Build the JSONL score row for a scoring result.

    Args:
        result: The scoring result.
        seed: Seed used for sampling.
        sample: Requested sample size.

    Returns:
        A JSON-serializable score row (schema per ``docs/rubric.md``).
    """
    dims = result.by_key()
    return {
        "date": _dt.datetime.now(_dt.timezone.utc).isoformat(),
        "rubric": "T",
        "version": rubric_t.RUBRIC_VERSION,
        "score": round(result.total, 2),
        "ci": [round(result.total_ci[0], 2), round(result.total_ci[1], 2)],
        "dimensions": {
            key: (None if dims[key].points is None else round(dims[key].points, 3))
            for key in ("T1", "T2", "T3", "T4", "T5", "T6", "T7")
        },
        "commit": _git_commit(),
        "notes": " ".join(result.notes),
        "prompt_versions": prompt_versions(),
        "seed": seed,
        "sample": sample,
        "cost_eur": round(result.cost_eur, 6),
    }


def append_score_row(row: dict[str, object], scores_file: str | Path) -> None:
    """Append one score row to the JSONL scores file, creating parents."""
    path = Path(scores_file)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_dump(result: RubricTResult, dump_path: str | Path) -> int:
    """Write one JSON row per judged sample to ``dump_path`` (S1.9).

    Prose (T2/T3) rows and T6 screen rows have distinguishable shapes (a
    ``kind`` field: ``"prose"`` or ``"screen"``). Every judge repeat is
    recorded, not just the mean, so intra-sample judge noise is inspectable
    after the fact. Nothing is written unless this function is called — the
    CLI only calls it when ``--dump`` is passed.

    Args:
        result: A scored :class:`RubricTResult`.
        dump_path: Destination JSONL path; parent directories are created.

    Returns:
        The number of rows written.
    """
    path = Path(dump_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, object]] = []
    for sample in result.prose_samples:
        rows.append(
            {
                "kind": "prose",
                "segment_id": sample.segment_id,
                "chapter_index": sample.chapter_index,
                "chapter_title": sample.chapter_title,
                "source": sample.source,
                "target": sample.target,
                "meaning": sample.meaning_scores,
                "fluency": sample.fluency_scores,
            }
        )
    for sample in result.screen_samples:
        rows.append(
            {
                "kind": "screen",
                "segment_id": sample.segment_id,
                "chapter_index": sample.chapter_index,
                "chapter_title": sample.chapter_title,
                "target": sample.target,
                "screen": sample.screen_scores,
            }
        )
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return len(rows)


def format_report(result: RubricTResult, *, title: str) -> str:
    """Render the human-readable score table.

    Args:
        result: The scoring result.
        title: Book title for the header.

    Returns:
        A multi-line report string.
    """
    lines = [f"Rubric T — {title}", ""]
    lines.append(f"{'dim':<4}{'name':<26}{'weight':>7}{'score':>13}{'95% CI':>20}")
    for dim in result.dimensions:
        if dim.points is None:
            score_col = "n/a"
            ci_col = "—"
        else:
            score_col = f"{dim.points:.1f}/{dim.weight:.0f}"
            ci_col = f"[{dim.ci_points[0]:.1f}, {dim.ci_points[1]:.1f}]" if dim.ci_points else "—"
        lines.append(f"{dim.key:<4}{dim.name:<26}{dim.weight:>7.0f}{score_col:>13}{ci_col:>20}")
    lines.append("")
    total_ci = f"[{result.total_ci[0]:.1f}, {result.total_ci[1]:.1f}]"
    lines.append(f"{'TOTAL':<37}{result.total:>10.1f}/100{total_ci:>20}")
    for note in result.notes:
        lines.append(f"  ! {note}")
    lines.append(f"  judge cost this run: €{result.cost_eur:.4f}")
    if result.judge_repeats > 1:
        m_noise = result.meaning_noise if result.meaning_noise is not None else 0.0
        f_noise = result.fluency_noise if result.fluency_noise is not None else 0.0
        lines.append(
            f"  judge noise floor (judge-repeats={result.judge_repeats}): "
            f"T2 mean intra-sample σ={m_noise:.2f}  T3 mean intra-sample σ={f_noise:.2f}"
        )
    return "\n".join(lines)


def result_to_dict(
    result: RubricTResult, *, seed: int, sample: int, title: str
) -> dict[str, object]:
    """Render the full result as a JSON-serializable dict (for ``--json``)."""
    payload = score_row(result, seed=seed, sample=sample)
    payload["title"] = title
    payload["complete"] = result.complete
    payload["sample_ids"] = result.sample_ids
    payload["judge_repeats"] = result.judge_repeats
    payload["judge_noise"] = {"T2": result.meaning_noise, "T3": result.fluency_noise}
    payload["dimensions_detail"] = {
        dim.key: {
            "name": dim.name,
            "weight": dim.weight,
            "points": None if dim.points is None else round(dim.points, 3),
            "ci_points": None if dim.ci_points is None else [round(c, 3) for c in dim.ci_points],
            "detail": dim.detail,
        }
        for dim in result.dimensions
    }
    return payload


def describe_plan(
    source: Book,
    translated: Book,
    *,
    sample_size: int,
    seed: int,
    glossary: dict[str, str] | None,
    actual_cost_eur: float | None,
    judge_repeats: int = 1,
) -> str:
    """Describe what a real (non-dry) run would do, making ZERO judge calls.

    Args:
        source: The normalized source book.
        translated: The normalized translated book.
        sample_size: Requested meaning/fluency sample size.
        seed: Sampling seed.
        glossary: Cache glossary (or ``None``).
        actual_cost_eur: Cache cost (or ``None``).
        judge_repeats: How many times a real run would judge each sample
            (S1.9); the reported call count is multiplied accordingly.

    Returns:
        A human-readable dry-run description.
    """
    alignment = rubric_t.align(source, translated)
    eligible = [
        (s, t)
        for s, t in alignment.pairs
        if t is not None and s.type.value == "paragraph" and s.text.strip() and t.text.strip()
    ]
    # v1.1: sampling draws from body prose only (front/back matter excluded).
    excluded = rubric_t.excluded_chapter_indices(source)
    pool, _ = rubric_t.restrict_to_body(
        eligible, lambda pair: pair[0].chapter_index, excluded, sample_size, "T2/T3"
    )
    n_pairs = min(sample_size, len(pool))
    is_pdf = source.source_format == "pdf"
    n_screen = rubric_t.T6_SAMPLE if is_pdf else 0
    # meaning + fluency per pair, + screen, each repeated judge_repeats times.
    judge_calls = (2 * n_pairs + n_screen) * judge_repeats
    t4_note = "glossary available" if glossary else "NO glossary in cache → dropped"
    t6_note = (
        f"PDF source → {n_screen} screen calls (x{judge_repeats} repeats)"
        if is_pdf
        else "non-PDF → automatic full (no judge calls)"
    )
    t7_note = "from cache" if actual_cost_eur is not None else "NO cost data → dropped"
    lines = [
        "DRY RUN — no judge calls, no cost, no score row written.",
        f"Source: {source.title} ({source.source_format}, {source.chapter_count} chapters, "
        f"{alignment.source_count} segments).",
        f"Translated: {alignment.target_count} segments.",
        "T1 completeness: computed exactly over all segments.",
        f"T2 meaning + T3 fluency: {n_pairs} pairs (seed {seed}) "
        f"= {2 * n_pairs} judge calls (x{judge_repeats} repeats).",
        f"T4 terminology: {t4_note}.",
        "T5 structure: automated diff (no judge).",
        f"T6 extraction: {t6_note}.",
        f"T7 cost: {t7_note}.",
        f"Judge repeats: {judge_repeats} (--judge-repeats; noise floor reported when > 1).",
        f"Total judge calls a real run would make: {judge_calls}.",
    ]
    return "\n".join(lines)


def run_eval(
    source: Book,
    translated: Book,
    judge: Judge,
    *,
    sample_size: int,
    seed: int,
    glossary: dict[str, str] | None,
    actual_cost_eur: float | None,
    judge_repeats: int = 1,
) -> RubricTResult:
    """Score a normalized source/translated pair (thin wrapper over score_book)."""
    return rubric_t.score_book(
        source,
        translated,
        judge,
        sample_size=sample_size,
        seed=seed,
        glossary=glossary,
        actual_cost_eur=actual_cost_eur,
        judge_repeats=judge_repeats,
    )
