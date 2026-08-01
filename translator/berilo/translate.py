"""Translate stage: batched, glossary-guided, resumable book translation.

Translates a :class:`~berilo.models.Book`'s segments in paragraph batches with
rolling context and a per-book glossary, resumable via a SQLite cache keyed on
``(book_hash, segment_hash, model, lang, prompt_version)``. The stage upholds
the product's two non-negotiable guarantees (CLAUDE.md §2):

* **Segment integrity.** The returned book has exactly the same segments — same
  count, order, IDs, positions, and types — as the input. Only ``text`` changes.
  A segment is never silently dropped; a segment that cannot be translated after
  a stricter retry and a per-segment fallback raises :class:`TranslationError`
  naming the book, chapter, and segment (loud failure).
* **Resumability.** Each batch's translations are written to the cache in one
  transaction immediately after the batch returns, so a killed run re-bills
  nothing: already-cached segments are never re-sent.

Batching groups consecutive source segments (respecting chapter boundaries)
into a single numbered completion (``[[1]]`` … ``[[n]]``); the previous batch's
last few source/target pairs are prepended as *context, do not retranslate* so
style and terminology stay coherent across chunk boundaries.

Which prompts are used is a parameter, not a constant: every entry point takes
a :class:`~berilo.prompts.TranslationStyle`, defaulting to
:data:`~berilo.prompts.BASELINE` so today's output is unchanged. A style may
also require extra passes — a once-per-book style memo injected into every
prompt, and a native-editor revision pass over each translated batch — both of
which are accounted for in the run's cost and in :func:`estimate_cost`.

This module also provides :func:`estimate_cost`, the no-API cost estimator that
backs ``berilo translate --dry-run``. Its estimate deliberately includes a
reasoning-token surcharge for gpt-5-class models (see
:data:`REASONING_TOKENS_PER_CALL`), because those models bill hidden reasoning
tokens as output and a naive chars/4 estimate underestimates badly.
"""

from __future__ import annotations

import dataclasses
import logging
import re
from collections.abc import Callable, Collection, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, replace

from berilo.cache import (
    CallRecord,
    SegmentTranslation,
    TranslationCache,
    book_hash,
    segment_hash,
)
from berilo.glossary import Glossary, glossary_identity
from berilo.models import Book, Segment
from berilo.plan import (
    DEFAULT_BATCH_SIZE,
    DEFAULT_CONCURRENCY,
    DEFAULT_CONTEXT_PAIRS,
    PositionedBatch,
    build_translation_plan,
    plan_waves,
)
from berilo.prompts import BASELINE, TranslationStyle, ensure_supports
from berilo.providers.base import (
    CompletionResult,
    ContentPolicyError,
    EmptyCompletionError,
    LLMClient,
    TruncatedCompletionError,
)
from berilo.providers.google_translate import GoogleTranslateClient

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------
# Tunables (named constants — no magic numbers).
# --------------------------------------------------------------------------

#: Marker wrapping each segment's ordinal in the prompt and expected in the
#: reply, e.g. ``[[1]]``. Anchored to the start of a line because that is where
#: :func:`_numbered_source_block` puts it and where both system prompts demand
#: it back ("each on its own line"); a ``[[2]]`` occurring *inside* a
#: translation is prose, not a marker (review finding 14).
_ANCHORED_MARKER_RE = re.compile(r"^[ \t]*\[\[(\d+)\]\]", re.MULTILINE)

#: The pre-anchoring pattern, kept as a second parsing attempt so a reply that
#: puts several markers on one line still parses exactly as it did before —
#: anchoring may only remove needless retries, never add one.
_LOOSE_MARKER_RE = re.compile(r"\[\[(\d+)\]\]")

# --------------------------------------------------------------------------
# Cost-estimation constants.
# --------------------------------------------------------------------------

#: Rough characters-per-token ratio for English prose (dry-run heuristic only).
CHARS_PER_TOKEN = 4

#: Estimated target/source output-length ratio. Slovenian runs slightly longer
#: than English on average; used only by the dry-run estimator.
TARGET_EXPANSION = 1.1

#: Fixed prompt scaffolding tokens per batch call (system instruction, glossary
#: block, rolling context, and the ``[[n]]`` numbering), added to the estimate.
PROMPT_OVERHEAD_TOKENS_PER_BATCH = 400

# Evidence (docs/findings.md, 2026-07-24): the ``doctor`` one-sentence smoke on
# gpt-5-mini billed 479 output tokens for a ~15-token *visible* translation
# (479 / 15 ≈ 32×). The model bills hidden reasoning tokens as output. That
# overhead is dominated by a roughly FIXED per-CALL reasoning budget rather than
# scaling with output length, so applying the raw 32× multiplier to a long
# batch would wildly overestimate. We therefore model the reasoning surcharge as
# a fixed number of output tokens added *per API call*: (479 − 15) ≈ 464. On a
# one-segment call this reproduces the observed ~32× inflation; across a full
# book it amortizes to a realistic figure inside the spec's €0.40–0.80 range.
_REASONING_EVIDENCE_OUTPUT_TOKENS = 479
_REASONING_EVIDENCE_VISIBLE_TOKENS = 15

#: Reasoning-token surcharge added per API call for reasoning-billing models.
REASONING_TOKENS_PER_CALL = _REASONING_EVIDENCE_OUTPUT_TOKENS - _REASONING_EVIDENCE_VISIBLE_TOKENS

#: Model-name prefixes that bill hidden reasoning tokens as output.
REASONING_BILLING_MODEL_PREFIXES = ("gpt-5", "o1", "o3", "o4")

# --------------------------------------------------------------------------
# Back-matter detection (docs/findings.md: back matter ≈ 47% of segments).
# --------------------------------------------------------------------------

#: Lowercased substrings that mark a chapter title as back matter. Matched
#: case-insensitively against the chapter title; used only when the caller opts
#: into ``--skip-back-matter`` (default OFF for safety).
BACK_MATTER_TITLE_PATTERNS = (
    "index",
    "endnotes",
    "notes",
    "bibliography",
    "acknowledg",
    "about the author",
)

# --------------------------------------------------------------------------
# Prompts. The text itself lives in the versioned registry (berilo/prompts.py);
# this stage only chooses which style to run and how to render its extra passes.
# --------------------------------------------------------------------------

#: Source characters sampled from the book's opening to derive the per-book
#: style memo for styles that declare a ``book_context_system``.
BOOK_CONTEXT_EXCERPT_CHARS = 6_000

#: Estimated output tokens for one book-context memo call (≤ 90 words).
BOOK_CONTEXT_OUTPUT_TOKENS = 200


class TranslationError(RuntimeError):
    """Raised when a segment cannot be translated after all fallbacks.

    The message names the book, chapter, and segment so the failure is loud and
    actionable, per the segment-integrity guarantee.
    """


@dataclass
class TranslationStats:
    """Mutable running totals for one :func:`translate_book` invocation.

    Attributes:
        total_segments: Total segments in the book.
        translated_segments: Segments newly translated via the API this run.
        cached_segments: Segments served from the cache this run.
        skipped_segments: Segments passed through untranslated (back matter).
        empty_segments: Empty/whitespace segments passed through unchanged.
        api_calls: Number of API calls made this run.
        input_tokens: Total input tokens billed this run.
        output_tokens: Total output tokens billed this run.
        cost_eur: Total EUR cost this run.
        revision_failures: Batches whose revision pass could not be applied
            (the first-pass translation was kept — integrity is preserved but
            those segments only have the un-revised quality).
        current_chapter_index: Chapter index of the most recent batch.
        current_chapter_title: Chapter title of the most recent batch.
    """

    total_segments: int
    translated_segments: int = 0
    cached_segments: int = 0
    skipped_segments: int = 0
    empty_segments: int = 0
    api_calls: int = 0
    revision_failures: int = 0
    mt_characters: int = 0
    mt_cost_eur: float = 0.0
    input_tokens: int = 0
    output_tokens: int = 0
    cost_eur: float = 0.0
    current_chapter_index: int | None = None
    current_chapter_title: str | None = None

    @property
    def processed_segments(self) -> int:
        """Segments accounted for so far (translated + cached + skipped + empty)."""
        return (
            self.translated_segments
            + self.cached_segments
            + self.skipped_segments
            + self.empty_segments
        )


ProgressCallback = Callable[[TranslationStats], None]


def is_back_matter_title(title: str | None) -> bool:
    """Return ``True`` if ``title`` names a back-matter chapter.

    Args:
        title: Chapter title (may be ``None``).

    Returns:
        Whether the title matches a known back-matter pattern.
    """
    if not title:
        return False
    lowered = title.strip().lower()
    return any(pattern in lowered for pattern in BACK_MATTER_TITLE_PATTERNS)


def back_matter_segment_ids(book: Book) -> set[str]:
    """Return the IDs of segments in back-matter chapters.

    Args:
        book: The source book.

    Returns:
        The set of segment IDs whose chapter title matches a back-matter
        pattern (see :func:`is_back_matter_title`).
    """
    return {segment.id for segment in book.segments if is_back_matter_title(segment.chapter_title)}


def _is_reasoning_model(model: str) -> bool:
    """Return ``True`` when ``model`` bills hidden reasoning tokens as output."""
    return model.startswith(REASONING_BILLING_MODEL_PREFIXES)


def _numbered_source_block(segments: Sequence[Segment]) -> str:
    """Render segments as a numbered ``[[n]] text`` block for the prompt."""
    return "\n\n".join(f"[[{i}]] {seg.text}" for i, seg in enumerate(segments, start=1))


def _context_block(context_pairs: Sequence[tuple[str, str]]) -> str:
    """Render rolling context as a do-not-retranslate block."""
    if not context_pairs:
        return ""
    lines = [f"SOURCE: {source}\nTRANSLATION: {target}" for source, target in context_pairs]
    return (
        "CONTEXT (already translated — for style/terminology continuity only; "
        "DO NOT retranslate or include these in your reply):\n" + "\n\n".join(lines)
    )


def _book_context_block(book_context: str | None) -> str:
    """Render the per-book style memo as a prompt block (empty when absent)."""
    if not book_context:
        return ""
    return "BOOK STYLE MEMO (apply to every segment):\n" + book_context.strip()


def _build_batch_prompt(
    segments: Sequence[Segment],
    glossary: Glossary | None,
    context_pairs: Sequence[tuple[str, str]],
    target_lang: str,
    book_context: str | None = None,
) -> str:
    """Assemble the user prompt for one batch completion.

    Args:
        segments: The batch's source segments.
        glossary: Optional glossary injected into the prompt.
        context_pairs: Rolling context (previous source/target pairs).
        target_lang: Target language code.
        book_context: Optional per-book style memo injected into every prompt.

    Returns:
        The assembled prompt string.
    """
    blocks: list[str] = [f"Translate into: {target_lang}."]
    memo = _book_context_block(book_context)
    if memo:
        blocks.append(memo)
    if glossary is not None and not glossary.is_empty():
        blocks.append(glossary.to_prompt_block())
    context = _context_block(context_pairs)
    if context:
        blocks.append(context)
    blocks.append(
        "Translate each of the following numbered segments. Reply with each "
        "segment's marker followed by its translation:"
    )
    blocks.append(_numbered_source_block(segments))
    return "\n\n".join(blocks)


def _split_on_markers(text: str, pattern: re.Pattern[str], expected_count: int) -> list[str]:
    """Split ``text`` on ``pattern``'s markers into a 1:1 list of translations.

    Args:
        text: The model's reply.
        pattern: Marker pattern to scan with (anchored or loose).
        expected_count: The number of segments that were sent.

    Returns:
        ``expected_count`` non-empty translations, in order 1..n.

    Raises:
        ValueError: If this pattern does not yield exactly that mapping.
    """
    matches = list(pattern.finditer(text))
    parsed: dict[int, str] = {}
    for i, match in enumerate(matches):
        index = int(match.group(1))
        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        parsed[index] = text[start:end].strip()

    if len(matches) != expected_count or len(parsed) != expected_count:
        raise ValueError(
            f"expected {expected_count} numbered segments, "
            f"found {len(matches)} markers ({len(parsed)} distinct)"
        )
    out: list[str] = []
    for n in range(1, expected_count + 1):
        value = parsed.get(n)
        if not value:
            raise ValueError(f"segment [[{n}]] missing or empty in reply")
        out.append(value)
    return out


def parse_numbered_response(text: str, expected_count: int) -> list[str]:
    """Parse a ``[[n]] translation`` reply into an ordered list of translations.

    Markers are looked for at the **start of a line** first, because that is
    where both the numbered source block and the system prompts put them. A
    ``[[2]]`` that occurs mid-sentence inside a translation ("element ``[[2]]``
    of the array") is therefore prose, not a fourth marker in a three-segment
    batch — before this anchoring it forced a strict retry and possibly a
    per-segment fallback, pure wasted spend (review finding 14).

    If line-anchored scanning does not produce a 1:1 mapping, the reply is
    re-scanned with the old unanchored pattern before failing, so a model that
    packs several markers onto one line parses exactly as it always did.
    Anchoring can only remove retries, never introduce one.

    Args:
        text: The model's reply.
        expected_count: The number of segments that were sent.

    Returns:
        A list of ``expected_count`` non-empty translations, in order 1..n.

    Raises:
        ValueError: If the reply has missing, extra, duplicate, or empty
            markers under both scans — i.e. it does not map 1:1 onto the sent
            segments.
    """
    try:
        return _split_on_markers(text, _ANCHORED_MARKER_RE, expected_count)
    except ValueError:
        # Fall through to the historical unanchored scan, which also owns the
        # diagnostic message when the reply is genuinely not 1:1.
        return _split_on_markers(text, _LOOSE_MARKER_RE, expected_count)


def _translate_single(
    segment: Segment,
    *,
    client: LLMClient,
    glossary: Glossary | None,
    target_lang: str,
    book_title: str,
    style: TranslationStyle = BASELINE,
    book_context: str | None = None,
) -> tuple[str, CompletionResult]:
    """Translate one segment on its own (per-segment fallback path).

    Uses the *style's* single-segment prompt and the same per-book memo as the
    batch path, so a batch that degrades to this fallback keeps the style's
    quality contract instead of silently reverting to baseline.

    This is the last rung of the retry ladder — there is no smaller unit to
    degrade to — so an empty or truncated response (:class:`EmptyCompletionError`
    / :class:`TruncatedCompletionError`) is a loud failure here, unlike at the
    batch level where the same conditions trigger a smaller retry instead.

    Raises:
        TranslationError: If the model returns an empty translation, or the
            provider reports the call was empty/truncated.
    """
    blocks: list[str] = [f"Translate into {target_lang}."]
    memo = _book_context_block(book_context)
    if memo:
        blocks.append(memo)
    if glossary is not None and not glossary.is_empty():
        blocks.append(glossary.to_prompt_block())
    blocks.append(segment.text)
    try:
        result = client.complete(prompt="\n\n".join(blocks), system=style.single_system)
    except (EmptyCompletionError, TruncatedCompletionError) as exc:
        raise TranslationError(
            f"Empty translation for segment {segment.id} "
            f"(chapter {segment.chapter_index} "
            f"{segment.chapter_title!r}) in book {book_title!r}: {exc}"
        ) from exc
    text = result.text.strip()
    if not text:
        raise TranslationError(
            f"Empty translation for segment {segment.id} "
            f"(chapter {segment.chapter_index} "
            f"{segment.chapter_title!r}) in book {book_title!r}."
        )
    return text, result


@dataclass
class _BatchOutcome:
    """One batch's translations plus the accounting needed by the caller.

    Attributes:
        translations: Translations aligned 1:1 with the batch's segments.
        results: Every completion result made (token/cost accounting).
        revision_failed: Whether a declared revision pass could not be applied.
    """

    translations: list[str]
    results: list[CompletionResult]
    revision_failed: bool = False
    mt_characters: int = 0
    mt_cost_eur: float = 0.0


def _translate_batch(
    segments: Sequence[Segment],
    *,
    client: LLMClient,
    glossary: Glossary | None,
    context_pairs: Sequence[tuple[str, str]],
    target_lang: str,
    book_title: str,
    style: TranslationStyle = BASELINE,
    book_context: str | None = None,
    fallback_client: LLMClient | None = None,
    mt_client: GoogleTranslateClient | None = None,
    source_lang: str | None = None,
) -> _BatchOutcome:
    """Translate one batch, retrying then falling back on a bad response.

    Attempt 1: numbered batch prompt. On a 1:1 mismatch, attempt 2 retries the
    same batch with a stricter prompt. If that also mismatches, each segment is
    translated on its own. A segment that still fails raises
    :class:`TranslationError`.

    If the provider refuses the batch on content-policy grounds
    (:class:`ContentPolicyError` — e.g. a history book quoting propaganda),
    the whole batch is retried once against ``fallback_client`` when one is
    configured; without a fallback the refusal is loud.

    When ``style`` declares a revision pass, the accepted translations go
    through it before being returned.

    Returns:
        The :class:`_BatchOutcome` for this batch.
    """
    if mt_client is not None:
        # The MT draft REPLACES the LLM's drafting call rather than preceding
        # it: the editor pass below already takes SOURCE + DRAFT, which is
        # exactly post-editing. So this path makes one LLM call per batch where
        # the two-pass style makes two. It requires a revising style — a draft
        # nobody edits is raw machine translation, and `ensure_supports` would
        # not catch that because it is a pipeline error, not a language one.
        if style.revise_system is None:
            raise TranslationError(
                f"A machine-translation draft needs a revising style to post-edit it; "
                f"style {style.name!r} declares no revision pass. Use --style revise_v1 "
                f"(or another revising style), or drop --mt-draft."
            )
        draft = mt_client.draft(
            [segment.text for segment in segments],
            source_lang=source_lang,
            target_lang=target_lang,
        )
        revised, revise_results = _revise_batch(
            segments,
            draft.texts,
            client=client,
            glossary=glossary,
            target_lang=target_lang,
            style=style,
            book_context=book_context,
        )
        return _BatchOutcome(
            translations=revised if revised is not None else draft.texts,
            results=revise_results,
            revision_failed=revised is None,
            mt_characters=draft.characters,
            mt_cost_eur=draft.cost_eur,
        )

    try:
        translations, results = _translate_batch_attempts(
            segments,
            client=client,
            glossary=glossary,
            context_pairs=context_pairs,
            target_lang=target_lang,
            book_title=book_title,
            style=style,
            book_context=book_context,
        )
    except ContentPolicyError as exc:
        if fallback_client is None:
            raise TranslationError(
                f"Provider refused a batch of {len(segments)} segments in "
                f"'{book_title}' on content-policy grounds and no fallback "
                f"provider is configured (set ANTHROPIC_API_KEY): {exc}"
            ) from exc
        logger.warning(
            "Content policy refusal for a batch of %d segments; retrying via fallback model.",
            len(segments),
        )
        client = fallback_client
        translations, results = _translate_batch_attempts(
            segments,
            client=fallback_client,
            glossary=glossary,
            context_pairs=context_pairs,
            target_lang=target_lang,
            book_title=book_title,
            style=style,
            book_context=book_context,
        )

    if style.revise_system is None:
        return _BatchOutcome(translations=translations, results=results)

    revised, revise_results = _revise_batch(
        segments,
        translations,
        client=client,
        glossary=glossary,
        target_lang=target_lang,
        style=style,
        book_context=book_context,
    )
    results.extend(revise_results)
    return _BatchOutcome(
        translations=revised if revised is not None else translations,
        results=results,
        revision_failed=revised is None,
    )


def _complete_batch_rung(
    client: LLMClient,
    *,
    prompt: str,
    system: str | None,
    results: list[CompletionResult],
    segment_count: int,
) -> CompletionResult | None:
    """Call ``client.complete()`` for one batch rung, degrading like a bad mapping.

    An empty or truncated response (:class:`EmptyCompletionError` /
    :class:`TruncatedCompletionError`) is a batch-level failure of exactly the
    same shape as a 1:1 mapping mismatch: this rung didn't work, but a
    stricter retry or a smaller (per-segment) prompt is precisely the
    remedy — a per-segment prompt is far less likely to hit the same
    token-budget ceiling. The call was still billed even though its text is
    unusable, so the (unusable-text) result carried on the exception is
    recorded here for cost accounting instead of being silently lost.

    Returns:
        The :class:`CompletionResult`, or ``None`` if this rung failed and
        the caller should move to the next one.
    """
    try:
        result = client.complete(prompt=prompt, system=system)
    except (EmptyCompletionError, TruncatedCompletionError) as exc:
        logger.warning(
            "Batch of %d segments returned no usable text (%s); "
            "trying the next rung of the retry ladder.",
            segment_count,
            exc,
        )
        if exc.result is not None:
            results.append(exc.result)
        return None
    results.append(result)
    return result


def _translate_batch_attempts(
    segments: Sequence[Segment],
    *,
    client: LLMClient,
    glossary: Glossary | None,
    context_pairs: Sequence[tuple[str, str]],
    target_lang: str,
    book_title: str,
    style: TranslationStyle = BASELINE,
    book_context: str | None = None,
) -> tuple[list[str], list[CompletionResult]]:
    """Run the batch → strict retry → per-segment ladder against one client."""
    results: list[CompletionResult] = []
    prompt = _build_batch_prompt(segments, glossary, context_pairs, target_lang, book_context)

    first = _complete_batch_rung(
        client,
        prompt=prompt,
        system=style.batch_system,
        results=results,
        segment_count=len(segments),
    )
    if first is not None:
        try:
            return parse_numbered_response(first.text, len(segments)), results
        except ValueError as exc:
            logger.warning(
                "Batch of %d segments returned a bad mapping (%s); retrying strictly.",
                len(segments),
                exc,
            )

    second = _complete_batch_rung(
        client,
        prompt=prompt,
        system=style.strict_system,
        results=results,
        segment_count=len(segments),
    )
    if second is not None:
        try:
            return parse_numbered_response(second.text, len(segments)), results
        except ValueError as exc:
            logger.warning(
                "Strict retry still bad (%s); falling back to per-segment translation.",
                exc,
            )

    translations: list[str] = []
    for segment in segments:
        text, result = _translate_single(
            segment,
            client=client,
            glossary=glossary,
            target_lang=target_lang,
            book_title=book_title,
            style=style,
            book_context=book_context,
        )
        translations.append(text)
        results.append(result)
    return translations, results


def _build_revise_prompt(
    segments: Sequence[Segment],
    drafts: Sequence[str],
    glossary: Glossary | None,
    target_lang: str,
    book_context: str | None,
) -> str:
    """Assemble the editor-pass prompt: one marker per segment, SOURCE + DRAFT."""
    blocks: list[str] = [f"Target language: {target_lang}."]
    memo = _book_context_block(book_context)
    if memo:
        blocks.append(memo)
    if glossary is not None and not glossary.is_empty():
        blocks.append(glossary.to_prompt_block())
    blocks.append(
        "Revise each numbered draft below. Reply with each segment's marker "
        "followed by the revised translation only:"
    )
    blocks.append(
        "\n\n".join(
            f"[[{i}]]\nSOURCE: {segment.text}\nDRAFT: {draft}"
            for i, (segment, draft) in enumerate(zip(segments, drafts), start=1)
        )
    )
    return "\n\n".join(blocks)


def _revise_batch(
    segments: Sequence[Segment],
    drafts: Sequence[str],
    *,
    client: LLMClient,
    glossary: Glossary | None,
    target_lang: str,
    style: TranslationStyle,
    book_context: str | None,
) -> tuple[list[str] | None, list[CompletionResult]]:
    """Run the native-editor revision pass over one already-translated batch.

    The pass is quality-only: it must never change the segment mapping. A reply
    that does not map 1:1 onto the batch is retried strictly once and then
    abandoned — the caller keeps the first-pass translations, so segment
    integrity holds and the loss of fluency is surfaced as
    :attr:`TranslationStats.revision_failures` rather than corrupting the book.

    Returns:
        ``(revised_translations_or_None, completion_results)``; ``None`` means
        the pass could not be applied.
    """
    prompt = _build_revise_prompt(segments, drafts, glossary, target_lang, book_context)
    results: list[CompletionResult] = []
    systems = [style.revise_system, style.revise_strict_system]

    for attempt, system in enumerate(systems, start=1):
        assert system is not None  # guarded by style.revise_system is not None
        try:
            result = client.complete(prompt=prompt, system=system)
        except ContentPolicyError as exc:
            logger.warning(
                "Revision pass refused on content-policy grounds for a batch of "
                "%d segments; keeping the un-revised translation: %s",
                len(segments),
                exc,
            )
            return None, results
        except (EmptyCompletionError, TruncatedCompletionError) as exc:
            logger.warning(
                "Revision pass attempt %d returned no usable text (%s).",
                attempt,
                exc,
            )
            if exc.result is not None:
                results.append(exc.result)
            continue
        results.append(result)
        try:
            return parse_numbered_response(result.text, len(segments)), results
        except ValueError as exc:
            logger.warning(
                "Revision pass attempt %d returned a bad mapping (%s).",
                attempt,
                exc,
            )

    logger.warning(
        "Revision pass failed for a batch of %d segments; keeping the un-revised translation.",
        len(segments),
    )
    return None, results


def build_book_context(
    book: Book,
    *,
    client: LLMClient,
    style: TranslationStyle,
    target_lang: str,
    model: str | None = None,
    cache: TranslationCache | None = None,
    excerpt_chars: int = BOOK_CONTEXT_EXCERPT_CHARS,
) -> tuple[str | None, CompletionResult | None]:
    """Derive (or load) the one-paragraph per-book style memo for ``style``.

    Makes at most one LLM call, memoized in the cache under
    ``(book, model, lang, prompt_version)``. Styles without a
    ``book_context_system`` return ``(None, None)`` without calling anything.

    Args:
        book: The source book.
        client: LLM client for the single derivation call.
        style: The translation style asking for the memo.
        target_lang: Target language code.
        model: Model identifier for cache keying; defaults to ``client.model``.
        cache: Optional cache for memoization.
        excerpt_chars: Source characters sampled from the book's opening.

    Returns:
        ``(memo_or_None, completion_result_or_None)``; the result is ``None``
        on a cache hit or when the style needs no memo.
    """
    if style.book_context_system is None:
        return None, None

    model_name = model if model is not None else getattr(client, "model", "unknown")
    bhash = book_hash(book)
    if cache is not None:
        cached = cache.get_book_context(bhash, model_name, target_lang, style.version)
        if cached is not None:
            logger.info("Book-context memo cache hit (%d chars).", len(cached))
            return cached, None

    excerpt = _book_excerpt(book, excerpt_chars)
    if not excerpt.strip():
        return None, None

    prompt = (
        f"Title: {book.title}\n"
        f"Author(s): {', '.join(book.authors) or 'unknown'}\n"
        f"Target language: {target_lang}\n\n"
        f"Opening excerpt:\n{excerpt}"
    )
    try:
        result: CompletionResult | None = client.complete(
            prompt=prompt, system=style.book_context_system
        )
        memo = result.text.strip()
    except (EmptyCompletionError, TruncatedCompletionError) as exc:
        # No smaller unit to retry into for a once-per-book memo call — but,
        # like a genuinely blank reply below, this is a best-effort pass, not
        # a correctness requirement (unlike a segment translation), so it
        # degrades to "no memo" rather than aborting the run. `exc.result`
        # (billed, unusable text) still needs recording below.
        logger.warning("Book-context call returned no usable text (%s).", exc)
        result = exc.result
        memo = ""

    if not memo:
        logger.warning("Book-context memo came back empty; translating without it.")
        # Cache the empty result too (not just non-empty memos below): otherwise a
        # killed-and-resumed run repeats this derivation call on every resume,
        # contradicting the "never re-bills the memo call" guarantee (finding 20).
        if cache is not None and result is not None:
            cache.store_book_context(
                bhash,
                model_name,
                target_lang,
                style.version,
                memo,
                CallRecord(
                    kind="book_context",
                    input_tokens=result.input_tokens,
                    output_tokens=result.output_tokens,
                    cost_eur=result.cost_eur,
                ),
            )
        return None, result

    logger.info("Book-context memo derived (%d chars).", len(memo))
    if cache is not None:
        cache.store_book_context(
            bhash,
            model_name,
            target_lang,
            style.version,
            memo,
            CallRecord(
                kind="book_context",
                input_tokens=result.input_tokens,
                output_tokens=result.output_tokens,
                cost_eur=result.cost_eur,
            ),
        )
    return memo, result


def _book_excerpt(book: Book, max_chars: int) -> str:
    """Concatenate the book's opening non-empty segments, capped at ``max_chars``."""
    parts: list[str] = []
    used = 0
    for segment in book.segments:
        text = segment.text.strip()
        if not text:
            continue
        parts.append(text)
        used += len(text)
        if used >= max_chars:
            break
    return "\n".join(parts)[:max_chars]


def _aggregate_call(results: Sequence[CompletionResult], kind: str) -> CallRecord:
    """Sum a batch's completion results into one accounting record."""
    return CallRecord(
        kind=kind,
        input_tokens=sum(r.input_tokens for r in results),
        output_tokens=sum(r.output_tokens for r in results),
        cost_eur=sum(r.cost_eur for r in results),
    )


def translate_book(
    book: Book,
    *,
    client: LLMClient,
    target_lang: str,
    cache: TranslationCache,
    glossary: Glossary | None = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    context_pairs: int = DEFAULT_CONTEXT_PAIRS,
    concurrency: int = DEFAULT_CONCURRENCY,
    skip_segment_ids: Collection[str] = (),
    on_progress: ProgressCallback | None = None,
    fallback_client: LLMClient | None = None,
    style: TranslationStyle = BASELINE,
    book_context: str | None = None,
    mt_client: GoogleTranslateClient | None = None,
    source_lang: str | None = None,
) -> Book:
    """Translate every eligible segment of ``book`` into ``target_lang``.

    Segments already present in ``cache`` are reused (never re-sent). Segments
    whose ID is in ``skip_segment_ids`` — and empty/whitespace segments — pass
    through untranslated so the returned book keeps a 1:1 segment mapping with
    the input. Each translated batch is committed to the cache immediately, so a
    killed run resumes without re-billing.

    Args:
        book: The normalized source book.
        client: LLM client for translation calls (its ``model`` keys the cache).
        target_lang: Target language code (e.g. ``"sl"``).
        cache: Translation cache for resumability and accounting.
        glossary: Optional glossary injected into every batch prompt. Its
            identity participates in the cache key, so translating the same
            book under different terms re-translates instead of serving text
            produced under the old ones.
        batch_size: Maximum consecutive segments per completion.
        context_pairs: Number of previous source/target pairs used as context.
        concurrency: Batches translated at once. ``1`` reproduces strictly
            sequential translation exactly, which is what
            ``test_plan_backed_translation_matches_the_sequential_baseline``
            asserts against.
        skip_segment_ids: Segment IDs to pass through untranslated.
        on_progress: Optional callback invoked with the running
            :class:`TranslationStats` after each batch and once at the end.
        fallback_client: Optional second-provider client used only for
            batches the primary provider refuses on content-policy grounds.
        style: Translation style (prompt set + extra passes). Its
            ``version`` participates in the cache key, so switching styles
            re-translates instead of serving text produced by another prompt.
            Defaults to :data:`~berilo.prompts.BASELINE`.
        book_context: Pre-derived per-book style memo. When given, the
            derivation is skipped entirely. A caller translating a book in
            *slices* — which is what the cloud worker does — would otherwise
            re-derive and re-bill the once-per-book memo on every slice. When
            ``None`` the memo is derived (and cached) exactly as before.

    Returns:
        A new :class:`~berilo.models.Book` with the same segments (count, order,
        IDs, positions, types) and translated ``text``.

    Raises:
        TranslationError: If a segment cannot be translated after retry and
            per-segment fallback.
        StyleLanguageError: If ``style`` is written for a language other than
            ``target_lang``. This is the gate every entry point passes through
            — CLI, experiment runner, or app — so a contradictory pair can
            never reach a billed call (review finding 4).
    """
    ensure_supports(style, target_lang)
    model = client.model
    bhash = book_hash(book)
    skip = set(skip_segment_ids)
    prompt_version = style.version
    # The glossary is prompt input like the style is, so it keys the cache too:
    # a changed glossary must re-translate, never re-serve (CLAUDE.md §9).
    ghash = glossary_identity(glossary)
    stats = TranslationStats(total_segments=len(book.segments))

    if book_context is None:
        book_context, context_result = build_book_context(
            book,
            client=client,
            style=style,
            target_lang=target_lang,
            model=model,
            cache=cache,
        )
        if context_result is not None:
            stats.api_calls += 1
            stats.input_tokens += context_result.input_tokens
            stats.output_tokens += context_result.output_tokens
            stats.cost_eur += context_result.cost_eur

    resolved: dict[int, str] = {}
    recent_pairs: list[tuple[str, str]] = []
    segments = book.segments

    def _remember(source: str, target: str) -> None:
        # ``context_pairs <= 0`` means "no rolling context": remember nothing.
        # Appending and skipping the trim (the pre-A3 behaviour) fed *every*
        # prior pair of the book into each batch — the exact opposite of the
        # intent, and a guaranteed blow-through of the per-batch token ceiling
        # on a full book (review finding 10).
        if context_pairs <= 0:
            return
        recent_pairs.append((source, target))
        if len(recent_pairs) > context_pairs:
            del recent_pairs[:-context_pairs]

    # Every batch boundary is decided here, before a single call is made. The
    # planner is the reference for core-spec Surface 3 and is gated by
    # ``contracts/vectors/v2/batch_plan/``.
    plan = build_translation_plan(
        segments,
        lookup=lambda segment: cache.get_translation(
            bhash, segment_hash(segment.text), model, target_lang, prompt_version, ghash
        ),
        skip_segment_ids=skip,
        batch_size=batch_size,
    )
    resolved.update(plan.resolved)
    stats.empty_segments = plan.empty_segments
    stats.skipped_segments = plan.skipped_segments
    stats.cached_segments = plan.cached_segments

    def _run_batch(
        batch: PositionedBatch, snapshot: list[tuple[str, str]]
    ) -> tuple[_BatchOutcome, CallRecord]:
        """Translate one batch and commit it. Runs in its own lane."""
        batch_segments = [segment for _position, segment in batch]
        outcome = _translate_batch(
            batch_segments,
            client=client,
            glossary=glossary,
            context_pairs=snapshot,
            target_lang=target_lang,
            book_title=book.title,
            style=style,
            book_context=book_context,
            fallback_client=fallback_client,
            mt_client=mt_client,
            source_lang=source_lang,
        )
        call = _aggregate_call(outcome.results, kind="batch")
        per_segment_cost = call.cost_eur / len(batch) if batch else 0.0
        # Committed inside the lane, not after the barrier: committing after
        # the wave discards batches the provider has already billed whenever
        # any sibling lane fails.
        cache.store_batch(
            bhash,
            model,
            target_lang,
            [
                SegmentTranslation(
                    segment_hash=segment_hash(segment.text),
                    text=text,
                    cost_eur=per_segment_cost,
                )
                for (_position, segment), text in zip(batch, outcome.translations)
            ],
            call,
            prompt_version,
            ghash,
        )
        return outcome, call

    def _fold(batch: PositionedBatch, outcome: _BatchOutcome, call: CallRecord) -> None:
        """Apply one finished batch to the run's sequential state."""
        for (position, segment), text in zip(batch, outcome.translations):
            resolved[position] = text
            _remember(segment.text, text)
        stats.translated_segments += len(batch)
        stats.api_calls += len(outcome.results)
        stats.revision_failures += int(outcome.revision_failed)
        stats.mt_characters += outcome.mt_characters
        # MT is billed by the character, not the token, so it is kept out of
        # cost_eur's token arithmetic and added alongside it.
        stats.mt_cost_eur += outcome.mt_cost_eur
        stats.input_tokens += call.input_tokens
        stats.output_tokens += call.output_tokens
        stats.cost_eur += call.cost_eur
        stats.current_chapter_index = batch[-1][1].chapter_index
        stats.current_chapter_title = batch[-1][1].chapter_title

    for wave in plan_waves(plan, concurrency=concurrency, context_pairs=context_pairs):
        # Cache hits sitting before this wave feed the rolling context exactly
        # as freshly translated segments do — that equality is what makes a
        # resumed run's prompts identical to an uninterrupted run's.
        for cached_source, cached_target in wave.preceding_pairs:
            _remember(cached_source, cached_target)

        # One snapshot for the whole wave, taken before any of it runs, so the
        # wave's prompts do not depend on which lane happens to finish first.
        snapshot = list(recent_pairs)

        if len(wave.batches) == 1:
            finished = [_run_batch(wave.batches[0], snapshot)]
        else:
            with ThreadPoolExecutor(max_workers=len(wave.batches)) as pool:
                futures = [pool.submit(_run_batch, batch, snapshot) for batch in wave.batches]
                # ``result()`` in submission order: the first lane to raise is
                # the one that propagates, and lanes that already committed
                # keep their spend. Nothing folds back before the wave is whole.
                finished = [future.result() for future in futures]

        # Folded in batch order, never completion order. ``resolved`` is keyed
        # by position and so is order-independent, but ``recent_pairs``, the
        # chapter in the progress line and ``revision_failures`` are sequential
        # state — folding those by completion would make reported progress
        # depend on network latency.
        for batch, (outcome, call) in zip(wave.batches, finished):
            _fold(batch, outcome, call)

        if on_progress is not None:
            on_progress(stats)

    if len(resolved) != len(segments):  # defensive: integrity invariant
        raise TranslationError(
            f"Segment count changed during translation: "
            f"{len(book.segments)} in, {len(resolved)} out."
        )

    output = [replace(segment, text=resolved[i]) for i, segment in enumerate(segments)]

    if on_progress is not None:
        on_progress(stats)

    return dataclasses.replace(book, segments=output)


# --------------------------------------------------------------------------
# Dry-run cost estimation (no API calls).
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class ChapterEstimate:
    """Per-chapter dry-run estimate.

    Attributes:
        index: Chapter index.
        title: Chapter title, if known.
        segments: Segments that would be translated in this chapter.
        input_tokens: Estimated input tokens for the chapter.
        output_tokens: Estimated output tokens for the chapter.
        cost_eur: Estimated EUR cost for the chapter.
    """

    index: int
    title: str | None
    segments: int
    input_tokens: int
    output_tokens: int
    cost_eur: float


@dataclass(frozen=True)
class CostEstimate:
    """Whole-book dry-run estimate.

    Attributes:
        model: Model the estimate is priced for.
        target_lang: Target language code.
        prompt_version: Translation style version the estimate is priced for.
        total_segments: Total segments in the book.
        translatable_segments: Segments that would be sent to the API.
        skipped_segments: Segments passed through untranslated (back matter).
        empty_segments: Empty/whitespace segments passed through unchanged.
        batches: Estimated number of batch API calls.
        reasoning_tokens: Reasoning-token surcharge included in ``output_tokens``.
        input_tokens: Estimated total input tokens.
        output_tokens: Estimated total output tokens (incl. reasoning surcharge).
        cost_eur: Estimated total EUR cost.
        chapters: Per-chapter breakdown (translatable chapters only).
        revision_calls: Extra editor-pass calls the style requires (one per
            batch for a revising style, zero otherwise).
        book_context_calls: Extra per-book style-memo calls (0 or 1).
    """

    model: str
    target_lang: str
    prompt_version: str
    total_segments: int
    translatable_segments: int
    skipped_segments: int
    empty_segments: int
    batches: int
    reasoning_tokens: int
    input_tokens: int
    output_tokens: int
    cost_eur: float
    chapters: list[ChapterEstimate] = field(default_factory=list)
    revision_calls: int = 0
    book_context_calls: int = 0


def _batches_for(segment_count: int, batch_size: int) -> int:
    """Number of batch calls needed for ``segment_count`` segments."""
    if segment_count <= 0:
        return 0
    return -(-segment_count // batch_size)  # ceil division


def estimate_cost(
    book: Book,
    *,
    model: str,
    target_lang: str,
    batch_size: int = DEFAULT_BATCH_SIZE,
    skip_segment_ids: Collection[str] = (),
    glossary: bool = True,
    style: TranslationStyle = BASELINE,
) -> CostEstimate:
    """Estimate the cost of translating ``book`` without making any API calls.

    Token counts use a chars/4 heuristic plus fixed per-batch prompt overhead.
    For reasoning-billing models the estimate adds
    :data:`REASONING_TOKENS_PER_CALL` output tokens per API call (glossary +
    batches + any extra passes), because those models bill hidden reasoning
    tokens as output and would otherwise be underestimated.

    The estimate is priced for the style it is asked about: a revising style
    adds one editor call per batch (which reads the source *and* the draft, and
    writes the draft again — roughly doubling the bill), and a book-context
    style adds one memo call for the book.

    Args:
        book: The source book.
        model: Model identifier to price against (must be in the pricing table).
        target_lang: Target language code.
        batch_size: Segments per batch (drives the batch count).
        skip_segment_ids: Segment IDs excluded from translation (back matter).
        glossary: Whether a glossary extraction call is included in the estimate.
        style: Translation style being priced (drives the extra passes).

    Returns:
        The :class:`CostEstimate`, including a per-chapter breakdown.

    Raises:
        StyleLanguageError: If ``style`` is written for a language other than
            ``target_lang``. The dry run refuses for the same reason the paid
            run does: quoting a price for a contradictory pair invites the user
            to approve it.
    """
    from berilo.providers.pricing import cost_eur

    ensure_supports(style, target_lang)
    skip = set(skip_segment_ids)
    reasoning = _is_reasoning_model(model)
    revising = style.revise_system is not None

    # Group translatable segments by chapter, preserving first-seen order.
    chapters: dict[int, dict[str, object]] = {}
    empty = 0
    skipped = 0
    for segment in book.segments:
        if not segment.text.strip():
            empty += 1
            continue
        if segment.id in skip:
            skipped += 1
            continue
        entry = chapters.setdefault(
            segment.chapter_index,
            {"title": segment.chapter_title, "segments": 0, "chars": 0},
        )
        entry["segments"] = int(entry["segments"]) + 1  # type: ignore[arg-type]
        entry["chars"] = int(entry["chars"]) + len(segment.text.strip())  # type: ignore[arg-type]

    chapter_estimates: list[ChapterEstimate] = []
    total_batches = 0
    total_reasoning = 0
    total_revision_calls = 0
    for chapter_index in sorted(chapters):
        entry = chapters[chapter_index]
        seg_count = int(entry["segments"])  # type: ignore[arg-type]
        chars = int(entry["chars"])  # type: ignore[arg-type]
        batches = _batches_for(seg_count, batch_size)
        total_batches += batches

        source_tokens = chars // CHARS_PER_TOKEN
        target_tokens = int(source_tokens * TARGET_EXPANSION)
        input_tokens = source_tokens + batches * PROMPT_OVERHEAD_TOKENS_PER_BATCH
        output_tokens = target_tokens
        calls = batches

        if revising:
            # The editor pass re-reads the source and the draft, and rewrites
            # the draft: input ≈ source + draft, output ≈ draft.
            overhead = batches * PROMPT_OVERHEAD_TOKENS_PER_BATCH
            input_tokens += source_tokens + target_tokens + overhead
            output_tokens += target_tokens
            calls += batches
            total_revision_calls += batches

        reasoning_tokens = calls * REASONING_TOKENS_PER_CALL if reasoning else 0
        output_tokens += reasoning_tokens
        total_reasoning += reasoning_tokens

        chapter_estimates.append(
            ChapterEstimate(
                index=chapter_index,
                title=entry["title"],  # type: ignore[arg-type]
                segments=seg_count,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                cost_eur=cost_eur(model, input_tokens, output_tokens),
            )
        )

    # Glossary extraction: one call over a sampled excerpt.
    extra_input = 0
    extra_output = 0
    if glossary and chapter_estimates:
        from berilo.glossary import DEFAULT_MAX_SAMPLE_CHARS

        extra_input += DEFAULT_MAX_SAMPLE_CHARS // CHARS_PER_TOKEN
        extra_output += 300 + (REASONING_TOKENS_PER_CALL if reasoning else 0)
        total_reasoning += REASONING_TOKENS_PER_CALL if reasoning else 0

    # Book-context memo: one call over the book's opening excerpt.
    book_context_calls = 0
    if style.book_context_system is not None and chapter_estimates:
        book_context_calls = 1
        extra_input += BOOK_CONTEXT_EXCERPT_CHARS // CHARS_PER_TOKEN
        extra_output += BOOK_CONTEXT_OUTPUT_TOKENS + (REASONING_TOKENS_PER_CALL if reasoning else 0)
        total_reasoning += REASONING_TOKENS_PER_CALL if reasoning else 0

    input_tokens = sum(c.input_tokens for c in chapter_estimates) + extra_input
    output_tokens = sum(c.output_tokens for c in chapter_estimates) + extra_output
    total_cost = cost_eur(model, input_tokens, output_tokens)

    return CostEstimate(
        model=model,
        target_lang=target_lang,
        prompt_version=style.version,
        total_segments=len(book.segments),
        translatable_segments=sum(c.segments for c in chapter_estimates),
        skipped_segments=skipped,
        empty_segments=empty,
        batches=total_batches,
        reasoning_tokens=total_reasoning,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        cost_eur=total_cost,
        chapters=chapter_estimates,
        revision_calls=total_revision_calls,
        book_context_calls=book_context_calls,
    )
