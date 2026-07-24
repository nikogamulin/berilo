"""Per-book glossary pass: fix renderings of names and recurring terms.

Before translating a book, one LLM call samples the source text and extracts
proper names, place names, and recurring domain terms into a ``source term ->
fixed target rendering`` map. That map is injected into every batch prompt so
terminology stays consistent across chunk boundaries (``docs/project_spec.md``
§4.2). People and places usually keep their canonical base form in Slovenian
but still decline, so the model is instructed to record the *base* form only.

The glossary is memoized in the translation cache keyed on
``(book_hash, model, lang)`` — it is built at most once per book/model/lang.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field

from berilo.cache import CallRecord, TranslationCache, book_hash
from berilo.models import Book
from berilo.providers.base import LLMClient

logger = logging.getLogger(__name__)

#: How many chapters (evenly sampled across the book) to feed the extractor.
DEFAULT_SAMPLE_CHAPTERS = 6

#: Cap on the source characters sent to the single glossary-extraction call, to
#: keep it cheap regardless of book size.
DEFAULT_MAX_SAMPLE_CHARS = 12_000

#: Upper bound on distinct glossary terms retained (keeps every batch prompt
#: small); the most-relevant terms the model returns are kept in order.
MAX_GLOSSARY_TERMS = 80

_GLOSSARY_SYSTEM = (
    "You build a translation glossary for a book. You are given sample "
    "passages of the source text. Identify proper names (people, places, "
    "organizations) and recurring domain-specific terms that MUST be rendered "
    "consistently throughout the translation. For each, give the canonical "
    "BASE form of the fixed rendering in the target language (do not inflect or "
    "decline it). Personal and place names usually keep their original spelling "
    "in Slovenian. Reply with ONLY a JSON object mapping each source term to its "
    "fixed target rendering. No prose, no code fences."
)

_JSON_OBJECT_RE = re.compile(r"\{.*\}", re.DOTALL)


@dataclass(frozen=True)
class Glossary:
    """A fixed ``source term -> target rendering`` map for one book.

    Attributes:
        terms: Mapping from a source term to its fixed target rendering.
    """

    terms: dict[str, str] = field(default_factory=dict)

    def is_empty(self) -> bool:
        """Return ``True`` when the glossary holds no terms."""
        return not self.terms

    def to_prompt_block(self) -> str:
        """Render the glossary as a prompt-ready instruction block.

        Returns:
            A human-readable block listing every ``term -> rendering`` pair,
            or an empty string when the glossary is empty.
        """
        if not self.terms:
            return ""
        lines = [f"- {source} -> {target}" for source, target in self.terms.items()]
        return (
            "GLOSSARY (use these fixed renderings for the following terms; "
            "inflect/decline them naturally as the target language requires):\n" + "\n".join(lines)
        )


def _sample_source_text(book: Book, *, sample_chapters: int, max_chars: int) -> str:
    """Concatenate source text from chapters evenly sampled across the book.

    Args:
        book: The source book.
        sample_chapters: Number of chapters to sample.
        max_chars: Hard cap on total characters returned.

    Returns:
        A newline-joined excerpt of the source text, truncated to ``max_chars``.
    """
    chapter_indices = sorted({segment.chapter_index for segment in book.segments})
    if not chapter_indices:
        return ""
    if len(chapter_indices) <= sample_chapters:
        chosen = set(chapter_indices)
    else:
        step = len(chapter_indices) / sample_chapters
        chosen = {chapter_indices[int(i * step)] for i in range(sample_chapters)}

    parts: list[str] = []
    used = 0
    for segment in book.segments:
        if segment.chapter_index not in chosen:
            continue
        text = segment.text.strip()
        if not text:
            continue
        parts.append(text)
        used += len(text)
        if used >= max_chars:
            break
    return "\n".join(parts)[:max_chars]


def _parse_glossary_json(raw: str) -> dict[str, str]:
    """Parse the extractor's JSON reply into a term map, tolerating stray prose.

    Args:
        raw: The raw model reply.

    Returns:
        The parsed ``term -> rendering`` map; empty if nothing parseable.
    """
    match = _JSON_OBJECT_RE.search(raw)
    if match is None:
        logger.warning("Glossary extraction returned no JSON object; using empty glossary.")
        return {}
    try:
        loaded = json.loads(match.group(0))
    except json.JSONDecodeError:
        logger.warning("Glossary extraction JSON did not parse; using empty glossary.")
        return {}
    if not isinstance(loaded, dict):
        return {}
    terms: dict[str, str] = {}
    for source, target in loaded.items():
        source_str = str(source).strip()
        target_str = str(target).strip()
        if source_str and target_str:
            terms[source_str] = target_str
        if len(terms) >= MAX_GLOSSARY_TERMS:
            break
    return terms


def build_glossary(
    book: Book,
    *,
    client: LLMClient,
    target_lang: str,
    model: str | None = None,
    cache: TranslationCache | None = None,
    sample_chapters: int = DEFAULT_SAMPLE_CHAPTERS,
    max_sample_chars: int = DEFAULT_MAX_SAMPLE_CHARS,
) -> Glossary:
    """Build (or load from cache) the fixed-term glossary for ``book``.

    Makes at most one LLM call. If a glossary for ``(book, model, lang)`` is
    already cached, no call is made.

    Args:
        book: The source book to extract terms from.
        client: LLM client used for the single extraction call.
        target_lang: Target language code (e.g. ``"sl"``).
        model: Model identifier for cache keying; defaults to ``client.model``.
        cache: Optional translation cache for memoization.
        sample_chapters: Number of chapters to sample for extraction.
        max_sample_chars: Character cap on the extraction prompt's source text.

    Returns:
        The resolved :class:`Glossary` (possibly empty).
    """
    model_name = model if model is not None else getattr(client, "model", "unknown")
    bhash = book_hash(book)

    if cache is not None:
        cached = cache.get_glossary(bhash, model_name, target_lang)
        if cached is not None:
            logger.info("Glossary cache hit (%d terms).", len(cached))
            return Glossary(terms=cached)

    sample = _sample_source_text(book, sample_chapters=sample_chapters, max_chars=max_sample_chars)
    if not sample.strip():
        return Glossary(terms={})

    prompt = f"Target language: {target_lang}.\n\n" "Source passages:\n" f"{sample}"
    result = client.complete(prompt=prompt, system=_GLOSSARY_SYSTEM)
    terms = _parse_glossary_json(result.text)
    logger.info("Glossary extracted: %d terms.", len(terms))

    if cache is not None:
        cache.store_glossary(
            bhash,
            model_name,
            target_lang,
            terms,
            CallRecord(
                kind="glossary",
                input_tokens=result.input_tokens,
                output_tokens=result.output_tokens,
                cost_eur=result.cost_eur,
            ),
        )
    return Glossary(terms=terms)
