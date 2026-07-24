"""Translate stage: batched, glossary-guided, resumable book translation.

Translates a :class:`~berilo.models.Book`'s segments in paragraph batches with
rolling context and a per-book glossary, resumable via a SQLite cache keyed on
``(book_hash, segment_hash, model, lang)``. The stage upholds the product's two
non-negotiable guarantees (CLAUDE.md §2):

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
from dataclasses import dataclass, field, replace

from berilo.cache import (
    CallRecord,
    SegmentTranslation,
    TranslationCache,
    book_hash,
    segment_hash,
)
from berilo.glossary import Glossary
from berilo.models import Book, Segment
from berilo.providers.base import CompletionResult, ContentPolicyError, LLMClient

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------
# Tunables (named constants — no magic numbers).
# --------------------------------------------------------------------------

#: Number of consecutive paragraphs grouped into one completion. Kept in the
#: 8–15 range so a batch is large enough to amortize prompt overhead but small
#: enough that a single mismatch retries cheaply.
DEFAULT_BATCH_SIZE = 10

#: Number of previous source/target paragraph pairs prepended to each batch as
#: rolling context (marked "do not retranslate").
DEFAULT_CONTEXT_PAIRS = 2

#: Marker wrapping each segment's ordinal in the prompt and expected in the
#: reply, e.g. ``[[1]]``.
_MARKER_RE = re.compile(r"\[\[(\d+)\]\]")

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
# Prompts.
# --------------------------------------------------------------------------

_TRANSLATE_SYSTEM = (
    "You are a professional literary translator. Translate the MEANING, not the "
    "words: preserve register and tone, render idioms natively, and keep "
    "terminology consistent with the glossary. Preserve any inline HTML tags "
    "exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each source segment is "
    "prefixed with a marker like [[1]]. Return EVERY segment, each prefixed with "
    "its EXACT same marker, in the SAME order, and translate nothing else. Do "
    "not merge, split, add, or drop segments. Output only the marked "
    "translations."
)

_TRANSLATE_SYSTEM_STRICT = (
    _TRANSLATE_SYSTEM + " CRITICAL: the previous attempt did not return exactly one [[n]] marker "
    "per source segment. Return EXACTLY the same markers you were given — no "
    "more, no fewer — each on its own line followed by that segment's "
    "translation."
)

_SINGLE_SEGMENT_SYSTEM = (
    "You are a professional literary translator. Translate the MEANING, not the "
    "words, preserving register, idioms, and any inline HTML tags exactly "
    "(<em>, <strong>, <i>, <b>, <sub>, <sup>). Reply with ONLY the translation, "
    "no markers, no commentary."
)


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
        current_chapter_index: Chapter index of the most recent batch.
        current_chapter_title: Chapter title of the most recent batch.
    """

    total_segments: int
    translated_segments: int = 0
    cached_segments: int = 0
    skipped_segments: int = 0
    empty_segments: int = 0
    api_calls: int = 0
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


def _build_batch_prompt(
    segments: Sequence[Segment],
    glossary: Glossary | None,
    context_pairs: Sequence[tuple[str, str]],
    target_lang: str,
) -> str:
    """Assemble the user prompt for one batch completion.

    Args:
        segments: The batch's source segments.
        glossary: Optional glossary injected into the prompt.
        context_pairs: Rolling context (previous source/target pairs).
        target_lang: Target language code.

    Returns:
        The assembled prompt string.
    """
    blocks: list[str] = [f"Translate into: {target_lang}."]
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


def parse_numbered_response(text: str, expected_count: int) -> list[str]:
    """Parse a ``[[n]] translation`` reply into an ordered list of translations.

    Args:
        text: The model's reply.
        expected_count: The number of segments that were sent.

    Returns:
        A list of ``expected_count`` non-empty translations, in order 1..n.

    Raises:
        ValueError: If the reply has missing, extra, duplicate, or empty
            markers — i.e. it does not map 1:1 onto the sent segments.
    """
    matches = list(_MARKER_RE.finditer(text))
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


def _translate_single(
    segment: Segment,
    *,
    client: LLMClient,
    glossary: Glossary | None,
    target_lang: str,
    book_title: str,
) -> tuple[str, CompletionResult]:
    """Translate one segment on its own (per-segment fallback path).

    Raises:
        TranslationError: If the model returns an empty translation.
    """
    blocks: list[str] = [f"Translate into {target_lang}."]
    if glossary is not None and not glossary.is_empty():
        blocks.append(glossary.to_prompt_block())
    blocks.append(segment.text)
    result = client.complete(prompt="\n\n".join(blocks), system=_SINGLE_SEGMENT_SYSTEM)
    text = result.text.strip()
    if not text:
        raise TranslationError(
            f"Empty translation for segment {segment.id} "
            f"(chapter {segment.chapter_index} "
            f"{segment.chapter_title!r}) in book {book_title!r}."
        )
    return text, result


def _translate_batch(
    segments: Sequence[Segment],
    *,
    client: LLMClient,
    glossary: Glossary | None,
    context_pairs: Sequence[tuple[str, str]],
    target_lang: str,
    book_title: str,
    fallback_client: LLMClient | None = None,
) -> tuple[list[str], list[CompletionResult]]:
    """Translate one batch, retrying then falling back on a bad response.

    Attempt 1: numbered batch prompt. On a 1:1 mismatch, attempt 2 retries the
    same batch with a stricter prompt. If that also mismatches, each segment is
    translated on its own. A segment that still fails raises
    :class:`TranslationError`.

    If the provider refuses the batch on content-policy grounds
    (:class:`ContentPolicyError` — e.g. a history book quoting propaganda),
    the whole batch is retried once against ``fallback_client`` when one is
    configured; without a fallback the refusal is loud.

    Returns:
        A tuple of (translations aligned 1:1 with ``segments``, all completion
        results made — for token/cost accounting).
    """
    try:
        return _translate_batch_attempts(
            segments,
            client=client,
            glossary=glossary,
            context_pairs=context_pairs,
            target_lang=target_lang,
            book_title=book_title,
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
        return _translate_batch_attempts(
            segments,
            client=fallback_client,
            glossary=glossary,
            context_pairs=context_pairs,
            target_lang=target_lang,
            book_title=book_title,
        )


def _translate_batch_attempts(
    segments: Sequence[Segment],
    *,
    client: LLMClient,
    glossary: Glossary | None,
    context_pairs: Sequence[tuple[str, str]],
    target_lang: str,
    book_title: str,
) -> tuple[list[str], list[CompletionResult]]:
    """Run the batch → strict retry → per-segment ladder against one client."""
    results: list[CompletionResult] = []

    first = client.complete(
        prompt=_build_batch_prompt(segments, glossary, context_pairs, target_lang),
        system=_TRANSLATE_SYSTEM,
    )
    results.append(first)
    try:
        return parse_numbered_response(first.text, len(segments)), results
    except ValueError as exc:
        logger.warning(
            "Batch of %d segments returned a bad mapping (%s); retrying strictly.",
            len(segments),
            exc,
        )

    second = client.complete(
        prompt=_build_batch_prompt(segments, glossary, context_pairs, target_lang),
        system=_TRANSLATE_SYSTEM_STRICT,
    )
    results.append(second)
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
        )
        translations.append(text)
        results.append(result)
    return translations, results


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
    skip_segment_ids: Collection[str] = (),
    on_progress: ProgressCallback | None = None,
    fallback_client: LLMClient | None = None,
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
        glossary: Optional glossary injected into every batch prompt.
        batch_size: Maximum consecutive segments per completion.
        context_pairs: Number of previous source/target pairs used as context.
        skip_segment_ids: Segment IDs to pass through untranslated.
        on_progress: Optional callback invoked with the running
            :class:`TranslationStats` after each batch and once at the end.
        fallback_client: Optional second-provider client used only for
            batches the primary provider refuses on content-policy grounds.

    Returns:
        A new :class:`~berilo.models.Book` with the same segments (count, order,
        IDs, positions, types) and translated ``text``.

    Raises:
        TranslationError: If a segment cannot be translated after retry and
            per-segment fallback.
    """
    model = client.model
    bhash = book_hash(book)
    skip = set(skip_segment_ids)
    stats = TranslationStats(total_segments=len(book.segments))

    output: list[Segment] = []
    recent_pairs: list[tuple[str, str]] = []
    segments = book.segments
    index = 0

    def _remember(source: str, target: str) -> None:
        recent_pairs.append((source, target))
        if context_pairs > 0 and len(recent_pairs) > context_pairs:
            del recent_pairs[:-context_pairs]

    while index < len(segments):
        segment = segments[index]

        if not segment.text.strip():
            output.append(replace(segment))
            stats.empty_segments += 1
            index += 1
            continue

        if segment.id in skip:
            output.append(replace(segment))
            stats.skipped_segments += 1
            index += 1
            continue

        shash = segment_hash(segment.text)
        cached = cache.get_translation(bhash, shash, model, target_lang)
        if cached is not None:
            output.append(replace(segment, text=cached))
            _remember(segment.text, cached)
            stats.cached_segments += 1
            index += 1
            continue

        # Gather a batch of consecutive, same-chapter, uncached, translatable
        # segments starting at the current one.
        batch: list[Segment] = [segment]
        cursor = index + 1
        while cursor < len(segments) and len(batch) < batch_size:
            candidate = segments[cursor]
            if candidate.chapter_index != segment.chapter_index:
                break
            if candidate.id in skip or not candidate.text.strip():
                break
            if (
                cache.get_translation(bhash, segment_hash(candidate.text), model, target_lang)
                is not None
            ):
                break
            batch.append(candidate)
            cursor += 1

        translations, results = _translate_batch(
            batch,
            client=client,
            glossary=glossary,
            context_pairs=list(recent_pairs),
            target_lang=target_lang,
            book_title=book.title,
            fallback_client=fallback_client,
        )

        # Persist immediately (kill-safety) — one transaction per batch.
        call = _aggregate_call(results, kind="batch")
        per_segment_cost = call.cost_eur / len(batch) if batch else 0.0
        cache.store_batch(
            bhash,
            model,
            target_lang,
            [
                SegmentTranslation(
                    segment_hash=segment_hash(seg.text),
                    text=text,
                    cost_eur=per_segment_cost,
                )
                for seg, text in zip(batch, translations)
            ],
            call,
        )

        for seg, text in zip(batch, translations):
            output.append(replace(seg, text=text))
            _remember(seg.text, text)

        stats.translated_segments += len(batch)
        stats.api_calls += len(results)
        stats.input_tokens += call.input_tokens
        stats.output_tokens += call.output_tokens
        stats.cost_eur += call.cost_eur
        stats.current_chapter_index = segment.chapter_index
        stats.current_chapter_title = segment.chapter_title
        if on_progress is not None:
            on_progress(stats)

        index = cursor

    if len(output) != len(book.segments):  # defensive: integrity invariant
        raise TranslationError(
            f"Segment count changed during translation: "
            f"{len(book.segments)} in, {len(output)} out."
        )

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
    """

    model: str
    target_lang: str
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
) -> CostEstimate:
    """Estimate the cost of translating ``book`` without making any API calls.

    Token counts use a chars/4 heuristic plus fixed per-batch prompt overhead.
    For reasoning-billing models the estimate adds
    :data:`REASONING_TOKENS_PER_CALL` output tokens per API call (glossary +
    batches), because those models bill hidden reasoning tokens as output and
    would otherwise be underestimated.

    Args:
        book: The source book.
        model: Model identifier to price against (must be in the pricing table).
        target_lang: Target language code.
        batch_size: Segments per batch (drives the batch count).
        skip_segment_ids: Segment IDs excluded from translation (back matter).
        glossary: Whether a glossary extraction call is included in the estimate.

    Returns:
        The :class:`CostEstimate`, including a per-chapter breakdown.
    """
    from berilo.providers.pricing import cost_eur

    skip = set(skip_segment_ids)
    reasoning = _is_reasoning_model(model)

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
    for chapter_index in sorted(chapters):
        entry = chapters[chapter_index]
        seg_count = int(entry["segments"])  # type: ignore[arg-type]
        chars = int(entry["chars"])  # type: ignore[arg-type]
        batches = _batches_for(seg_count, batch_size)
        total_batches += batches

        source_tokens = chars // CHARS_PER_TOKEN
        input_tokens = source_tokens + batches * PROMPT_OVERHEAD_TOKENS_PER_BATCH
        output_tokens = int(source_tokens * TARGET_EXPANSION)
        reasoning_tokens = batches * REASONING_TOKENS_PER_CALL if reasoning else 0
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
    glossary_input = 0
    glossary_output = 0
    if glossary and chapter_estimates:
        from berilo.glossary import DEFAULT_MAX_SAMPLE_CHARS

        glossary_input = DEFAULT_MAX_SAMPLE_CHARS // CHARS_PER_TOKEN
        glossary_output = 300 + (REASONING_TOKENS_PER_CALL if reasoning else 0)
        total_reasoning += REASONING_TOKENS_PER_CALL if reasoning else 0

    input_tokens = sum(c.input_tokens for c in chapter_estimates) + glossary_input
    output_tokens = sum(c.output_tokens for c in chapter_estimates) + glossary_output
    total_cost = cost_eur(model, input_tokens, output_tokens)

    return CostEstimate(
        model=model,
        target_lang=target_lang,
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
    )
