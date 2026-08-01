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

from berilo.cache import CallRecord, SegmentTranslation, TranslationCache, book_hash, segment_hash
from berilo.cli import cli
from berilo.glossary import Glossary, build_glossary
from berilo.models import Book, ImageResource, Segment, SegmentType, make_segment_id
from berilo.prompts import BASELINE, get_style, style_names
from berilo.providers.base import (
    CompletionResult,
    LLMClient,
    TruncatedCompletionError,
)
from berilo.providers.pricing import cost_eur
from berilo.translate import (
    REASONING_TOKENS_PER_CALL,
    TranslationError,
    TranslationStats,
    back_matter_segment_ids,
    estimate_cost,
    is_back_matter_title,
    parse_numbered_response,
    translate_book,
)

_MARKER_RE = re.compile(r"\[\[(\d+)\]\]")
_PREFIX = "SL::"
_REVISED_PREFIX = "ED::"
FAKE_ANTHROPIC_KEY = "test-anthropic-key-not-a-real-secret-0000000000"

#: System prompts that identify an extra pass, taken from the registry itself
#: so the fakes classify calls exactly rather than by guessing at wording.
_REVISE_SYSTEMS = {
    text
    for name in style_names()
    for text in (get_style(name).revise_system, get_style(name).revise_strict_system)
    if text is not None
}
_BOOK_CONTEXT_SYSTEMS = {
    get_style(name).book_context_system
    for name in style_names()
    if get_style(name).book_context_system is not None
}


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


def _draft_of(revise_body: str) -> str:
    """Pull the DRAFT line out of one ``SOURCE:``/``DRAFT:`` revise-prompt entry."""
    _, _, draft = revise_body.partition("DRAFT: ")
    return draft.strip()


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
        self.revise_prompts: list[str] = []
        self.translation_calls = 0
        self.glossary_calls = 0
        self.revise_calls = 0
        self.book_context_calls = 0

    def _kind(self, prompt: str | None, system: str | None) -> str:
        if system in _REVISE_SYSTEMS:
            return "revise"
        if system in _BOOK_CONTEXT_SYSTEMS:
            return "book_context"
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
        if kind == "book_context":
            self.book_context_calls += 1
            return self._result(self._book_context_response())
        if kind == "revise":
            assert prompt is not None
            self.revise_calls += 1
            self.revise_prompts.append(prompt)
            return self._revise_response(prompt)
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

    def _book_context_response(self) -> str:
        return "Reportorial nonfiction; short declarative sentences; dry irony."

    def _revise_response(self, prompt: str) -> CompletionResult:
        parts = [
            f"[[{n}]] {_REVISED_PREFIX}{_draft_of(body)}" for n, body in _extract_numbered(prompt)
        ]
        return self._result("\n".join(parts))


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


class _EmptyBookContextClient(FakeLLMClient):
    """Book-context call returns an empty memo; everything else is normal."""

    def _book_context_response(self) -> str:
        return ""


class _TruncatingBookContextClient(FakeLLMClient):
    """The book-context call is billed but truncated; everything else is normal.

    Same regression class as ``_TruncatingClient``/``_ReviseTruncatingClient``:
    the once-per-book memo call has no smaller unit to retry into, but — like
    a genuinely blank memo reply — it is a best-effort pass, not a
    correctness requirement, so it must degrade to "no memo" rather than
    aborting the whole run.
    """

    def complete(self, prompt=None, messages=None, *, max_tokens=None, system=None):
        if self._kind(prompt, system) == "book_context":
            raise TruncatedCompletionError(
                "truncated mid-generation",
                result=CompletionResult(
                    text="",
                    input_tokens=50,
                    output_tokens=999,
                    cost_eur=cost_eur(self.model, 50, 999),
                    model=self.model,
                ),
            )
        return super().complete(prompt, messages, max_tokens=max_tokens, system=system)


class _TruncatingClient(FakeLLMClient):
    """Raises TruncatedCompletionError on the first ``fail_batch_calls``
    batch-level ([[n]] numbered prompt) calls, then answers normally.

    Optionally also truncates every per-segment fallback call
    (``fail_single``), to drive the ladder's very last rung. Mirrors
    ``MismatchClient``'s shape but for review finding 7's guard (a provider
    billing a call while returning no usable text) rather than a bad mapping.
    """

    def __init__(self, *, fail_batch_calls: int = 0, fail_single: bool = False, **kwargs) -> None:
        super().__init__(**kwargs)
        self.fail_batch_calls = fail_batch_calls
        self.fail_single = fail_single
        self._batch_attempts = 0

    def _truncated(self) -> TruncatedCompletionError:
        return TruncatedCompletionError(
            "truncated mid-generation",
            result=CompletionResult(
                text="",
                input_tokens=50,
                output_tokens=999,
                cost_eur=cost_eur(self.model, 50, 999),
                model=self.model,
            ),
        )

    def complete(self, prompt=None, messages=None, *, max_tokens=None, system=None):
        kind = self._kind(prompt, system)
        if kind == "batch":
            self._batch_attempts += 1
            if self._batch_attempts <= self.fail_batch_calls:
                raise self._truncated()
        elif kind == "single" and self.fail_single:
            raise self._truncated()
        return super().complete(prompt, messages, max_tokens=max_tokens, system=system)


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


def test_translate_book_carries_images_through_untouched() -> None:
    """Images are resources: never translated, never dropped, never re-keyed."""
    book = _paragraph_book(3)
    book.images = [
        ImageResource(
            id="img0001",
            media_type="image/png",
            data=b"\x89PNG not-a-real-image",
            source_href="OEBPS/img/figure1.png",
            chapter_index=0,
            anchor_segment_id=book.segments[1].id,
            alt="A figure",
        )
    ]
    before = book_hash(book)

    result = translate_book(book, client=FakeLLMClient(), target_lang="sl", cache=_memory_cache())

    assert result.images == book.images
    assert book_hash(result) == before


class _PolicyRefusingClient(FakeLLMClient):
    """Fake primary client that refuses every translation call on policy."""

    def complete(self, prompt=None, messages=None, **kwargs):  # type: ignore[override]
        from berilo.providers.base import ContentPolicyError

        if prompt and _MARKER_RE.search(prompt):
            raise ContentPolicyError("flagged")
        return super().complete(prompt=prompt, messages=messages, **kwargs)


def test_content_policy_refusal_routes_batch_to_fallback_client() -> None:
    """A policy-refused batch is retried via the fallback with 1:1 integrity."""
    book = _paragraph_book(4)
    primary = _PolicyRefusingClient()
    fallback = FakeLLMClient(model="claude-haiku-4-5")

    result = translate_book(
        book,
        client=primary,
        target_lang="sl",
        cache=_memory_cache(),
        glossary=None,
        fallback_client=fallback,
    )

    assert len(result.segments) == len(book.segments)
    assert all(seg.text.startswith(_PREFIX) for seg in result.segments)
    assert fallback.translation_calls >= 1


def test_content_policy_refusal_without_fallback_is_loud() -> None:
    """No fallback configured -> TranslationError naming the cause."""
    book = _paragraph_book(3)
    primary = _PolicyRefusingClient()

    with pytest.raises(TranslationError, match="content-policy"):
        translate_book(
            book,
            client=primary,
            target_lang="sl",
            cache=_memory_cache(),
            glossary=None,
        )


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


# --------------------------------------------------------------------------
# review finding 7 regression: a billed-but-truncated/empty response must
# degrade through the SAME retry ladder as a bad mapping, not abort the run.
# --------------------------------------------------------------------------


def test_batch_truncation_on_first_attempt_recovers_via_strict_retry() -> None:
    """A truncated first batch response degrades to the strict-retry rung.

    Regression test for the finding-7 fix's own bug: EmptyCompletionError/
    TruncatedCompletionError used to propagate straight out of
    ``client.complete()`` with no handler, aborting the whole book instead
    of being treated like a bad mapping.
    """
    book = _paragraph_book(2)
    client = _TruncatingClient(fail_batch_calls=1)

    result = translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=2
    )

    assert all(seg.text.startswith(_PREFIX) for seg in result.segments)
    assert not any(
        call["kind"] == "single" for call in client.calls
    ), "the strict retry must have succeeded without needing per-segment fallback"


def test_batch_truncation_on_both_attempts_falls_back_to_per_segment() -> None:
    """A batch that truncates on both batch-level attempts falls through to
    the per-segment fallback and completes the book, exactly like a batch
    that persistently mismatches (test_batch_mismatch_retries_then_falls_back_
    per_segment) — truncation must never be a smaller-granularity dead end.
    """
    book = _paragraph_book(2)
    client = _TruncatingClient(fail_batch_calls=2)

    result = translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=2
    )

    assert all(seg.text.startswith(_PREFIX) for seg in result.segments)
    single_calls = [call for call in client.calls if call["kind"] == "single"]
    assert len(single_calls) == 2, "per-segment fallback must have run for both segments"


def test_single_segment_truncation_still_raises_translation_error() -> None:
    """The finding-7 guarantee survives at the level with no smaller retry
    unit: once the ladder reaches per-segment translation, a truncated/empty
    response there is a loud TranslationError, same as a plain empty reply.
    """
    book = _paragraph_book(1)
    client = _TruncatingClient(fail_batch_calls=2, fail_single=True)

    with pytest.raises(TranslationError, match="Empty translation"):
        translate_book(book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=1)


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
# Prompt registry (S1.10): style selection, cache keying, extra passes.
# --------------------------------------------------------------------------


class _ReviseMismatchClient(FakeLLMClient):
    """Translates fine but the editor pass never returns a 1:1 mapping."""

    def _revise_response(self, prompt: str) -> CompletionResult:
        pairs = _extract_numbered(prompt)[:-1]  # drop the last marker
        parts = [f"[[{n}]] {_REVISED_PREFIX}{_draft_of(body)}" for n, body in pairs]
        return self._result("\n".join(parts))


class _ReviseTruncatingClient(FakeLLMClient):
    """Translates fine but every editor-pass call is billed and truncated.

    Same regression class as ``_TruncatingClient`` (review finding 7's
    guard escaping unhandled), applied to the revise pass rather than the
    batch-translate ladder.
    """

    def complete(self, prompt=None, messages=None, *, max_tokens=None, system=None):
        if self._kind(prompt, system) == "revise":
            raise TruncatedCompletionError(
                "truncated mid-generation",
                result=CompletionResult(
                    text="",
                    input_tokens=50,
                    output_tokens=999,
                    cost_eur=cost_eur(self.model, 50, 999),
                    model=self.model,
                ),
            )
        return super().complete(prompt, messages, max_tokens=max_tokens, system=system)


def test_default_style_is_baseline_and_sends_the_pre_refactor_prompt() -> None:
    """With no style argument the engine sends baseline_v1's system prompts."""
    book = _paragraph_book(3)
    client = FakeLLMClient()

    translate_book(book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=3)

    systems = [call["system"] for call in client.calls]
    assert systems == [BASELINE.batch_system]


def test_style_version_keys_the_cache_so_a_variant_retranslates() -> None:
    """The A/B blocker: a different prompt must MISS, not serve baseline text."""
    book = _paragraph_book(4)
    cache = _memory_cache()
    bhash = book_hash(book)

    baseline_client = FakeLLMClient()
    translate_book(
        book, client=baseline_client, target_lang="sl", cache=cache, batch_size=4, style=BASELINE
    )
    assert baseline_client.translation_calls == 1

    variant = get_style("sl_style_v1")
    variant_client = FakeLLMClient()
    result = translate_book(
        book, client=variant_client, target_lang="sl", cache=cache, batch_size=4, style=variant
    )

    # The variant actually called the model instead of replaying the cache.
    assert variant_client.translation_calls == 1
    assert variant_client.calls[0]["system"] == variant.batch_system
    assert len(result.segments) == len(book.segments)

    # Both versions are stored side by side and each reads back its own text.
    shash = segment_hash("Paragraph 0.")
    assert cache.get_translation(bhash, shash, "gpt-5-mini", "sl", "baseline_v1") is not None
    assert cache.get_translation(bhash, shash, "gpt-5-mini", "sl", "sl_style_v1") is not None
    rows = cache._conn.execute(
        "SELECT DISTINCT prompt_version FROM translations ORDER BY prompt_version"
    ).fetchall()
    assert [row["prompt_version"] for row in rows] == ["baseline_v1", "sl_style_v1"]


def test_baseline_run_against_a_populated_cache_makes_zero_api_calls() -> None:
    """A pre-existing (migrated) baseline cache re-bills nothing."""
    book = _paragraph_book(5)
    cache = _memory_cache()
    bhash = book_hash(book)
    cache.store_batch(
        bhash,
        "gpt-5-mini",
        "sl",
        [
            SegmentTranslation(
                segment_hash=segment_hash(seg.text), text=f"{_PREFIX}{seg.text}", cost_eur=0.0
            )
            for seg in book.segments
        ],
        CallRecord(kind="batch", input_tokens=1, output_tokens=1, cost_eur=0.0),
        "baseline_v1",
    )

    client = FakeLLMClient()
    result = translate_book(book, client=client, target_lang="sl", cache=cache, glossary=None)

    assert client.calls == []
    expected = [f"{_PREFIX}{seg.text}" for seg in book.segments]
    assert [seg.text for seg in result.segments] == expected


def test_variant_contract_applies_to_the_single_segment_fallback() -> None:
    """A batch that degrades to per-segment translation keeps the style's prompt."""
    book = _paragraph_book(3)
    style = get_style("sl_style_v1")
    client = MismatchClient()

    translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=3, style=style
    )

    singles = [call for call in client.calls if call["kind"] == "single"]
    assert len(singles) == 3
    for call in singles:
        assert call["system"] == style.single_system
        assert "SLOVENIAN STYLE CONTRACT" in call["system"]
        assert call["system"] != BASELINE.single_system


@pytest.mark.parametrize("name", style_names())
def test_every_style_preserves_segment_integrity(name: str) -> None:
    """Count, order, IDs, positions and types survive every registered style."""
    segments = [
        _segment("Chapter One", 0, 0, "Chapter One", seg_type=SegmentType.HEADING, heading_level=1),
        _segment("First paragraph.", 0, 1, "Chapter One"),
        _segment("  ", 0, 2, "Chapter One"),
        _segment("Second paragraph.", 0, 3, "Chapter One"),
    ]
    book = _book(segments)

    result = translate_book(
        book,
        client=FakeLLMClient(),
        target_lang="sl",
        cache=_memory_cache(),
        batch_size=2,
        style=get_style(name),
    )

    assert len(result.segments) == len(book.segments)
    for original, translated in zip(book.segments, result.segments):
        assert (translated.id, translated.position, translated.type) == (
            original.id,
            original.position,
            original.type,
        )
    assert result.segments[2].text == "  "  # empty passthrough untouched


def test_revise_style_runs_an_editor_pass_over_every_batch() -> None:
    """revise_v1 adds one editor call per batch and returns the revised text."""
    book = _paragraph_book(4)
    style = get_style("revise_v1")
    client = FakeLLMClient()

    result = translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=2, style=style
    )

    assert client.translation_calls == 2  # two batches
    assert client.revise_calls == 2  # one editor pass each
    assert all(seg.text.startswith(f"{_REVISED_PREFIX}{_PREFIX}") for seg in result.segments)
    # The editor saw both the source and the draft for every segment.
    for prompt in client.revise_prompts:
        assert "SOURCE: Paragraph" in prompt
        assert f"DRAFT: {_PREFIX}Paragraph" in prompt


def test_revise_failure_keeps_the_draft_and_is_counted() -> None:
    """A bad editor reply never corrupts the mapping — it is reported instead."""
    book = _paragraph_book(3)
    style = get_style("revise_v1")
    client = _ReviseMismatchClient()
    seen: list[TranslationStats] = []

    result = translate_book(
        book,
        client=client,
        target_lang="sl",
        cache=_memory_cache(),
        batch_size=3,
        style=style,
        on_progress=seen.append,
    )

    assert client.revise_calls == 2  # attempt + strict retry, then abandoned
    assert len(result.segments) == 3
    assert all(seg.text == f"{_PREFIX}Paragraph {i}." for i, seg in enumerate(result.segments))
    assert seen[-1].revision_failures == 1


def test_revise_pass_truncation_keeps_the_unrevised_translation_instead_of_aborting() -> None:
    """review finding 7 regression, same class as the batch ladder: a
    truncated/empty revise-pass response must degrade to 'keep the
    un-revised translation' (exactly like a bad-mapping revise reply, see
    ``test_revise_failure_keeps_the_draft_and_is_counted`` above), not
    escape ``_revise_batch`` and abort the book. revise_v1 is the DEFAULT
    style, so this path is not an edge case.
    """
    book = _paragraph_book(2)
    style = get_style("revise_v1")
    client = _ReviseTruncatingClient()
    seen: list[TranslationStats] = []

    result = translate_book(
        book,
        client=client,
        target_lang="sl",
        cache=_memory_cache(),
        batch_size=2,
        style=style,
        on_progress=seen.append,
    )

    assert len(result.segments) == 2
    assert all(seg.text.startswith(_PREFIX) for seg in result.segments)
    assert not any(seg.text.startswith(_REVISED_PREFIX) for seg in result.segments)
    assert seen[-1].revision_failures == 1


def test_book_context_memo_is_derived_once_and_injected_everywhere() -> None:
    """book_context_v1 derives one memo, injects it in every prompt, caches it."""
    book = _paragraph_book(6)
    style = get_style("book_context_v1")
    cache = _memory_cache()
    client = FakeLLMClient()

    translate_book(book, client=client, target_lang="sl", cache=cache, batch_size=3, style=style)

    assert client.book_context_calls == 1
    assert len(client.batch_prompts) == 2
    for prompt in client.batch_prompts:
        assert "BOOK STYLE MEMO" in prompt
        assert "Reportorial nonfiction" in prompt

    # Memoized per (book, model, lang, prompt_version): a resumed run is free.
    assert cache.get_book_context(book_hash(book), "gpt-5-mini", "sl", "book_context_v1")
    resumed = FakeLLMClient()
    translate_book(book, client=resumed, target_lang="sl", cache=cache, batch_size=3, style=style)
    assert resumed.calls == []


def test_book_context_memo_reaches_the_single_segment_fallback() -> None:
    """Fallback segments get the same memo the batch prompts got."""
    book = _paragraph_book(2)
    style = get_style("book_context_v1")
    client = MismatchClient()

    translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=2, style=style
    )

    singles = [call for call in client.calls if call["kind"] == "single"]
    assert len(singles) == 2
    assert all("Reportorial nonfiction" in call["prompt"] for call in singles)


def test_empty_book_context_memo_is_cached_and_not_rebilled_on_resume() -> None:
    """review finding 20: an empty memo must still be cached.

    Without the fix, only a non-empty memo is stored, so a killed-and-resumed
    run repeats the derivation call on every resume — contradicting the
    "resumed run neither re-bills the memo call" guarantee.
    """
    book = _paragraph_book(2)
    style = get_style("book_context_v1")
    cache = _memory_cache()
    client = _EmptyBookContextClient()

    translate_book(book, client=client, target_lang="sl", cache=cache, batch_size=2, style=style)
    assert client.book_context_calls == 1

    # The empty memo must be a cache HIT (row exists), not merely absent.
    cached = cache.get_book_context(book_hash(book), "gpt-5-mini", "sl", "book_context_v1")
    assert cached == ""

    resumed = _EmptyBookContextClient()
    translate_book(book, client=resumed, target_lang="sl", cache=cache, batch_size=2, style=style)
    assert resumed.book_context_calls == 0, "resumed run must not re-derive a cached empty memo"


def test_truncated_book_context_memo_degrades_to_no_memo_instead_of_aborting() -> None:
    """review finding 7 regression, same class as the batch ladder and the
    revise pass: a billed-but-truncated book-context call must degrade to
    "translate without a memo" — the pre-existing behavior for a genuinely
    blank reply — rather than let TruncatedCompletionError escape
    ``build_book_context`` and abort the whole book.
    """
    book = _paragraph_book(2)
    style = get_style("book_context_v1")
    client = _TruncatingBookContextClient()

    result = translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=2, style=style
    )

    assert len(result.segments) == 2
    assert all(seg.text.startswith(_PREFIX) for seg in result.segments)
    assert all("BOOK STYLE MEMO" not in prompt for prompt in client.batch_prompts)


def test_baseline_style_asks_for_no_extra_passes() -> None:
    """The default path is unchanged: no memo call, no editor call."""
    book = _paragraph_book(4)
    client = FakeLLMClient()

    translate_book(book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=2)

    assert client.book_context_calls == 0
    assert client.revise_calls == 0
    assert {call["kind"] for call in client.calls} == {"batch"}


def test_estimate_cost_reflects_the_style_it_is_asked_about() -> None:
    """revise_v1 roughly doubles the bill; book_context_v1 adds one call."""
    book = _paragraph_book(20)
    baseline = estimate_cost(book, model="gpt-5-mini", target_lang="sl", batch_size=10)
    revise = estimate_cost(
        book,
        model="gpt-5-mini",
        target_lang="sl",
        batch_size=10,
        style=get_style("revise_v1"),
    )
    book_context = estimate_cost(
        book,
        model="gpt-5-mini",
        target_lang="sl",
        batch_size=10,
        style=get_style("book_context_v1"),
    )

    assert baseline.prompt_version == "baseline_v1"
    assert baseline.revision_calls == 0 and baseline.book_context_calls == 0

    assert revise.prompt_version == "revise_v1"
    assert revise.revision_calls == revise.batches == 2
    assert revise.cost_eur > baseline.cost_eur
    # Two calls per batch instead of one: the reasoning surcharge doubles too.
    assert revise.reasoning_tokens == baseline.reasoning_tokens + 2 * REASONING_TOKENS_PER_CALL

    assert book_context.book_context_calls == 1
    assert book_context.cost_eur > baseline.cost_eur
    assert book_context.reasoning_tokens == baseline.reasoning_tokens + REASONING_TOKENS_PER_CALL


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
                # Pinned: this test is about back-matter pass-through, not about
                # which prompt style is default. Under the revise_v1 default the
                # fake tags translated text "ED::" rather than "SL::"; pinning
                # keeps the assertion focused and decoupled from that choice.
                "--style",
                "baseline_v1",
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


def test_cli_prints_fallback_spend_in_the_total(
    monkeypatch: pytest.MonkeyPatch, epub_builder
) -> None:
    """review finding 5: a content-policy fallback's spend must reach the printed total.

    Before the fix, ``_CostTrackingClient`` wrapped only the primary client, so
    a batch retried via the fallback provider was real spend absent from the
    CLI's "€ total" line. The printed total must equal ``stats.cost_eur``,
    computed independently here via a direct ``translate_book`` call against
    equivalent fresh clients/cache.
    """
    import berilo.assemble as assemble_module
    import berilo.providers as providers_module
    from berilo.normalize import normalize

    monkeypatch.setenv("ANTHROPIC_API_KEY", FAKE_ANTHROPIC_KEY)

    def _fake_create_client(model, config):
        if model == "claude-haiku-4-5":
            return FakeLLMClient(model=model)
        return _PolicyRefusingClient(model=model)

    def _fake_build_epub(book, output_path, *, bilingual=False, source_book=None):
        return output_path

    monkeypatch.setattr(providers_module, "create_client", _fake_create_client)
    monkeypatch.setattr(assemble_module, "build_epub", _fake_build_epub, raising=False)

    epub = _write_epub(None, epub_builder)

    # Independently compute the total translate_book actually spends against
    # equivalent fresh clients/cache — this is what the CLI-printed total must
    # equal once the fallback client's spend is included.
    book = normalize(str(epub))
    captured_stats: dict = {}
    translate_book(
        book,
        client=_PolicyRefusingClient(model="gpt-5-mini"),
        target_lang="sl",
        cache=_memory_cache(),
        glossary=None,
        fallback_client=FakeLLMClient(model="claude-haiku-4-5"),
        on_progress=lambda stats: captured_stats.__setitem__("stats", stats),
        style=get_style("baseline_v1"),
    )
    expected_cost = captured_stats["stats"].cost_eur
    assert expected_cost > 0, "the fallback batch must have real, nonzero cost"

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
                "--no-glossary",
                "--style",
                "baseline_v1",
                "--cache-db",
                "cache.db",
            ],
        )

    assert result.exit_code == 0, result.output
    match = re.search(r"€([\d.]+) total", result.output)
    assert match is not None, result.output
    printed_total = float(match.group(1))
    assert printed_total == pytest.approx(expected_cost, abs=1e-4)


# --------------------------------------------------------------------------
# S1.12 — revise_v1 is the default translation style.
# --------------------------------------------------------------------------


def test_default_style_is_revise_v1() -> None:
    """The E2 bake-off promoted revise_v1 to the default (see ledger 2026-07-25)."""
    from berilo import prompts

    assert prompts.DEFAULT.name == "revise_v1"
    assert prompts.DEFAULT_STYLE_NAME == "revise_v1"
    assert prompts.DEFAULT.revise_system is not None, "the default must carry the revision pass"


def test_cli_unknown_style_fails_loudly_and_lists_valid_names(epub_builder) -> None:
    """A typo in --style must never silently fall back to the default."""
    epub = _write_epub(None, epub_builder)
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(
            cli, ["translate", str(epub), "--dry-run", "--style", "no_such_style", "--to", "sl"]
        )

    assert result.exit_code != 0
    assert "no_such_style" in result.output
    assert "revise_v1" in result.output, "the error must list the valid style names"


def test_cli_dry_run_default_style_costs_about_twice_baseline(epub_builder) -> None:
    """The estimate must price the style actually selected, not always baseline.

    revise_v1 runs a second editor pass per batch, so its estimate must be
    materially higher than baseline_v1's — otherwise a two-pass run would be
    approved against a one-pass number.
    """
    import re

    epub = _write_epub(None, epub_builder)
    runner = CliRunner()

    def _estimate(args: list[str]) -> float:
        with runner.isolated_filesystem():
            result = runner.invoke(
                cli,
                ["translate", str(epub), "--dry-run", "--model", "gpt-5-mini", "--to", "sl", *args],
            )
        assert result.exit_code == 0, result.output
        match = re.search(r"Estimated total cost:?\s*€([0-9.]+)", result.output) or re.search(
            r"€([0-9.]+)", result.output
        )
        assert match is not None, result.output
        return float(match.group(1))

    baseline = _estimate(["--style", "baseline_v1"])
    default = _estimate([])
    # The ratio is compressed on this tiny fixture because the fixed per-batch
    # prompt overhead dominates; on a real ~2300-segment book it is ~2.09x.
    # Assert the direction and a clear margin, not a brittle exact multiple.
    assert default > baseline * 1.25, f"default {default} should exceed baseline {baseline}"
    assert default < baseline * 3.0, f"default {default} implausibly above baseline {baseline}"


def test_cli_dry_run_names_the_style_and_flags_the_second_pass(epub_builder) -> None:
    """The dry run must say which style it priced and that it is two-pass."""
    epub = _write_epub(None, epub_builder)
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(
            cli, ["translate", str(epub), "--dry-run", "--model", "gpt-5-mini", "--to", "sl"]
        )

    assert result.exit_code == 0, result.output
    assert "revise_v1" in result.output
    assert "second native-editor pass" in result.output


def test_print_summary_warns_when_revision_pass_failed() -> None:
    """A batch that fell back to un-revised text must be surfaced, never silent."""
    from click.testing import CliRunner as _CliRunner

    from berilo import prompts
    from berilo.cli import _print_summary
    from berilo.translate import TranslationStats

    stats = TranslationStats(total_segments=10)
    stats.translated_segments = 10
    stats.revision_failures = 2

    runner = _CliRunner()
    with runner.isolation() as streams:
        _print_summary(stats, skip_back_matter=False, style=prompts.DEFAULT)
    text = streams[0].getvalue().decode()

    assert "revision pass could not be applied" in text
    assert "2 batch" in text
    assert "revise_v1" in text


def test_cli_default_style_also_skips_back_matter(monkeypatch, epub_builder) -> None:
    """Back matter stays untranslated under the revise_v1 default too.

    The focused test above pins ``baseline_v1``; this one exercises the real
    default path so the two-pass style cannot quietly start translating
    (and billing for) Index/Notes chapters.
    """
    import berilo.assemble as assemble_module
    import berilo.providers as providers_module

    captured: dict = {}

    monkeypatch.setattr(
        providers_module, "create_client", lambda model, config: FakeLLMClient(model=model)
    )

    def _fake_build_epub(book, output_path, *, bilingual=False, source_book=None):
        captured["book"] = book
        return output_path

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
    assert "revise_v1" in result.output, "the summary must name the style actually used"
    book = captured["book"]
    index_segments = [s for s in book.segments if s.chapter_title == "Index"]
    assert index_segments
    for seg in index_segments:
        assert not seg.text.startswith(_PREFIX)
        assert not seg.text.startswith(_REVISED_PREFIX)
    story_segments = [s for s in book.segments if s.chapter_title == "Chapter One"]
    assert any(
        s.text.startswith(_REVISED_PREFIX) for s in story_segments
    ), "under the default style, body prose must come back through the revision pass"


# --------------------------------------------------------------------------
# A3 — language-bound styles, anchored markers, and context_pairs=0.
# Review findings 4 (HIGH), 14, 10.
# --------------------------------------------------------------------------


class _StrayMarkerClient(FakeLLMClient):
    """Answers correctly, but segment 2's translation contains a literal ``[[2]]``.

    This is review finding 14's exact scenario: the reply is a perfect 1:1
    mapping, yet an unanchored scan counts a fourth marker and forces a strict
    retry plus possibly a per-segment fallback — pure wasted spend.
    """

    def _batch_response(self, prompt: str) -> CompletionResult:
        parts = []
        for n, text in _extract_numbered(prompt):
            body = f"{_PREFIX}{text}"
            if n == 2:
                body += " (element [[2]] of the array)"
            parts.append(f"[[{n}]] {body}")
        return self._result("\n".join(parts))


def test_a_marker_inside_a_translation_does_not_force_a_strict_retry() -> None:
    """Finding 14: prose containing ``[[2]]`` costs exactly one call, not three.

    The call count IS the assertion — the defect's whole cost is the needless
    retry ladder it triggers.
    """
    book = _paragraph_book(3)
    client = _StrayMarkerClient()

    result = translate_book(
        book, client=client, target_lang="sl", cache=_memory_cache(), batch_size=3
    )

    assert client.translation_calls == 1, "one batch call: no strict retry, no per-segment fallback"
    assert [call["kind"] for call in client.calls] == ["batch"]
    # The stray marker stays inside its own translation rather than splitting it.
    assert result.segments[1].text == f"{_PREFIX}Paragraph 1. (element [[2]] of the array)"
    assert len(result.segments) == 3


def test_parse_numbered_response_anchors_markers_to_line_starts() -> None:
    """A mid-line ``[[n]]`` is prose; a line-leading one is a marker."""
    reply = "[[1]] a\n[[2]] see [[2]] in the array\n[[3]] c"
    assert parse_numbered_response(reply, 3) == ["a", "see [[2]] in the array", "c"]
    # Leading indentation still marks a segment.
    assert parse_numbered_response("  [[1]] a\n\t[[2]] b", 2) == ["a", "b"]


def test_anchoring_never_adds_a_retry_for_a_single_line_reply() -> None:
    """Markers packed onto one line parse exactly as they did before anchoring.

    Anchoring may only remove needless retries; introducing one for a reply the
    old parser accepted would just move finding 14's cost somewhere else.
    """
    assert parse_numbered_response("[[1]] a [[2]] b [[3]] c", 3) == ["a", "b", "c"]
    # A genuinely broken mapping still fails, under either scan.
    with pytest.raises(ValueError):
        parse_numbered_response("[[1]] a [[3]] c", 2)


def test_context_pairs_zero_produces_no_context_block() -> None:
    """Finding 10: ``0`` disables rolling context instead of feeding the whole book."""
    book = _paragraph_book(6)
    client = FakeLLMClient()

    translate_book(
        book,
        client=client,
        target_lang="sl",
        cache=_memory_cache(),
        batch_size=1,
        context_pairs=0,
    )

    assert len(client.batch_prompts) == 6
    for prompt in client.batch_prompts:
        assert "CONTEXT (already translated" not in prompt
    # The defect's signature: the last batch carrying every prior pair.
    assert "Paragraph 0." not in client.batch_prompts[-1]


def test_context_pairs_two_still_trims_to_two() -> None:
    """The positive case is unchanged: at most N pairs ride along."""
    book = _paragraph_book(6)
    client = FakeLLMClient()

    translate_book(
        book,
        client=client,
        target_lang="sl",
        cache=_memory_cache(),
        batch_size=1,
        context_pairs=2,
    )

    last = client.batch_prompts[-1]
    assert last.count("SOURCE: ") == 2, "exactly two pairs, never the whole book"
    assert "Paragraph 4." in last and "Paragraph 3." in last
    assert "Paragraph 0." not in last


def test_translate_book_refuses_a_style_bound_to_another_language() -> None:
    """Finding 4: a Slovenian editor pass over a German draft never reaches the API."""
    from berilo.prompts import StyleLanguageError, get_style

    book = _paragraph_book(3)
    client = FakeLLMClient()

    with pytest.raises(StyleLanguageError, match="revise_v1"):
        translate_book(
            book,
            client=client,
            target_lang="de",
            cache=_memory_cache(),
            style=get_style("revise_v1"),
        )

    assert client.calls == [], "the refusal must happen before anything is billed"


def test_estimate_cost_refuses_a_style_bound_to_another_language() -> None:
    """The dry run refuses too: quoting a price invites the user to approve it."""
    from berilo.prompts import StyleLanguageError, get_style

    with pytest.raises(StyleLanguageError):
        estimate_cost(
            _paragraph_book(3),
            model="gpt-5-mini",
            target_lang="de",
            style=get_style("revise_v1"),
        )


def test_translate_book_accepts_the_generic_two_pass_style_for_any_target() -> None:
    """revise_generic_v1 covers --to de with a second pass and no language contract."""
    from berilo.prompts import get_style

    book = _paragraph_book(3)
    client = FakeLLMClient()
    style = get_style("revise_generic_v1")

    result = translate_book(
        book,
        client=client,
        target_lang="de",
        cache=_memory_cache(),
        batch_size=3,
        style=style,
    )

    assert client.revise_calls == 1, "the generic style still runs its editor pass"
    for prompt in client.batch_prompts:
        assert "Translate into: de." in prompt
    for call in client.calls:
        assert "Slovenian" not in (call["system"] or "")
        assert "šumniki" not in (call["system"] or "")
    assert all(seg.text.startswith(_REVISED_PREFIX) for seg in result.segments)


def test_cli_to_de_resolves_a_generic_style_and_runs_no_slovenian_editor_pass(
    monkeypatch, epub_builder
) -> None:
    """The Verify line, end to end: ``--to de`` must not run a Slovenian editor pass."""
    import berilo.assemble as assemble_module
    import berilo.providers as providers_module

    clients: list[FakeLLMClient] = []

    def _make_client(model, config):
        client = FakeLLMClient(model=model)
        clients.append(client)
        return client

    monkeypatch.setattr(providers_module, "create_client", _make_client)
    monkeypatch.setattr(
        assemble_module,
        "build_epub",
        lambda book, output_path, *, bilingual=False, source_book=None: output_path,
        raising=False,
    )

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
                "de",
                "--yes",
                "--no-glossary",
                "--cache-db",
                "cache.db",
            ],
        )

    assert result.exit_code == 0, result.output
    assert "revise_generic_v1" in result.output, "the resolved style must be in the run summary"
    assert "resolved for 'de'" in result.output
    assert clients, "a client must have been constructed"
    for client in clients:
        for call in client.calls:
            system = call["system"] or ""
            assert "native Slovenian editor" not in system
            assert "SLOVENIAN STYLE CONTRACT" not in system


def test_cli_refuses_a_slovenian_style_against_a_german_target(epub_builder) -> None:
    """The mismatch is loud and actionable, not a silently contradictory paid run."""
    epub = _write_epub(None, epub_builder)
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(
            cli,
            ["translate", str(epub), "--dry-run", "--style", "revise_v1", "--to", "de"],
        )

    assert result.exit_code != 0
    assert "revise_v1" in result.output
    assert "revise_generic_v1" in result.output, "the refusal must name what to use instead"
    assert "Dry run" not in result.output, "no estimate may be printed for a refused pair"


# --------------------------------------------------------------------------
# Wave execution (core-spec Surface 3).
#
# The first test here is the regression gate for the plan/execute refactor: it
# asserts literal output and passes BEFORE the refactor as well as after, so a
# change in how batches are cut shows up here rather than in a book.
# --------------------------------------------------------------------------


def _lettered_book(count: int, *, chapters: int = 1) -> Book:
    """A book of ``count`` paragraphs spread over ``chapters`` chapters."""
    segments = []
    for position in range(count):
        chapter = position * chapters // count
        segments.append(
            _segment(f"Sentence {position}.", chapter, position, f"Chapter {chapter}")
        )
    return _book(segments)


def test_plan_backed_translation_matches_the_sequential_baseline() -> None:
    """The refactor must be output-identical, not merely output-equivalent."""
    segments = [
        _segment("Alpha.", 0, 0, "Chapter One"),
        _segment("Beta.", 0, 1, "Chapter One"),
        _segment("   ", 0, 2, "Chapter One"),
        _segment("Gamma.", 1, 3, "Chapter Two"),
        _segment("Delta.", 1, 4, "Chapter Two"),
    ]
    book = _book(segments)
    client = FakeLLMClient()
    with _memory_cache() as cache:
        out = translate_book(book, client=client, target_lang="sl", cache=cache)

    assert [s.id for s in out.segments] == [s.id for s in book.segments]
    assert [s.text for s in out.segments] == [
        "SL::Alpha.",
        "SL::Beta.",
        "   ",
        "SL::Gamma.",
        "SL::Delta.",
    ]
