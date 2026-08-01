"""Tests for machine-translation drafting with LLM post-editing.

Everything here is offline: a fake MT client stands in for Google, so no key is
needed and nothing is billed.
"""

from __future__ import annotations

import pytest

from berilo.cache import TranslationCache
from berilo.prompts import BASELINE, get_style
from berilo.providers.google_translate import (
    DraftResult,
    MachineTranslationError,
    cost_eur_for_chars,
)
from berilo.translate import TranslationError, translate_book
from tests.test_translate import FakeLLMClient, _book, _segment  # noqa: F401


class FakeMT:
    """Deterministic stand-in: prefixes each segment and records every call."""

    name = "fake-mt"

    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.batches: list[list[str]] = []

    def draft(self, texts, *, source_lang, target_lang):
        if self.fail:
            raise MachineTranslationError("provider said no")
        self.batches.append(list(texts))
        chars = sum(len(t) for t in texts)
        return DraftResult(
            texts=[f"MT::{t}" for t in texts],
            characters=chars,
            cost_eur=cost_eur_for_chars(chars),
        )


def _paragraphs(count: int):
    return _book([_segment(f"Paragraph {i}.", 0, i, "Chapter One") for i in range(count)])


def test_the_llm_makes_one_call_per_batch_instead_of_two():
    """The draft REPLACES the drafting call; it does not precede it.

    A revising style normally costs two LLM calls per batch — draft, then edit.
    With an MT draft the editor still runs but the drafting call is gone, which
    is the entire efficiency claim.
    """
    book = _paragraphs(4)
    mt, client = FakeMT(), FakeLLMClient()

    with TranslationCache(":memory:") as cache:
        translate_book(
            book,
            client=client,
            target_lang="sl",
            cache=cache,
            style=get_style("revise_v1"),
            mt_client=mt,
            batch_size=10,
        )

    assert len(mt.batches) == 1, "one MT call for the batch"
    kinds = [c["kind"] for c in client.calls]
    assert kinds.count("batch") == 0, "the LLM must not draft when MT did"
    assert kinds.count("revise") == 1, "but it must still edit"


def test_the_editor_sees_the_machine_draft():
    book = _paragraphs(2)
    mt, client = FakeMT(), FakeLLMClient()

    with TranslationCache(":memory:") as cache:
        translate_book(
            book,
            client=client,
            target_lang="sl",
            cache=cache,
            style=get_style("revise_v1"),
            mt_client=mt,
        )

    revise_prompts = [c["prompt"] for c in client.calls if c["kind"] == "revise"]
    assert revise_prompts
    assert "MT::Paragraph 0." in revise_prompts[0], "the draft must reach the editor"
    assert "Paragraph 0." in revise_prompts[0], "and so must the source"


def test_segment_integrity_holds_through_the_draft_path():
    book = _paragraphs(7)
    with TranslationCache(":memory:") as cache:
        out = translate_book(
            book,
            client=FakeLLMClient(),
            target_lang="sl",
            cache=cache,
            style=get_style("revise_v1"),
            mt_client=FakeMT(),
            batch_size=3,
        )
    assert [s.id for s in out.segments] == [s.id for s in book.segments]
    assert all(s.text for s in out.segments)


def test_a_non_revising_style_is_refused_loudly():
    """A draft nobody edits is raw machine translation wearing a style's name."""
    book = _paragraphs(2)
    with TranslationCache(":memory:") as cache:
        with pytest.raises(TranslationError, match="revising style"):
            translate_book(
                book,
                client=FakeLLMClient(),
                target_lang="sl",
                cache=cache,
                style=BASELINE,
                mt_client=FakeMT(),
            )


def test_mt_spend_is_reported_separately_from_token_cost():
    """MT bills per character, so it must not vanish into token arithmetic."""
    book = _paragraphs(3)
    seen = []
    with TranslationCache(":memory:") as cache:
        translate_book(
            book,
            client=FakeLLMClient(),
            target_lang="sl",
            cache=cache,
            style=get_style("revise_v1"),
            mt_client=FakeMT(),
            on_progress=seen.append,
        )
    stats = seen[-1]
    assert stats.mt_characters > 0
    assert stats.mt_cost_eur > 0


def test_an_mt_failure_is_loud_not_silent():
    """Falling back to an unedited or LLM-drafted book would change the pipeline
    while the run still reported the style the user asked for."""
    book = _paragraphs(2)
    with TranslationCache(":memory:") as cache:
        with pytest.raises(MachineTranslationError):
            translate_book(
                book,
                client=FakeLLMClient(),
                target_lang="sl",
                cache=cache,
                style=get_style("revise_v1"),
                mt_client=FakeMT(fail=True),
            )


def test_the_price_is_the_documented_list_price():
    # 1M characters at USD 20 * 0.92 EUR/USD.
    assert cost_eur_for_chars(1_000_000) == pytest.approx(18.4)
    assert cost_eur_for_chars(0) == 0.0
