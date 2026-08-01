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

**Styles are bound to target languages** (review finding 4). A style whose
prompts name a specific language declares it in
:attr:`TranslationStyle.target_langs`; :func:`resolve_style` picks the right
style for a ``(target language, execution context)`` pair and *refuses* an
explicit style that contradicts the target instead of billing a two-pass run
whose editor pass argues with its own translation pass. The declaration never
reaches the model, so it is deliberately outside :attr:`prompt_digest` and
outside :attr:`version`: binding an existing style to a language does **not**
change its cache identity.
"""

from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass
from enum import Enum

logger = logging.getLogger(__name__)

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

# --------------------------------------------------------------------------
# Language-agnostic editor pass (review finding 4): the same two-pass structure
# as _REVISE_SYSTEM with every Slovenian-specific rule replaced by its
# language-neutral statement, so a target other than Slovenian gets the
# measured benefit of a second pass without contradictory instructions.
# --------------------------------------------------------------------------

_GENERIC_REVISE_SYSTEM = (
    "You are a native editor of the target language revising a draft "
    "translation. Fix fluency only: word order calqued from the source, "
    "nominalizations that the target language would express as verbs, pronouns "
    "and articles the target language's morphology makes redundant, passive "
    "constructions the target language would render actively, wrong inflection, "
    "agreement or case, missing or wrong diacritics, and idioms rendered word "
    "for word instead of by their native equivalent. The result must read as "
    "prose written in the target language, not prose translated into it. You "
    "must NOT change the meaning, add or remove information, or alter names, "
    "numbers, or terminology fixed by the glossary. Preserve inline HTML tags "
    "exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each segment is prefixed "
    "with a marker like [[1]] followed by its SOURCE and its DRAFT. Return "
    "EVERY segment, each prefixed with its EXACT same marker, in the SAME "
    "order, containing only the revised translation. Do not merge, split, add, "
    "or drop segments. If a draft already reads naturally, return it unchanged. "
    "Output only the marked segments."
)

# --------------------------------------------------------------------------
# Language tags.
# --------------------------------------------------------------------------

#: Separators that introduce a region/script subtag in a BCP-47-ish language
#: tag. Everything from the first one onward is dropped when matching a style's
#: declared languages, so ``sl-SI`` and ``sl_SI`` both match ``sl``.
_LANG_SUBTAG_SEPARATORS = ("-", "_")


def normalize_lang(target_lang: str) -> str:
    """Reduce a target-language tag to the primary subtag used for matching.

    The rule, in full, because the Android app mirrors it against a free-text
    target-language field: strip surrounding whitespace, lowercase, then keep
    everything before the first ``-`` or ``_``. Nothing else is interpreted —
    no ISO 639-2 → 639-1 mapping, no language-name lookup. A tag that does not
    reduce to a declared code simply matches no language-bound style and
    resolves to the language-agnostic one, which is a correct translation
    contract rather than a failure.

    Args:
        target_lang: Target language tag as the user supplied it.

    Returns:
        The lowercase primary subtag, e.g. ``"sl"`` for ``" sl-SI "``.
    """
    tag = target_lang.strip().lower()
    for separator in _LANG_SUBTAG_SEPARATORS:
        tag = tag.split(separator, 1)[0]
    return tag


class StyleLanguageError(ValueError):
    """A translation style was asked to run against a language it does not cover.

    Raised instead of letting a Slovenian-specific system prompt drive a run
    whose user message says "Translate into: de" — contradictory instructions
    billed at the two-pass rate (review finding 4).
    """


class ExecutionContext(Enum):
    """Where a translation run executes, which sets its *default* style tier.

    The tablet is a materially different context from the workstation: a full
    book is a multi-hour job on battery, so the quality tier is an explicit
    user choice there rather than a silent default (plan §6, resolved by Niko
    2026-07-26).
    """

    WORKSTATION = "workstation"
    DEVICE = "device"


class StyleTier(Enum):
    """How much the caller is willing to spend per segment.

    ``ECONOMY`` is one API call per batch; ``QUALITY`` adds the native-editor
    pass, roughly doubling calls and cost for a measured Rubric T gain.
    """

    ECONOMY = "economy"
    QUALITY = "quality"


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
        target_langs: Primary language subtags this style's prompts are written
            for, or ``None`` when the prompts name no language and the style
            therefore covers any target. **Not part of the style's identity** —
            it never reaches the model, so declaring it on an existing style
            leaves every cached row addressable (see module docstring).
    """

    name: str
    version: str
    description: str
    batch_system: str
    strict_system: str
    single_system: str
    book_context_system: str | None = None
    revise_system: str | None = None
    target_langs: tuple[str, ...] | None = None

    def supports(self, target_lang: str) -> bool:
        """Return whether this style's prompts are written for ``target_lang``.

        Args:
            target_lang: Target language tag as the user supplied it; reduced
                by :func:`normalize_lang` before matching.

        Returns:
            ``True`` for a language-agnostic style, or when the tag's primary
            subtag is one of :attr:`target_langs`.
        """
        if self.target_langs is None:
            return True
        return normalize_lang(target_lang) in self.target_langs

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

#: Primary subtag of the only language any shipped style is written for.
SLOVENIAN = "sl"

SL_STYLE = TranslationStyle(
    name="sl_style_v1",
    version="sl_style_v1",
    description="Baseline plus an explicit Slovenian style contract.",
    batch_system=_SL_BATCH,
    strict_system=_SL_STRICT,
    single_system=_SL_SINGLE,
    target_langs=(SLOVENIAN,),
)

BOOK_CONTEXT = TranslationStyle(
    name="book_context_v1",
    version="book_context_v1",
    description="Slovenian contract plus a per-book style memo in every prompt.",
    batch_system=_SL_BATCH,
    strict_system=_SL_STRICT,
    single_system=_SL_SINGLE,
    book_context_system=_BOOK_CONTEXT_SYSTEM,
    target_langs=(SLOVENIAN,),
)

REVISE = TranslationStyle(
    name="revise_v1",
    version="revise_v1",
    description="Slovenian contract plus a native-editor revision pass per batch.",
    batch_system=_SL_BATCH,
    strict_system=_SL_STRICT,
    single_system=_SL_SINGLE,
    revise_system=_REVISE_SYSTEM,
    target_langs=(SLOVENIAN,),
)

#: Two-pass structure for every target Berilo has no language contract for.
#: Carries the baseline (language-agnostic) translation prompts plus a
#: language-neutral editor pass, so ``--to de`` gets the measured benefit of a
#: second pass without a Slovenian editor arguing with a German draft.
REVISE_GENERIC = TranslationStyle(
    name="revise_generic_v1",
    version="revise_generic_v1",
    description="Baseline prompts plus a language-neutral native-editor revision pass.",
    batch_system=_BASELINE_BATCH_SYSTEM,
    strict_system=_BASELINE_BATCH_SYSTEM + STRICT_MARKER_CLAUSE,
    single_system=_BASELINE_SINGLE_SYSTEM,
    revise_system=_GENERIC_REVISE_SYSTEM,
)

_STYLES: dict[str, TranslationStyle] = {
    style.name: style for style in (BASELINE, SL_STYLE, BOOK_CONTEXT, REVISE, REVISE_GENERIC)
}

#: Version string the cache uses for rows written before the registry existed.
BASELINE_VERSION = BASELINE.version

# Default style, set by the E2 bake-off on 2026-07-25 (Kaplan, 6 contiguous
# runs x 10 segments, cluster-bootstrapped, seed 42; see
# loops/build/ledger.jsonl). Against baseline_v1, revise_v1 won BOTH judged
# dimensions with CI lower bounds above zero — T3 fluency +0.48 [+0.17, +0.87]
# and T2 meaning +0.23 [+0.07, +0.45] — while sl_style_v1 (the same Slovenian
# contract WITHOUT the revision pass) was null on fluency (+0.05 [-0.10, +0.22])
# and hinted at a meaning regression. The gain therefore comes from the second
# editing pass, not from the style contract. revise_v1 costs ~2.9x baseline in
# translation calls but measures EUR1.23/100k words, inside Rubric T7's EUR1.50
# full-marks threshold, which is why it ships as the default rather than an
# opt-in flag.
DEFAULT = REVISE

#: Name of the default style (used as the CLI default for ``--style``).
DEFAULT_STYLE_NAME = DEFAULT.name

# --------------------------------------------------------------------------
# Resolution table (plan §3.3 + the §6 decision of 2026-07-26).
# --------------------------------------------------------------------------

#: ``(tier, primary language subtag) -> style``. ``None`` as the language key
#: is the fallback row for every target with no language-specific contract.
#:
#: The ECONOMY row is ``baseline_v1`` for Slovenian too, and that is a measured
#: choice, not an oversight: the E2 bake-off (2026-07-25, loops/build/ledger.jsonl)
#: found ``sl_style_v1`` — the same Slovenian contract *without* the second pass —
#: null on fluency (+0.05 [-0.10, +0.22]) and hinting at a meaning regression
#: against ``baseline_v1``. The gain lives in the editor pass, so a single-pass
#: run buys nothing by paying for the contract's extra prompt tokens.
_STYLE_TABLE: dict[tuple[StyleTier, str | None], TranslationStyle] = {
    (StyleTier.QUALITY, SLOVENIAN): REVISE,
    (StyleTier.QUALITY, None): REVISE_GENERIC,
    (StyleTier.ECONOMY, SLOVENIAN): BASELINE,
    (StyleTier.ECONOMY, None): BASELINE,
}

#: The tier each execution context defaults to. Resolved by Niko 2026-07-26:
#: single-pass is the on-device default with the quality tier an explicit,
#: cost-labelled opt-in; the workstation keeps the two-pass default.
_DEFAULT_TIER: dict[ExecutionContext, StyleTier] = {
    ExecutionContext.WORKSTATION: StyleTier.QUALITY,
    ExecutionContext.DEVICE: StyleTier.ECONOMY,
}


def default_tier(context: ExecutionContext) -> StyleTier:
    """Return the style tier ``context`` runs at unless the user overrides it."""
    return _DEFAULT_TIER[context]


def ensure_supports(style: TranslationStyle, target_lang: str) -> None:
    """Refuse loudly when ``style`` is not written for ``target_lang``.

    Args:
        style: The style about to be run.
        target_lang: Target language tag the run will translate into.

    Raises:
        StyleLanguageError: If the style declares target languages and
            ``target_lang`` is not one of them. The message names the style,
            its declared languages, and the style that *would* have resolved,
            so the refusal is actionable rather than merely loud.
    """
    if style.supports(target_lang):
        return
    declared = ", ".join(style.target_langs or ())
    suggestion = _STYLE_TABLE[
        (StyleTier.QUALITY if style.revise_system is not None else StyleTier.ECONOMY, None)
    ]
    raise StyleLanguageError(
        f"translation style {style.name!r} is written for {declared} only and "
        f"cannot translate into {target_lang!r}: its prompts would instruct the "
        f"model in one language while the request asks for another. "
        f"Use --style {suggestion.name} (or drop --style to resolve automatically)."
    )


def resolve_style(
    target_lang: str,
    *,
    requested: TranslationStyle | None = None,
    context: ExecutionContext = ExecutionContext.WORKSTATION,
    tier: StyleTier | None = None,
) -> TranslationStyle:
    """Resolve the translation style for a target language and execution context.

    The rule, in full — the Android side mirrors it against a free-text
    target-language field, so it is stated here rather than spread across
    callers:

    1. An explicitly ``requested`` style is honoured, but only after
       :func:`ensure_supports` — a mismatch is a refusal, never a silent
       contradiction.
    2. Otherwise the tier is ``tier`` if given, else :func:`default_tier` for
       ``context`` (workstation → QUALITY, device → ECONOMY).
    3. The style is ``_STYLE_TABLE[(tier, normalize_lang(target_lang))]`` if
       that language has a row, else the table's language-agnostic row.

    Args:
        target_lang: Target language tag (``"sl"``, ``"de"``, ``"sl-SI"``…).
        requested: A style the user named explicitly, or ``None`` to resolve.
        context: Where the run executes; sets the default tier.
        tier: Explicit tier override (the app's "higher quality, ~2× cost"
            toggle), or ``None`` to take the context's default.

    Returns:
        The resolved :class:`TranslationStyle`.

    Raises:
        StyleLanguageError: If ``requested`` does not cover ``target_lang``.
    """
    if requested is not None:
        ensure_supports(requested, target_lang)
        logger.info(
            "Translation style %s requested explicitly for target %r.",
            requested.name,
            target_lang,
        )
        return requested

    effective_tier = tier if tier is not None else default_tier(context)
    lang = normalize_lang(target_lang)
    key = (effective_tier, lang if (effective_tier, lang) in _STYLE_TABLE else None)
    style = _STYLE_TABLE[key]
    logger.info(
        "Resolved translation style %s for target %r (context=%s, tier=%s).",
        style.name,
        target_lang,
        context.value,
        effective_tier.value,
    )
    return style


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
