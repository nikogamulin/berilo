"""Versioned registry of translation styles (system prompts + extra passes).

Translation quality is a property of the *prompt*, and Rubric T3 (fluency) is
flat at 12.4–13.5/20 across every book translated so far — a systemic property
of the translate stage rather than of any book. Improving it means swapping
prompts and measuring, which requires two things this module provides:

* **Named, versioned styles.** Every style carries an explicit
  :attr:`TranslationStyle.version` string, so any translation — and any rubric
  score row derived from it — traces back to the exact prompt that produced it.
  The version participates in the translation cache key
  (``cache.TranslationCache``), so switching styles correctly *misses* the cache
  instead of silently serving text produced by a different prompt.
* **Complete contracts.** A style supplies the batch system prompt, the strict
  retry prompt, *and* the single-segment fallback prompt, so a batch that falls
  back to per-segment translation does not silently regress to baseline
  quality. Styles may also declare extra passes: a per-book style memo
  (:attr:`TranslationStyle.book_context_system`) and a native-editor revision
  pass (:attr:`TranslationStyle.revise_system`).

:data:`BASELINE` reproduces the pre-registry prompts **byte-identically**;
``tests/test_prompts.py`` pins that against the literal historical text. The
same test pins a digest of every other style, so editing a style's text without
bumping its version — which would make the cache serve stale translations under
a version string that no longer describes them — fails loudly.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

# --------------------------------------------------------------------------
# Baseline prompt text (pre-registry, must not drift — see test_prompts.py).
# --------------------------------------------------------------------------

_BASELINE_BATCH_SYSTEM = (
    "You are a professional literary translator. Translate the MEANING, not the "
    "words: preserve register and tone, render idioms natively, and keep "
    "terminology consistent with the glossary. Preserve any inline HTML tags "
    "exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each source segment is "
    "prefixed with a marker like [[1]]. Return EVERY segment, each prefixed with "
    "its EXACT same marker, in the SAME order, and translate nothing else. Do "
    "not merge, split, add, or drop segments. Output only the marked "
    "translations."
)

#: Appended to a batch system prompt when the previous attempt did not return a
#: 1:1 marker mapping. Kept separate so every style gets the same strict retry.
STRICT_MARKER_CLAUSE = (
    " CRITICAL: the previous attempt did not return exactly one [[n]] marker "
    "per source segment. Return EXACTLY the same markers you were given — no "
    "more, no fewer — each on its own line followed by that segment's "
    "translation."
)

_BASELINE_SINGLE_SYSTEM = (
    "You are a professional literary translator. Translate the MEANING, not the "
    "words, preserving register, idioms, and any inline HTML tags exactly "
    "(<em>, <strong>, <i>, <b>, <sub>, <sup>). Reply with ONLY the translation, "
    "no markers, no commentary."
)

# --------------------------------------------------------------------------
# Slovenian style contract (docs/plans/2026-07-25-fluency-uplift.md §4.1).
# --------------------------------------------------------------------------

_SL_CONTRACT = (
    "SLOVENIAN STYLE CONTRACT — the result must read as prose written in "
    "Slovenian, not prose translated into it:\n"
    "- Do not calque English word order. Reorder to natural Slovenian "
    "information structure (known before new) and keep the clitic cluster "
    "(se, si, ga, je, bi, mu) in second position.\n"
    "- Prefer verbs to nominalizations: 'ko so preiskali' rather than 'po "
    "izvedbi preiskave'.\n"
    "- Drop subject pronouns that the verb ending already carries; keep one "
    "only for contrast or emphasis.\n"
    "- Avoid passive calques. NEVER render the English agentive 'by' as 's "
    "strani'; use an active clause, a reflexive passive ('se je zgodilo'), or "
    "an impersonal third-person plural.\n"
    "- Use the dual wherever exactly two entities are meant (dva/dve, sta, "
    "jima, njiju).\n"
    "- Write full šumniki (č, š, ž) and correct case endings; decline personal "
    "names, place names and borrowed terms as Slovenian requires.\n"
    "- Render idioms with their Slovenian equivalents, never word for word; "
    "where no equivalent exists, state the sense plainly.\n"
    "- Keep the source's paragraph breaks and roughly its sentence rhythm, but "
    "split an English sentence when Slovenian syntax would otherwise strain."
)

_BOOK_CONTEXT_SYSTEM = (
    "You brief a literary translator before they begin a book. From the title, "
    "author and opening excerpt, write ONE paragraph of at most 90 words that "
    "states the genre, the register (formal, colloquial, academic, "
    "journalistic), the narrator's voice and stance, and the two or three "
    "stylistic habits the translator must reproduce (sentence length, irony, "
    "technical density, use of jargon). Write it as instructions to the "
    "translator, in English. No preamble, no bullet points, no quoting of the "
    "excerpt."
)

_REVISE_SYSTEM = (
    "You are a native Slovenian editor revising a draft translation. Fix "
    "fluency only: calqued English word order, nominalizations that should be "
    "verbs, redundant subject pronouns, 's strani' and other passive calques, "
    "missing dual, missing or wrong šumniki, wrong case endings, and idioms "
    "rendered literally. You must NOT change the meaning, add or remove "
    "information, or alter names, numbers, or terminology fixed by the "
    "glossary. Preserve inline HTML tags exactly (<em>, <strong>, <i>, <b>, "
    "<sub>, <sup>). Each segment is prefixed with a marker like [[1]] followed "
    "by its SOURCE and its DRAFT. Return EVERY segment, each prefixed with its "
    "EXACT same marker, in the SAME order, containing only the revised "
    "translation. Do not merge, split, add, or drop segments. If a draft "
    "already reads naturally, return it unchanged. Output only the marked "
    "segments.\n\n" + _SL_CONTRACT
)


@dataclass(frozen=True)
class TranslationStyle:
    """One named, versioned translation prompt contract.

    Attributes:
        name: Registry key, e.g. ``"sl_style_v1"``.
        version: Version string written into the translation cache key. Bump it
            whenever any prompt text below changes, or cached translations
            produced by the old text will be served under the new style.
        description: One-line summary of the hypothesis this style encodes.
        batch_system: System prompt for the numbered batch completion.
        strict_system: System prompt for the strict retry after a 1:1 mismatch.
        single_system: System prompt for the per-segment fallback path, so
            fallback segments obey the same contract as batched ones.
        book_context_system: System prompt for a once-per-book style memo
            injected into every batch prompt, or ``None`` for no such pass.
        revise_system: System prompt for a second native-editor pass over each
            translated batch, or ``None`` for no revision pass.
    """

    name: str
    version: str
    description: str
    batch_system: str
    strict_system: str
    single_system: str
    book_context_system: str | None = None
    revise_system: str | None = None

    @property
    def revise_strict_system(self) -> str | None:
        """Strict retry prompt for the revision pass, or ``None`` if no pass."""
        if self.revise_system is None:
            return None
        return self.revise_system + STRICT_MARKER_CLAUSE

    @property
    def prompt_digest(self) -> str:
        """Short digest over every prompt string this style carries.

        Pinned per style in ``tests/test_prompts.py``: a text edit without a
        version bump changes the digest and fails that test.
        """
        payload = "\n\x00\n".join(
            [
                self.batch_system,
                self.strict_system,
                self.single_system,
                self.book_context_system or "",
                self.revise_system or "",
            ]
        )
        return hashlib.sha1(payload.encode("utf-8")).hexdigest()[:16]


def _sl_style_prompts() -> tuple[str, str, str]:
    """Return the (batch, strict, single) prompts carrying the Slovenian contract."""
    batch = _BASELINE_BATCH_SYSTEM + "\n\n" + _SL_CONTRACT
    return batch, batch + STRICT_MARKER_CLAUSE, _BASELINE_SINGLE_SYSTEM + "\n\n" + _SL_CONTRACT


_SL_BATCH, _SL_STRICT, _SL_SINGLE = _sl_style_prompts()

#: The pre-registry prompts, byte-identical. Default for every entry point, so
#: existing caches stay valid and today's output is unchanged.
BASELINE = TranslationStyle(
    name="baseline_v1",
    version="baseline_v1",
    description="Pre-registry prompts: generic literary-translation instruction.",
    batch_system=_BASELINE_BATCH_SYSTEM,
    strict_system=_BASELINE_BATCH_SYSTEM + STRICT_MARKER_CLAUSE,
    single_system=_BASELINE_SINGLE_SYSTEM,
)

SL_STYLE = TranslationStyle(
    name="sl_style_v1",
    version="sl_style_v1",
    description="Baseline plus an explicit Slovenian style contract.",
    batch_system=_SL_BATCH,
    strict_system=_SL_STRICT,
    single_system=_SL_SINGLE,
)

BOOK_CONTEXT = TranslationStyle(
    name="book_context_v1",
    version="book_context_v1",
    description="Slovenian contract plus a per-book style memo in every prompt.",
    batch_system=_SL_BATCH,
    strict_system=_SL_STRICT,
    single_system=_SL_SINGLE,
    book_context_system=_BOOK_CONTEXT_SYSTEM,
)

REVISE = TranslationStyle(
    name="revise_v1",
    version="revise_v1",
    description="Slovenian contract plus a native-editor revision pass per batch.",
    batch_system=_SL_BATCH,
    strict_system=_SL_STRICT,
    single_system=_SL_SINGLE,
    revise_system=_REVISE_SYSTEM,
)

_STYLES: dict[str, TranslationStyle] = {
    style.name: style for style in (BASELINE, SL_STYLE, BOOK_CONTEXT, REVISE)
}

#: Version string the cache uses for rows written before the registry existed.
BASELINE_VERSION = BASELINE.version


def get_style(name: str) -> TranslationStyle:
    """Look up a translation style by registry name.

    Args:
        name: Registry key, e.g. ``"baseline_v1"``.

    Returns:
        The registered :class:`TranslationStyle`.

    Raises:
        KeyError: If no style with that name is registered (message lists the
            available names, so a typo fails loudly rather than falling back to
            the default and silently changing quality).
    """
    try:
        return _STYLES[name]
    except KeyError:
        raise KeyError(f"unknown translation style {name!r}; available: {style_names()}") from None


def style_names() -> list[str]:
    """Return the registered style names in registration order."""
    return list(_STYLES)
