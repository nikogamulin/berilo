"""Tests for the A/B prompt-variant harness (S1.11).

Everything here is offline: no real LLM client is constructed and no API budget
is spent. The fakes replay deterministic translations and judge verdicts and
record every call, so the tests can pin the four properties that make this
harness a *measurement instrument* rather than a demo:

* the variant is produced through the real ``translate_book`` production path,
  with production batching, rolling context, and the book's cached glossary;
* control and variant coexist in the cache without colliding, and a variant
  whose prompt version equals the control's is refused outright;
* every CI resamples whole contiguous RUNS, not segments — pinned against a
  synthetic case where the two answers disagree about significance;
* ``--dry-run`` spends nothing and makes zero calls.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from berilo import experiment
from berilo.cache import BASELINE_PROMPT_VERSION, TranslationCache, book_hash, segment_hash
from berilo.cli import cli
from berilo.eval import sampling
from berilo.eval.judge import Judge
from berilo.experiment import (
    DEFAULT_RUN_LENGTH,
    ExperimentError,
    JudgedPair,
    build_plan,
    build_scratch_book,
    candidate_runs,
    cluster_bootstrap_ci,
    format_plan,
    format_result,
    result_to_dict,
    run_experiment,
    select_runs,
    write_pairs_dump,
)
from berilo.glossary import Glossary
from berilo.models import Book, Segment, SegmentType, make_segment_id
from berilo.prompts import BASELINE, SL_STYLE, get_style
from berilo.providers.base import CompletionResult, LLMClient
from berilo.translate import DEFAULT_BATCH_SIZE, DEFAULT_CONTEXT_PAIRS

# --------------------------------------------------------------------------
# Builders and fakes.
# --------------------------------------------------------------------------

_MEANING_MARKER = "translation-quality judge"
_FLUENCY_MARKER = "native Slovenian editor"
_CONTROL_PREFIX = "CTRL::"
_VARIANT_PREFIX = "VAR::"


def _make_book(
    *,
    chapters: int = 2,
    paragraphs_per_chapter: int = 40,
    title: str = "Test Book",
    source_format: str = "epub",
    chapter_titles: list[str] | None = None,
) -> Book:
    """Build a synthetic source book of headed chapters of prose paragraphs."""
    segments: list[Segment] = []
    position = 0
    for chapter in range(chapters):
        chapter_title = chapter_titles[chapter] if chapter_titles else f"Chapter {chapter + 1}"
        heading = f"{chapter_title}"
        segments.append(
            Segment(
                id=make_segment_id(heading, chapter, position),
                type=SegmentType.HEADING,
                text=heading,
                chapter_index=chapter,
                chapter_title=chapter_title,
                position=position,
                heading_level=1,
            )
        )
        position += 1
        for index in range(paragraphs_per_chapter):
            text = f"Chapter {chapter} paragraph {index}. " + "Some source prose here. " * 4
            segments.append(
                Segment(
                    id=make_segment_id(text, chapter, position),
                    type=SegmentType.PARAGRAPH,
                    text=text,
                    chapter_index=chapter,
                    chapter_title=chapter_title,
                    position=position,
                )
            )
            position += 1
    return Book(
        title=title,
        authors=["Test Author"],
        language="en",
        source_path="synthetic.epub",
        source_format=source_format,
        segments=segments,
    )


def _control_book(source: Book, prefix: str = _CONTROL_PREFIX) -> Book:
    """Build the 'already translated' book: every segment prefixed."""
    import dataclasses

    return dataclasses.replace(
        source,
        segments=[dataclasses.replace(seg, text=prefix + seg.text) for seg in source.segments],
    )


class FakeTranslateClient(LLMClient):
    """Deterministic translator: answers numbered batches with ``VAR::`` prefixes.

    Records every batch prompt so tests can assert the glossary block, the
    rolling-context block, and the batch size the production path really used.
    """

    def __init__(self, *, model: str = "gpt-5-mini", prefix: str = _VARIANT_PREFIX) -> None:
        self.model = model
        self.prefix = prefix
        self.batch_prompts: list[str] = []
        self.batch_sizes: list[int] = []
        self.other_prompts: list[str] = []

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        **kwargs: object,
    ) -> CompletionResult:
        text = prompt or ""
        entries = experiment_markers(text)
        if entries:
            self.batch_prompts.append(text)
            self.batch_sizes.append(len(entries))
            reply = "\n".join(f"[[{n}]] {self.prefix}{body}" for n, body in entries)
        else:
            self.other_prompts.append(text)
            reply = "A memo about the book's register and voice."
        return CompletionResult(
            text=reply,
            input_tokens=100,
            output_tokens=50,
            cost_eur=0.001,
            model=self.model,
        )


def experiment_markers(prompt: str) -> list[tuple[int, str]]:
    """Extract ``(n, text)`` pairs from a numbered ``[[n]] text`` prompt block."""
    import re

    matches = list(re.finditer(r"\[\[(\d+)\]\]", prompt))
    out: list[tuple[int, str]] = []
    for i, match in enumerate(matches):
        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(prompt)
        out.append((int(match.group(1)), prompt[start:end].strip()))
    return out


class ArmAwareJudgeClient(LLMClient):
    """Judge that scores by which ARM the judged text came from.

    Detects the arm from the ``CTRL::``/``VAR::`` prefix baked into the fake
    translations, so a test can dial in a known effect size and check that the
    harness recovers it as a paired delta.
    """

    def __init__(
        self,
        *,
        control_meaning: int = 4,
        variant_meaning: int = 4,
        control_fluency: int = 3,
        variant_fluency: int = 4,
        cost_per_call: float = 0.0002,
    ) -> None:
        self.model = "gpt-5-mini"
        self.scores = {
            ("meaning", "control"): control_meaning,
            ("meaning", "variant"): variant_meaning,
            ("fluency", "control"): control_fluency,
            ("fluency", "variant"): variant_fluency,
        }
        self.cost_per_call = cost_per_call
        self.calls: list[tuple[str, str]] = []

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        **kwargs: object,
    ) -> CompletionResult:
        text = prompt or ""
        role = "meaning" if _MEANING_MARKER in text else "fluency"
        # The judged text is the target; only the target carries an arm prefix
        # in the fluency prompt, and the meaning prompt carries both — the
        # variant prefix wins because the source never carries one.
        arm = "variant" if _VARIANT_PREFIX in text else "control"
        self.calls.append((role, arm))
        return CompletionResult(
            text=f"SCORE: {self.scores[(role, arm)]}",
            input_tokens=10,
            output_tokens=3,
            cost_eur=self.cost_per_call,
            model=self.model,
        )


class RunCorrelatedJudgeClient(LLMClient):
    """Judge whose variant verdict is constant within a run but varies between runs.

    Real batching induces exactly this correlation — one prompt, one context,
    one batch — so it is the structure the cluster bootstrap exists to respect.
    The run is recovered from the paragraph number baked into the fixture text,
    which is monotone within a contiguous run.
    """

    #: Fixture runs are cut every ``RUN_LENGTH + context_pairs`` paragraphs.
    BUCKET = DEFAULT_RUN_LENGTH + DEFAULT_CONTEXT_PAIRS

    def __init__(self, *, control_fluency: int = 3) -> None:
        self.model = "gpt-5-mini"
        self.control_fluency = control_fluency
        self.calls: list[tuple[str, str]] = []

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        **kwargs: object,
    ) -> CompletionResult:
        import re

        text = prompt or ""
        role = "meaning" if _MEANING_MARKER in text else "fluency"
        arm = "variant" if _VARIANT_PREFIX in text else "control"
        self.calls.append((role, arm))
        if role == "meaning":
            score = 4
        elif arm == "control":
            score = self.control_fluency
        else:
            match = re.search(r"paragraph (\d+)", text)
            index = int(match.group(1)) if match else 0
            score = 5 if (index // self.BUCKET) % 2 == 0 else 2
        return CompletionResult(
            text=f"SCORE: {score}",
            input_tokens=10,
            output_tokens=3,
            cost_eur=0.0002,
            model=self.model,
        )


@pytest.fixture
def scratch_cache(tmp_path: Path):
    """A throwaway on-disk cache for one test."""
    cache = TranslationCache(tmp_path / "scratch.db")
    yield cache
    cache.close()


def _plan(source: Book, translated: Book, **overrides):
    """Build a plan with test-friendly defaults."""
    kwargs = {
        "style": SL_STYLE,
        "control_version": BASELINE_PROMPT_VERSION,
        "model": "gpt-5-mini",
        "judge_model": "gpt-5-mini",
        "target_lang": "sl",
        "glossary": Glossary(terms={"Ljubljana": "Ljubljana"}),
        "runs": 3,
        "run_length": DEFAULT_RUN_LENGTH,
        "seed": 42,
    }
    kwargs.update(overrides)
    return build_plan(source, translated, **kwargs)


# --------------------------------------------------------------------------
# Run selection: contiguous, body-prose, non-overlapping, seeded.
# --------------------------------------------------------------------------


def test_runs_are_contiguous_same_chapter_prose():
    source = _make_book()
    pool = candidate_runs(source, _control_book(source), run_length=10)
    assert pool
    for run in pool:
        positions = [source.segments.index(seg) for seg in run.segments]
        assert positions == list(range(positions[0], positions[0] + 10)), "run is not contiguous"
        assert len({seg.chapter_index for seg in run.segments}) == 1
        assert all(seg.type == SegmentType.PARAGRAPH for seg in run.segments)


def test_runs_do_not_overlap_each_other_or_their_lead_ins():
    source = _make_book()
    pool = candidate_runs(source, _control_book(source), run_length=10, context_pairs=2)
    judged_ids = [seg.id for run in pool for seg in run.segments]
    lead_ids = [seg.id for run in pool for seg, _ in run.lead_in]
    assert len(judged_ids) == len(set(judged_ids)), "a segment appears in two runs"
    assert set(judged_ids).isdisjoint(lead_ids), "a judged segment is another run's lead-in"


def test_runs_are_primed_with_real_predecessors():
    source = _make_book()
    pool = candidate_runs(source, _control_book(source), run_length=10, context_pairs=2)
    # Every run except a block-opening one gets the full lead-in depth.
    with_lead_in = [run for run in pool if run.lead_in]
    assert with_lead_in
    for run in with_lead_in:
        predecessor = run.lead_in[-1][0]
        assert source.segments.index(predecessor) == run.start_position - 1
        assert run.lead_in[-1][1] == _CONTROL_PREFIX + predecessor.text


def test_front_and_back_matter_chapters_are_excluded_from_the_pool():
    source = _make_book(chapters=3, chapter_titles=["Copyright", "Chapter One", "Index"])
    plan = _plan(source, _control_book(source), runs=2)
    assert plan.runs, "expected body-prose runs"
    assert {run.chapter_index for run in plan.runs} == {1}


def test_run_selection_is_seeded_and_reproducible():
    source = _make_book()
    pool = candidate_runs(source, _control_book(source), run_length=10)
    assert [r.start_position for r in select_runs(pool, 3, 42)] == [
        r.start_position for r in select_runs(pool, 3, 42)
    ]
    assert [r.start_position for r in select_runs(pool, 3, 42)] != [
        r.start_position for r in select_runs(pool, 3, 7)
    ]


def test_a_book_with_no_long_enough_run_fails_loudly():
    source = _make_book(chapters=1, paragraphs_per_chapter=3)
    with pytest.raises(ExperimentError, match="No contiguous run"):
        _plan(source, _control_book(source), run_length=10)


# --------------------------------------------------------------------------
# Cluster bootstrap — the statistical core.
# --------------------------------------------------------------------------


def test_cluster_bootstrap_is_wider_than_a_segment_bootstrap_and_flips_the_verdict():
    """A segment-level bootstrap manufactures significance the cluster one refuses.

    Four runs of ten perfectly-correlated segments: three runs at +1, one at -1.
    There are really four independent draws, so the effect is a coin flip. A
    segment-level resample sees forty and calls it significant.
    """
    clusters = [[1.0] * 10, [1.0] * 10, [1.0] * 10, [-1.0] * 10]
    flat = [value for cluster in clusters for value in cluster]

    cluster_ci = cluster_bootstrap_ci(clusters, seed=42)
    segment_ci = sampling.bootstrap_ci(
        lambda idx: sampling.mean([flat[i] for i in idx]), len(flat), seed=42
    )

    assert segment_ci[0] > 0, "sanity: the naive bootstrap calls this significant"
    assert cluster_ci[0] <= 0, "cluster bootstrap must not call 3-of-4 runs significant"
    cluster_width = cluster_ci[1] - cluster_ci[0]
    segment_width = segment_ci[1] - segment_ci[0]
    assert cluster_width > 2 * segment_width


def test_cluster_bootstrap_is_seeded_and_brackets_the_mean():
    clusters = [[0.5, 1.0], [0.0, 0.5], [1.0, 1.5]]
    first = cluster_bootstrap_ci(clusters, seed=11, n_resamples=500)
    assert first == cluster_bootstrap_ci(clusters, seed=11, n_resamples=500)
    mean = sampling.mean([v for c in clusters for v in c])
    assert first[0] <= mean <= first[1]


def test_cluster_bootstrap_handles_an_empty_input():
    assert cluster_bootstrap_ci([]) == (0.0, 0.0)
    assert cluster_bootstrap_ci([[], []]) == (0.0, 0.0)


def test_reported_ci_is_the_cluster_bootstrap_not_the_segment_bootstrap(scratch_cache):
    """End-to-end: the reported CI is a run-level resample, and it is wider for it.

    The judge here is *run-correlated* — every segment of a run scores alike but
    runs differ — which is exactly the structure real batching induces. Under
    that structure a segment-level bootstrap of the same deltas is strictly
    narrower, so this fails if the harness ever resamples segments.
    """
    source = _make_book()
    translated = _control_book(source)
    plan = _plan(source, translated, runs=3)
    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(RunCorrelatedJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=Glossary(terms={}),
    )
    fluency = next(d for d in result.deltas if d.key == "T3")
    clusters: list[list[float]] = [[] for _ in plan.runs]
    for pair in result.pairs:
        clusters[pair.run_index].append(float(pair.variant_fluency - pair.control_fluency))
    flat = [value for cluster in clusters for value in cluster]
    assert len({tuple(cluster) for cluster in clusters}) > 1, "fixture must vary across runs"

    assert fluency.ci == cluster_bootstrap_ci(clusters, seed=plan.seed)
    assert fluency.clusters == len(plan.runs)

    segment_ci = sampling.bootstrap_ci(
        lambda idx: sampling.mean([flat[i] for i in idx]), len(flat), seed=plan.seed
    )
    assert (fluency.ci[1] - fluency.ci[0]) > (segment_ci[1] - segment_ci[0])


# --------------------------------------------------------------------------
# Production path.
# --------------------------------------------------------------------------


def test_harness_calls_translate_book_with_production_parameters(scratch_cache, monkeypatch):
    """The variant must come from translate_book, not a bespoke one-shot call."""
    source = _make_book()
    translated = _control_book(source)
    glossary = Glossary(terms={"Ljubljana": "Ljubljana"})
    plan = _plan(source, translated, glossary=glossary)

    recorded: list[dict] = []
    real = experiment.translate_book

    def _spy(book, **kwargs):
        recorded.append(kwargs)
        return real(book, **kwargs)

    monkeypatch.setattr(experiment, "translate_book", _spy)
    run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=glossary,
    )

    assert len(recorded) == 1, "the production path must be entered exactly once"
    kwargs = recorded[0]
    assert kwargs["batch_size"] == DEFAULT_BATCH_SIZE
    assert kwargs["context_pairs"] == DEFAULT_CONTEXT_PAIRS
    assert kwargs["style"] is SL_STYLE
    assert kwargs["glossary"] is glossary
    assert kwargs["cache"] is scratch_cache


def test_variant_batches_carry_the_glossary_and_real_rolling_context(scratch_cache):
    """Substantive proof of production conditions, not just the function name."""
    source = _make_book()
    translated = _control_book(source)
    client = FakeTranslateClient()
    plan = _plan(source, translated, runs=3)
    run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=client,
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=Glossary(terms={"Ljubljana": "Ljubljana"}),
    )

    assert client.batch_sizes == [DEFAULT_RUN_LENGTH] * 3, "one production-shaped batch per run"
    for prompt in client.batch_prompts:
        assert "GLOSSARY" in prompt
        assert "CONTEXT (already translated" in prompt
        assert _CONTROL_PREFIX in prompt, "rolling context must be real prior translation"


def test_lead_in_segments_are_context_only_and_never_judged(scratch_cache):
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=2)
    lead_ids = {seg.id for run in plan.runs for seg, _ in run.lead_in}
    assert lead_ids, "the fixture should produce lead-ins"

    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    judged_ids = {pair.segment_id for pair in result.pairs}
    assert judged_ids.isdisjoint(lead_ids)
    assert len(result.pairs) == plan.judged_segments


def test_scratch_book_orders_lead_ins_before_their_run():
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=2)
    scratch = build_scratch_book(source, plan.runs)
    ids = [seg.id for seg in scratch.segments]
    for run in plan.runs:
        first_judged = ids.index(run.segments[0].id)
        for offset, (segment, _) in enumerate(reversed(run.lead_in), start=1):
            assert ids[first_judged - offset] == segment.id


# --------------------------------------------------------------------------
# Cache separation.
# --------------------------------------------------------------------------


def test_control_and_variant_coexist_in_the_cache_without_colliding(scratch_cache):
    source = _make_book()
    translated = _control_book(source)
    plan = _plan(source, translated, runs=2)
    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    scratch_hash = book_hash(build_scratch_book(source, plan.runs))
    for run in plan.runs:
        for segment in run.segments:
            shash = segment_hash(segment.text)
            control = scratch_cache.get_translation(
                scratch_hash, shash, "gpt-5-mini", "sl", BASELINE_PROMPT_VERSION
            )
            variant = scratch_cache.get_translation(
                scratch_hash, shash, "gpt-5-mini", "sl", SL_STYLE.version
            )
            assert control == _CONTROL_PREFIX + segment.text
            assert variant == _VARIANT_PREFIX + segment.text.strip()
            assert control != variant
    assert all(pair.control != pair.variant for pair in result.pairs)


def test_a_variant_whose_version_equals_the_control_is_refused():
    source = _make_book()
    with pytest.raises(ExperimentError, match="indistinguishable"):
        _plan(source, _control_book(source), style=BASELINE, control_version=BASELINE.version)


def test_the_experiment_never_touches_the_production_cache(tmp_path):
    """The production cache is read for the glossary and never written."""
    source = _make_book()
    production = tmp_path / "production.db"
    with TranslationCache(production) as cache:
        cache.store_glossary(book_hash(source), "gpt-5-mini", "sl", {"Ljubljana": "Ljubljana"})
    before = production.read_bytes()

    with TranslationCache(tmp_path / "scratch.db") as scratch:
        plan = _plan(source, _control_book(source), runs=2)
        run_experiment(
            source,
            plan=plan,
            style=SL_STYLE,
            client=FakeTranslateClient(),
            judge=Judge(ArmAwareJudgeClient()),
            scratch_cache=scratch,
            glossary=Glossary(terms={"Ljubljana": "Ljubljana"}),
        )
    assert production.read_bytes() == before


def test_rerunning_the_same_variant_rebills_nothing(scratch_cache):
    source = _make_book()
    translated = _control_book(source)
    plan = _plan(source, translated, runs=2)
    kwargs = dict(
        style=SL_STYLE,
        scratch_cache=scratch_cache,
        glossary=None,
    )
    first = run_experiment(
        source,
        plan=plan,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        **kwargs,
    )
    second_client = FakeTranslateClient()
    second = run_experiment(
        source,
        plan=plan,
        client=second_client,
        judge=Judge(ArmAwareJudgeClient()),
        **kwargs,
    )
    assert first.translation_cost_eur > 0
    assert second.translation_cost_eur == 0.0
    assert second_client.batch_prompts == []
    assert [p.variant for p in second.pairs] == [p.variant for p in first.pairs]


# --------------------------------------------------------------------------
# Paired deltas and reporting.
# --------------------------------------------------------------------------


def test_paired_delta_is_computed_over_matched_pairs(scratch_cache):
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=3)
    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(
            ArmAwareJudgeClient(
                control_meaning=4, variant_meaning=4, control_fluency=3, variant_fluency=4
            )
        ),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    by_key = {delta.key: delta for delta in result.deltas}

    assert len(result.pairs) == 30
    for pair in result.pairs:
        assert pair.source and pair.control and pair.variant
        assert pair.control.startswith(_CONTROL_PREFIX)
        assert pair.variant.startswith(_VARIANT_PREFIX)

    assert by_key["T3"].control_mean == pytest.approx(3.0)
    assert by_key["T3"].variant_mean == pytest.approx(4.0)
    assert by_key["T3"].delta == pytest.approx(1.0)
    assert by_key["T3"].verdict == "win"
    # A constant +1 on the 1-5 scale is +1/5 of T3's 20 rubric points.
    assert by_key["T3"].points_delta == pytest.approx(4.0)

    assert by_key["T2"].delta == pytest.approx(0.0)
    assert by_key["T2"].verdict == "inconclusive"


def test_a_regression_is_reported_as_a_regression(scratch_cache):
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=3)
    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient(control_fluency=4, variant_fluency=2)),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    fluency = next(d for d in result.deltas if d.key == "T3")
    assert fluency.delta == pytest.approx(-2.0)
    assert fluency.verdict == "regression"
    assert fluency.ci[1] < 0


def test_judge_call_count_and_cost_are_reported(scratch_cache):
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=2)
    judge_client = ArmAwareJudgeClient(cost_per_call=0.0002)
    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(judge_client),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    expected_calls = plan.judged_segments * experiment.JUDGE_CALLS_PER_SEGMENT
    assert len(judge_client.calls) == expected_calls == plan.judge_calls
    assert result.judge_cost_eur == pytest.approx(expected_calls * 0.0002)
    assert result.total_cost_eur == pytest.approx(
        result.judge_cost_eur + result.translation_cost_eur
    )
    assert result.eur_per_1k_words > 0
    assert result.eur_per_100k_words == pytest.approx(result.eur_per_1k_words * 100)


def test_report_names_the_resampling_unit_and_the_win_rule(scratch_cache):
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=2)
    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    report = format_result(result)
    assert "contiguous RUN" in report
    assert "CI lower bound is above zero" in report
    assert "/1k source words" in report

    payload = result_to_dict(result)
    assert payload["resampling_unit"] == "run"
    assert set(payload["deltas"]) == {"T2", "T3"}
    assert payload["cost_eur"]["total"] == pytest.approx(result.total_cost_eur)


def test_a_low_cluster_count_is_flagged_in_both_the_plan_and_the_report(scratch_cache):
    """A coarse CI must never read as a clean 'no effect'."""
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=3)
    assert len(plan.runs) < experiment.MIN_INFORMATIVE_CLUSTERS
    assert "little power" in format_plan(plan)

    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    assert "not measured" in format_result(result)
    assert result_to_dict(result)["low_power"] is True


def test_an_adequate_cluster_count_is_not_flagged(scratch_cache):
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=experiment.MIN_INFORMATIVE_CLUSTERS)
    assert len(plan.runs) == experiment.MIN_INFORMATIVE_CLUSTERS
    assert "little power" not in format_plan(plan)

    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    assert "not measured" not in format_result(result)
    assert result_to_dict(result)["low_power"] is False


def test_pairs_dump_round_trips(scratch_cache, tmp_path):
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=2)
    result = run_experiment(
        source,
        plan=plan,
        style=SL_STYLE,
        client=FakeTranslateClient(),
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    dump = tmp_path / "pairs.jsonl"
    assert write_pairs_dump(result, dump) == len(result.pairs)
    rows = [json.loads(line) for line in dump.read_text(encoding="utf-8").splitlines()]
    assert len(rows) == len(result.pairs)
    assert set(rows[0]) == {field for field in JudgedPair.__dataclass_fields__}


def test_missing_variant_translation_refuses_to_report(scratch_cache, monkeypatch):
    """Segment integrity: a dropped segment must never become a silent delta."""
    import dataclasses

    source = _make_book()
    plan = _plan(source, _control_book(source), runs=2)
    real = experiment.translate_book

    def _dropping(book, **kwargs):
        translated = real(book, **kwargs)
        return dataclasses.replace(translated, segments=translated.segments[:-1])

    monkeypatch.setattr(experiment, "translate_book", _dropping)
    with pytest.raises(ExperimentError, match="1:1"):
        run_experiment(
            source,
            plan=plan,
            style=SL_STYLE,
            client=FakeTranslateClient(),
            judge=Judge(ArmAwareJudgeClient()),
            scratch_cache=scratch_cache,
            glossary=None,
        )


# --------------------------------------------------------------------------
# Book-context styles.
# --------------------------------------------------------------------------


def test_book_context_memo_is_derived_from_the_whole_book_not_the_sample(scratch_cache):
    """A memo computed from a random mid-book slice would mis-measure the variant."""
    source = _make_book()
    plan = _plan(source, _control_book(source), style=get_style("book_context_v1"), runs=2)
    client = FakeTranslateClient()
    run_experiment(
        source,
        plan=plan,
        style=get_style("book_context_v1"),
        client=client,
        judge=Judge(ArmAwareJudgeClient()),
        scratch_cache=scratch_cache,
        glossary=None,
    )
    assert len(client.other_prompts) == 1, "exactly one style-memo call"
    memo_prompt = client.other_prompts[0]
    assert source.segments[0].text in memo_prompt, "memo must see the book's opening"
    assert all("BOOK STYLE MEMO" in prompt for prompt in client.batch_prompts)


# --------------------------------------------------------------------------
# Planning and the CLI (dry run spends nothing).
# --------------------------------------------------------------------------


def test_plan_prices_the_run_without_any_call():
    source = _make_book()
    plan = _plan(source, _control_book(source), runs=3)
    assert plan.judged_segments == 30
    assert plan.judge_calls == 120
    assert plan.estimated_judge_cost_eur > 0
    assert plan.estimated_translation_cost_eur > 0
    assert plan.estimated_cost_eur == pytest.approx(
        plan.estimated_translation_cost_eur + plan.estimated_judge_cost_eur
    )
    text = format_plan(plan)
    assert "DRY RUN" in text
    assert "no cost" in text
    assert "cluster-bootstrapped over 3 runs, not 30 segments" in text


def test_cli_dry_run_spends_nothing_and_makes_no_call(tmp_path, epub_builder, monkeypatch):
    source_path, translated_path = _write_epub_pair(tmp_path, epub_builder)

    def _explode(*args, **kwargs):
        raise AssertionError("--dry-run must not construct a provider client")

    monkeypatch.setattr("berilo.providers.create_client", _explode)

    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "ab",
                str(translated_path),
                "--source",
                str(source_path),
                "--cache-db",
                str(tmp_path / "production.db"),
                "--variant",
                "sl_style_v1",
                "--runs",
                "2",
                "--dry-run",
            ],
        )
    assert result.exit_code == 0, result.output
    assert "DRY RUN" in result.output
    assert "judge calls" in result.output.lower()
    assert "Estimated cost" in result.output


def test_cli_rejects_an_unknown_variant(tmp_path, epub_builder):
    source_path, translated_path = _write_epub_pair(tmp_path, epub_builder)
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "ab",
                str(translated_path),
                "--source",
                str(source_path),
                "--cache-db",
                str(tmp_path / "production.db"),
                "--variant",
                "no_such_style",
                "--dry-run",
            ],
        )
    assert result.exit_code == 1
    assert "unknown translation style" in result.output


def test_cli_rejects_a_variant_that_collides_with_the_control(tmp_path, epub_builder):
    source_path, translated_path = _write_epub_pair(tmp_path, epub_builder)
    runner_ = CliRunner()
    with runner_.isolated_filesystem():
        result = runner_.invoke(
            cli,
            [
                "ab",
                str(translated_path),
                "--source",
                str(source_path),
                "--cache-db",
                str(tmp_path / "production.db"),
                "--variant",
                "baseline_v1",
                "--dry-run",
            ],
        )
    assert result.exit_code == 1
    assert "indistinguishable" in result.output


def _write_epub_pair(tmp_path: Path, epub_builder) -> tuple[Path, Path]:
    """Write a synthetic source EPUB and its 'translated' counterpart."""
    paragraphs = [
        f"<p>Chapter one paragraph {i}. This is some ordinary body prose text.</p>"
        for i in range(30)
    ]
    body = "<h1>Chapter One</h1>" + "".join(paragraphs)
    source_path = epub_builder(
        [{"id": "c1", "href": "c1.xhtml", "body": body, "nav_title": "Chapter One"}]
    )
    translated_body = "<h1>Poglavje ena</h1>" + "".join(
        p.replace("<p>", f"<p>{_CONTROL_PREFIX}") for p in paragraphs
    )
    translated_path = epub_builder(
        [{"id": "c1", "href": "c1.xhtml", "body": translated_body, "nav_title": "Poglavje ena"}],
        language="sl",
    )
    return source_path, translated_path
