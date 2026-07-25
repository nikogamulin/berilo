"""Tests for the versioned translation-prompt registry (S1.10).

The registry's whole purpose is that a prompt change is *never* silent, so
these tests are deliberately literal:

* ``baseline_v1`` is pinned against the pre-registry prompt text, pasted below
  verbatim. If the refactor drifted by a single character, five books' worth of
  cached translations would no longer match the prompt they are attributed to.
* Every other style's prompt text is pinned by digest. Editing a style without
  bumping its version fails here, which is the failure this story exists to
  prevent: the cache would serve text produced by the old wording under the new
  version string.

No LLM client is constructed; nothing here costs anything.
"""

from __future__ import annotations

import pytest

from berilo import prompts
from berilo.prompts import BASELINE, TranslationStyle, get_style, style_names

# --------------------------------------------------------------------------
# Pre-refactor prompt text, copied verbatim from translate.py before S1.10.
# --------------------------------------------------------------------------

_PRE_REFACTOR_TRANSLATE_SYSTEM = (
    "You are a professional literary translator. Translate the MEANING, not the "
    "words: preserve register and tone, render idioms natively, and keep "
    "terminology consistent with the glossary. Preserve any inline HTML tags "
    "exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each source segment is "
    "prefixed with a marker like [[1]]. Return EVERY segment, each prefixed with "
    "its EXACT same marker, in the SAME order, and translate nothing else. Do "
    "not merge, split, add, or drop segments. Output only the marked "
    "translations."
)

_PRE_REFACTOR_TRANSLATE_SYSTEM_STRICT = (
    _PRE_REFACTOR_TRANSLATE_SYSTEM
    + " CRITICAL: the previous attempt did not return exactly one [[n]] marker "
    "per source segment. Return EXACTLY the same markers you were given — no "
    "more, no fewer — each on its own line followed by that segment's "
    "translation."
)

_PRE_REFACTOR_SINGLE_SEGMENT_SYSTEM = (
    "You are a professional literary translator. Translate the MEANING, not the "
    "words, preserving register, idioms, and any inline HTML tags exactly "
    "(<em>, <strong>, <i>, <b>, <sub>, <sup>). Reply with ONLY the translation, "
    "no markers, no commentary."
)

#: Digest of every registered style's prompt text. A deliberate prompt edit
#: must bump the style's ``version`` (and this pin) in the same commit.
_PINNED_DIGESTS = {
    "baseline_v1": "b6199d788ddac339",
    "sl_style_v1": "feae5d1328bf4c6e",
    "book_context_v1": "ba3ed8b8e73c0474",
    "revise_v1": "66f0704615450652",
}


def test_baseline_prompts_are_byte_identical_to_pre_refactor_text() -> None:
    """baseline_v1 reproduces the pre-registry prompts exactly, character for character."""
    assert BASELINE.batch_system == _PRE_REFACTOR_TRANSLATE_SYSTEM
    assert BASELINE.strict_system == _PRE_REFACTOR_TRANSLATE_SYSTEM_STRICT
    assert BASELINE.single_system == _PRE_REFACTOR_SINGLE_SEGMENT_SYSTEM
    assert BASELINE.version == "baseline_v1"
    # The baseline declares no extra passes: today's run shape is unchanged.
    assert BASELINE.book_context_system is None
    assert BASELINE.revise_system is None
    assert BASELINE.revise_strict_system is None


def test_registry_exposes_the_four_planned_styles() -> None:
    """The registry holds baseline plus the three plan §4 variants, keyed by name."""
    assert style_names() == ["baseline_v1", "sl_style_v1", "book_context_v1", "revise_v1"]
    for name in style_names():
        style = get_style(name)
        assert style.name == name
        assert style.version == name  # version is traceable from the score row
        assert style.description


def test_unknown_style_fails_loudly() -> None:
    """A typo raises instead of silently falling back to the default prompt."""
    with pytest.raises(KeyError, match="unknown translation style"):
        get_style("sl_style_v2")


@pytest.mark.parametrize("name", ["sl_style_v1", "book_context_v1", "revise_v1"])
def test_variants_carry_the_slovenian_contract_on_every_path(name: str) -> None:
    """Batch, strict retry AND single-segment fallback all carry the contract.

    The fallback path is the one that regresses silently: without this, a batch
    that degrades to per-segment translation would be produced by the baseline
    prompt yet cached under the variant's version.
    """
    style = get_style(name)
    for prompt in (style.batch_system, style.strict_system, style.single_system):
        assert "SLOVENIAN STYLE CONTRACT" in prompt
        assert "s strani" in prompt  # the passive-calque ban
        assert "dual" in prompt
        assert "šumniki" in prompt
    # The structural 1:1 marker rules survive in the batch prompts.
    assert "Do not merge, split, add, or drop segments." in style.batch_system
    assert style.strict_system.endswith(prompts.STRICT_MARKER_CLAUSE)


def test_only_declared_styles_request_extra_passes() -> None:
    """book_context_v1 asks for a memo; revise_v1 asks for an editor pass; others don't."""
    assert get_style("sl_style_v1").book_context_system is None
    assert get_style("sl_style_v1").revise_system is None

    book_context = get_style("book_context_v1")
    assert book_context.book_context_system is not None
    assert "90 words" in book_context.book_context_system
    assert book_context.revise_system is None

    revise = get_style("revise_v1")
    assert revise.revise_system is not None
    assert "must NOT change the meaning" in revise.revise_system
    assert revise.revise_strict_system == revise.revise_system + prompts.STRICT_MARKER_CLAUSE
    assert revise.book_context_system is None


def test_prompt_text_is_pinned_to_its_version() -> None:
    """Changing any style's wording without bumping its version fails here."""
    actual = {name: get_style(name).prompt_digest for name in style_names()}
    assert actual == _PINNED_DIGESTS


def test_styles_are_immutable() -> None:
    """A style cannot be mutated at runtime behind the cache's back."""
    with pytest.raises(Exception):  # FrozenInstanceError is a dataclass detail
        BASELINE.batch_system = "something else"  # type: ignore[misc]
    assert isinstance(BASELINE, TranslationStyle)
