"""Per-book glossary pass: fix renderings of names and recurring terms.

Before translating a book, one LLM call samples the source text and extracts
proper names, place names, and recurring domain terms into a ``source term ->
fixed target rendering`` map. That map is injected into every batch prompt so
terminology stays consistent across chunk boundaries (``docs/project_spec.md``
§4.2). People and places usually keep their canonical base form in Slovenian
but still decline, so the model is instructed to record the *base* form only.

The glossary is memoized in the translation cache keyed on
``(book_hash, model, lang, prompt_version)`` — it is built at most once per
book/model/lang *and glossary-prompt identity*. That last component is
:func:`glossary_prompt_version`, **derived** from the extraction prompt and the
sampling parameters rather than declared by hand: without it, improving the
extraction prompt to fix a mistranslated name would hit the unchanged key and
return the old terms at zero cost (CLAUDE.md §9).

The resulting terms in turn key every translation row through
:func:`glossary_identity`, so a *different glossary* can never be served a
translation produced under the old one.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass, field

from berilo.cache import CallRecord, TranslationCache, book_hash, glossary_hash
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

#: User-message template for the extraction call. Named (rather than inlined)
#: so it participates in :func:`glossary_prompt_version` — every string that
#: reaches the model must be inside the version, or a prompt edit becomes a
#: silent no-op on the next run.
_GLOSSARY_PROMPT_TEMPLATE = "Target language: {target_lang}.\n\nSource passages:\n{sample}"

_JSON_OBJECT_RE = re.compile(r"\{.*\}", re.DOTALL)

#: Prefix on every derived glossary prompt version, so the value is legible in
#: a database dump rather than a bare digest.
_GLOSSARY_VERSION_PREFIX = "glossary_"

#: Hex characters of the payload digest kept in the version string.
_GLOSSARY_VERSION_DIGEST_CHARS = 12


def glossary_prompt_version(
    *,
    sample_chapters: int = DEFAULT_SAMPLE_CHAPTERS,
    max_sample_chars: int = DEFAULT_MAX_SAMPLE_CHARS,
    max_terms: int = MAX_GLOSSARY_TERMS,
) -> str:
    """Return the cache identity of the glossary-extraction pass.

    Derived from every input that shapes the extracted terms — the system
    prompt, the user-message template, and the sampling parameters actually
    used — so that editing any of them changes the key automatically. A
    hand-maintained version string is exactly what was forgotten the first
    time this defect appeared (CLAUDE.md §9).

    Args:
        sample_chapters: Number of chapters sampled for extraction.
        max_sample_chars: Character cap on the sampled source text.
        max_terms: Cap on retained glossary terms.

    Returns:
        A short, stable version string such as ``"glossary_a1b2c3d4e5f6"``.
    """
    payload = "\n".join(
        (
            _GLOSSARY_SYSTEM,
            _GLOSSARY_PROMPT_TEMPLATE,
            f"sample_chapters={sample_chapters}",
            f"max_sample_chars={max_sample_chars}",
            f"max_terms={max_terms}",
        )
    )
    digest = hashlib.sha1(payload.encode("utf-8")).hexdigest()[:_GLOSSARY_VERSION_DIGEST_CHARS]
    return f"{_GLOSSARY_VERSION_PREFIX}{digest}"


#: Identity of the glossary pass under its default sampling parameters.
GLOSSARY_PROMPT_VERSION = glossary_prompt_version()


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

        Terms are rendered **sorted by source term**, never in insertion
        order. The block is what :func:`glossary_identity` hashes, and the
        model that extracts the terms offers no ordering contract: two
        extractions of a semantically identical glossary in a different order
        would otherwise key differently and re-translate the whole book at full
        price (~€1.45 at the ``revise_v1`` default) for no change at all.

        Returns:
            A human-readable block listing every ``term -> rendering`` pair in
            source-term order, or an empty string when the glossary is empty.
        """
        if not self.terms:
            return ""
        lines = [f"- {source} -> {self.terms[source]}" for source in sorted(self.terms)]
        return (
            "GLOSSARY (use these fixed renderings for the following terms; "
            "inflect/decline them naturally as the target language requires):\n" + "\n".join(lines)
        )


def glossary_identity(glossary: Glossary | None) -> str:
    """Return the translation-cache component identifying ``glossary``.

    Computed over :meth:`Glossary.to_prompt_block` — the exact text the
    glossary contributes to every batch prompt — so the key tracks what the
    model actually saw. ``None`` and an empty glossary render the same empty
    block and therefore share one identity, which is correct: both inject
    nothing.

    Args:
        glossary: The glossary that will be injected, or ``None``.

    Returns:
        A 40-character hexadecimal sha1 digest.
    """
    return glossary_hash("" if glossary is None else glossary.to_prompt_block())


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

    Makes at most one LLM call. If a glossary for
    ``(book, model, lang, glossary_prompt_version)`` is already cached, no call
    is made. Changing the extraction prompt or the sampling parameters changes
    that last component, so the pass re-runs instead of returning stale terms.

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
    prompt_version = glossary_prompt_version(
        sample_chapters=sample_chapters,
        max_sample_chars=max_sample_chars,
    )

    if cache is not None:
        cached = cache.get_glossary(bhash, model_name, target_lang, prompt_version)
        if cached is not None:
            logger.info("Glossary cache hit (%d terms, %s).", len(cached), prompt_version)
            return Glossary(terms=cached)

    sample = _sample_source_text(book, sample_chapters=sample_chapters, max_chars=max_sample_chars)
    if not sample.strip():
        return Glossary(terms={})

    prompt = _GLOSSARY_PROMPT_TEMPLATE.format(target_lang=target_lang, sample=sample)
    result = client.complete(prompt=prompt, system=_GLOSSARY_SYSTEM)
    terms = _parse_glossary_json(result.text)
    logger.info("Glossary extracted: %d terms.", len(terms))

    if cache is not None:
        cache.store_glossary(
            bhash,
            model_name,
            target_lang,
            terms,
            call=CallRecord(
                kind="glossary",
                input_tokens=result.input_tokens,
                output_tokens=result.output_tokens,
                cost_eur=result.cost_eur,
            ),
            prompt_version=prompt_version,
        )
    return Glossary(terms=terms)
