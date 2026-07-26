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
    "revise_generic_v1": "0bd76ab82aeb01b3",
}

#: Version strings that address rows in the real translation cache
#: (13,426 paid rows / ~€7 across 6 books as of 2026-07-26). A3 bound styles to
#: target languages; the binding must not touch either half of a style's cache
#: identity, so both the version and the prompt text are pinned per style.
_PRE_A3_VERSIONS = {
    "baseline_v1": "baseline_v1",
    "sl_style_v1": "sl_style_v1",
    "book_context_v1": "book_context_v1",
    "revise_v1": "revise_v1",
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


def test_registry_exposes_the_planned_styles() -> None:
    """The registry holds baseline, the three plan §4 variants, and A3's generic pair."""
    assert style_names() == [
        "baseline_v1",
        "sl_style_v1",
        "book_context_v1",
        "revise_v1",
        "revise_generic_v1",
    ]
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


# --------------------------------------------------------------------------
# A3 — language-bound styles (review finding 4).
# --------------------------------------------------------------------------


def test_a3_did_not_move_any_pre_existing_style_identity() -> None:
    """Binding a style to a language leaves both halves of its cache identity alone.

    The real cache holds 13,426 paid rows keyed on ``prompt_version``; the
    prompt text behind that version is pinned separately. If A3 had put
    ``target_langs`` into either, every one of those rows would be stranded and
    re-translating them costs real money.
    """
    contract = prompts._SL_CONTRACT
    for name, version in _PRE_A3_VERSIONS.items():
        style = get_style(name)
        assert style.version == version
        assert style.prompt_digest == _PINNED_DIGESTS[name]
    # revise_v1 specifically: two books' worth of paid rows live under it, so
    # its three prompt strings are compared to the historical text directly.
    revise = get_style("revise_v1")
    assert revise.batch_system == _PRE_REFACTOR_TRANSLATE_SYSTEM + "\n\n" + contract
    assert revise.strict_system == revise.batch_system + prompts.STRICT_MARKER_CLAUSE
    assert revise.single_system == _PRE_REFACTOR_SINGLE_SEGMENT_SYSTEM + "\n\n" + contract
    assert revise.revise_system is not None
    assert revise.revise_system.startswith("You are a native Slovenian editor")
    assert revise.revise_system.endswith(contract)


def test_slovenian_styles_declare_slovenian_and_nothing_else() -> None:
    """Every style whose prompts name Slovenian is bound to it; the others are open."""
    for name in ("sl_style_v1", "book_context_v1", "revise_v1"):
        assert get_style(name).target_langs == ("sl",)
    assert get_style("baseline_v1").target_langs is None
    assert get_style("revise_generic_v1").target_langs is None


def test_generic_revise_style_carries_a_second_pass_with_no_language_contract() -> None:
    """revise_generic_v1 is the two-pass structure minus every Slovenian rule."""
    generic = get_style("revise_generic_v1")
    assert generic.revise_system is not None
    assert generic.revise_strict_system == generic.revise_system + prompts.STRICT_MARKER_CLAUSE
    for prompt in (
        generic.batch_system,
        generic.strict_system,
        generic.single_system,
        generic.revise_system,
    ):
        assert "Slovenian" not in prompt
        assert "šumniki" not in prompt
        assert "s strani" not in prompt
    # It still holds the structural guarantees the batch parser depends on.
    assert "Do not merge, split, add, or drop segments." in generic.batch_system
    assert "Do not merge, split, add, or drop segments." in generic.revise_system
    assert "must NOT change the meaning" in generic.revise_system


@pytest.mark.parametrize("tag", ["sl", "SL", " sl ", "sl-SI", "sl_SI", "SL-si"])
def test_language_tags_reduce_to_their_primary_subtag(tag: str) -> None:
    """The matching rule the Android free-text field mirrors: strip, lower, cut at -/_."""
    assert prompts.normalize_lang(tag) == "sl"
    assert get_style("revise_v1").supports(tag)


@pytest.mark.parametrize("tag", ["de", "de-DE", "fr", "slv", "Slovenian", "en"])
def test_a_slovenian_style_does_not_claim_other_targets(tag: str) -> None:
    """Anything that does not reduce to 'sl' is out of revise_v1's declared scope."""
    assert not get_style("revise_v1").supports(tag)


def test_style_target_mismatch_is_a_loud_actionable_refusal() -> None:
    """--style revise_v1 --to de refuses and names the style that would have run."""
    with pytest.raises(prompts.StyleLanguageError) as excinfo:
        prompts.ensure_supports(get_style("revise_v1"), "de")
    message = str(excinfo.value)
    assert "revise_v1" in message
    assert "'de'" in message
    assert "revise_generic_v1" in message  # actionable, not merely loud


def test_language_agnostic_styles_never_refuse() -> None:
    """baseline_v1 and revise_generic_v1 translate into anything."""
    for name in ("baseline_v1", "revise_generic_v1"):
        prompts.ensure_supports(get_style(name), "de")
        prompts.ensure_supports(get_style(name), "sl")


def test_resolution_table_defaults_differ_by_execution_context() -> None:
    """Workstation resolves to the two-pass style; the device resolves to single-pass.

    Niko's 2026-07-26 decision: a multi-hour battery-powered job makes the
    quality tier an explicit choice, never a silent default.
    """
    workstation = prompts.resolve_style("sl", context=prompts.ExecutionContext.WORKSTATION)
    device = prompts.resolve_style("sl", context=prompts.ExecutionContext.DEVICE)

    assert workstation.name == "revise_v1"
    assert workstation.revise_system is not None  # two passes
    assert device.name == "baseline_v1"
    assert device.revise_system is None  # one pass
    # The workstation default is also the module-level DEFAULT, unchanged.
    assert workstation is prompts.DEFAULT


def test_resolution_table_defaults_differ_by_target_language() -> None:
    """A non-Slovenian target never resolves to a Slovenian-bound style."""
    assert prompts.resolve_style("de").name == "revise_generic_v1"
    assert prompts.resolve_style("fr").name == "revise_generic_v1"
    assert prompts.resolve_style("sl-SI").name == "revise_v1"
    for tag in ("de", "fr", "sl", "Slovenian"):
        assert prompts.resolve_style(tag).supports(tag)


def test_the_device_quality_toggle_resolves_to_the_two_pass_style() -> None:
    """B7's "higher quality, ~2× cost" toggle is a tier override, not a context lie."""
    opt_in = prompts.resolve_style(
        "sl",
        context=prompts.ExecutionContext.DEVICE,
        tier=prompts.StyleTier.QUALITY,
    )
    assert opt_in.name == "revise_v1"
    assert prompts.default_tier(prompts.ExecutionContext.DEVICE) is prompts.StyleTier.ECONOMY
    assert prompts.default_tier(prompts.ExecutionContext.WORKSTATION) is prompts.StyleTier.QUALITY


def test_an_explicit_style_is_honoured_but_only_when_it_fits() -> None:
    """resolve_style respects --style, and refuses it rather than contradicting itself."""
    assert prompts.resolve_style("sl", requested=get_style("sl_style_v1")).name == "sl_style_v1"
    assert prompts.resolve_style("de", requested=get_style("baseline_v1")).name == "baseline_v1"
    with pytest.raises(prompts.StyleLanguageError):
        prompts.resolve_style("de", requested=get_style("revise_v1"))


def test_every_resolvable_style_covers_the_language_it_was_resolved_for() -> None:
    """The table can never hand back a style that its own guard would refuse."""
    for context in prompts.ExecutionContext:
        for tier in prompts.StyleTier:
            for tag in ("sl", "sl-SI", "de", "ja", "pt-BR", "xyz"):
                style = prompts.resolve_style(tag, context=context, tier=tier)
                prompts.ensure_supports(style, tag)  # must not raise
