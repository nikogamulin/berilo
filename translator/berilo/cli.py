"""Command-line entry point for the Berilo translator.

Subcommands (all implemented): ``translate`` (S1.5), ``inspect`` (S1.1, with
``--screen`` from S1.2), ``eval`` (S1.7, Rubric T; ``--dump``/``--judge-repeats``
from S1.9), ``ab`` (S1.11, paired prompt-variant experiments), ``doctor`` (S1.4),
``serve`` (S1.15, LAN book handoff to the tablet).
"""

from __future__ import annotations

import io
import json
import logging
import zipfile
from pathlib import Path
from typing import TYPE_CHECKING, Any

import click
from click.core import ParameterSource

from berilo import prompts
from berilo.cache import BASELINE_PROMPT_VERSION
from berilo.models import Book
from berilo.normalize import normalize
from berilo.providers.base import CompletionResult, LLMClient

if TYPE_CHECKING:
    from berilo.translate import CostEstimate, TranslationStats

logger = logging.getLogger(__name__)

NOT_IMPLEMENTED_EXIT_CODE = 1
INPUT_ERROR_EXIT_CODE = 1
#: Cheap Anthropic model used only for content-policy-refused batches.
FALLBACK_TRANSLATION_MODEL = "claude-haiku-4-5"
PREVIEW_SEGMENT_COUNT = 3
PREVIEW_CHAR_LIMIT = 120

#: Errors from ``normalize`` that mean "bad/unsupported input file", surfaced to
#: the user as a clean message + nonzero exit rather than a traceback.
_NORMALIZE_ERRORS = (ValueError, NotImplementedError, OSError, zipfile.BadZipFile)


@click.group()
@click.version_option(package_name="berilo-translator")
def cli() -> None:
    """Berilo translator CLI: translate books with meaning-preserving LLM translation."""


def _mt_client(enabled: bool):
    """Build the machine-translation draft client, or return ``None``.

    Reads the key from the environment rather than taking it as a flag: a key on
    a command line lands in shell history and in any process listing, which is
    the one place `.env` exists to keep it out of.

    Args:
        enabled: Whether ``--mt-draft`` was passed.

    Returns:
        A ``GoogleTranslateClient``, or ``None`` when the flag is absent.

    Raises:
        click.ClickException: If the flag is set but no key is configured.
    """
    if not enabled:
        return None
    import os

    from berilo.providers.google_translate import GoogleTranslateClient

    key = os.environ.get("GOOGLE_TRANSLATE_API_KEY", "")
    if not key:
        raise click.ClickException(
            "--mt-draft needs GOOGLE_TRANSLATE_API_KEY in your .env " "(see .env.example)."
        )
    return GoogleTranslateClient(key)


def _batching_kwargs(concurrency: int | None, batch_size: int | None) -> dict[str, int]:
    """Return only the batching overrides the user actually set.

    Omitting an unset flag rather than defaulting it here keeps exactly one
    definition of each default — the engine's. Repeating the numbers in the CLI
    would give them a second place to drift, and because the dry-run estimate is
    priced from the same values, a disagreement would quote a cost the run does
    not go on to incur.

    Args:
        concurrency: ``--concurrency``, or ``None`` when unset.
        batch_size: ``--batch-size``, or ``None`` when unset.

    Returns:
        Keyword arguments to splat into ``translate_book`` / ``estimate_cost``.
    """
    kwargs: dict[str, int] = {}
    if concurrency is not None:
        kwargs["concurrency"] = concurrency
    if batch_size is not None:
        kwargs["batch_size"] = batch_size
    return kwargs


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
    "--style",
    "style_name",
    default=None,
    help=(
        "Translation prompt style from berilo.prompts (default: resolved from "
        "--to — a two-pass native-editor style, revise_v1 for Slovenian and "
        "revise_generic_v1 otherwise; use baseline_v1 for the cheaper "
        "single-pass prompt). A style bound to another language is refused."
    ),
)
@click.option(
    "--mt-draft",
    is_flag=True,
    help=(
        "Draft with Google Translate, then post-edit with the LLM. Needs "
        "GOOGLE_TRANSLATE_API_KEY and a revising style. Bills Google per "
        "character — run --dry-run first."
    ),
)
@click.option(
    "--concurrency",
    default=None,
    type=int,
    help=("Batches translated at once (default: 4). Use 1 for strictly " "sequential translation."),
)
@click.option(
    "--batch-size",
    default=None,
    type=int,
    help="Segments per API call (default: 20).",
)
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
    style_name: str | None,
    mt_draft: bool,
    concurrency: int | None,
    batch_size: int | None,
    cache_db: str | None,
    output: str | None,
) -> None:
    """Translate SOURCE_FILE into --to and write a translated EPUB.

    A real run REQUIRES the user's go-ahead for the printed cost estimate (this
    is enforced socially — the estimate and a "proceeding" line print first).
    Use ``--dry-run`` to see the estimate without spending anything.

    The prompt style is resolved from the target language (see
    :func:`berilo.prompts.resolve_style`); pass ``--style baseline_v1`` for the
    cheaper single-pass prompt. Naming a style bound to another language is a
    refusal, not a silent contradiction.
    """
    from dotenv import find_dotenv

    from berilo.config import load_config
    from berilo.translate import back_matter_segment_ids, estimate_cost

    try:
        requested_style = prompts.get_style(style_name) if style_name else None
    except KeyError as exc:
        click.echo(f"translate: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

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

    try:
        style = prompts.resolve_style(lang, requested=requested_style)
    except prompts.StyleLanguageError as exc:
        click.echo(f"translate: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    skip_ids = back_matter_segment_ids(book) if skip_back_matter else set()

    if dry_run:
        estimate = estimate_cost(
            book,
            model=model_name,
            target_lang=lang,
            skip_segment_ids=skip_ids,
            glossary=not no_glossary,
            style=style,
            **_batching_kwargs(None, batch_size),
        )
        _print_estimate(estimate, skip_back_matter=skip_back_matter, style=style)
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

    # Content-policy fallback: an OpenAI-moderated batch (e.g. a history book
    # quoting propaganda) is retried once via Anthropic when a key exists.
    fallback_client = None
    if not model_name.startswith("claude") and config.anthropic_api_key:
        fallback_client = create_client(FALLBACK_TRANSLATION_MODEL, config)

    estimate = estimate_cost(
        book,
        model=model_name,
        target_lang=lang,
        skip_segment_ids=skip_ids,
        glossary=not no_glossary,
        style=style,
        **_batching_kwargs(None, batch_size),
    )
    click.echo(
        f"Estimated cost: €{estimate.cost_eur:.4f} "
        f"({estimate.translatable_segments} segments, ~{estimate.batches} batches, "
        f"model {model_name}, style {style.name})."
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
    # Wrap the fallback client too: a content-policy-refused batch is real spend
    # against a second provider, and the printed total must include it (review
    # finding 5) rather than silently under-reporting the run's actual cost.
    tracked_fallback = _CostTrackingClient(fallback_client) if fallback_client is not None else None
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
            fallback_client=tracked_fallback,
            style=style,
            mt_client=_mt_client(mt_draft),
            source_lang=book.language,
            **_batching_kwargs(concurrency, batch_size),
        )
        total_cost_eur = tracked_client.total_cost_eur + (
            tracked_fallback.total_cost_eur if tracked_fallback is not None else 0.0
        )
        _print_summary(
            latest.get("stats"),
            skip_back_matter=skip_back_matter,
            total_cost_eur=total_cost_eur,
            style=style,
            target_lang=lang,
        )

        out_path = Path(output) if output else _default_output_path(source_file, lang)
        _assemble_output(translated, out_path, bilingual=bilingual, source_book=book)
    finally:
        cache.close()


def _default_output_path(source_file: str, lang: str) -> Path:
    """Return the default translated-EPUB path next to the source file."""
    source = Path(source_file)
    return source.with_name(f"{source.stem}.{lang}.epub")


def _print_estimate(
    estimate: CostEstimate,
    *,
    skip_back_matter: bool,
    style: prompts.TranslationStyle | None = None,
) -> None:
    """Print the dry-run per-chapter table and total estimated cost.

    Args:
        estimate: The computed dry-run estimate.
        skip_back_matter: Whether back matter was excluded from the estimate.
        style: Prompt style the estimate was priced for, named in the header so
            a two-pass style's higher figure is never mistaken for a bug.
    """
    style_note = f", style {style.name}" if style is not None else ""
    click.echo(
        f"Dry run — model {estimate.model} → '{estimate.target_lang}'{style_note}. No API calls."
    )
    if style is not None and style.revise_system is not None:
        click.echo(
            f"  Style '{style.name}' runs a second native-editor pass per batch, "
            "so it makes roughly twice the calls of a single-pass style."
        )
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
    stats: TranslationStats | None,
    *,
    skip_back_matter: bool,
    total_cost_eur: float | None = None,
    style: prompts.TranslationStyle | None = None,
    target_lang: str | None = None,
) -> None:
    """Print the end-of-run summary line.

    Args:
        stats: Running totals from the translation, or ``None`` if nothing ran.
        skip_back_matter: Whether back matter was passed through untranslated.
        total_cost_eur: True total cost including the glossary call.
        style: Prompt style used. When it carries a revision pass, any batch
            whose revision could not be applied is surfaced loudly — those
            segments silently hold only single-pass quality.
        target_lang: Target language the style was resolved for, named beside
            it so the resolution is visible in the run summary (plan §3.3)
            rather than inferred from the default.
    """
    if stats is None:
        return
    total = stats.cost_eur if total_cost_eur is None else total_cost_eur
    style_note = ""
    if style is not None:
        resolved_for = f" (resolved for '{target_lang}')" if target_lang else ""
        style_note = f" Style: {style.name}{resolved_for}."
    click.echo(
        f"Done: {stats.total_segments} segments "
        f"({stats.translated_segments} translated, {stats.cached_segments} from cache, "
        f"{stats.skipped_segments} back matter, {stats.empty_segments} empty). "
        f"{stats.api_calls} API calls, "
        f"{stats.input_tokens} in / {stats.output_tokens} out tokens, "
        f"€{total:.4f} total (incl. glossary).{style_note}"
    )
    if skip_back_matter and stats.skipped_segments:
        click.echo(
            f"{stats.skipped_segments} back-matter segments were passed through UNTRANSLATED."
        )
    if stats.revision_failures:
        click.echo(
            f"WARNING: the revision pass could not be applied to "
            f"{stats.revision_failures} batch(es); those segments carry only "
            f"un-revised, single-pass quality."
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


#: Exit code when source and translated structure cannot be aligned (scramble).
ALIGNMENT_ERROR_EXIT_CODE = 2


@cli.command(name="eval")
@click.argument("translated_epub", type=click.Path())
@click.option(
    "--source",
    "source_file",
    default=None,
    type=click.Path(),
    help="Source file (default: auto-discovered next to the translated EPUB).",
)
@click.option("--sample", default=40, type=int, help="Number of prose pairs to judge.")
@click.option("--seed", default=42, type=int, help="Random seed for sampling and bootstrap.")
@click.option("--judge-model", default=None, help="Override the judge model (default from config).")
@click.option(
    "--to", "target_language", default=None, help="Target language code (default from config)."
)
@click.option(
    "--scores-file",
    default=None,
    type=click.Path(),
    help="Score-row destination (default: loops/build/rubric_scores.jsonl).",
)
@click.option(
    "--cache-db", default=None, type=click.Path(), help="Override the translation cache path."
)
@click.option("--json", "as_json", is_flag=True, help="Emit machine-readable JSON.")
@click.option("--dry-run", is_flag=True, help="Describe the run without any judge calls or cost.")
@click.option("--no-write", is_flag=True, help="Do not append a score row.")
@click.option(
    "--dump",
    "dump_path",
    default=None,
    type=click.Path(),
    help="Write one JSON row per judged T2/T3/T6 sample to this JSONL path (S1.9).",
)
@click.option(
    "--judge-repeats",
    default=1,
    type=click.IntRange(min=1),
    help="Judge each sample this many times, to measure the judge's own noise floor.",
)
@click.pass_context
def eval_(
    ctx: click.Context,
    translated_epub: str,
    source_file: str | None,
    sample: int,
    seed: int,
    judge_model: str | None,
    target_language: str | None,
    scores_file: str | None,
    cache_db: str | None,
    as_json: bool,
    dry_run: bool,
    no_write: bool,
    dump_path: str | None,
    judge_repeats: int,
) -> None:
    """Score TRANSLATED_EPUB against Rubric T (seeded judge + bootstrap CI)."""
    from dotenv import find_dotenv

    from berilo.cache import DEFAULT_CACHE_PATH
    from berilo.config import load_config
    from berilo.eval import runner
    from berilo.eval.judge import Judge, JudgeError
    from berilo.eval.rubric_t import AlignmentError

    env_file = find_dotenv(usecwd=True) or None
    config = load_config(env_file=env_file, judge_model=judge_model, target_lang=target_language)
    lang = config.target_lang

    # Resolve and normalize the source book.
    source_path = (
        Path(source_file) if source_file else runner.discover_source(translated_epub, lang)
    )
    if source_path is None:
        click.echo(
            "eval: could not locate the source file — pass --source explicitly "
            f"(looked next to {translated_epub}).",
            err=True,
        )
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    try:
        source = normalize(source_path)
        translated = normalize(translated_epub)
    except _NORMALIZE_ERRORS as exc:
        click.echo(f"eval: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    cache_path = cache_db or DEFAULT_CACHE_PATH
    glossary, actual_cost = runner.read_cache_facts(
        cache_path, source, config.translation_model, lang
    )

    if dry_run:
        click.echo(
            runner.describe_plan(
                source,
                translated,
                sample_size=sample,
                seed=seed,
                glossary=glossary,
                actual_cost_eur=actual_cost,
                judge_repeats=judge_repeats,
            )
        )
        ctx.exit(0)
        return

    try:
        from berilo.providers import create_client

        client = create_client(config.judge_model, config)
    except ValueError as exc:
        click.echo(f"eval: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    try:
        result = runner.run_eval(
            source,
            translated,
            Judge(client),
            sample_size=sample,
            seed=seed,
            glossary=glossary,
            actual_cost_eur=actual_cost,
            judge_repeats=judge_repeats,
        )
    except AlignmentError as exc:
        click.echo(f"eval: ALIGNMENT FAILURE — {exc}", err=True)
        ctx.exit(ALIGNMENT_ERROR_EXIT_CODE)
        return
    except JudgeError as exc:
        click.echo(f"eval: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    if dump_path is not None:
        n_rows = runner.write_dump(result, dump_path)
        if not as_json:
            click.echo(f"Wrote {n_rows} sample row(s) to {dump_path}")

    if as_json:
        payload = runner.result_to_dict(result, seed=seed, sample=sample, title=source.title)
        click.echo(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        click.echo(runner.format_report(result, title=source.title))

    if not no_write:
        destination = scores_file or runner.DEFAULT_SCORES_FILE
        runner.append_score_row(runner.score_row(result, seed=seed, sample=sample), destination)
        if not as_json:
            click.echo(f"Wrote score row to {destination}")


@cli.command(name="ab")
@click.argument("translated_epub", type=click.Path())
@click.option("--variant", required=True, help="Translation style to test (see berilo.prompts).")
@click.option(
    "--control",
    default=BASELINE_PROMPT_VERSION,
    help="Prompt version attributed to the existing translation.",
)
@click.option(
    "--source",
    "source_file",
    default=None,
    type=click.Path(),
    help="Source file (default: auto-discovered next to the translated EPUB).",
)
@click.option("--runs", default=None, type=int, help="Contiguous runs (clusters) to sample.")
@click.option("--run-length", default=None, type=int, help="Body-prose segments per run.")
@click.option("--seed", default=None, type=int, help="Seed for run selection and the bootstrap.")
@click.option("--model", default=None, help="Override the translation model.")
@click.option("--judge-model", default=None, help="Override the judge model.")
@click.option(
    "--to", "target_language", default=None, help="Target language code (default from config)."
)
@click.option(
    "--cache-db",
    default=None,
    type=click.Path(),
    help="Production cache read for the book's glossary (never written).",
)
@click.option(
    "--scratch-cache",
    default=None,
    type=click.Path(),
    help="Scratch cache the variant is written to (never the production cache).",
)
@click.option(
    "--dump",
    "dump_path",
    default=None,
    type=click.Path(),
    help="Write one JSON row per judged pair (source, both arms, all four verdicts).",
)
@click.option("--json", "as_json", is_flag=True, help="Emit machine-readable JSON.")
@click.option("--dry-run", is_flag=True, help="Print the plan and estimate without spending.")
@click.option("--yes", "-y", "assume_yes", is_flag=True, help="Skip the cost confirmation prompt.")
@click.pass_context
def ab(
    ctx: click.Context,
    translated_epub: str,
    variant: str,
    control: str,
    source_file: str | None,
    runs: int | None,
    run_length: int | None,
    seed: int | None,
    model: str | None,
    judge_model: str | None,
    target_language: str | None,
    cache_db: str | None,
    scratch_cache: str | None,
    dump_path: str | None,
    as_json: bool,
    dry_run: bool,
    assume_yes: bool,
) -> None:
    """A/B a prompt variant against TRANSLATED_EPUB's existing translation.

    Re-translates a few seeded, contiguous runs of body prose through the real
    ``translate_book`` path (production batch size, rolling context, the book's
    cached glossary) under ``--variant``, then judges control and variant paired
    on T2 (meaning) and T3 (fluency). Deltas carry 95 % bootstrap CIs whose
    resampling unit is the contiguous RUN, not the segment.
    """
    from dotenv import find_dotenv

    from berilo import experiment
    from berilo.cache import DEFAULT_CACHE_PATH, TranslationCache
    from berilo.config import load_config
    from berilo.eval import runner
    from berilo.eval.judge import Judge, JudgeError
    from berilo.eval.rubric_t import AlignmentError
    from berilo.glossary import Glossary
    from berilo.prompts import StyleLanguageError, ensure_supports, get_style
    from berilo.translate import TranslationError

    env_file = find_dotenv(usecwd=True) or None
    config = load_config(
        env_file=env_file,
        translation_model=model,
        judge_model=judge_model,
        target_lang=target_language,
    )
    lang = config.target_lang

    try:
        style = get_style(variant)
        ensure_supports(style, lang)
    except (KeyError, StyleLanguageError) as exc:
        click.echo(f"ab: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    source_path = (
        Path(source_file) if source_file else runner.discover_source(translated_epub, lang)
    )
    if source_path is None:
        click.echo(
            "ab: could not locate the source file — pass --source explicitly "
            f"(looked next to {translated_epub}).",
            err=True,
        )
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    try:
        source = normalize(source_path)
        translated = normalize(translated_epub)
    except _NORMALIZE_ERRORS as exc:
        click.echo(f"ab: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    # The production cache is read READ-ONLY, for the book's glossary only.
    cached_glossary, _ = runner.read_cache_facts(
        cache_db or DEFAULT_CACHE_PATH, source, config.translation_model, lang
    )
    glossary = Glossary(terms=dict(cached_glossary)) if cached_glossary else None

    try:
        plan = experiment.build_plan(
            source,
            translated,
            style=style,
            control_version=control,
            model=config.translation_model,
            judge_model=config.judge_model,
            target_lang=lang,
            glossary=glossary,
            runs=runs if runs is not None else experiment.DEFAULT_RUNS,
            run_length=run_length if run_length is not None else experiment.DEFAULT_RUN_LENGTH,
            seed=seed if seed is not None else experiment.DEFAULT_SEED,
        )
    except experiment.ExperimentError as exc:
        click.echo(f"ab: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return
    except AlignmentError as exc:
        click.echo(f"ab: ALIGNMENT FAILURE — {exc}", err=True)
        ctx.exit(ALIGNMENT_ERROR_EXIT_CODE)
        return

    if dry_run:
        click.echo(experiment.format_plan(plan))
        ctx.exit(0)
        return

    click.echo(
        f"Estimated cost: €{plan.estimated_cost_eur:.4f} "
        f"({plan.judged_segments} segments re-translated under '{plan.variant_name}', "
        f"{plan.judge_calls} judge calls)."
    )
    if not assume_yes and not click.confirm(f"Proceed with the A/B run for '{variant}'?"):
        ctx.exit(0)
        return

    try:
        from berilo.providers import create_client

        client = create_client(config.translation_model, config)
        judge_client = create_client(config.judge_model, config)
    except ValueError as exc:
        click.echo(f"ab: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return

    cache = TranslationCache(scratch_cache or experiment.DEFAULT_EXPERIMENT_CACHE_PATH)
    try:
        result = experiment.run_experiment(
            source,
            plan=plan,
            style=style,
            client=client,
            judge=Judge(judge_client),
            scratch_cache=cache,
            glossary=glossary,
        )
    except (experiment.ExperimentError, JudgeError, TranslationError) as exc:
        click.echo(f"ab: {exc}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)
        return
    finally:
        cache.close()

    if dump_path is not None:
        n_rows = experiment.write_pairs_dump(result, dump_path)
        if not as_json:
            click.echo(f"Wrote {n_rows} judged-pair row(s) to {dump_path}")

    if as_json:
        click.echo(json.dumps(experiment.result_to_dict(result), ensure_ascii=False, indent=2))
    else:
        click.echo(experiment.format_result(result))


@cli.command()
@click.option(
    "--dir",
    "directory",
    default="data/examples",
    show_default=True,
    type=click.Path(file_okay=False),
    help="Directory of EPUBs to publish.",
)
@click.option(
    "--port",
    default=8577,
    show_default=True,
    help="TCP port to listen on; falls back to a free port if this one is busy.",
)
@click.option(
    "--host",
    default="0.0.0.0",  # noqa: S104 - the tablet has to be able to reach it
    show_default=True,
    help="Interface to bind; 127.0.0.1 keeps the server on this machine.",
)
@click.option("--no-qr", is_flag=True, help="Print the URL without a QR code.")
@click.pass_context
def serve(ctx: click.Context, directory: str, port: int, host: str, no_qr: bool) -> None:
    """Publish translated EPUBs on the LAN so a tablet can download them.

    Prints a tokenized URL (and a QR code for it) and serves until Ctrl-C.
    Scan the code with the tablet, tap a book to download it, then import the
    file in the reader app. Books are served from this machine only — nothing
    is uploaded anywhere.
    """
    from berilo.serve.server import BookServer, lan_address_candidates

    source_dir = Path(directory)
    if not source_dir.is_dir():
        click.echo(f"Not a directory: {source_dir}", err=True)
        ctx.exit(INPUT_ERROR_EXIT_CODE)

    explicit_port = ctx.get_parameter_source("port") is not ParameterSource.DEFAULT
    try:
        server = BookServer(source_dir, host=host, port=port)
    except OSError as error:
        if explicit_port:
            click.echo(f"Could not bind {host}:{port} — {error}", err=True)
            ctx.exit(INPUT_ERROR_EXIT_CODE)
            return
        # The default port is a popular one; rather than fail, take any free
        # port — the URL is printed and QR-encoded anyway, so it costs nothing.
        click.echo(f"Port {port} is busy ({error.strerror}); using a free port.", err=True)
        try:
            server = BookServer(source_dir, host=host, port=0)
        except OSError as fallback_error:
            click.echo(f"Could not bind {host} — {fallback_error}", err=True)
            ctx.exit(INPUT_ERROR_EXIT_CODE)
            return

    logging.basicConfig(level=logging.INFO, format="%(message)s")
    books = server.catalog()
    bound_host = host if host not in ("0.0.0.0", "") else None  # noqa: S104
    url = server.url(bound_host)

    click.echo(f"Berilo — {len(books)} EPUB(s) from {source_dir}")
    click.echo(url)
    if not no_qr:
        click.echo(_qr_block(url))

    # Address detection is a guess: a box with a VPN or Docker bridges has
    # many addresses and the routing table cannot know which one the tablet
    # shares a network with. Show the alternatives so a wrong guess is a
    # visible second option, not a silent timeout.
    if bound_host is None:
        alternatives = [address for address in lan_address_candidates() if address not in url]
        if alternatives:
            click.echo("\nIf that address does not load, try:")
            for address in alternatives:
                click.echo(f"  http://{address}:{server.port}/?t={server.token}")
    click.echo("\nCtrl-C to stop.\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        click.echo("\nStopped.")


def _qr_block(url: str) -> str:
    """Render *url* as a QR code for the terminal, or a hint if segno is missing."""
    try:
        import segno
    except ImportError:  # pragma: no cover - depends on the install
        return "(install `segno` for a scannable QR code)"

    buffer = io.StringIO()
    segno.make(url, error="m").terminal(buffer, compact=True)
    return buffer.getvalue()


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
