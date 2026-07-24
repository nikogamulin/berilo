"""Tests for the translate engine (S1.5): batching, glossary, cache, dry-run.

All tests are offline: no real LLM client is ever constructed. A
``FakeLLMClient`` (and small subclasses for the mismatch/kill scenarios) replays
deterministic responses and records the prompts it receives, so we can assert
segment integrity, cache resumability, glossary injection, and cost estimation
without spending a cent. Fixtures are local to this file; ``conftest.py`` is
untouched.
"""

from __future__ import annotations

import json
import re

import pytest
from click.testing import CliRunner

from berilo.cache import TranslationCache, book_hash, segment_hash
from berilo.cli import cli
from berilo.glossary import Glossary, build_glossary
from berilo.models import Book, Segment, SegmentType, make_segment_id
from berilo.providers.base import CompletionResult, LLMClient
from berilo.providers.pricing import cost_eur
from berilo.translate import (
    REASONING_TOKENS_PER_CALL,
    TranslationError,
    back_matter_segment_ids,
    estimate_cost,
    is_back_matter_title,
    parse_numbered_response,
    translate_book,
)

_MARKER_RE = re.compile(r"\[\[(\d+)\]\]")
_PREFIX = "SL::"


# --------------------------------------------------------------------------
# Fakes.
# --------------------------------------------------------------------------


def _extract_numbered(prompt: str) -> list[tuple[int, str]]:
    """Extract ``(n, text)`` pairs from a numbered ``[[n]] text`` block."""
    matches = list(_MARKER_RE.finditer(prompt))
    out: list[tuple[int, str]] = []
    for i, match in enumerate(matches):
        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(prompt)
        out.append((int(match.group(1)), prompt[start:end].strip()))
    return out


class FakeLLMClient(LLMClient):
    """Deterministic offline stand-in for a provider client.

    Batch calls (prompts containing ``[[n]]`` markers) are answered by prefixing
    each source segment with ``SL::``; glossary calls (system mentions
    "glossary", no markers) return a canned JSON map; single-segment fallback
    calls return the prefixed source. Every call and every batch prompt is
    recorded for assertions.
    """

    def __init__(self, *, model: str = "gpt-5-mini", glossary_terms: dict | None = None) -> None:
        self.model = model
        self.glossary_terms = dict(glossary_terms or {})
        self.calls: list[dict] = []
        self.batch_prompts: list[str] = []
        self.translation_calls = 0
        self.glossary_calls = 0

    def _kind(self, prompt: str | None, system: str | None) -> str:
        if prompt and _MARKER_RE.search(prompt):
            return "batch"
        if system and "glossary" in system.lower():
            return "glossary"
        return "single"

    def _result(self, text: str, out_tokens: int | None = None) -> CompletionResult:
        input_tokens = 100
        output_tokens = out_tokens if out_tokens is not None else max(1, len(text) // 4)
        return CompletionResult(
            text=text,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_eur=cost_eur(self.model, input_tokens, output_tokens),
            model=self.model,
        )

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        *,
        max_tokens: int | None = None,
        system: str | None = None,
    ) -> CompletionResult:
        kind = self._kind(prompt, system)
        self.calls.append({"prompt": prompt, "system": system, "kind": kind})
        if kind == "glossary":
            self.glossary_calls += 1
            return self._result(json.dumps(self.glossary_terms, ensure_ascii=False), 20)
        self.translation_calls += 1
        if kind == "batch":
            assert prompt is not None
            self.batch_prompts.append(prompt)
            return self._batch_response(prompt)
        assert prompt is not None
        return self._single_response(prompt)

    def _batch_response(self, prompt: str) -> CompletionResult:
        parts = [f"[[{n}]] {_PREFIX}{text}" for n, text in _extract_numbered(prompt)]
        return self._result("\n".join(parts))

    def _single_response(self, prompt: str) -> CompletionResult:
        source = prompt.strip().split("\n\n")[-1]
        return self._result(f"{_PREFIX}{source}")


class MismatchClient(FakeLLMClient):
    """Always drops one segment from batch replies (forces retry + fallback)."""

    def _batch_response(self, prompt: str) -> CompletionResult:
        pairs = _extract_numbered(prompt)
        if len(pairs) > 1:
            pairs = pairs[:-1]  # drop the last marker -> count mismatch
        parts = [f"[[{n}]] {_PREFIX}{text}" for n, text in pairs]
        return self._result("\n".join(parts))


class BrokenClient(MismatchClient):
    """Batches mismatch AND per-segment fallback returns empty -> loud failure."""

    def _single_response(self, prompt: str) -> CompletionResult:
        return self._result("")


class _KillSwitch(Exception):
    """Simulates the process dying between batches."""


class KillAfterBatchesClient(FakeLLMClient):
    """Serves ``kill_after`` batches, then raises before serving the next.

    The raising call is not recorded (it models a process killed before the
    request completed), so a resumed run's call count reflects only real work.
    """

    def __init__(self, *, kill_after: int, **kwargs) -> None:
        super().__init__(**kwargs)
        self.kill_after = kill_after
        self._served_batches = 0

    def complete(self, prompt=None, messages=None, *, max_tokens=None, system=None):
        if self._kind(prompt, system) == "batch":
            if self._served_batches >= self.kill_after:
                raise _KillSwitch("process killed mid-run")
            self._served_batches += 1
        return super().complete(prompt, messages, max_tokens=max_tokens, system=system)


# --------------------------------------------------------------------------
# Book fixtures.
# --------------------------------------------------------------------------


def _segment(
    text: str,
    chapter_index: int,
    position: int,
    chapter_title: str,
    *,
    seg_type: SegmentType = SegmentType.PARAGRAPH,
    heading_level: int | None = None,
) -> Segment:
    return Segment(
        id=make_segment_id(text, chapter_index, position),
        type=seg_type,
        text=text,
        chapter_index=chapter_index,
        chapter_title=chapter_title,
        position=position,
        heading_level=heading_level,
    )


def _book(segments: list[Segment], title: str = "Test Book") -> Book:
    return Book(
        title=title,
        authors=["Author"],
        language="en",
        source_path="/tmp/test.epub",
        source_format="epub",
        segments=segments,
    )


def _paragraph_book(count: int, *, chapter_index: int = 0, title: str = "Chapter One") -> Book:
    return _book([_segment(f"Paragraph {i}.", chapter_index, i, title) for i in range(count)])


def _memory_cache() -> TranslationCache:
    return TranslationCache(":memory:")


# --------------------------------------------------------------------------
# Segment integrity (1:1 mapping).
# --------------------------------------------------------------------------


def test_translate_book_preserves_segment_integrity() -> None:
    """Output has the same segments (count, ids, order, types, positions)."""
    segments = [
        _segment("Chapter One", 0, 0, "Chapter One", seg_type=SegmentType.HEADING, heading_level=1),
        _segment("First paragraph.", 0, 1, "Chapter One"),
        _segment("   ", 0, 2, "Chapter One"),  # empty -> passthrough
        _segment("Para with <em>emphasis</em>.", 0, 3, "Chapter One"),
        _segment("Second chapter text.", 1, 4, "Chapter Two"),
    ]
    book = _book(segments)
    client = FakeLLMClient()

    result = translate_book(book, client=client, target_lang="sl", cache=_memory_cache())

    assert len(result.segments) == len(book.segments)
    for original, translated in zip(book.segments, result.segments):
        assert translated.id == original.id
        assert translated.position == original.position
        assert translated.type is original.type
        assert translated.chapter_index == original.chapter_index
    # Empty segment passes through unchanged; others are translated.
    assert result.segments[2].text == "   "
    assert result.segments[1].text == f"{_PREFIX}First paragraph."
    # Inline tags survive the round trip.
    assert "<em>emphasis</em>" in result.segments[3].text


def test_translate_book_raises_if_segment_count_would_change() -> None:
    """The completeness invariant is a hard failure, not a silent drop."""
    # A malformed batch that is silently truncated would drop segments; the
    # engine's retry+fallback path prevents that. Here we assert every source
    # segment yields exactly one output segment even with a mismatching model.
    book = _paragraph_book(4)
    result = translate_book(
        book, client=MismatchClient(), target_lang="sl", cache=_memory_cache(), batch_size=4
    )
    assert len(result.segments) == 4
    assert all(seg.text.startswith(_PREFIX) for seg in result.segments)


# --------------------------------------------------------------------------
# Batch mismatch -> retry -> fallback -> loud failure.
# --------------------------------------------------------------------------


def test_batch_mismatch_retries_then_falls_back_per_segment() -> None:
    """A persistently mismatching batch falls back to per-segment translation."""
    book = _paragraph_book(3)
    client = MismatchClient()

    result = translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=3
    )

    assert all(seg.text == f"{_PREFIX}Paragraph {i}." for i, seg in enumerate(result.segments))
    # 2 batch attempts (initial + strict) + 3 per-segment fallback calls.
    assert client.translation_calls == 2 + 3


def test_persistent_failure_raises_translation_error_naming_segment() -> None:
    """A segment that cannot be translated raises a loud TranslationError."""
    book = _book([_segment("Alpha.", 0, 0, "Chapter One"), _segment("Beta.", 0, 1, "Chapter One")])

    with pytest.raises(TranslationError) as excinfo:
        translate_book(
            book, client=BrokenClient(), target_lang="sl", cache=_memory_cache(), batch_size=2
        )

    message = str(excinfo.value)
    assert "Test Book" in message
    assert "chapter 0" in message


def test_parse_numbered_response_rejects_bad_mappings() -> None:
    """The 1:1 parser rejects missing, extra, and empty segments."""
    assert parse_numbered_response("[[1]] a\n[[2]] b", 2) == ["a", "b"]
    with pytest.raises(ValueError):
        parse_numbered_response("[[1]] a", 2)  # missing
    with pytest.raises(ValueError):
        parse_numbered_response("[[1]] a\n[[2]] b\n[[3]] c", 2)  # extra
    with pytest.raises(ValueError):
        parse_numbered_response("[[1]] a\n[[2]]   ", 2)  # empty


# --------------------------------------------------------------------------
# Cache round-trip: second run = 0 API calls.
# --------------------------------------------------------------------------


def test_second_run_makes_zero_api_calls_and_identical_output() -> None:
    """A fully cached book re-translates with no calls and identical text."""
    book = _paragraph_book(12)
    cache = _memory_cache()

    first_client = FakeLLMClient(glossary_terms={"Alpha": "Alfa"})
    glossary = build_glossary(book, client=first_client, target_lang="sl", cache=cache)
    first = translate_book(
        book, client=first_client, target_lang="sl", cache=cache, glossary=glossary
    )
    assert first_client.translation_calls > 0
    assert first_client.glossary_calls == 1

    second_client = FakeLLMClient(glossary_terms={"Alpha": "Alfa"})
    glossary2 = build_glossary(book, client=second_client, target_lang="sl", cache=cache)
    second = translate_book(
        book, client=second_client, target_lang="sl", cache=cache, glossary=glossary2
    )

    assert second_client.calls == []  # zero API calls of any kind on the resume
    assert [s.text for s in second.segments] == [s.text for s in first.segments]


# --------------------------------------------------------------------------
# Kill at ~50% -> resume with no re-billed segments.
# --------------------------------------------------------------------------


def test_kill_midway_then_resume_rebills_nothing() -> None:
    """A run killed after the first batch resumes without re-sending it."""
    book = _paragraph_book(6)  # batch_size 3 -> exactly 2 batches
    cache = _memory_cache()
    bhash = book_hash(book)

    # Run 1: glossary + batch 1 served, batch 2 "kills" the process.
    run1 = KillAfterBatchesClient(kill_after=1)
    with pytest.raises(_KillSwitch):
        glossary = build_glossary(book, client=run1, target_lang="sl", cache=cache)
        translate_book(
            book, client=run1, target_lang="sl", cache=cache, glossary=glossary, batch_size=3
        )
    assert run1.translation_calls == 1  # only batch 1 served
    # Batch 1 is cached; batch 2 is not.
    assert (
        cache.get_translation(bhash, segment_hash("Paragraph 0."), "gpt-5-mini", "sl") is not None
    )
    assert cache.get_translation(bhash, segment_hash("Paragraph 4."), "gpt-5-mini", "sl") is None

    # Run 2: clean client, SAME cache -> only the remaining batch is sent.
    run2 = FakeLLMClient()
    glossary2 = build_glossary(book, client=run2, target_lang="sl", cache=cache)
    result = translate_book(
        book, client=run2, target_lang="sl", cache=cache, glossary=glossary2, batch_size=3
    )

    assert run2.glossary_calls == 0  # glossary was cached in run 1
    assert run2.translation_calls == 1  # only batch 2
    # Total translation calls across both runs == a single clean run's 2 batches.
    assert run1.translation_calls + run2.translation_calls == 2
    # Run 2's batch re-sent ONLY the uncached segments (4,5,6), never batch 1's.
    resent = {text for _, text in _extract_numbered(run2.batch_prompts[0])}
    assert resent == {"Paragraph 3.", "Paragraph 4.", "Paragraph 5."}
    # Every segment is translated in the end.
    assert all(seg.text.startswith(_PREFIX) for seg in result.segments)


# --------------------------------------------------------------------------
# Glossary.
# --------------------------------------------------------------------------


def test_glossary_injected_into_every_batch_prompt() -> None:
    """The glossary block appears in each batch prompt sent to the model."""
    book = _paragraph_book(6)
    glossary = Glossary(terms={"Ministry": "Ministrstvo", "Ivan": "Ivan"})
    client = FakeLLMClient()

    translate_book(
        book,
        client=client,
        target_lang="sl",
        cache=_memory_cache(),
        glossary=glossary,
        batch_size=3,
    )

    assert len(client.batch_prompts) == 2
    for prompt in client.batch_prompts:
        assert "Ministry -> Ministrstvo" in prompt
        assert "Ivan -> Ivan" in prompt


def test_build_glossary_is_cached_after_first_call() -> None:
    """Glossary extraction runs once; a second build hits the cache."""
    book = _paragraph_book(4)
    cache = _memory_cache()

    client1 = FakeLLMClient(glossary_terms={"Alpha": "Alfa"})
    g1 = build_glossary(book, client=client1, target_lang="sl", cache=cache)
    assert g1.terms == {"Alpha": "Alfa"}
    assert client1.glossary_calls == 1

    client2 = FakeLLMClient(glossary_terms={"Alpha": "Alfa"})
    g2 = build_glossary(book, client=client2, target_lang="sl", cache=cache)
    assert g2.terms == {"Alpha": "Alfa"}
    assert client2.glossary_calls == 0  # served from cache


# --------------------------------------------------------------------------
# Dry-run cost estimate (no API calls) + reasoning surcharge.
# --------------------------------------------------------------------------


def test_dry_run_estimate_makes_no_calls_and_is_nonzero() -> None:
    """estimate_cost needs no client and returns a positive, chapter-broken-down cost."""
    book = _book([_segment(f"Paragraph {i}.", i // 3, i, f"Chapter {i // 3}") for i in range(9)])
    estimate = estimate_cost(book, model="gpt-5-mini", target_lang="sl")

    assert estimate.cost_eur > 0
    assert estimate.input_tokens > 0
    assert estimate.output_tokens > 0
    assert len(estimate.chapters) == 3
    assert estimate.translatable_segments == 9


def test_estimate_includes_reasoning_surcharge_for_reasoning_models() -> None:
    """gpt-5-class estimates add a reasoning-token surcharge; others do not."""
    book = _paragraph_book(20)

    reasoning = estimate_cost(book, model="gpt-5-mini", target_lang="sl", batch_size=10)
    plain = estimate_cost(book, model="claude-haiku-4-5", target_lang="sl", batch_size=10)

    assert reasoning.reasoning_tokens > 0
    # 2 batch calls + 1 glossary call, each surcharged.
    assert reasoning.reasoning_tokens == 3 * REASONING_TOKENS_PER_CALL
    assert reasoning.output_tokens > reasoning.reasoning_tokens
    assert plain.reasoning_tokens == 0


# --------------------------------------------------------------------------
# Back matter.
# --------------------------------------------------------------------------


def test_is_back_matter_title() -> None:
    """Back-matter titles are recognised case-insensitively."""
    for title in ("Index", "NOTES", "Bibliography", "Acknowledgments", "About the Author"):
        assert is_back_matter_title(title)
    for title in ("Chapter One", "The Beginning", None, ""):
        assert not is_back_matter_title(title)


def test_skip_back_matter_passes_it_through_untranslated() -> None:
    """Skipped back matter stays in the output as untranslated source text."""
    segments = [
        _segment("Story para one.", 0, 0, "Chapter One"),
        _segment("Story para two.", 0, 1, "Chapter One"),
        _segment("Index entry A.", 1, 2, "Index"),
        _segment("Index entry B.", 1, 3, "Index"),
    ]
    book = _book(segments)
    skip = back_matter_segment_ids(book)
    assert len(skip) == 2

    client = FakeLLMClient()
    result = translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), skip_segment_ids=skip
    )

    # Integrity preserved: nothing dropped.
    assert len(result.segments) == 4
    # Story chapter translated; index passed through verbatim.
    assert result.segments[0].text.startswith(_PREFIX)
    assert result.segments[2].text == "Index entry A."
    assert result.segments[3].text == "Index entry B."
    # Only the story chapter's segments were ever sent to the model.
    sent = {text for prompt in client.batch_prompts for _, text in _extract_numbered(prompt)}
    assert sent == {"Story para one.", "Story para two."}


def test_skip_back_matter_estimate_excludes_it() -> None:
    """A --skip-back-matter dry run excludes back matter from the priced work."""
    segments = [
        _segment("Story para one.", 0, 0, "Chapter One"),
        _segment("Index entry A.", 1, 1, "Index"),
        _segment("Index entry B.", 1, 2, "Index"),
    ]
    book = _book(segments)
    skip = back_matter_segment_ids(book)

    estimate = estimate_cost(book, model="gpt-5-mini", target_lang="sl", skip_segment_ids=skip)
    assert estimate.skipped_segments == 2
    assert estimate.translatable_segments == 1
    assert [c.title for c in estimate.chapters] == ["Chapter One"]


# --------------------------------------------------------------------------
# CLI: dry run and skip-back-matter full run (client + assembler mocked).
# --------------------------------------------------------------------------


def _write_epub(runner_path, epub_builder):  # pragma: no cover - helper
    return epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "body": "<h1>Chapter One</h1><p>Hello there.</p>",
                "nav_title": "Chapter One",
            },
            {
                "id": "idx",
                "href": "idx.xhtml",
                "body": "<h1>Index</h1><p>Apples, 1</p>",
                "nav_title": "Index",
            },
        ]
    )


def test_cli_dry_run_makes_no_api_calls_and_exits_zero(epub_builder) -> None:
    """`translate --dry-run` prints an estimate, spends nothing, exits 0."""
    epub = _write_epub(None, epub_builder)
    runner = CliRunner()
    with runner.isolated_filesystem():  # keep find_dotenv from reading the repo .env
        result = runner.invoke(
            cli, ["translate", str(epub), "--dry-run", "--model", "gpt-5-mini", "--to", "sl"]
        )

    assert result.exit_code == 0, result.output
    assert "Dry run" in result.output
    assert "Estimated cost" in result.output


def test_cli_skip_back_matter_reports_and_passes_through(monkeypatch, epub_builder) -> None:
    """`translate --skip-back-matter` translates the body, passes back matter through."""
    import berilo.assemble as assemble_module
    import berilo.providers as providers_module

    captured: dict = {}

    def _fake_create_client(model, config):
        return FakeLLMClient(model=model)

    def _fake_build_epub(book, output_path, *, bilingual=False, source_book=None):
        captured["book"] = book
        return output_path

    monkeypatch.setattr(providers_module, "create_client", _fake_create_client)
    monkeypatch.setattr(assemble_module, "build_epub", _fake_build_epub, raising=False)

    epub = _write_epub(None, epub_builder)
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(
            cli,
            [
                "translate",
                str(epub),
                "--model",
                "gpt-5-mini",
                "--to",
                "sl",
                "--yes",
                "--skip-back-matter",
                "--no-glossary",
                "--cache-db",
                "cache.db",
            ],
        )

    assert result.exit_code == 0, result.output
    assert "UNTRANSLATED" in result.output
    book = captured["book"]
    index_segments = [s for s in book.segments if s.chapter_title == "Index"]
    assert index_segments
    assert all(not s.text.startswith(_PREFIX) for s in index_segments)
    story_segments = [s for s in book.segments if s.chapter_title == "Chapter One"]
    assert any(s.text.startswith(_PREFIX) for s in story_segments)
