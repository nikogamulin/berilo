"""Command-line entry point for the Berilo translator.

Subcommands: ``translate``, ``inspect``, ``eval``, ``doctor``. ``inspect``
is implemented (S1.1); ``translate``, ``eval``, and ``doctor`` remain stubs
— each prints "not implemented" and exits 1, except the ``--help`` paths
(handled by click) which exit 0.
"""

from __future__ import annotations

import json
import logging
from typing import Any

import click

from berilo.models import Book
from berilo.normalize import normalize

logger = logging.getLogger(__name__)

NOT_IMPLEMENTED_EXIT_CODE = 1
PREVIEW_SEGMENT_COUNT = 3
PREVIEW_CHAR_LIMIT = 120


@click.group()
@click.version_option(package_name="berilo-translator")
def cli() -> None:
    """Berilo translator CLI: translate books with meaning-preserving LLM translation."""


@cli.command()
@click.argument("source_file", type=click.Path())
@click.option("--to", "target_language", default="sl", help="Target language code.")
@click.option("--model", default=None, help="Override the default translation model.")
@click.option("--bilingual", is_flag=True, help="Emit a bilingual source+target EPUB.")
@click.option("--dry-run", is_flag=True, help="Estimate cost without calling the API.")
@click.pass_context
def translate(
    ctx: click.Context,
    source_file: str,
    target_language: str,
    model: str | None,
    bilingual: bool,
    dry_run: bool,
) -> None:
    """Translate SOURCE_FILE into --to and write a translated EPUB."""
    click.echo("translate: not implemented")
    ctx.exit(NOT_IMPLEMENTED_EXIT_CODE)


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
@click.pass_context
def inspect(ctx: click.Context, source_file: str, as_json: bool) -> None:
    """Preview SOURCE_FILE's extraction quality and segment statistics."""
    try:
        book = normalize(source_file)
    except (ValueError, NotImplementedError, OSError) as exc:
        click.echo(f"inspect: {exc}", err=True)
        ctx.exit(NOT_IMPLEMENTED_EXIT_CODE)
        return

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
    click.echo("doctor: not implemented")
    ctx.exit(NOT_IMPLEMENTED_EXIT_CODE)


def main() -> None:
    """Console-script entry point (registered as ``berilo`` in pyproject.toml)."""
    cli()


if __name__ == "__main__":
    main()
