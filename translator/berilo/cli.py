"""Command-line entry point for the Berilo translator.

Subcommands: ``translate`` (S1.5), ``inspect`` (S1.1), ``doctor`` (S1.4) are
implemented; ``eval`` remains a stub — it prints "not implemented" and exits 1,
except the ``--help`` paths (handled by click) which exit 0.
"""

from __future__ import annotations

import json
import logging
import zipfile
from pathlib import Path
from typing import TYPE_CHECKING, Any

import click

from berilo.models import Book
from berilo.normalize import normalize
from berilo.providers.base import CompletionResult, LLMClient

if TYPE_CHECKING:
    from berilo.translate import CostEstimate, TranslationStats

logger = logging.getLogger(__name__)

NOT_IMPLEMENTED_EXIT_CODE = 1
INPUT_ERROR_EXIT_CODE = 1
PREVIEW_SEGMENT_COUNT = 3
PREVIEW_CHAR_LIMIT = 120

#: Errors from ``normalize`` that mean "bad/unsupported input file", surfaced to
#: the user as a clean message + nonzero exit rather than a traceback.
_NORMALIZE_ERRORS = (ValueError, NotImplementedError, OSError, zipfile.BadZipFile)


@click.group()
@click.version_option(package_name="berilo-translator")
def cli() -> None:
    """Berilo translator CLI: translate books with meaning-preserving LLM translation."""


@cli.command()
@click.argument("source_file", type=click.Path())
@click.option(
    "--to", "target_language", default=None, help="Target language code (default from config)."
)
@click.option("--model", default=None, help="Override the default translation model.")
@click.option("--dry-run", is_flag=True, help="Estimate cost without calling the API.")
@click.option("--bilingual", is_flag=True, help="Emit a bilingual source+target EPUB.")
@click.option(
    "--skip-back-matter",
    is_flag=True,
    help="Skip translating Index/Notes/Bibliography-style chapters (passed through untranslated).",
)
@click.option("--no-glossary", is_flag=True, help="Disable the per-book glossary pass.")
@click.option("--yes", "-y", "assume_yes", is_flag=True, help="Skip the cost confirmation prompt.")
@click.option(
    "--cache-db",
    default=None,
    type=click.Path(),
    help="Override the translation cache database path.",
)
@click.option(
    "--output",
    "-o",
    default=None,
    type=click.Path(),
    help="Output EPUB path (default: <source stem>.<lang>.epub next to the source).",
)
@click.pass_context
def translate(
    ctx: click.Context,
    source_file: str,
    target_language: str | None,
    model: str | None,
    dry_run: bool,
    bilingual: bool,
    skip_back_matter: bool,
    no_glossary: bool,
    assume_yes: bool,
    cache_db: str | None,
    output: str | None,
) -> None:
    """Translate SOURCE_FILE into --to and write a translated EPUB.

    A real run REQUIRES the user's go-ahead for the printed cost estimate (this
    is enforced socially — the estimate and a "proceeding" line print first).
    Use ``--dry-run`` to see the estimate without spending anything.
    """
    from dotenv import find_dotenv

    from berilo.config import load_config
    from berilo.translate import back_matter_segment_ids, estimate_cost

    try:
        book = normalize(source_file)
    except _NORMALIZE_ERRORS as exc:
        click.echo(f"translate: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    env_file = find_dotenv(usecwd=True) or None
    config = load_config(env_file=env_file, translation_model=model, target_lang=target_language)
    model_name = config.translation_model
    lang = config.target_lang
    skip_ids = back_matter_segment_ids(book) if skip_back_matter else set()

    if dry_run:
        estimate = estimate_cost(
            book,
            model=model_name,
            target_lang=lang,
            skip_segment_ids=skip_ids,
            glossary=not no_glossary,
        )
        _print_estimate(estimate, skip_back_matter=skip_back_matter)
        ctx.exit(0)
        return

    from berilo.cache import DEFAULT_CACHE_PATH, TranslationCache
    from berilo.glossary import build_glossary
    from berilo.translate import translate_book

    try:
        from berilo.providers import create_client

        client = create_client(model_name, config)
    except ValueError as exc:
        click.echo(f"translate: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    estimate = estimate_cost(
        book,
        model=model_name,
        target_lang=lang,
        skip_segment_ids=skip_ids,
        glossary=not no_glossary,
    )
    click.echo(
        f"Estimated cost: €{estimate.cost_eur:.4f} "
        f"({estimate.translatable_segments} segments, ~{estimate.batches} batches, "
        f"model {model_name})."
    )
    if not assume_yes and not click.confirm(f"Proceed with translation into '{lang}'?"):
        ctx.exit(0)
        return

    latest: dict[str, TranslationStats] = {}

    def _on_progress(stats: TranslationStats) -> None:
        latest["stats"] = stats
        click.echo(
            f"  ch {stats.current_chapter_index} "
            f"({stats.current_chapter_title or '—'}): "
            f"{stats.processed_segments}/{stats.total_segments} segments, "
            f"€{stats.cost_eur:.4f} spent"
        )

    cache = TranslationCache(cache_db or DEFAULT_CACHE_PATH)
    tracked_client = _CostTrackingClient(client)
    try:
        glossary = None
        if not no_glossary:
            glossary = build_glossary(
                book, client=tracked_client, target_lang=lang, model=model_name, cache=cache
            )
        translated = translate_book(
            book,
            client=tracked_client,
            target_lang=lang,
            cache=cache,
            glossary=glossary,
            skip_segment_ids=skip_ids,
            on_progress=_on_progress,
        )
        _print_summary(
            latest.get("stats"),
            skip_back_matter=skip_back_matter,
            total_cost_eur=tracked_client.total_cost_eur,
        )

        out_path = Path(output) if output else _default_output_path(source_file, lang)
        _assemble_output(translated, out_path, bilingual=bilingual, source_book=book)
    finally:
        cache.close()


def _default_output_path(source_file: str, lang: str) -> Path:
    """Return the default translated-EPUB path next to the source file."""
    source = Path(source_file)
    return source.with_name(f"{source.stem}.{lang}.epub")


def _print_estimate(estimate: CostEstimate, *, skip_back_matter: bool) -> None:
    """Print the dry-run per-chapter table and total estimated cost."""
    click.echo(f"Dry run — model {estimate.model} → '{estimate.target_lang}'. No API calls.")
    click.echo(f"{'chapter':<48} {'segments':>9} {'est.tokens':>11}")
    for chapter in estimate.chapters:
        title = (chapter.title or f"chapter {chapter.index}")[:46]
        tokens = chapter.input_tokens + chapter.output_tokens
        click.echo(f"{title:<48} {chapter.segments:>9} {tokens:>11}")
    click.echo(
        f"\nTranslatable segments: {estimate.translatable_segments} "
        f"(of {estimate.total_segments}); "
        f"skipped back matter: {estimate.skipped_segments}; empty: {estimate.empty_segments}."
    )
    click.echo(
        f"Estimated tokens: {estimate.input_tokens} in / {estimate.output_tokens} out "
        f"(incl. {estimate.reasoning_tokens} reasoning tokens)."
    )
    click.echo(f"Estimated cost: €{estimate.cost_eur:.4f} across ~{estimate.batches} batches.")
    if skip_back_matter and estimate.skipped_segments:
        click.echo(
            f"Back matter ({estimate.skipped_segments} segments) will pass through UNTRANSLATED."
        )


class _CostTrackingClient(LLMClient):
    """LLMClient proxy that sums the EUR cost of every call it forwards.

    Captures glossary + batch spend in one place so the end-of-run summary
    reports the true total, not just batch-call cost.
    """

    def __init__(self, inner: LLMClient) -> None:
        self._inner = inner
        self.total_cost_eur = 0.0

    def __getattr__(self, name: str) -> Any:
        """Delegate every other attribute (e.g. ``model``) to the wrapped client."""
        return getattr(self._inner, name)

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
        **kwargs: Any,
    ) -> CompletionResult:
        """Forward to the wrapped client and accumulate the call's cost."""
        result = self._inner.complete(prompt=prompt, messages=messages, **kwargs)
        self.total_cost_eur += result.cost_eur
        return result


def _print_summary(
    stats: TranslationStats | None, *, skip_back_matter: bool, total_cost_eur: float | None = None
) -> None:
    """Print the end-of-run summary line."""
    if stats is None:
        return
    total = stats.cost_eur if total_cost_eur is None else total_cost_eur
    click.echo(
        f"Done: {stats.total_segments} segments "
        f"({stats.translated_segments} translated, {stats.cached_segments} from cache, "
        f"{stats.skipped_segments} back matter, {stats.empty_segments} empty). "
        f"{stats.api_calls} API calls, "
        f"{stats.input_tokens} in / {stats.output_tokens} out tokens, "
        f"€{total:.4f} total (incl. glossary)."
    )
    if skip_back_matter and stats.skipped_segments:
        click.echo(
            f"{stats.skipped_segments} back-matter segments were passed through UNTRANSLATED."
        )


def _assemble_output(book: Book, output_path: Path, *, bilingual: bool, source_book: Book) -> None:
    """Assemble the final EPUB (the last step); tolerate a not-yet-built assembler.

    Translation is already fully persisted to the cache before this runs, so if
    the assembler is unavailable the run is still resumable — re-running will
    reuse every cached segment at zero cost.
    """
    from berilo import assemble

    build_epub = getattr(assemble, "build_epub", None)
    if build_epub is None:
        click.echo(
            "Translation complete and cached; EPUB assembly (build_epub) is not "
            "available yet — re-run once it lands to emit the EPUB (0 API cost)."
        )
        return
    try:
        result_path = build_epub(book, output_path, bilingual=bilingual, source_book=source_book)
    except NotImplementedError:
        click.echo(
            "Translation complete and cached; EPUB assembly is not implemented yet "
            "— re-run once it lands to emit the EPUB (0 API cost)."
        )
        return
    click.echo(f"Wrote {result_path}")


def _summarize(book: Book) -> dict[str, Any]:
    """Build the JSON-serializable inspection summary for a normalized book.

    Args:
        book: The normalized book to summarize.

    Returns:
        A dict with book metadata, aggregate segment counts, per-type
        counts, and a per-chapter breakdown.
    """
    chapters: dict[int, dict[str, Any]] = {}
    empty_segment_count = 0
    segment_type_counts: dict[str, int] = {}
    for segment in book.segments:
        if not segment.text.strip():
            empty_segment_count += 1
        segment_type_counts[segment.type.value] = segment_type_counts.get(segment.type.value, 0) + 1
        chapter = chapters.setdefault(
            segment.chapter_index,
            {"index": segment.chapter_index, "title": segment.chapter_title, "segment_count": 0},
        )
        chapter["segment_count"] += 1

    chapter_list = [chapters[index] for index in sorted(chapters)]
    return {
        "title": book.title,
        "authors": book.authors,
        "language": book.language,
        "source_format": book.source_format,
        "chapter_count": len(chapter_list),
        "segment_count": len(book.segments),
        "empty_segment_count": empty_segment_count,
        "segment_type_counts": segment_type_counts,
        "chapters": chapter_list,
    }


@cli.command()
@click.argument("source_file", type=click.Path())
@click.option("--json", "as_json", is_flag=True, help="Emit machine-readable JSON.")
@click.option("--screen", "do_screen", is_flag=True, help="LLM-screen sampled segments (paid).")
@click.option("--sample", "sample_n", default=30, type=int, help="Segments to screen.")
@click.option("--seed", default=42, type=int, help="Random seed for screening sample.")
@click.pass_context
def inspect(
    ctx: click.Context,
    source_file: str,
    as_json: bool,
    do_screen: bool,
    sample_n: int,
    seed: int,
) -> None:
    """Preview SOURCE_FILE's extraction quality and segment statistics."""
    try:
        book = normalize(source_file)
    except (ValueError, NotImplementedError, OSError, zipfile.BadZipFile) as exc:
        click.echo(f"inspect: {exc}", err=True)
        ctx.exit(NOT_IMPLEMENTED_EXIT_CODE)
        return

    if do_screen:
        from dotenv import find_dotenv

        from berilo.config import load_config
        from berilo.providers import create_client
        from berilo.screen import sample_segments, screen_segments

        config = load_config(env_file=find_dotenv(usecwd=True) or None)
        client = create_client(config.judge_model, config)
        report = screen_segments(sample_segments(book, n=sample_n, seed=seed), client)
        click.echo(
            f"screen: {report.clean_count}/{report.total} clean "
            f"({report.clean_fraction * 100:.1f}%), cost €{report.cost_eur:.4f}, "
            f"model {config.judge_model}, seed {seed}"
        )
        for verdict in report.flagged:
            click.echo(f"  flagged [{verdict.segment.id[:10]}]: {verdict.segment.text[:100]}")

    summary = _summarize(book)
    if as_json:
        click.echo(json.dumps(summary, ensure_ascii=False, indent=2))
        return

    authors = ", ".join(summary["authors"]) or "unknown author"
    click.echo(f"{summary['title']} — {authors}")
    click.echo(f"language: {summary['language']}   format: {summary['source_format']}")
    click.echo(
        f"{summary['chapter_count']} chapters, {summary['segment_count']} segments, "
        f"{summary['empty_segment_count']} empty"
    )
    click.echo("segment types:")
    for type_name, count in summary["segment_type_counts"].items():
        click.echo(f"  {type_name}: {count}")
    click.echo(f"first {PREVIEW_SEGMENT_COUNT} segments:")
    for segment in book.segments[:PREVIEW_SEGMENT_COUNT]:
        preview = segment.text
        if len(preview) > PREVIEW_CHAR_LIMIT:
            preview = preview[:PREVIEW_CHAR_LIMIT] + "…"
        click.echo(f"  [{segment.type.value}] {preview}")


@cli.command(name="eval")
@click.argument("translated_epub", type=click.Path())
@click.option("--sample", default=40, type=int, help="Number of segments to sample.")
@click.option("--seed", default=42, type=int, help="Random seed for sampling.")
@click.pass_context
def eval_(ctx: click.Context, translated_epub: str, sample: int, seed: int) -> None:
    """Score TRANSLATED_EPUB against Rubric T."""
    click.echo("eval: not implemented")
    ctx.exit(NOT_IMPLEMENTED_EXIT_CODE)


@cli.command()
@click.pass_context
def doctor(ctx: click.Context) -> None:
    """Smoke-test the configured LLM provider with a one-sentence request."""
    from dotenv import find_dotenv

    from berilo.config import load_config
    from berilo.providers.doctor import run_doctor

    env_file = find_dotenv(usecwd=True) or None
    ctx.exit(run_doctor(load_config(env_file=env_file)))


def main() -> None:
    """Console-script entry point (registered as ``berilo`` in pyproject.toml)."""
    cli()


if __name__ == "__main__":
    main()
