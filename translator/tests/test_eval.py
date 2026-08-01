"""Tests for the Rubric T eval harness (S1.7) and its instrumentation (S1.9).

All judge calls are mocked — no real LLM client is constructed and no API budget
is spent. Fakes replay deterministic ``SCORE: <n>`` verdicts and record their
calls so we can assert seeded-sampling determinism, the T1 completeness cap,
loud alignment-mismatch detection, structural diffs, bootstrap-CI sanity,
verdict parsing/retry, weight renormalization, the score-row schema, the
CLI's dry-run (zero judge calls) contract, and (S1.9) the per-sample
``--dump`` and ``--judge-repeats`` judge-noise-floor instrumentation.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from berilo.cache import CallRecord, SegmentTranslation, TranslationCache, book_hash
from berilo.cli import cli
from berilo.eval import runner, sampling
from berilo.eval.judge import Judge, JudgeError, parse_score
from berilo.eval.rubric_t import (
    RUBRIC_VERSION,
    AlignmentError,
    _term_occurrences,
    align,
    excluded_chapter_indices,
    score_book,
    score_cost,
    score_extraction,
    score_structure,
    score_terminology,
)
from berilo.models import Book, Segment, SegmentType, make_segment_id
from berilo.providers.base import CompletionResult, LLMClient

# --------------------------------------------------------------------------
# Fakes and builders.
# --------------------------------------------------------------------------

_MEANING_MARKER = "translation-quality judge"
_FLUENCY_MARKER = "native Slovenian editor"
_SCREEN_MARKER = "extraction cleanliness"


class ScriptedJudgeClient(LLMClient):
    """Offline judge client returning fixed ``SCORE: n`` verdicts per role.

    Detects the judge role from a distinctive phrase in the rendered prompt and
    replies with the configured score. Records every call for assertions and
    bills a tiny fixed cost so cost accounting is exercised.
    """

    def __init__(self, *, meaning: int = 5, fluency: int = 5, screen: int = 1) -> None:
        self.model = "gpt-5-mini"
        self.meaning = meaning
        self.fluency = fluency
        self.screen = screen
        self.calls: list[str] = []

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        **kwargs: object,
    ) -> CompletionResult:
        text = prompt or ""
        if _MEANING_MARKER in text:
            role, score = "meaning", self.meaning
        elif _FLUENCY_MARKER in text:
            role, score = "fluency", self.fluency
        elif _SCREEN_MARKER in text:
            role, score = "screen", self.screen
        else:  # pragma: no cover - defensive
            role, score = "unknown", 0
        self.calls.append(role)
        return CompletionResult(
            text=f"SCORE: {score}",
            input_tokens=10,
            output_tokens=3,
            cost_eur=0.0001,
            model=self.model,
        )


class GarbageClient(LLMClient):
    """Judge client whose replies never parse (optionally valid after N calls)."""

    def __init__(self, valid_after: int | None = None) -> None:
        self.model = "gpt-5-mini"
        self.n = 0
        self.valid_after = valid_after

    def complete(self, prompt=None, messages=None, **kwargs) -> CompletionResult:  # noqa: ANN001
        self.n += 1
        text = (
            "SCORE: 4" if self.valid_after is not None and self.n > self.valid_after else "no idea"
        )
        return CompletionResult(
            text=text, input_tokens=1, output_tokens=1, cost_eur=0.0, model=self.model
        )


class ExplodingClient(LLMClient):
    """Judge client that fails the test if ``complete`` is ever called."""

    model = "gpt-5-mini"

    def complete(self, prompt=None, messages=None, **kwargs):  # noqa: ANN001
        raise AssertionError("no judge call expected")


def make_book(
    specs: list[tuple[SegmentType, str, int, int | None]],
    *,
    source_format: str = "epub",
    title: str = "Test Book",
    titles: dict[int, str] | None = None,
) -> Book:
    """Build a :class:`Book` from ``(type, text, chapter_index, heading_level)`` specs.

    ``titles`` optionally overrides the chapter title per chapter index (used to
    exercise front/back-matter recognition, which keys on chapter title).
    """
    titles = titles or {}
    segments = [
        Segment(
            id=make_segment_id(text, chapter, pos),
            type=seg_type,
            text=text,
            chapter_index=chapter,
            chapter_title=titles.get(chapter, f"Chapter {chapter}"),
            position=pos,
            heading_level=level,
        )
        for pos, (seg_type, text, chapter, level) in enumerate(specs)
    ]
    return Book(
        title=title,
        authors=["A"],
        language="en",
        source_path="x",
        source_format=source_format,
        segments=segments,
    )


def _prose_specs(n: int, *, chapters: int = 2) -> list[tuple[SegmentType, str, int, int | None]]:
    """Build ``n`` paragraph specs spread across ``chapters`` chapters, each headed."""
    specs: list[tuple[SegmentType, str, int, int | None]] = []
    per = max(1, n // chapters)
    for i in range(n):
        chapter = min(i // per, chapters - 1)
        if i % per == 0:
            specs.append((SegmentType.HEADING, f"Heading {chapter}", chapter, 1))
        specs.append(
            (SegmentType.PARAGRAPH, f"Source paragraph number {i} with content.", chapter, None)
        )
    return specs


def _translate(book: Book, *, empty_positions: set[int] | None = None) -> Book:
    """Return a structurally-identical 'translation' (text prefixed, types kept)."""
    empty_positions = empty_positions or set()
    specs = [
        (
            seg.type,
            "" if seg.position in empty_positions else f"SL {seg.text}",
            seg.chapter_index,
            seg.heading_level,
        )
        for seg in book.segments
    ]
    return make_book(specs, source_format="epub", title=f"[SL] {book.title}")


# --------------------------------------------------------------------------
# Sampling + bootstrap.
# --------------------------------------------------------------------------


def test_select_indices_is_deterministic_and_seed_sensitive() -> None:
    a = sampling.select_indices(200, 40, seed=42)
    b = sampling.select_indices(200, 40, seed=42)
    c = sampling.select_indices(200, 40, seed=7)
    assert a == b
    assert a != c
    assert a == sorted(a)
    assert len(a) == 40
    # Sample >= pool returns every index.
    assert sampling.select_indices(30, 40, seed=42) == list(range(30))


def test_bootstrap_ci_contains_point_and_shrinks_with_n() -> None:
    small = [5, 1, 5, 1, 5, 1, 5, 1, 5, 1]  # n=10, mean 3.0
    large = small * 10  # n=100, same mean/variance

    def stat(data):
        return lambda idx: sampling.mean([data[i] for i in idx])

    lo_s, hi_s = sampling.bootstrap_ci(stat(small), len(small), seed=1)
    lo_l, hi_l = sampling.bootstrap_ci(stat(large), len(large), seed=1)
    point = sampling.mean(small)
    assert lo_s <= point <= hi_s
    assert lo_l <= point <= hi_l
    assert (hi_l - lo_l) < (hi_s - lo_s)  # CI shrinks with larger n


# --------------------------------------------------------------------------
# Judge parsing + retry.
# --------------------------------------------------------------------------


def test_parse_score_valid_out_of_range_and_garbage() -> None:
    assert parse_score("SCORE: 4", range(1, 6)) == 4
    assert parse_score("blah\nSCORE: 5 done", range(1, 6)) == 5
    assert parse_score("SCORE: 9", range(1, 6)) is None  # out of range
    assert parse_score("no verdict here", range(1, 6)) is None


def test_judge_retries_once_then_raises() -> None:
    always_bad = Judge(GarbageClient())
    with pytest.raises(JudgeError):
        always_bad.meaning("en", "sl")
    assert always_bad.calls == 2  # initial + one retry

    recovers = Judge(GarbageClient(valid_after=1))
    assert recovers.meaning("en", "sl") == 4
    assert recovers.calls == 2


def test_judge_accumulates_cost() -> None:
    judge = Judge(ScriptedJudgeClient(meaning=5))
    judge.meaning("en", "sl")
    judge.fluency("sl")
    assert judge.calls == 2
    assert judge.total_cost_eur == pytest.approx(0.0002)


# --------------------------------------------------------------------------
# Alignment.
# --------------------------------------------------------------------------


def test_align_perfect_pairs_one_to_one() -> None:
    source = make_book(_prose_specs(20))
    translated = _translate(source)
    alignment = align(source, translated)
    assert alignment.source_count == alignment.target_count
    assert all(t is not None for _, t in alignment.pairs)
    assert len(alignment.pairs) == len(source.segments)


def test_align_scramble_raises_loudly() -> None:
    source = make_book(
        [
            (SegmentType.HEADING, "H", 0, 1),
            (SegmentType.PARAGRAPH, "one", 0, None),
            (SegmentType.PARAGRAPH, "two", 0, None),
        ]
    )
    # Equal count, but a heading where a paragraph belongs (types don't correspond).
    scrambled = make_book(
        [
            (SegmentType.PARAGRAPH, "SL one", 0, None),
            (SegmentType.HEADING, "SL H", 0, 1),
            (SegmentType.PARAGRAPH, "SL two", 0, None),
        ]
    )
    with pytest.raises(AlignmentError):
        align(source, scrambled)


def test_align_truncation_marks_missing_not_error() -> None:
    source = make_book(_prose_specs(20))
    translated = _translate(source)
    translated.segments = translated.segments[:-3]  # drop a tail of translations
    alignment = align(source, translated)
    missing = [s for s, t in alignment.pairs if t is None]
    assert len(missing) == 3
    assert alignment.source_count == 20 + 2  # 20 paragraphs + 2 headings
    assert alignment.target_count == alignment.source_count - 3


# --------------------------------------------------------------------------
# Completeness cap.
# --------------------------------------------------------------------------


def test_incomplete_translation_caps_total_at_40() -> None:
    source = make_book(_prose_specs(20))
    # One empty translation -> T1 < 100%.
    empty_pos = next(seg.position for seg in source.segments if seg.type == SegmentType.PARAGRAPH)
    translated = _translate(source, empty_positions={empty_pos})
    result = score_book(source, translated, Judge(ScriptedJudgeClient()), sample_size=10, seed=42)
    t1 = result.by_key()["T1"]
    assert t1.fraction is not None and t1.fraction < 1.0
    assert not result.complete
    assert result.total <= 40.0
    assert result.total_ci[1] <= 40.0
    assert any("INCOMPLETE" in note for note in result.notes)


def test_perfect_translation_scores_high() -> None:
    source = make_book(_prose_specs(30))
    translated = _translate(source)
    result = score_book(
        source,
        translated,
        Judge(ScriptedJudgeClient(meaning=5, fluency=5)),
        sample_size=20,
        seed=42,
    )
    keyed = result.by_key()
    assert keyed["T1"].fraction == pytest.approx(1.0)
    assert keyed["T2"].points == pytest.approx(30.0)
    assert keyed["T3"].points == pytest.approx(20.0)
    assert result.complete
    # T4 (no glossary) and T7 (no cost) dropped -> renormalized over 90.
    assert keyed["T4"].points is None
    assert keyed["T7"].points is None
    assert result.total == pytest.approx(100.0)  # every available dim maxed
    assert result.total_ci[0] <= result.total <= result.total_ci[1] + 1e-9


# --------------------------------------------------------------------------
# Structure (T5).
# --------------------------------------------------------------------------


def test_structure_perfect_is_full_and_broken_is_less() -> None:
    source = make_book(
        [
            (SegmentType.HEADING, "H1", 0, 1),
            (SegmentType.PARAGRAPH, "p with <em>emph</em> and <strong>bold</strong>", 0, None),
            (SegmentType.LIST_ITEM, "item", 0, None),
            (SegmentType.BLOCKQUOTE, "quote", 0, None),
            (SegmentType.HEADING, "H2", 1, 1),
            (SegmentType.PARAGRAPH, "another <em>emph</em> para", 1, None),
        ]
    )
    perfect = _translate(source)  # keeps emphasis tags (prefixes text)
    perfect_score = score_structure(source, perfect)
    assert perfect_score.fraction == pytest.approx(1.0)
    assert perfect_score.points == pytest.approx(10.0)

    broken = make_book(
        [
            (
                SegmentType.PARAGRAPH,
                "SL p no emphasis",
                0,
                None,
            ),  # dropped H1, list, quote, emphasis
            (SegmentType.HEADING, "SL H2", 0, 1),
            (SegmentType.PARAGRAPH, "SL another para", 0, None),
        ]
    )
    broken_score = score_structure(source, broken)
    assert broken_score.points < 10.0


# --------------------------------------------------------------------------
# Terminology (T4).
# --------------------------------------------------------------------------


def test_terminology_none_when_no_glossary() -> None:
    source = make_book(_prose_specs(6))
    translated = _translate(source)
    dim = score_terminology(align(source, translated), None)
    assert dim.fraction is None
    assert "renormalized" in dim.detail


def test_terminology_hit_rate_declension_tolerant() -> None:
    source = make_book(
        [
            (SegmentType.PARAGRAPH, "Ljubljana is a city. Ljubljana grows.", 0, None),
            (SegmentType.PARAGRAPH, "Zagreb is elsewhere.", 0, None),
        ]
    )
    # Translation renders the term declined ("Ljubljani"), which must still match.
    translated = make_book(
        [
            (SegmentType.PARAGRAPH, "V Ljubljani je mesto. Ljubljani raste.", 0, None),
            (SegmentType.PARAGRAPH, "Zagreb je drugje.", 0, None),
        ]
    )
    glossary = {"Ljubljana": "Ljubljana", "Zagreb": "Beograd"}  # Zagreb deliberately mistranslated
    dim = score_terminology(align(source, translated), glossary)
    # 2 Ljubljana occurrences matched, 1 Zagreb occurrence unmatched -> 2/3.
    assert dim.fraction == pytest.approx(2 / 3)


# --------------------------------------------------------------------------
# Extraction (T6) + Cost (T7).
# --------------------------------------------------------------------------


def test_extraction_pdf_screens_paragraphs() -> None:
    source_fmt = "pdf"
    translated = make_book(
        [(SegmentType.PARAGRAPH, f"para {i}", 0, None) for i in range(30)], source_format="pdf"
    )
    judge = Judge(ScriptedJudgeClient(screen=1))
    dim, scores, fell_back, samples = score_extraction(
        source_fmt, translated, judge, sample_size=20, seed=1
    )
    assert len(scores) == 20  # sampled 20 paragraphs
    assert len(samples) == 20
    assert all(s.screen_scores == [1] for s in samples)  # judge_repeats=1 default
    assert judge.calls == 20
    assert dim.fraction == pytest.approx(1.0)
    assert fell_back is False


def test_extraction_epub_is_automatic_full_no_calls() -> None:
    translated = make_book([(SegmentType.PARAGRAPH, "p", 0, None)])
    judge = Judge(ExplodingClient())
    dim, scores, fell_back, samples = score_extraction(
        "epub", translated, judge, sample_size=20, seed=1
    )
    assert dim.fraction == pytest.approx(1.0)
    assert scores == []
    assert fell_back is False
    assert samples == []


def test_cost_scoring_bands() -> None:
    assert score_cost(None, 1000).fraction is None
    # €0.50 over 100k words -> well under €1.50 threshold -> full.
    assert score_cost(0.5, 100_000).fraction == pytest.approx(1.0)
    # €4.50 / 100k words -> zero.
    assert score_cost(4.5, 100_000).fraction == pytest.approx(0.0)
    # €3.00 / 100k words -> halfway on the 1.5->4.5 ramp.
    assert score_cost(3.0, 100_000).fraction == pytest.approx(0.5)


def test_weight_renormalization_with_all_optional_dims_present() -> None:
    source = make_book(_prose_specs(20))
    translated = _translate(source)
    glossary = {"paragraph": "odstavek"}
    result = score_book(
        source,
        translated,
        Judge(ScriptedJudgeClient(meaning=4, fluency=4)),
        sample_size=15,
        seed=42,
        glossary=glossary,
        actual_cost_eur=0.10,
    )
    keyed = result.by_key()
    assert keyed["T4"].points is not None  # glossary present -> T4 available
    assert keyed["T7"].points is not None  # cost present -> T7 available
    # No dimensions dropped -> total over full weight 100, no renormalization note.
    assert not any("Dropped" in n for n in result.notes)


# --------------------------------------------------------------------------
# Score-row schema + persistence.
# --------------------------------------------------------------------------


def test_score_row_schema_and_append(tmp_path: Path) -> None:
    source = make_book(_prose_specs(12))
    translated = _translate(source)
    result = score_book(source, translated, Judge(ScriptedJudgeClient()), sample_size=8, seed=42)
    row = runner.score_row(result, seed=42, sample=8)
    for key in (
        "date",
        "rubric",
        "version",
        "score",
        "ci",
        "dimensions",
        "commit",
        "notes",
        "prompt_versions",
        "seed",
        "sample",
        "cost_eur",
    ):
        assert key in row
    assert row["rubric"] == "T"
    assert row["version"] == "1.1"  # v1.1 rubric revision recorded
    assert RUBRIC_VERSION == "1.1"
    assert row["prompt_versions"]["meaning"] == "meaning_v1"  # prompts unchanged
    assert set(row["dimensions"]) == {"T1", "T2", "T3", "T4", "T5", "T6", "T7"}

    dest = tmp_path / "scores.jsonl"
    runner.append_score_row(row, dest)
    runner.append_score_row(row, dest)
    lines = dest.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 2
    assert json.loads(lines[0])["rubric"] == "T"


# --------------------------------------------------------------------------
# Source discovery.
# --------------------------------------------------------------------------


def test_discover_source_strips_language_suffix(tmp_path: Path) -> None:
    (tmp_path / "book.epub").write_text("x")
    translated = tmp_path / "book.sl.epub"
    translated.write_text("y")
    found = runner.discover_source(translated, "sl")
    assert found == tmp_path / "book.epub"
    # Nothing to find for an unrelated name.
    assert runner.discover_source(tmp_path / "other.sl.epub", "sl") is None


# --------------------------------------------------------------------------
# CLI.
# --------------------------------------------------------------------------


def _write_epub(builder, specs: list[dict], **kw) -> Path:
    return builder(specs, **kw)


def _epub_pair(epub_builder) -> tuple[Path, Path]:
    """Build a synthetic source EPUB and a structurally-identical translation."""
    src_items = [
        {
            "id": "c1",
            "href": "c1.xhtml",
            "nav_title": "Chapter One",
            "body": "<h1>Chapter One</h1>"
            + "".join(f"<p>Source paragraph {i}.</p>" for i in range(15)),
        },
        {
            "id": "c2",
            "href": "c2.xhtml",
            "nav_title": "Chapter Two",
            "body": "<h1>Chapter Two</h1>"
            + "".join(f"<p>More source text {i}.</p>" for i in range(15)),
        },
    ]
    tgt_items = [
        {
            "id": "c1",
            "href": "c1.xhtml",
            "nav_title": "Poglavje Ena",
            "body": "<h1>SL Chapter One</h1>"
            + "".join(f"<p>SL paragraph {i}.</p>" for i in range(15)),
        },
        {
            "id": "c2",
            "href": "c2.xhtml",
            "nav_title": "Poglavje Dve",
            "body": "<h1>SL Chapter Two</h1>"
            + "".join(f"<p>SL more text {i}.</p>" for i in range(15)),
        },
    ]
    source = _write_epub(epub_builder, src_items, title="My Book")
    translated = _write_epub(epub_builder, tgt_items, title="[SL] My Book", language="sl")
    return source, translated


def _fake_client_factory(client: LLMClient):
    def factory(model: str, config):  # noqa: ANN001
        return client

    return factory


def test_cli_eval_runs_prints_ci_and_writes_row(epub_builder, tmp_path, monkeypatch) -> None:
    source, translated = _epub_pair(epub_builder)
    monkeypatch.setattr(
        "berilo.providers.create_client", _fake_client_factory(ScriptedJudgeClient())
    )
    scores_file = tmp_path / "rubric_scores.jsonl"
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "eval",
                str(translated),
                "--source",
                str(source),
                "--sample",
                "20",
                "--seed",
                "42",
                "--scores-file",
                str(scores_file),
            ],
        )
    assert result.exit_code == 0, result.output
    assert "Rubric T" in result.output
    assert "TOTAL" in result.output
    assert "95% CI" in result.output
    assert scores_file.exists()
    row = json.loads(scores_file.read_text(encoding="utf-8").strip())
    assert row["rubric"] == "T"
    assert row["seed"] == 42


def test_cli_eval_same_seed_identical_sample(epub_builder, tmp_path, monkeypatch) -> None:
    source, translated = _epub_pair(epub_builder)
    monkeypatch.setattr(
        "berilo.providers.create_client", _fake_client_factory(ScriptedJudgeClient())
    )
    runner_ = CliRunner()

    def run(seed: int) -> dict:
        with runner_.isolated_filesystem():
            res = runner_.invoke(
                cli,
                [
                    "eval",
                    str(translated),
                    "--source",
                    str(source),
                    "--sample",
                    "20",
                    "--seed",
                    str(seed),
                    "--json",
                    "--no-write",
                ],
            )
        assert res.exit_code == 0, res.output
        return json.loads(res.output)

    first = run(42)
    second = run(42)
    third = run(7)
    assert first["sample_ids"] == second["sample_ids"]
    assert first["sample_ids"] != third["sample_ids"]


def test_cli_dry_run_makes_zero_judge_calls(epub_builder, tmp_path, monkeypatch) -> None:
    source, translated = _epub_pair(epub_builder)
    exploding = ExplodingClient()
    monkeypatch.setattr("berilo.providers.create_client", _fake_client_factory(exploding))
    scores_file = tmp_path / "should_not_exist.jsonl"
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "eval",
                str(translated),
                "--source",
                str(source),
                "--dry-run",
                "--scores-file",
                str(scores_file),
            ],
        )
    assert result.exit_code == 0, result.output
    assert "DRY RUN" in result.output
    assert "judge calls" in result.output
    assert not scores_file.exists()  # dry-run writes no row


def test_cli_alignment_failure_exits_loudly(epub_builder, monkeypatch) -> None:
    # Source and translated with incompatible structure (scramble) -> exit 2.
    src_items = [
        {"id": "c1", "href": "c1.xhtml", "nav_title": "One", "body": "<h1>H</h1><p>a</p><p>b</p>"}
    ]
    tgt_items = [
        {"id": "c1", "href": "c1.xhtml", "nav_title": "Ena", "body": "<p>a</p><h1>H</h1><p>b</p>"}
    ]
    source = epub_builder(src_items, title="X")
    translated = epub_builder(tgt_items, title="[SL] X", language="sl")
    monkeypatch.setattr(
        "berilo.providers.create_client", _fake_client_factory(ScriptedJudgeClient())
    )
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli, ["eval", str(translated), "--source", str(source), "--no-write"]
        )
    assert result.exit_code == 2
    assert "ALIGNMENT FAILURE" in result.output


# --------------------------------------------------------------------------
# v1.1: body-prose sampling pools + word-boundary T4.
# --------------------------------------------------------------------------


class ContentScreenClient(ScriptedJudgeClient):
    """Screen client that judges a paragraph clean iff its text contains 'Body'."""

    def complete(self, prompt=None, messages=None, **kwargs):  # noqa: ANN001
        text = prompt or ""
        if _SCREEN_MARKER in text:
            self.calls.append("screen")
            score = 1 if "Body" in text else 0
            return CompletionResult(f"SCORE: {score}", 10, 3, 0.0001, self.model)
        return super().complete(prompt, messages, **kwargs)


def _book_with_notes(*, body_per_chapter: int = 10) -> Book:
    """Source book with two body chapters and a back-matter 'Notes' chapter (2)."""
    specs: list[tuple[SegmentType, str, int, int | None]] = []
    for ch in (0, 1):
        specs.append((SegmentType.HEADING, f"Chapter {ch}", ch, 1))
        for i in range(body_per_chapter):
            specs.append((SegmentType.PARAGRAPH, f"Body {ch}-{i} prose sentence.", ch, None))
    specs.append((SegmentType.HEADING, "Notes", 2, 1))
    for i in range(body_per_chapter):
        specs.append((SegmentType.PARAGRAPH, f"Note {i} citation fragment.", 2, None))
    return make_book(specs, titles={2: "Notes"})


def test_excluded_chapter_indices_recognizes_front_and_back_matter() -> None:
    specs = [
        (SegmentType.PARAGRAPH, "body", 0, None),
        (SegmentType.PARAGRAPH, "notes citation", 1, None),
        (SegmentType.PARAGRAPH, "copyright text", 2, None),
        (SegmentType.PARAGRAPH, "index entry", 3, None),
    ]
    book = make_book(specs, titles={0: "Chapter 1", 1: "Notes", 2: "Copyright", 3: "Index"})
    assert excluded_chapter_indices(book) == {1, 2, 3}


def test_t23_sampling_excludes_back_matter_chapter() -> None:
    source = _book_with_notes()
    translated = _translate(source)
    id_to_chapter = {seg.id: seg.chapter_index for seg in source.segments}
    result = score_book(source, translated, Judge(ScriptedJudgeClient()), sample_size=12, seed=42)
    # No sampled pair comes from the Notes back-matter chapter (index 2).
    assert result.sample_ids
    assert all(id_to_chapter[sid] != 2 for sid in result.sample_ids)
    assert not any("fell back" in n for n in result.notes)


def test_t23_sampling_falls_back_when_body_pool_too_small() -> None:
    # Only 2 body paragraphs, but a large Notes chapter; sample_size 10 > body pool.
    specs: list[tuple[SegmentType, str, int, int | None]] = [
        (SegmentType.HEADING, "Chapter 0", 0, 1),
        (SegmentType.PARAGRAPH, "Body 0-0 prose.", 0, None),
        (SegmentType.PARAGRAPH, "Body 0-1 prose.", 0, None),
        (SegmentType.HEADING, "Notes", 1, 1),
    ]
    for i in range(20):
        specs.append((SegmentType.PARAGRAPH, f"Note {i} citation.", 1, None))
    source = make_book(specs, titles={1: "Notes"})
    translated = _translate(source)
    result = score_book(source, translated, Judge(ScriptedJudgeClient()), sample_size=10, seed=42)
    # Fallback engaged: pool reverted to the full paragraph set, and it is noted.
    assert any("fell back" in n for n in result.notes)
    assert len(result.sample_ids) == 10


def test_t6_sampling_excludes_back_matter_paragraphs() -> None:
    # PDF source: body paragraphs are 'clean', Notes paragraphs are 'dirty'.
    specs: list[tuple[SegmentType, str, int, int | None]] = []
    for i in range(20):
        specs.append((SegmentType.PARAGRAPH, f"Body {i} clean prose.", 0, None))
    for i in range(20):
        specs.append((SegmentType.PARAGRAPH, f"Note {i} junk header 12.", 1, None))
    translated = make_book(specs, source_format="pdf", titles={1: "Notes"})
    judge = Judge(ContentScreenClient())
    dim, scores, fell_back, samples = score_extraction(
        "pdf", translated, judge, sample_size=20, seed=1, excluded_chapters={1}
    )
    # Only body (chapter 0) paragraphs screened -> all clean, no fallback.
    assert fell_back is False
    assert len(scores) == 20
    assert dim.fraction == pytest.approx(1.0)
    assert all(seg.chapter_index == 0 for seg in samples)  # excluded chapter never sampled


def test_term_occurrences_uses_word_boundaries() -> None:
    # The classic bug: 'UN' matched inside 'united'/'understand' as a substring.
    text = "The UN met. The united nations understand the UN."
    assert _term_occurrences("UN", text) == 2  # two standalone 'UN', not 4
    assert _term_occurrences("un", text) == 2  # case-insensitive, still boundary-anchored
    assert _term_occurrences("nations", text) == 1


def test_terminology_word_boundary_no_substring_inflation() -> None:
    source = make_book(
        [
            (SegmentType.PARAGRAPH, "The UN is united. Nations understand.", 0, None),
            (SegmentType.PARAGRAPH, "The UN acts.", 0, None),
        ]
    )
    translated = make_book(
        [
            (SegmentType.PARAGRAPH, "OZN je združena. Narodi razumejo.", 0, None),
            (SegmentType.PARAGRAPH, "OZN ukrepa.", 0, None),
        ]
    )
    glossary = {"UN": "OZN"}
    dim = score_terminology(align(source, translated), glossary)
    # 2 whole-word 'UN' occurrences, both rendered 'OZN' -> 2/2, not diluted by
    # 'united'/'understand' substring hits.
    assert dim.fraction == pytest.approx(1.0)
    assert "2/2" in dim.detail


# --------------------------------------------------------------------------
# S1.9: per-sample dump + judge-repeat noise floor.
# --------------------------------------------------------------------------


class CyclingJudgeClient(LLMClient):
    """Judge client cycling meaning verdicts across repeats (noise-floor tests).

    Fluency and screen replies are fixed (zero noise), so a noise-floor test
    can assert T2 noise > 0 while T3 noise == 0 from the same run.
    """

    def __init__(self, meaning_cycle: list[int], *, fluency: int = 4, screen: int = 1) -> None:
        self.model = "gpt-5-mini"
        self.meaning_cycle = meaning_cycle
        self.fluency = fluency
        self.screen = screen
        self._n_meaning = 0
        self.calls: list[str] = []

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        **kwargs: object,
    ) -> CompletionResult:
        text = prompt or ""
        if _MEANING_MARKER in text:
            score = self.meaning_cycle[self._n_meaning % len(self.meaning_cycle)]
            self._n_meaning += 1
            role = "meaning"
        elif _FLUENCY_MARKER in text:
            role, score = "fluency", self.fluency
        elif _SCREEN_MARKER in text:
            role, score = "screen", self.screen
        else:  # pragma: no cover - defensive
            role, score = "unknown", 0
        self.calls.append(role)
        return CompletionResult(
            text=f"SCORE: {score}",
            input_tokens=10,
            output_tokens=3,
            cost_eur=0.0001,
            model=self.model,
        )


def test_sampling_stdev_matches_population_formula() -> None:
    assert sampling.stdev([]) == 0.0
    assert sampling.stdev([7]) == 0.0
    # mean 3, variance (4+1+0+1+4)/5 = 2 -> stdev sqrt(2).
    assert sampling.stdev([1, 2, 3, 4, 5]) == pytest.approx(2**0.5)


def test_judge_repeats_multiplies_calls_and_keeps_score_shape() -> None:
    source = make_book(_prose_specs(10))
    translated = _translate(source)
    judge = Judge(ScriptedJudgeClient(meaning=4, fluency=4))
    result = score_book(source, translated, judge, sample_size=5, seed=42, judge_repeats=3)
    # epub source -> T6 automatic full, no screen calls: meaning+fluency only.
    assert judge.calls == 5 * 3 * 2
    assert result.judge_repeats == 3
    assert len(result.prose_samples) == 5
    for sample in result.prose_samples:
        assert sample.meaning_scores == [4, 4, 4]
        assert sample.fluency_scores == [4, 4, 4]
    # Existing dimension/score shape is unaffected by repeats.
    assert result.by_key()["T2"].points is not None
    assert result.by_key()["T3"].points is not None


def test_judge_repeats_default_one_is_unchanged_behavior() -> None:
    source = make_book(_prose_specs(10))
    translated = _translate(source)
    judge = Judge(ScriptedJudgeClient(meaning=5, fluency=5))
    result = score_book(source, translated, judge, sample_size=5, seed=42)
    assert result.judge_repeats == 1
    assert judge.calls == 5 * 2
    assert all(len(s.meaning_scores) == 1 for s in result.prose_samples)


def test_score_book_computes_intra_sample_noise_floor() -> None:
    source = make_book(_prose_specs(6))
    translated = _translate(source)
    judge = Judge(CyclingJudgeClient(meaning_cycle=[3, 5], fluency=4))
    result = score_book(source, translated, judge, sample_size=6, seed=42, judge_repeats=4)
    assert result.meaning_noise is not None
    assert result.meaning_noise > 0  # meaning alternates 3/5 across repeats
    assert result.fluency_noise == pytest.approx(0.0)  # fluency is constant


def test_write_dump_prose_rows_all_fields_populated(tmp_path: Path) -> None:
    source = make_book(_prose_specs(40, chapters=4))
    translated = _translate(source)
    judge = Judge(ScriptedJudgeClient(meaning=4, fluency=3))
    result = score_book(source, translated, judge, sample_size=40, seed=42)
    dump_path = tmp_path / "dump.jsonl"
    n_rows = runner.write_dump(result, dump_path)
    assert n_rows == 40
    lines = dump_path.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 40
    for line in lines:
        row = json.loads(line)
        assert row["kind"] == "prose"
        for key in (
            "segment_id",
            "chapter_index",
            "chapter_title",
            "source",
            "target",
            "meaning",
            "fluency",
        ):
            assert key in row and row[key] not in (None, "", [])
        assert row["meaning"] == [4]
        assert row["fluency"] == [3]


def test_write_dump_judge_repeats_records_every_repeat(tmp_path: Path) -> None:
    source = make_book(_prose_specs(10))
    translated = _translate(source)
    judge = Judge(ScriptedJudgeClient(meaning=5, fluency=2))
    result = score_book(source, translated, judge, sample_size=10, seed=42, judge_repeats=3)
    dump_path = tmp_path / "dump.jsonl"
    n_rows = runner.write_dump(result, dump_path)
    assert n_rows == 10
    rows = [json.loads(line) for line in dump_path.read_text(encoding="utf-8").splitlines()]
    assert all(row["meaning"] == [5, 5, 5] for row in rows)
    assert all(row["fluency"] == [2, 2, 2] for row in rows)


def test_write_dump_screen_rows_are_distinguishable_from_prose_rows(tmp_path: Path) -> None:
    # PDF source triggers real T6 screening (score_extraction), unlike epub.
    specs = [(SegmentType.PARAGRAPH, f"Body {i} clean prose.", 0, None) for i in range(25)]
    source = make_book(specs, source_format="pdf")
    translated = _translate(source)
    judge = Judge(ScriptedJudgeClient(meaning=5, fluency=5, screen=1))
    result = score_book(source, translated, judge, sample_size=10, seed=42, judge_repeats=2)
    assert result.screen_samples  # PDF source produced screen samples
    dump_path = tmp_path / "dump.jsonl"
    runner.write_dump(result, dump_path)
    rows = [json.loads(line) for line in dump_path.read_text(encoding="utf-8").splitlines()]
    prose_rows = [r for r in rows if r["kind"] == "prose"]
    screen_rows = [r for r in rows if r["kind"] == "screen"]
    assert prose_rows and screen_rows
    for row in screen_rows:
        assert "source" not in row and "meaning" not in row and "fluency" not in row
        assert row["screen"] == [1, 1]  # judge_repeats=2, both clean
        for key in ("segment_id", "chapter_index", "chapter_title", "target"):
            assert key in row


def test_cli_eval_dump_writes_one_row_per_judged_sample(
    epub_builder, tmp_path, monkeypatch
) -> None:
    source, translated = _epub_pair(epub_builder)
    monkeypatch.setattr(
        "berilo.providers.create_client", _fake_client_factory(ScriptedJudgeClient())
    )
    dump_path = tmp_path / "dump.jsonl"
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "eval",
                str(translated),
                "--source",
                str(source),
                "--sample",
                "20",
                "--seed",
                "42",
                "--dump",
                str(dump_path),
                "--no-write",
            ],
        )
    assert result.exit_code == 0, result.output
    assert dump_path.exists()
    lines = dump_path.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 20
    assert all(json.loads(line)["kind"] == "prose" for line in lines)


def test_cli_eval_no_dump_flag_writes_no_file(epub_builder, tmp_path, monkeypatch) -> None:
    source, translated = _epub_pair(epub_builder)
    monkeypatch.setattr(
        "berilo.providers.create_client", _fake_client_factory(ScriptedJudgeClient())
    )
    would_be_dump = tmp_path / "should_not_exist.jsonl"
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "eval",
                str(translated),
                "--source",
                str(source),
                "--sample",
                "10",
                "--no-write",
            ],
        )
    assert result.exit_code == 0, result.output
    assert not would_be_dump.exists()


def test_cli_eval_judge_repeats_multiplies_judge_calls(epub_builder, tmp_path, monkeypatch) -> None:
    source, translated = _epub_pair(epub_builder)
    client = ScriptedJudgeClient(meaning=4, fluency=4)
    monkeypatch.setattr("berilo.providers.create_client", _fake_client_factory(client))
    dump_path = tmp_path / "dump.jsonl"
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "eval",
                str(translated),
                "--source",
                str(source),
                "--sample",
                "10",
                "--judge-repeats",
                "3",
                "--dump",
                str(dump_path),
                "--no-write",
            ],
        )
    assert result.exit_code == 0, result.output
    # 10 pairs x (meaning + fluency) x 3 repeats, epub source -> no T6 calls.
    assert len(client.calls) == 10 * 2 * 3
    rows = [json.loads(line) for line in dump_path.read_text(encoding="utf-8").splitlines()]
    assert all(len(row["meaning"]) == 3 for row in rows)
    assert all(len(row["fluency"]) == 3 for row in rows)
    assert "judge noise floor" in result.output


def test_cli_eval_dry_run_zero_calls_reports_repeat_multiplier(
    epub_builder, tmp_path, monkeypatch
) -> None:
    source, translated = _epub_pair(epub_builder)
    exploding = ExplodingClient()
    monkeypatch.setattr("berilo.providers.create_client", _fake_client_factory(exploding))
    dump_path = tmp_path / "should_not_exist.jsonl"
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "eval",
                str(translated),
                "--source",
                str(source),
                "--sample",
                "10",
                "--judge-repeats",
                "4",
                "--dump",
                str(dump_path),
                "--dry-run",
                "--no-write",
            ],
        )
    assert result.exit_code == 0, result.output
    assert "DRY RUN" in result.output
    # 10 pairs -> 20 base judge calls, x4 repeats -> 80; no calls actually made.
    assert "80" in result.output
    assert not dump_path.exists()  # dry-run writes no dump either


# --------------------------------------------------------------------------
# T7 actual-cost accounting (review finding 11).
# --------------------------------------------------------------------------


def test_read_cache_facts_charges_only_the_evaluated_model(tmp_path: Path) -> None:
    """T7's cost must not sum an earlier, more expensive model's spend.

    The glossary query always filtered on ``model``; the cost query on the same
    rows did not. A book translated first with an expensive model and re-run
    with ``gpt-5-mini`` therefore charged the mini run with both runs' spend and
    inflated the per-100k-word figure.
    """
    source = _book_with_notes(body_per_chapter=2)
    bhash = book_hash(source)
    db = tmp_path / "translations.db"

    expensive = CallRecord(kind="batch", input_tokens=100, output_tokens=100, cost_eur=5.0)
    cheap = CallRecord(kind="batch", input_tokens=100, output_tokens=100, cost_eur=0.25)
    rows = [SegmentTranslation(segment_hash="s1", text="prevod", cost_eur=0.0)]
    with TranslationCache(db) as cache:
        cache.store_batch(bhash, "gpt-5", "sl", rows, expensive, "baseline_v1")
        cache.store_batch(bhash, "gpt-5-mini", "sl", rows, cheap, "baseline_v1")

    _, cost = runner.read_cache_facts(db, source, "gpt-5-mini", "sl")
    assert cost == pytest.approx(0.25)

    _, expensive_cost = runner.read_cache_facts(db, source, "gpt-5", "sl")
    assert expensive_cost == pytest.approx(5.0)


def test_read_cache_facts_returns_the_glossary_for_the_evaluated_model(tmp_path: Path) -> None:
    """The glossary lookup stays model-scoped after the prompt-version change."""
    source = _book_with_notes(body_per_chapter=2)
    bhash = book_hash(source)
    db = tmp_path / "translations.db"
    with TranslationCache(db) as cache:
        cache.store_glossary(bhash, "gpt-5-mini", "sl", {"Kaplan": "Kaplan"})

    glossary, _ = runner.read_cache_facts(db, source, "gpt-5-mini", "sl")
    assert glossary == {"Kaplan": "Kaplan"}
    other, _ = runner.read_cache_facts(db, source, "gpt-5", "sl")
    assert other is None
