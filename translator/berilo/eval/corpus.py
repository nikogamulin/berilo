"""Build the fixed evaluation corpus: small, representative sample books.

Rubric T scores a *translated book*. Scoring a real one costs €0.60–€1.45 and
tens of minutes, which is fine at a milestone and far too slow to sit inside a
build loop. This module cuts a fixed, deterministic sample out of the reference
books and assembles it into an ordinary EPUB, so the whole existing pipeline
runs on it unchanged::

    berilo translate corpus/build/berilo-sample-standard.epub --to sl
    berilo eval corpus/build/berilo-sample-standard.sl.epub

**The prose never enters this repository.** The reference books are
copyrighted and live outside it (see ``BERILO_REFS_DIR``); the built sample
EPUBs are gitignored. What *is* committed is ``corpus/manifest-<tier>.json``,
which records only derived values — hashes, indices, counts, and which strata
each selected segment satisfies. `verify` re-derives the selection from the
books on disk and reports any drift, so the manifest is an auditable
description of a sample it does not contain.

Representativeness is by explicit stratification, not by trusting a random
draw at this size: every source book contributes, every paragraph-length band
is present, and each of the textual features that stress a translator (numbers,
acronyms, quotations, dashes, citations, PDF hyphen-breaks) is guaranteed a
minimum count. The manifest's ``coverage`` block records what was *achieved*
rather than what was intended, and ``coverage.unmet_strata`` names any floor
that could not be filled — the shortfall is the interesting case, so it is
stated rather than hidden.

Regenerate with::

    PYTHONPATH=translator python3 -m berilo.eval.corpus build
    PYTHONPATH=translator python3 -m berilo.eval.corpus verify
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import random
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from berilo.assemble import build_epub
from berilo.cache import book_hash, segment_hash
from berilo.models import Book, Segment, SegmentType, make_segment_id
from berilo.normalize import normalize
from berilo.screen import back_matter_chapter_indices, front_matter_chapter_indices

logger = logging.getLogger(__name__)

#: Bumped whenever the manifest *shape* changes, so a reader that pins it fails
#: loudly rather than mis-parsing.
MANIFEST_VERSION = 1

#: Environment variable naming the directory of reference books. The books are
#: copyrighted and deliberately live outside this repository; the default is the
#: workspace checkout's ``refs/``, which is a sibling of the repo root.
ENV_REFS_DIR = "BERILO_REFS_DIR"

#: Sample sizes, in body-prose paragraphs, per tier. `smoke` is sized to run
#: inside a dev loop; `standard` to carry a Rubric T score worth quoting.
TIERS: dict[str, int] = {"smoke": 40, "standard": 150}

#: Target languages the samples exist to evaluate. `sl` is primary — Rubric T's
#: judge prompts name Slovenian explicitly — and the rest are the locales
#: `berilo-cloud` ships.
TARGET_LANGS: tuple[str, ...] = ("sl", "de", "es", "it", "pt")

#: Minimum words for a paragraph to be eligible. Below this a segment is mostly
#: a fragment, a stray caption, or extraction debris, and judging a translation
#: of it measures the extractor rather than the translator.
MIN_WORDS = 15

#: Paragraph-length bands, as ``(name, min_words, max_words)`` with the last
#: unbounded. Long paragraphs stress batching and marker alignment; short ones
#: stress marker *density*, since more markers fit in one request.
LENGTH_BANDS: tuple[tuple[str, int, int | None], ...] = (
    ("short", MIN_WORDS, 34),
    ("medium", 35, 79),
    ("long", 80, None),
)

#: Textual features that stress a translator, each with the failure it provokes.
#: Prevalence in the reference corpus is in `corpus/README.md`; the rare ones
#: are why selection is stratified rather than uniform, since a 40-paragraph
#: uniform draw would miss `hyphen_break` (0.5 %) altogether about four times in
#: five.
STRATA: dict[str, re.Pattern[str]] = {
    # Digits must survive verbatim; a mistranslated quantity is a factual error
    # the prose gives no clue about.
    "digits": re.compile(r"\d"),
    # Years are digits that some languages reformat, and dates are a classic
    # place for a model to "helpfully" localise.
    "year": re.compile(r"\b(1[89]\d\d|20\d\d)\b"),
    # Acronyms must not be translated, expanded, or case-folded.
    "acronym": re.compile(r"\b[A-Z]{2,6}\b"),
    # Quotation marks differ per language (Slovenian uses „…“); getting them
    # wrong is a fluency defect a judge will catch.
    "quotes": re.compile(r"[“”\"]"),
    # Em/en dashes are frequently mangled into hyphens or dropped.
    "dash": re.compile(r"[—–]"),
    "parenthetical": re.compile(r"\([^)]{3,}\)"),
    # Citation markers must survive as-is.
    "citation": re.compile(r"\[\d+\]|\(\d{4}\)"),
    # A PDF extraction artifact: a word split across a line break. The
    # translator has to cope with "informa- tion" appearing mid-sentence.
    "hyphen_break": re.compile(r"[a-z]-\s+[a-z]"),
    # Non-ASCII in the *source* — accented names, curly quotes, symbols.
    "non_ascii": re.compile(r"[^\x00-\x7f]"),
    # A trailing colon introduces a list that the segmenter did not capture as
    # list items; the following paragraph depends on it.
    "colon_lead": re.compile(r":\s*$"),
}

#: Minimum selected segments per stratum, per tier. Rare strata cannot always
#: reach these; `SELECTION_REPORT` records the shortfall rather than hiding it.
STRATUM_FLOOR: dict[str, int] = {"smoke": 2, "standard": 6}

#: Minimum segments contributed by each source book, per tier, so a large book
#: cannot crowd a small one out of the sample entirely.
BOOK_FLOOR: dict[str, int] = {"smoke": 4, "standard": 12}

#: Seed for every draw. Fixed, because the sample's value is that it does not
#: move between runs.
SEED = 42

#: Junk that download sites staple onto titles and filenames. Stripped so the
#: committed manifest carries a clean citation and no provenance advertising.
_TITLE_JUNK = (
    re.compile(r"^_?OceanofPDF\.com_?", re.I),
    re.compile(r"\(\s*z-library[^)]*\)", re.I),
    re.compile(r"\(\s*[^)]*z-lib[^)]*\)", re.I),
    re.compile(r"\(\s*1lib[^)]*\)", re.I),
)


@dataclass(frozen=True)
class Candidate:
    """One eligible source paragraph, with everything selection needs."""

    book_slug: str
    chapter_index: int
    position: int
    text: str
    words: int
    band: str
    strata: frozenset[str]
    segment_hash: str
    source_format: str


@dataclass
class SourceBook:
    """A normalized reference book and its eligible paragraphs."""

    slug: str
    title: str
    authors: list[str]
    source_format: str
    book_hash: str
    total_segments: int
    candidates: list[Candidate] = field(default_factory=list)


def slugify(value: str) -> str:
    """Reduce a title to a stable lowercase slug.

    Args:
        value: A raw title, possibly carrying download-site junk.

    Returns:
        A hyphenated ASCII slug, at most eight words.
    """
    cleaned = clean_title(value)
    folded = unicodedata.normalize("NFKD", cleaned).encode("ascii", "ignore").decode()
    words = re.findall(r"[a-z0-9]+", folded.lower())
    return "-".join(words[:8]) or "untitled"


def clean_title(value: str) -> str:
    """Strip download-site markers from a title.

    The reference files carry names like ``_OceanofPDF.com_Complex_Adaptive…``.
    Those strings would end up in a public repository as a record of where the
    file came from, which is nobody's business and not the point of a citation.

    Args:
        value: The raw title.

    Returns:
        The title with known junk removed and separators normalized.
    """
    out = value
    for pattern in _TITLE_JUNK:
        out = pattern.sub("", out)
    out = out.replace("_", " ")
    return re.sub(r"\s+", " ", out).strip(" -")


def refs_dir(explicit: Path | None = None) -> Path:
    """Locate the directory of reference books.

    Args:
        explicit: A path supplied on the command line, which wins.

    Returns:
        The directory, which need not exist — callers decide whether absence is
        fatal, because it is normal in CI and in an agent worktree.
    """
    if explicit is not None:
        return Path(explicit)
    from_env = os.environ.get(ENV_REFS_DIR)
    if from_env:
        return Path(from_env)
    return repo_root() / ".." / "refs"


def repo_root(start: Path | None = None) -> Path:
    """Locate the repository root by walking up from ``start``.

    Args:
        start: Path to start from; defaults to this module's location.

    Returns:
        The directory holding ``translator/pyproject.toml``.

    Raises:
        FileNotFoundError: If no ancestor holds that file.
    """
    origin = (start or Path(__file__)).resolve()
    marker = Path("translator", "pyproject.toml")
    for parent in origin.parents:
        if (parent / marker).is_file():
            return parent
    raise FileNotFoundError(f"could not locate {marker} above {origin}")


def corpus_dir(start: Path | None = None) -> Path:
    """Return the committed corpus directory."""
    return repo_root(start) / "corpus"


def band_of(words: int) -> str:
    """Return the length band a paragraph of ``words`` words falls in."""
    for name, low, high in LENGTH_BANDS:
        if words >= low and (high is None or words <= high):
            return name
    return LENGTH_BANDS[0][0]


def strata_of(text: str) -> frozenset[str]:
    """Return every stratum whose pattern appears in ``text``."""
    return frozenset(name for name, pattern in STRATA.items() if pattern.search(text))


def eligible(book: Book) -> list[Segment]:
    """Return the body-prose paragraphs of ``book`` worth judging.

    Front and back matter are excluded for the reason Rubric T v1.1 records:
    pooling Notes and index fragments as prose inflates denominators and
    measures the extractor, not the translation.

    Args:
        book: A normalized book.

    Returns:
        Eligible paragraph segments in document order.
    """
    skip = front_matter_chapter_indices(book) | back_matter_chapter_indices(book)
    return [
        segment
        for segment in book.segments
        if segment.type is SegmentType.PARAGRAPH
        and segment.chapter_index not in skip
        and len(segment.text.split()) >= MIN_WORDS
    ]


def load_sources(directory: Path) -> list[SourceBook]:
    """Normalize every supported book in ``directory``.

    Args:
        directory: Directory holding the reference books.

    Returns:
        One `SourceBook` per readable file, ordered by slug so the result does
        not depend on filesystem ordering.

    Raises:
        FileNotFoundError: If ``directory`` does not exist.
    """
    if not directory.is_dir():
        raise FileNotFoundError(f"no reference directory at {directory}")

    sources: list[SourceBook] = []
    for path in sorted(directory.iterdir()):
        if path.suffix.lower() not in (".epub", ".pdf", ".mobi", ".azw3"):
            continue
        try:
            book = normalize(path)
        except Exception as error:  # noqa: BLE001 - one bad file must not stop the build
            logger.warning("skipping %s: %s", path.name, error)
            continue

        slug = slugify(book.title or path.stem)
        source = SourceBook(
            slug=slug,
            title=clean_title(book.title or path.stem),
            authors=[clean_title(a) for a in book.authors],
            source_format=book.source_format,
            book_hash=book_hash(book),
            total_segments=len(book.segments),
        )
        for segment in eligible(book):
            words = len(segment.text.split())
            source.candidates.append(
                Candidate(
                    book_slug=slug,
                    chapter_index=segment.chapter_index,
                    position=segment.position,
                    text=segment.text,
                    words=words,
                    band=band_of(words),
                    strata=strata_of(segment.text),
                    segment_hash=segment_hash(segment.text),
                    source_format=book.source_format,
                )
            )
        sources.append(source)
        logger.info("%s: %d eligible paragraphs", slug, len(source.candidates))

    return sorted(sources, key=lambda s: s.slug)


def _quotas(sources: list[SourceBook], size: int, floor: int) -> dict[str, int]:
    """Split ``size`` across books, proportional to eligible count but floored.

    A floor exists because proportional-only allocation lets the largest book
    dominate: `active-measures` has five times the eligible paragraphs of
    `generative-ai-in-cybersecurity`, and a sample that reflects that ratio
    tests one author's prose, not the corpus.

    Args:
        sources: The source books.
        size: Total paragraphs wanted.
        floor: Minimum per book.

    Returns:
        Paragraphs to take from each book slug, summing to ``size``.
    """
    usable = [s for s in sources if s.candidates]
    if not usable:
        return {}

    total = sum(len(s.candidates) for s in usable)
    quotas = {s.slug: max(floor, round(size * len(s.candidates) / total)) for s in usable}
    # Reconcile to exactly `size`, adjusting the largest books first so the
    # floors survive.
    order = sorted(usable, key=lambda s: -len(s.candidates))
    while sum(quotas.values()) > size:
        for s in order:
            if sum(quotas.values()) <= size:
                break
            if quotas[s.slug] > floor:
                quotas[s.slug] -= 1
    while sum(quotas.values()) < size:
        for s in order:
            if sum(quotas.values()) >= size:
                break
            if quotas[s.slug] < len(s.candidates):
                quotas[s.slug] += 1
    return quotas


def _band_quotas(candidates: list[Candidate], quota: int) -> dict[str, int]:
    """Split one book's quota across length bands, floored at one each."""
    present = [name for name, _, _ in LENGTH_BANDS if any(c.band == name for c in candidates)]
    if not present:
        return {}
    counts = Counter(c.band for c in candidates)
    total = sum(counts[name] for name in present)
    quotas = {name: max(1, round(quota * counts[name] / total)) for name in present}
    order = sorted(present, key=lambda n: -counts[n])
    while sum(quotas.values()) > quota:
        for name in order:
            if sum(quotas.values()) <= quota:
                break
            if quotas[name] > 1:
                quotas[name] -= 1
    while sum(quotas.values()) < quota:
        for name in order:
            if sum(quotas.values()) >= quota:
                break
            if quotas[name] < counts[name]:
                quotas[name] += 1
    return quotas


def select(sources: list[SourceBook], tier: str, seed: int = SEED) -> list[Candidate]:
    """Choose the sample for ``tier``, deterministically.

    Two passes. The first spends quota on **coverage**: for each stratum in a
    fixed order, take candidates carrying it until the tier's floor is met. The
    second fills whatever quota remains in shuffled order. Both walk a single
    seeded shuffle, so the result depends only on the corpus, the tier, and the
    seed.

    Coverage comes first because the rare strata are the point. `hyphen_break`
    appears in 0.5 % of eligible paragraphs; a uniform draw of 40 would miss it
    more often than not, and the sample would silently stop testing whether the
    translator copes with a word split across a line break.

    Args:
        sources: The source books.
        tier: A key of `TIERS`.
        seed: Integer seed.

    Returns:
        The selected candidates, ordered by book slug then document position.

    Raises:
        KeyError: If ``tier`` is not a known tier.
    """
    size = TIERS[tier]
    quotas = _quotas(sources, size, BOOK_FLOOR[tier])
    floor = STRATUM_FLOOR[tier]

    rng = random.Random(f"{seed}:{tier}")
    by_slug = {s.slug: s for s in sources}
    shuffled: dict[str, list[Candidate]] = {}
    band_room: dict[str, dict[str, int]] = {}
    for slug, quota in quotas.items():
        pool = list(by_slug[slug].candidates)
        rng.shuffle(pool)
        shuffled[slug] = pool
        band_room[slug] = _band_quotas(by_slug[slug].candidates, quota)

    chosen: dict[tuple[str, int, int], Candidate] = {}
    taken = Counter()
    stratum_counts = Counter()

    def room(candidate: Candidate) -> bool:
        """Whether this candidate's book and band still have quota."""
        slug = candidate.book_slug
        if taken[slug] >= quotas[slug]:
            return False
        return band_room[slug].get(candidate.band, 0) > 0

    def take(candidate: Candidate) -> None:
        """Record a selection and spend its quota."""
        key = (candidate.book_slug, candidate.chapter_index, candidate.position)
        if key in chosen:
            return
        chosen[key] = candidate
        taken[candidate.book_slug] += 1
        band_room[candidate.book_slug][candidate.band] -= 1
        stratum_counts.update(candidate.strata)

    # Pass 1 — coverage. Rarest strata first, so a candidate carrying a rare
    # feature is not consumed by a common stratum's quota before its turn.
    rarity = sorted(
        STRATA,
        key=lambda name: sum(1 for s in sources for c in s.candidates if name in c.strata),
    )
    for name in rarity:
        for slug in sorted(shuffled):
            for candidate in shuffled[slug]:
                if stratum_counts[name] >= floor:
                    break
                if name in candidate.strata and room(candidate):
                    take(candidate)

    # Pass 2 — fill the remaining quota.
    for slug in sorted(shuffled):
        for candidate in shuffled[slug]:
            if taken[slug] >= quotas[slug]:
                break
            if room(candidate):
                take(candidate)

    return sorted(chosen.values(), key=lambda c: (c.book_slug, c.chapter_index, c.position))


def build_sample_book(selected: list[Candidate], sources: list[SourceBook], tier: str) -> Book:
    """Assemble the selected paragraphs into one `Book`.

    Each source book becomes one chapter, introduced by a heading, so the
    output is an ordinary EPUB that `berilo translate` and `berilo eval` handle
    with no special cases. Segment ids are regenerated for the new coordinates:
    the sample is its own book, not a view onto six others.

    Args:
        selected: The chosen paragraphs.
        sources: The source books, for titles.
        tier: The tier name, recorded in the sample's title.

    Returns:
        A `Book` ready for `build_epub`.
    """
    titles = {s.slug: s.title for s in sources}
    segments: list[Segment] = []
    grouped: dict[str, list[Candidate]] = defaultdict(list)
    for candidate in selected:
        grouped[candidate.book_slug].append(candidate)

    for chapter_index, slug in enumerate(sorted(grouped)):
        heading = f"Excerpts — {titles.get(slug, slug)}"
        segments.append(
            Segment(
                id=make_segment_id(heading, chapter_index, 0),
                type=SegmentType.HEADING,
                text=heading,
                chapter_index=chapter_index,
                chapter_title=heading,
                position=0,
                heading_level=1,
            )
        )
        for offset, candidate in enumerate(grouped[slug], start=1):
            segments.append(
                Segment(
                    id=make_segment_id(candidate.text, chapter_index, offset),
                    type=SegmentType.PARAGRAPH,
                    text=candidate.text,
                    chapter_index=chapter_index,
                    chapter_title=heading,
                    position=offset,
                )
            )

    return Book(
        title=f"Berilo evaluation sample ({tier})",
        authors=["Berilo evaluation corpus"],
        language="en",
        source_path=f"corpus/build/berilo-sample-{tier}.epub",
        source_format="epub",
        segments=segments,
    )


def manifest_for(
    tier: str,
    selected: list[Candidate],
    sources: list[SourceBook],
    sample: Book,
    epub_sha256: str | None,
) -> dict[str, Any]:
    """Describe a built sample without reproducing any of it.

    Every value here is derived: hashes, indices, counts, band and stratum
    labels. No segment text, no titles of chapters, nothing from which the
    source prose could be recovered — the books are copyrighted and this file
    is committed to a public repository.

    Args:
        tier: The tier name.
        selected: The chosen paragraphs.
        sources: The source books.
        sample: The assembled sample book.
        epub_sha256: Digest of the built EPUB, if it was written.

    Returns:
        The manifest document.
    """
    used = {c.book_slug for c in selected}
    per_book = Counter(c.book_slug for c in selected)
    per_band = Counter(c.band for c in selected)
    per_stratum = Counter(name for c in selected for name in c.strata)
    words = sum(c.words for c in selected)

    return {
        "manifest_version": MANIFEST_VERSION,
        "tier": tier,
        "seed": SEED,
        "target_languages": list(TARGET_LANGS),
        "sample": {
            "paragraphs": len(selected),
            "source_words": words,
            "chapters": sample.chapter_count,
            "book_hash": book_hash(sample),
            "epub_sha256": epub_sha256,
        },
        "books": [
            {
                "slug": s.slug,
                "title": s.title,
                "authors": s.authors,
                "source_format": s.source_format,
                "book_hash": s.book_hash,
                "eligible_paragraphs": len(s.candidates),
                "selected": per_book.get(s.slug, 0),
            }
            for s in sources
            if s.slug in used
        ],
        "coverage": {
            "bands": {name: per_band.get(name, 0) for name, _, _ in LENGTH_BANDS},
            "strata": {name: per_stratum.get(name, 0) for name in sorted(STRATA)},
            "stratum_floor": STRATUM_FLOOR[tier],
            "unmet_strata": sorted(
                name for name in STRATA if per_stratum.get(name, 0) < STRATUM_FLOOR[tier]
            ),
        },
        "segments": [
            {
                "book": c.book_slug,
                "chapter_index": c.chapter_index,
                "position": c.position,
                "segment_hash": c.segment_hash,
                "words": c.words,
                "band": c.band,
                "strata": sorted(c.strata),
            }
            for c in selected
        ],
    }


def render_manifest(manifest: dict[str, Any]) -> str:
    """Render a manifest as stable, diffable JSON.

    Segment records go one per line: a 150-record manifest stays reviewable
    instead of exploding to a thousand lines of indented scalars.

    Args:
        manifest: The manifest document.

    Returns:
        The rendered text, newline-terminated.
    """
    shallow = {k: v for k, v in manifest.items() if k != "segments"}
    head = json.dumps(shallow, ensure_ascii=False, indent=2)[:-2].rstrip()
    rows = ",\n".join(
        "    " + json.dumps(row, ensure_ascii=False, separators=(", ", ": "))
        for row in manifest["segments"]
    )
    return f'{head},\n  "segments": [\n{rows}\n  ]\n}}\n'


def verify(manifest: dict[str, Any], directory: Path) -> list[str]:
    """Check a manifest still describes the books on disk.

    This is what makes the manifest more than a record. It catches a reference
    file being replaced, and — more usefully — a change in `normalize` that
    shifts segment boundaries, which would silently repoint every selection at
    different prose while every hash in the manifest stayed put.

    Args:
        manifest: A loaded manifest.
        directory: Directory holding the reference books.

    Returns:
        Human-readable mismatch descriptions; empty means the manifest holds.
    """
    problems: list[str] = []
    sources = {s.book_hash: s for s in load_sources(directory)}

    for entry in manifest["books"]:
        source = sources.get(entry["book_hash"])
        if source is None:
            problems.append(
                f"{entry['slug']}: no book in {directory} hashes to {entry['book_hash'][:12]}…"
            )
            continue
        if len(source.candidates) != entry["eligible_paragraphs"]:
            problems.append(
                f"{entry['slug']}: {len(source.candidates)} eligible paragraphs now, "
                f"manifest says {entry['eligible_paragraphs']} — normalize changed?"
            )

    by_slug: dict[str, dict[tuple[int, int], Candidate]] = {}
    for source in sources.values():
        by_slug[source.slug] = {(c.chapter_index, c.position): c for c in source.candidates}

    for row in manifest["segments"]:
        pool = by_slug.get(row["book"])
        if pool is None:
            problems.append(f"{row['book']}: book missing, cannot check its segments")
            continue
        candidate = pool.get((row["chapter_index"], row["position"]))
        if candidate is None:
            problems.append(
                f"{row['book']} ch{row['chapter_index']}:{row['position']} is no longer eligible"
            )
        elif candidate.segment_hash != row["segment_hash"]:
            problems.append(
                f"{row['book']} ch{row['chapter_index']}:{row['position']} text changed "
                f"({row['segment_hash'][:8]}… -> {candidate.segment_hash[:8]}…)"
            )

    return problems


def build(directory: Path, out_dir: Path, tiers: list[str]) -> dict[str, dict[str, Any]]:
    """Build every tier and write the sample EPUBs and manifests.

    Args:
        directory: Directory holding the reference books.
        out_dir: The ``corpus/`` directory to write into.
        tiers: Tier names to build.

    Returns:
        The manifests, keyed by tier.
    """
    sources = load_sources(directory)
    build_dir = out_dir / "build"
    build_dir.mkdir(parents=True, exist_ok=True)

    manifests: dict[str, dict[str, Any]] = {}
    for tier in tiers:
        selected = select(sources, tier)
        sample = build_sample_book(selected, sources, tier)
        epub_path = build_dir / f"berilo-sample-{tier}.epub"
        build_epub(sample, epub_path)
        digest = hashlib.sha256(epub_path.read_bytes()).hexdigest()

        manifest = manifest_for(tier, selected, sources, sample, digest)
        (out_dir / f"manifest-{tier}.json").write_text(render_manifest(manifest), encoding="utf-8")
        manifests[tier] = manifest
        logger.info(
            "%s: %d paragraphs, %d words, %d books -> %s",
            tier,
            manifest["sample"]["paragraphs"],
            manifest["sample"]["source_words"],
            len(manifest["books"]),
            epub_path.name,
        )
    return manifests


def main(argv: list[str] | None = None) -> int:
    """Command-line entry point.

    Args:
        argv: Arguments, defaulting to ``sys.argv[1:]``.

    Returns:
        Process exit code.
    """
    parser = argparse.ArgumentParser(description="Build or verify the evaluation corpus.")
    parser.add_argument("action", choices=("build", "verify"), help="what to do")
    parser.add_argument(
        "--refs",
        type=Path,
        default=None,
        help=f"reference book directory (default: ${ENV_REFS_DIR}, else <repo>/../refs)",
    )
    parser.add_argument(
        "--out", type=Path, default=None, help="corpus directory (default: <repo>/corpus)"
    )
    parser.add_argument(
        "--tier", action="append", choices=sorted(TIERS), help="tier(s); default all"
    )
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    directory = refs_dir(args.refs)
    out_dir = args.out or corpus_dir()
    tiers = args.tier or sorted(TIERS, key=lambda t: TIERS[t])

    if not directory.is_dir():
        logger.error(
            "no reference books at %s — set %s to the directory holding them",
            directory,
            ENV_REFS_DIR,
        )
        return 2

    if args.action == "build":
        build(directory, out_dir, tiers)
        return 0

    failed = False
    for tier in tiers:
        path = out_dir / f"manifest-{tier}.json"
        if not path.is_file():
            logger.error("no manifest at %s — run `build` first", path)
            failed = True
            continue
        problems = verify(json.loads(path.read_text(encoding="utf-8")), directory)
        if problems:
            failed = True
            logger.error("%s: %d problem(s)", tier, len(problems))
            for problem in problems[:20]:
                logger.error("  %s", problem)
        else:
            logger.info("%s: manifest holds", tier)
    return 1 if failed else 0


if __name__ == "__main__":  # pragma: no cover - module entry point
    sys.exit(main())
