"""One-command verification of a pipeline change against the sample corpus.

``berilo translate`` and ``berilo eval`` already do everything this needs. What
this adds is the *procedure* — the ordering, the cache isolation, and the
side-by-side table — because getting that procedure subtly wrong is how a
comparison comes back meaningless while looking fine.

Two traps it exists to remove, both already recorded in ``docs/findings.md``:

* **A warm cache serves segments at EUR 0 and zero API calls**, so a second arm
  run against the first arm's cache measures nothing at all. A verification run
  that reported "0 API calls" for work it had genuinely done is what put that
  finding in the register. Every arm here gets its own throwaway cache file.
* **A cache key that omits the thing you changed turns the experiment into a
  no-op.** The key covers model, language, prompt version and glossary — but
  *not* batch size or concurrency, because those do not change a segment's
  text. Two arms differing only in batching would therefore collide in one
  cache and the second would be served free. Separate caches are what make the
  batching comparison honest, not an optional tidiness.

Costs are printed before anything is billed, and ``--dry-run`` prices the whole
comparison without making a single call.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path

#: Arms of the standard comparison: (label, extra CLI arguments).
#: ``baseline`` reproduces the pre-wave-loop pipeline exactly.
SPEED_ARMS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("baseline (1 lane, batch 10)", ("--concurrency", "1", "--batch-size", "10")),
    ("waves (4 lanes, batch 20)", ("--concurrency", "4", "--batch-size", "20")),
)

#: Arms of the machine-translation comparison. Both use the same batching, so
#: the only difference is where the draft came from.
MT_ARMS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("LLM draft + LLM edit", ()),
    ("Google draft + LLM edit", ("--mt-draft",)),
)


@dataclass
class ArmResult:
    """One arm's measured outcome.

    Attributes:
        label: Human-readable arm name.
        seconds: Wall clock for the translate step.
        output: Path to the translated EPUB.
        failed: Why the arm failed, or ``None``.
    """

    label: str
    seconds: float
    output: Path | None
    failed: str | None = None


def _run(args: list[str]) -> tuple[int, str]:
    """Run a berilo subcommand, streaming nothing, returning (code, output)."""
    completed = subprocess.run(  # noqa: S603 — fixed argv, no shell
        [sys.executable, "-m", "berilo.cli", *args],
        capture_output=True,
        text=True,
    )
    return completed.returncode, completed.stdout + completed.stderr


def run_arm(
    source: Path,
    lang: str,
    label: str,
    extra: tuple[str, ...],
    workdir: Path,
    style: str,
) -> ArmResult:
    """Translate ``source`` once, in isolation, and time it.

    Each arm gets its own cache database and its own output path, so no arm can
    be served another's work for free.

    Args:
        source: The sample EPUB.
        lang: Target language subtag.
        label: Arm name for the table.
        extra: Extra CLI arguments that define this arm.
        workdir: Scratch directory for caches and outputs.
        style: Translation style name.

    Returns:
        The :class:`ArmResult`.
    """
    slug = "".join(c if c.isalnum() else "-" for c in label).strip("-")
    cache = workdir / f"{slug}.sqlite"
    output = workdir / f"{slug}.{lang}.epub"

    started = time.monotonic()
    code, out = _run(
        [
            "translate",
            str(source),
            "--to",
            lang,
            "--style",
            style,
            "--cache-db",
            str(cache),
            "-o",
            str(output),
            "-y",
            *extra,
        ]
    )
    elapsed = time.monotonic() - started
    if code != 0:
        return ArmResult(
            label, elapsed, None, failed=out.strip().splitlines()[-1] if out else "failed"
        )
    return ArmResult(label, elapsed, output)


def score_arm(result: ArmResult, source: Path, sample: int, seed: int) -> str:
    """Score one arm with Rubric T and return the printed report.

    ``--source`` is passed explicitly. ``berilo eval`` otherwise auto-discovers
    the source next to the translated file, and every arm's output lives in a
    scratch directory precisely so the arms cannot share a cache — so the
    discovery would look in the wrong place and the whole comparison would
    produce timings and no scores.
    """
    if result.output is None:
        return "(not scored — the translate step failed)"
    code, out = _run(
        [
            "eval",
            str(result.output),
            "--source",
            str(source),
            "--sample",
            str(sample),
            "--seed",
            str(seed),
            "--no-write",
        ]
    )
    return out.strip() if code == 0 else f"(eval failed) {out.strip()}"


def dry_run_cost(source: Path, lang: str, style: str, arms) -> str:
    """Price every arm without making a single billed call."""
    lines = []
    for label, extra in arms:
        code, out = _run(
            ["translate", str(source), "--to", lang, "--style", style, "--dry-run", *extra]
        )
        total = next(
            (line for line in reversed(out.splitlines()) if "EUR" in line or "€" in line),
            "(no estimate line found)",
        )
        lines.append(f"  {label:32s} {total.strip()}")
    return "\n".join(lines)


def compare(
    source: Path,
    *,
    lang: str,
    style: str,
    arms,
    sample: int,
    seed: int,
    dry_run: bool,
) -> int:
    """Run every arm, score each, and print the comparison.

    Returns:
        A process exit code.
    """
    if not source.exists():
        print(f"No such sample book: {source}", file=sys.stderr)
        print("Build the corpus first: python3 -m berilo.eval.corpus build", file=sys.stderr)
        return 2

    print(f"Comparing {len(arms)} arms on {source.name} -> {lang}, style {style}\n")
    print("Estimated cost, no calls made:")
    print(dry_run_cost(source, lang, style, arms))
    if dry_run:
        print("\n--dry-run: stopping before anything is billed.")
        return 0

    workdir = Path(tempfile.mkdtemp(prefix="berilo-verify-"))
    print(f"\nEach arm gets its own cache under {workdir}")
    print("(a shared cache would serve the second arm for free and measure nothing)\n")

    try:
        results = [run_arm(source, lang, label, extra, workdir, style) for label, extra in arms]

        print("\n=== wall clock ===")
        for result in results:
            note = f"  FAILED: {result.failed}" if result.failed else ""
            print(f"  {result.label:32s} {result.seconds:7.1f}s{note}")
        ok = [r for r in results if r.output is not None]
        if len(ok) == 2 and ok[1].seconds > 0:
            print(f"\n  speedup: {ok[0].seconds / ok[1].seconds:.2f}x")

        print("\n=== Rubric T ===")
        print("Compare the scores AND their confidence intervals. A difference")
        print("inside the CIs is not a difference.\n")
        for result in results:
            print(f"--- {result.label} ---")
            print(score_arm(result, source, sample, seed))
            print()
        return 0
    finally:
        print(f"Scratch caches left in {workdir} (delete when done).")


def main(argv: list[str] | None = None) -> int:
    """Entry point for ``python -m berilo.eval.verify``."""
    import argparse

    parser = argparse.ArgumentParser(
        prog="python -m berilo.eval.verify",
        description=(
            "Run a paired comparison on the sample corpus: translate each arm "
            "with its own cache, then score both with Rubric T."
        ),
    )
    parser.add_argument(
        "--what",
        choices=("speed", "mt"),
        default="speed",
        help="speed: batching vs the old sequential pipeline. mt: --mt-draft vs LLM drafting.",
    )
    parser.add_argument("--book", default="corpus/build/berilo-sample-standard.epub")
    parser.add_argument("--to", dest="lang", default="sl")
    parser.add_argument("--style", default="revise_v1")
    parser.add_argument("--sample", type=int, default=40)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Price every arm and stop. Makes no billed calls.",
    )
    args = parser.parse_args(argv)

    return compare(
        Path(args.book),
        lang=args.lang,
        style=args.style,
        arms=SPEED_ARMS if args.what == "speed" else MT_ARMS,
        sample=args.sample,
        seed=args.seed,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    raise SystemExit(main())
