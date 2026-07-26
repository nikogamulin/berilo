"""Tests for the glossary's participation in the cache key (A2).

The glossary is injected into every batch prompt, but before A2 it appeared in
neither the translation key ``(book, segment, model, lang, prompt_version)``
nor — as a prompt version — its own ``glossaries`` key ``(book, model, lang)``.
Improving the glossary therefore hit the unchanged keys and returned the old
terms *and* the old translations at zero cost: a null result indistinguishable
from a real one, and a recurrence of CLAUDE.md §9's canonical cache-key rule in
the subsystem that produced it.

These tests pin both halves of the fix:

* a *different glossary* is a different translation row (never a re-serve), and
* a *changed glossary prompt* re-extracts instead of returning stale terms.

Everything is offline: a counting fake stands in for the provider, so a test
that reached a real API would fail rather than bill.
"""

from __future__ import annotations

import json

import pytest

from berilo import glossary as glossary_module
from berilo.cache import (
    BASELINE_GLOSSARY_PROMPT_VERSION,
    EMPTY_GLOSSARY_HASH,
    CallRecord,
    SegmentTranslation,
    TranslationCache,
    book_hash,
    segment_hash,
)
from berilo.glossary import (
    GLOSSARY_PROMPT_VERSION,
    Glossary,
    build_glossary,
    glossary_identity,
    glossary_prompt_version,
)
from berilo.models import Book, Segment, SegmentType, make_segment_id
from berilo.providers.base import CompletionResult, LLMClient
from berilo.translate import translate_book

#: The glossary prompt version as it stood when ``glossary_hash`` joined the
#: translation key. **When you intentionally change the glossary prompt or its
#: sampling this assertion fails — that is the mechanism working.** Update this
#: literal, never :data:`berilo.cache.BASELINE_GLOSSARY_PROMPT_VERSION`, which
#: is the frozen identity the migration attributes pre-existing rows to.
_PINNED_GLOSSARY_VERSION = "glossary_3512dce61808"

_MODEL = "gpt-5-mini"
_LANG = "sl"


class CountingClient(LLMClient):
    """Offline client that answers glossary and batch calls and counts both."""

    def __init__(self, terms: dict[str, str]) -> None:
        self.model = _MODEL
        self.terms = dict(terms)
        self.glossary_calls = 0
        self.batch_calls = 0
        self.batch_prompts: list[str] = []

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        *,
        max_tokens: int | None = None,
        system: str | None = None,
    ) -> CompletionResult:
        # Marker-bearing prompts are batches; the batch system prompt also
        # mentions "glossary", so markers must be checked first.
        if prompt is None or not _numbered_entries(prompt):
            self.glossary_calls += 1
            return self._result(json.dumps(self.terms, ensure_ascii=False))
        self.batch_calls += 1
        self.batch_prompts.append(prompt)
        # Echo the numbered markers back, tagging the reply with the glossary
        # in force so a re-serve from the wrong key is visible in the text.
        signature = "+".join(sorted(self.terms.values())) or "none"
        lines = [f"[[{n}]] SL[{signature}]::{text}" for n, text in _numbered_entries(prompt)]
        return self._result("\n".join(lines))

    def _result(self, text: str) -> CompletionResult:
        return CompletionResult(
            text=text, input_tokens=10, output_tokens=10, cost_eur=0.0001, model=self.model
        )


def _numbered_entries(prompt: str) -> list[tuple[int, str]]:
    """Extract ``(n, text)`` pairs from a ``[[n]] text`` block."""
    import re

    matches = list(re.finditer(r"\[\[(\d+)\]\]", prompt))
    out: list[tuple[int, str]] = []
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(prompt)
        out.append((int(match.group(1)), prompt[match.end() : end].strip()))
    return out


def _book(paragraphs: int = 2) -> Book:
    segments = [
        Segment(
            id=make_segment_id(f"Paragraph {i}.", 0, i),
            chapter_index=0,
            chapter_title="Chapter One",
            position=i,
            type=SegmentType.PARAGRAPH,
            text=f"Paragraph {i}.",
        )
        for i in range(paragraphs)
    ]
    return Book(
        title="Test Book",
        authors=["Author"],
        language="en",
        source_path="/tmp/test.epub",
        source_format="epub",
        segments=segments,
    )


# --------------------------------------------------------------------------
# Glossary identity: derived from what actually reaches the prompt.
# --------------------------------------------------------------------------


def test_glossary_identity_distinguishes_different_terms() -> None:
    """Two glossaries that render differently key differently."""
    first = glossary_identity(Glossary(terms={"Kaplan": "Kaplan"}))
    second = glossary_identity(Glossary(terms={"Kaplan": "Kaplanova"}))
    assert first != second


def test_glossary_identity_ignores_term_insertion_order() -> None:
    """Re-extraction must not re-bill a book just because the order changed.

    ``build_glossary`` takes its terms from the model, which offers no ordering
    contract. If the rendered block followed dict insertion order, two
    extractions of a semantically identical glossary would key differently and
    every segment of the book would miss cache and re-translate at full price
    (~€1.45 at the ``revise_v1`` default) for no change whatsoever — the exact
    silent-spend class this story exists to remove.
    """
    forward = Glossary(terms={"Kaplan": "Kaplan", "Heartland": "Osrcje", "Rimland": "Obrobje"})
    shuffled = Glossary(terms={"Rimland": "Obrobje", "Kaplan": "Kaplan", "Heartland": "Osrcje"})

    assert list(forward.terms) != list(shuffled.terms)  # the orders really do differ
    assert forward.terms == shuffled.terms  # ...but the content is the same
    assert forward.to_prompt_block() == shuffled.to_prompt_block()
    assert glossary_identity(forward) == glossary_identity(shuffled)


def test_glossary_block_is_rendered_in_source_term_order() -> None:
    """The deterministic order is sorted by source term, not arbitrary."""
    block = Glossary(terms={"Rimland": "Obrobje", "Heartland": "Osrcje"}).to_prompt_block()
    assert block.index("Heartland") < block.index("Rimland")


def test_no_glossary_and_empty_glossary_share_one_identity() -> None:
    """Both inject nothing, so both must resolve to the empty-glossary key."""
    assert glossary_identity(None) == EMPTY_GLOSSARY_HASH
    assert glossary_identity(Glossary(terms={})) == EMPTY_GLOSSARY_HASH


def test_glossary_prompt_version_is_pinned_and_frozen_for_migration() -> None:
    """Today's derived version is the value the migration attributes rows to."""
    assert GLOSSARY_PROMPT_VERSION == _PINNED_GLOSSARY_VERSION
    assert BASELINE_GLOSSARY_PROMPT_VERSION == _PINNED_GLOSSARY_VERSION


def test_changing_the_glossary_prompt_changes_its_version(monkeypatch) -> None:
    """The version is derived from the prompt, so it cannot be forgotten."""
    monkeypatch.setattr(
        glossary_module, "_GLOSSARY_SYSTEM", glossary_module._GLOSSARY_SYSTEM + " Prefer X."
    )
    assert glossary_prompt_version() != _PINNED_GLOSSARY_VERSION


def test_changing_the_sampling_changes_the_version() -> None:
    """Sampling parameters shape the extracted terms, so they key the pass too."""
    assert glossary_prompt_version(sample_chapters=12) != _PINNED_GLOSSARY_VERSION
    assert glossary_prompt_version(max_sample_chars=100) != _PINNED_GLOSSARY_VERSION


# --------------------------------------------------------------------------
# The defect: a glossary change must not be a zero-cost no-op.
# --------------------------------------------------------------------------


def test_changing_the_glossary_prompt_re_extracts_instead_of_serving_old_terms(
    monkeypatch,
) -> None:
    """The exact failure scenario from review finding 3, proven fixed.

    Build a glossary, improve the extraction prompt, build again: the second
    build must call the model and return the *new* terms. Before A2 the key was
    ``(book, model, lang)``, so it hit and returned the old terms at €0.
    """
    book = _book()
    with TranslationCache(":memory:") as cache:
        first = CountingClient({"Kaplan": "Kaplan"})
        built = build_glossary(book, client=first, target_lang=_LANG, cache=cache)
        assert first.glossary_calls == 1
        assert built.terms == {"Kaplan": "Kaplan"}

        # Same prompt again: a genuine cache hit, no call.
        repeat = CountingClient({"Kaplan": "WRONG"})
        again = build_glossary(book, client=repeat, target_lang=_LANG, cache=cache)
        assert repeat.glossary_calls == 0
        assert again.terms == {"Kaplan": "Kaplan"}

        # Now "improve" the extraction prompt.
        monkeypatch.setattr(
            glossary_module,
            "_GLOSSARY_SYSTEM",
            glossary_module._GLOSSARY_SYSTEM + " Keep Slovenian feminine surnames.",
        )
        improved = CountingClient({"Kaplan": "Kaplanova"})
        result = build_glossary(book, client=improved, target_lang=_LANG, cache=cache)

        assert improved.glossary_calls == 1, "improved prompt was never sent to the model"
        assert result.terms == {"Kaplan": "Kaplanova"}


def test_two_glossaries_store_two_rows_and_each_reads_back_its_own_text() -> None:
    """The same segment under two glossaries is two cache rows, not one."""
    book = _book(paragraphs=1)
    bhash = book_hash(book)
    shash = segment_hash(book.segments[0].text)
    first = Glossary(terms={"Kaplan": "Kaplan"})
    second = Glossary(terms={"Kaplan": "Kaplanova"})

    with TranslationCache(":memory:") as cache:
        for glossary, text in ((first, "prvi prevod"), (second, "drugi prevod")):
            cache.store_batch(
                bhash,
                _MODEL,
                _LANG,
                [SegmentTranslation(segment_hash=shash, text=text, cost_eur=0.0)],
                CallRecord(kind="batch", input_tokens=1, output_tokens=1, cost_eur=0.0),
                "revise_v1",
                glossary_identity(glossary),
            )

        assert (
            cache.get_translation(
                bhash, shash, _MODEL, _LANG, "revise_v1", glossary_identity(first)
            )
            == "prvi prevod"
        )
        assert (
            cache.get_translation(
                bhash, shash, _MODEL, _LANG, "revise_v1", glossary_identity(second)
            )
            == "drugi prevod"
        )
        rows = cache._conn.execute("SELECT COUNT(*) AS n FROM translations").fetchone()
        assert rows["n"] == 2


def test_a_third_glossary_misses_rather_than_serving_another_glossarys_text() -> None:
    """A never-run glossary must MISS — this is what made the experiment a no-op."""
    book = _book(paragraphs=1)
    bhash = book_hash(book)
    shash = segment_hash(book.segments[0].text)
    stored = Glossary(terms={"Kaplan": "Kaplan"})

    with TranslationCache(":memory:") as cache:
        cache.store_batch(
            bhash,
            _MODEL,
            _LANG,
            [SegmentTranslation(segment_hash=shash, text="prevod", cost_eur=0.0)],
            CallRecord(kind="batch", input_tokens=1, output_tokens=1, cost_eur=0.0),
            "revise_v1",
            glossary_identity(stored),
        )
        other = glossary_identity(Glossary(terms={"Kaplan": "Kaplanova"}))
        assert cache.get_translation(bhash, shash, _MODEL, _LANG, "revise_v1", other) is None
        assert cache.cached_hashes(bhash, _MODEL, _LANG, "revise_v1", other) == set()
        assert cache.cached_hashes(
            bhash, _MODEL, _LANG, "revise_v1", glossary_identity(stored)
        ) == {shash}


def test_translate_book_re_translates_under_a_changed_glossary() -> None:
    """End to end: a new glossary costs API calls instead of re-serving text."""
    book = _book(paragraphs=2)
    first = Glossary(terms={"Kaplan": "Kaplan"})
    second = Glossary(terms={"Kaplan": "Kaplanova"})

    with TranslationCache(":memory:") as cache:
        client_a = CountingClient(first.terms)
        out_a = translate_book(
            book, client=client_a, target_lang=_LANG, cache=cache, glossary=first
        )
        assert client_a.batch_calls == 1

        # Same glossary: fully cached, zero calls, identical text.
        client_repeat = CountingClient(first.terms)
        out_repeat = translate_book(
            book, client=client_repeat, target_lang=_LANG, cache=cache, glossary=first
        )
        assert client_repeat.batch_calls == 0
        assert [s.text for s in out_repeat.segments] == [s.text for s in out_a.segments]

        # Changed glossary: the model IS called and the new terms show up.
        client_b = CountingClient(second.terms)
        out_b = translate_book(
            book, client=client_b, target_lang=_LANG, cache=cache, glossary=second
        )
        assert client_b.batch_calls == 1, "changed glossary was a zero-cost no-op"
        assert [s.text for s in out_b.segments] != [s.text for s in out_a.segments]
        assert all("Kaplanova" in s.text for s in out_b.segments)

        # Both arms survive side by side; neither overwrote the other.
        assert cache._conn.execute("SELECT COUNT(*) AS n FROM translations").fetchone()["n"] == 4


def test_the_glossary_actually_reaches_the_prompt_it_keys() -> None:
    """The key is only honest if the glossary really is in the batch prompt."""
    book = _book(paragraphs=1)
    glossary = Glossary(terms={"Kaplan": "Kaplanova"})
    with TranslationCache(":memory:") as cache:
        client = CountingClient(glossary.terms)
        translate_book(book, client=client, target_lang=_LANG, cache=cache, glossary=glossary)
    assert any("Kaplanova" in prompt for prompt in client.batch_prompts)


@pytest.mark.parametrize("terms", [{}, {"Kaplan": "Kaplan"}])
def test_glossary_round_trips_through_the_cache_under_its_prompt_version(
    terms: dict[str, str],
) -> None:
    """A stored glossary is readable only under the version that produced it."""
    with TranslationCache(":memory:") as cache:
        cache.store_glossary("book-1", _MODEL, _LANG, terms, prompt_version=GLOSSARY_PROMPT_VERSION)
        assert cache.get_glossary("book-1", _MODEL, _LANG, GLOSSARY_PROMPT_VERSION) == terms
        assert cache.get_glossary("book-1", _MODEL, _LANG, "glossary_other") is None
