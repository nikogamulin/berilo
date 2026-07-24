"""Command-line entry point for the Berilo translator.

Subcommands: ``translate``, ``inspect``, ``eval``, ``doctor``. All are stubs
in this skeleton — each prints "not implemented" and exits 1, except the
``--help`` paths (handled by click) which exit 0.
"""

from __future__ import annotations

import logging

import click

logger = logging.getLogger(__name__)

NOT_IMPLEMENTED_EXIT_CODE = 1


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


@cli.command()
@click.argument("source_file", type=click.Path())
@click.option("--json", "as_json", is_flag=True, help="Emit machine-readable JSON.")
@click.pass_context
def inspect(ctx: click.Context, source_file: str, as_json: bool) -> None:
    """Preview SOURCE_FILE's extraction quality and segment statistics."""
    click.echo("inspect: not implemented")
    ctx.exit(NOT_IMPLEMENTED_EXIT_CODE)


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
