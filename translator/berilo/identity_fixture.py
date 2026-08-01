"""Cross-language identity fixtures for the Kotlin port (B1a).

``book_hash`` is a sha1 over the ordered segment IDs and ``make_segment_id`` is
a sha1 over ``"{chapter_index}:{position}:{text.strip()}"``. If the Android
reader assigns a different ``chapter_index`` or ``position`` to the same EPUB,
every ID differs, ``book_hash`` differs, and the tablet shares no cache row
with the workstation — a book already paid for on the laptop gets re-billed in
full on the device. This module emits the fixtures that make that agreement
testable in CI, in both languages.

**A fixture never contains book text.** ``data/`` is gitignored because the
example books are copyrighted; these fixtures are committed. They therefore
carry only *derived* identity — sha1 digests, counts, structural indices — from
which the source text cannot be recovered. The one fixture that does carry text
is built from the synthetic book defined here.

Regenerate with::

    python3 -m berilo.identity_fixture
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import logging
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from berilo.cache import book_hash, segment_hash
from berilo.models import Book, ImageResource, Segment, SegmentType, make_segment_id

logger = logging.getLogger(__name__)

#: Bumped whenever the fixture *shape* changes. The Kotlin reader pins it, so a
#: silent shape drift between generator and port fails a test rather than
#: mis-parsing.
FIXTURE_VERSION = 1

#: Fixture slug -> the leading part of the example book's filename. Only the
#: title prefix is recorded: the on-disk names carry download provenance that
#: has no business in a committed file.
EXAMPLE_SOURCES: dict[str, str] = {
    "new-rules-of-war": "The New Rules of War",
    "revenge-of-geography": "The Revenge of Geography",
    "sandworm": "Sandworm",
    "ember-spark": "Ember Spark",
}

#: Filename suffixes of *translator output* sitting next to the sources in
#: ``data/examples`` — never a fixture source.
_TRANSLATED_SUFFIXES = (".sl.epub", ".si.epub")

#: Path of the fixture directory relative to the repository root. The Kotlin
#: suite reads these off the unit-test classpath, which is the only zero-config
#: way for a JVM test to reach them.
FIXTURE_DIR_PARTS = ("android", "app", "src", "test", "resources", "identity")

#: Keys whose value is a list of records rendered one per line. Keeps a
#: 2309-segment fixture diffable without exploding it to 16k lines.
_ROW_LIST_KEYS = frozenset({"segments", "images", "vectors"})

#: Compact separators: records are single-line, so ``json.dumps`` defaults
#: would only add trailing whitespace.
_ROW_SEPARATORS = (", ", ": ")

#: Every code point for which Python's ``str.isspace()`` is true, and therefore
#: exactly what ``str.strip()`` removes. Java's ``Character.isWhitespace() ||
#: Character.isSpaceChar()`` — what Kotlin's ``Char.isWhitespace()`` delegates
#: to, and therefore what ``String.trim()`` uses — covers all of these **except
#: U+0085 NEXT-LINE**. That single divergence is why the port carries its own
#: strip instead of calling ``trim()``. Pinned by ``tests/test_identity_fixture``
#: against a full scan of the Unicode space.
PYTHON_WHITESPACE_CODEPOINTS: tuple[int, ...] = (
    0x09,
    0x0A,
    0x0B,
    0x0C,
    0x0D,
    0x1C,
    0x1D,
    0x1E,
    0x1F,
    0x20,
    0x85,
    0xA0,
    0x1680,
    0x2000,
    0x2001,
    0x2002,
    0x2003,
    0x2004,
    0x2005,
    0x2006,
    0x2007,
    0x2008,
    0x2009,
    0x200A,
    0x2028,
    0x2029,
    0x202F,
    0x205F,
    0x3000,
)

#: All of the above as one string, used as adversarial padding in the vectors.
PYTHON_WHITESPACE = "".join(chr(codepoint) for codepoint in PYTHON_WHITESPACE_CODEPOINTS)

#: Code points Python does **not** strip even though they render as nothing:
#: zero-width space and word joiner. Present in the vectors so an over-eager
#: port that strips "anything invisible" fails.
NON_STRIPPED_INVISIBLES = "\u200b\u2060"

#: A 1x1 PNG, so the synthetic fixture carries real image bytes without copying
#: anything out of ``data/``.
_SYNTHETIC_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmM"
    "IQAAAABJRU5ErkJggg=="
)

#: A 1x1 GIF, so the synthetic book exercises more than one media type.
_SYNTHETIC_GIF = base64.b64decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

#: Text bodies of the synthetic corpus, as ``(type, heading_level, text)``.
#: Deliberately adversarial about identity: šumniki, an astral-plane emoji, an
#: inline-emphasis span, an internal newline, and — critically — padding with
#: U+0085, which Python strips and Kotlin's ``trim()`` does not.
_SYNTHETIC_BLOCKS: tuple[tuple[SegmentType, int | None, str], ...] = (
    (SegmentType.HEADING, 1, "Prvo poglavje"),
    (SegmentType.PARAGRAPH, None, "Šumniki: čebela žveji šipek."),
    (SegmentType.PARAGRAPH, None, "  Vodilni in sledilni presledki se odstranijo.  "),
    (SegmentType.PARAGRAPH, None, "\u0085\tU+0085 je presledek v Pythonu, ne v Javi.\u0085"),
    (SegmentType.PARAGRAPH, None, "\u00a0Nepretrgani presledek se prav tako odstrani.\u00a0"),
    (SegmentType.LIST_ITEM, None, "Prva postavka"),
    (SegmentType.LIST_ITEM, None, "Druga postavka z <em>poudarkom</em>"),
    (SegmentType.HEADING, 2, "Drugo poglavje"),
    (SegmentType.BLOCKQUOTE, None, "Navedek čez\ndve vrstici."),
    (SegmentType.CAPTION, None, "Slika 1: risba zmaja \U0001f409"),
    (SegmentType.OTHER, None, "Ljubljana, 26. julij — ostanek skeniranja"),
    (SegmentType.PARAGRAPH, None, "Zadnji odstavek tretjega poglavja."),
)

#: Index into ``_SYNTHETIC_BLOCKS`` at which a new chapter starts. ``position``
#: keeps running across them, which is exactly the book-global numbering
#: ``normalize_epub`` produces.
_SYNTHETIC_CHAPTER_STARTS = (0, 7, 11)

#: Chapter titles of the synthetic corpus, one per chapter.
_SYNTHETIC_CHAPTER_TITLES: tuple[str | None, ...] = ("Prvo poglavje", "Drugo poglavje", None)

#: ``make_segment_id`` / ``segment_hash`` probe vectors, as
#: ``(chapter_index, position, text)``. Every code point Python strips is
#: represented, plus two it does not, plus šumniki, astral characters, an empty
#: string, a whitespace-only string and book-scale indices.
_ID_VECTORS: tuple[tuple[int, int, str], ...] = (
    (0, 0, ""),
    (0, 0, "   "),
    (0, 0, PYTHON_WHITESPACE),
    (0, 0, "Hello, world."),
    (0, 0, "  Hello, world.  "),
    (0, 1, "Hello, world."),
    (1, 0, "Hello, world."),
    (3, 17, "Čebela žveji šipek."),
    (3, 17, " Čebela žveji šipek. "),
    (3, 17, PYTHON_WHITESPACE + "Čebela žveji šipek." + PYTHON_WHITESPACE),
    (0, 0, "Vrstica ena\nVrstica dve"),
    (0, 0, "Ničelna" + NON_STRIPPED_INVISIBLES + "širina ostane"),
    (0, 0, NON_STRIPPED_INVISIBLES + "Vodilna nevidnost ostane"),
    (0, 0, "Zmaj \U0001f409 in ognjeni \U0001f525 znak"),
    (0, 0, "<em>Poudarjeno</em> besedilo z &amp; entiteto"),
    (26, 2308, "Zadnji segment knjige."),
)


def _render_row(row: Mapping[str, Any]) -> str:
    """Render one record as a single deterministic JSON line."""
    return json.dumps(row, ensure_ascii=False, separators=_ROW_SEPARATORS)


def _render_row_list(rows: Sequence[Mapping[str, Any]]) -> str:
    """Render a list of records as one indented JSON line per record."""
    if not rows:
        return "[]"
    body = ",\n".join(f"    {_render_row(row)}" for row in rows)
    return f"[\n{body}\n  ]"


def render_fixture(fixture: Mapping[str, Any]) -> str:
    """Render a fixture mapping to its committed, byte-stable JSON text.

    Top-level keys are written one per line; the bulk lists named in
    :data:`_ROW_LIST_KEYS` get one record per line so a 2309-segment fixture
    stays reviewable in a diff.

    Args:
        fixture: The fixture mapping, in the key order it should be written.

    Returns:
        The JSON text, newline-terminated.
    """
    lines = []
    for key, value in fixture.items():
        rendered = _render_row_list(value) if key in _ROW_LIST_KEYS else _render_row(value)
        lines.append(f"  {json.dumps(key)}: {rendered}")
    return "{\n" + ",\n".join(lines) + "\n}\n"


def build_identity_fixture(book: Book, slug: str) -> dict[str, Any]:
    """Derive the committed identity fixture for a book.

    Carries no book text by construction: every field is a count, a structural
    index, an enum name or a sha1 digest.

    Args:
        book: The normalized book.
        slug: Fixture slug (the file stem under the fixture directory).

    Returns:
        The fixture mapping, ready for :func:`render_fixture`.
    """
    return {
        "fixture_version": FIXTURE_VERSION,
        "source": {"slug": slug, "format": book.source_format},
        "language": book.language,
        "book_hash": book_hash(book),
        "segment_count": len(book.segments),
        "chapter_count": book.chapter_count,
        "image_count": len(book.images),
        "segments": [
            {
                "chapter_index": segment.chapter_index,
                "position": segment.position,
                "type": segment.type.value,
                "heading_level": segment.heading_level,
                "id": segment.id,
                "segment_hash": segment_hash(segment.text),
            }
            for segment in book.segments
        ],
        "images": [
            {
                "id": image.id,
                "media_type": image.media_type,
                "chapter_index": image.chapter_index,
                "anchor_segment_id": image.anchor_segment_id,
                "data_sha1": hashlib.sha1(image.data).hexdigest(),
            }
            for image in book.images
        ],
    }


def build_id_vector_fixture() -> dict[str, Any]:
    """Derive the ``make_segment_id`` / ``segment_hash`` probe vectors.

    Returns:
        The fixture mapping, ready for :func:`render_fixture`.
    """
    return {
        "fixture_version": FIXTURE_VERSION,
        "vectors": [
            {
                "chapter_index": chapter_index,
                "position": position,
                "text": text,
                "id": make_segment_id(text, chapter_index, position),
                "segment_hash": segment_hash(text),
            }
            for chapter_index, position, text in _ID_VECTORS
        ],
    }


def build_synthetic_book() -> Book:
    """Build the synthetic corpus the Kotlin suite turns back into a ``Book``.

    The identity fixtures for the real books cannot carry text, yet the port
    still has to be exercised on the whole
    text -> ``make_segment_id`` -> ``book_hash`` path. This book supplies that
    text without touching ``data/``.

    Returns:
        A :class:`~berilo.models.Book` with three chapters and two images.
    """
    segments: list[Segment] = []
    chapter_index = -1
    for position, (segment_type, heading_level, text) in enumerate(_SYNTHETIC_BLOCKS):
        if position in _SYNTHETIC_CHAPTER_STARTS:
            chapter_index += 1
        segments.append(
            Segment(
                id=make_segment_id(text, chapter_index, position),
                type=segment_type,
                text=text,
                chapter_index=chapter_index,
                chapter_title=_SYNTHETIC_CHAPTER_TITLES[chapter_index],
                position=position,
                heading_level=heading_level,
            )
        )
    images = [
        ImageResource(
            id="img0001",
            media_type="image/png",
            data=_SYNTHETIC_PNG,
            source_href="images/pika.png",
            chapter_index=0,
            anchor_segment_id=None,
            alt="Ena sama pika",
        ),
        ImageResource(
            id="img0002",
            media_type="image/gif",
            data=_SYNTHETIC_GIF,
            source_href="images/zmaj.gif",
            chapter_index=1,
            anchor_segment_id=segments[8].id,
            alt=None,
        ),
    ]
    return Book(
        title="Sintetična knjiga",
        authors=["Ana Novak", "Boris Kovač"],
        language="sl",
        source_path="synthetic.epub",
        source_format="epub",
        segments=segments,
        images=images,
    )


def build_legacy_book_json(book_json: str) -> str:
    """Strip a serialized book back to its pre-S1.14 shape.

    ``images`` and ``heading_level`` post-date the original payload and
    :meth:`Book.from_json` tolerates their absence. The port must too, so the
    tolerance gets a fixture rather than a comment.

    Args:
        book_json: JSON text from :meth:`Book.to_json`.

    Returns:
        The same payload without ``images`` and without any ``heading_level``.
    """
    payload = json.loads(book_json)
    payload.pop("images", None)
    for segment in payload["segments"]:
        segment.pop("heading_level", None)
    return json.dumps(payload, ensure_ascii=False, indent=2)


def default_fixture_dir(start: Path | None = None) -> Path:
    """Locate the committed fixture directory by walking up from ``start``.

    Args:
        start: Path to start from; defaults to this module's location.

    Returns:
        The fixture directory path (which need not exist yet; its Android test
        source set must).

    Raises:
        FileNotFoundError: If no ancestor holds ``android/app/src/test``.
    """
    origin = (start or Path(__file__)).resolve()
    marker = Path(*FIXTURE_DIR_PARTS[:4])
    for parent in origin.parents:
        if (parent / marker).is_dir():
            return parent.joinpath(*FIXTURE_DIR_PARTS)
    raise FileNotFoundError(f"could not locate {marker} above {origin}")


def resolve_example(examples_dir: Path, prefix: str) -> Path | None:
    """Find the source EPUB whose filename starts with ``prefix``.

    Translator *output* sits in the same directory, so anything carrying a
    language suffix is rejected.

    Args:
        examples_dir: Directory holding the example books.
        prefix: Leading part of the source filename.

    Returns:
        The matching path, or ``None`` when the book is not available.
    """
    matches = sorted(
        path
        for path in examples_dir.glob(f"{prefix}*.epub")
        if not path.name.endswith(_TRANSLATED_SUFFIXES)
    )
    return matches[0] if matches else None


def write_synthetic_fixtures(fixture_dir: Path) -> list[Path]:
    """Write every fixture that needs no book from ``data/``.

    Args:
        fixture_dir: Directory to write into; created if absent.

    Returns:
        The paths written, in write order.
    """
    fixture_dir.mkdir(parents=True, exist_ok=True)
    book = build_synthetic_book()
    book_json = book.to_json()
    written = []
    for name, text in (
        ("segment_id_vectors.json", render_fixture(build_id_vector_fixture())),
        ("synthetic.identity.json", render_fixture(build_identity_fixture(book, "synthetic"))),
        ("synthetic.book.json", book_json + "\n"),
        ("synthetic.book.legacy.json", build_legacy_book_json(book_json) + "\n"),
    ):
        path = fixture_dir / name
        path.write_text(text, encoding="utf-8")
        written.append(path)
    return written


def write_example_fixtures(fixture_dir: Path, examples_dir: Path) -> list[Path]:
    """Regenerate the identity fixture of every available example book.

    Args:
        fixture_dir: Directory to write into; created if absent.
        examples_dir: Directory holding the (gitignored) example books.

    Returns:
        The paths written, in slug order. A missing book is skipped with a
        warning — ``data/`` exists only in the main checkout.
    """
    from berilo.normalize import normalize

    fixture_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for slug, prefix in EXAMPLE_SOURCES.items():
        source = resolve_example(examples_dir, prefix)
        if source is None:
            logger.warning("No example book for %s under %s — skipped", slug, examples_dir)
            continue
        book = normalize(source)
        path = fixture_dir / f"{slug}.identity.json"
        path.write_text(render_fixture(build_identity_fixture(book, slug)), encoding="utf-8")
        logger.info(
            "%s: book_hash=%s segments=%d chapters=%d images=%d",
            slug,
            book_hash(book),
            len(book.segments),
            book.chapter_count,
            len(book.images),
        )
        written.append(path)
    return written


def main(argv: Sequence[str] | None = None) -> int:
    """Regenerate the committed cross-language identity fixtures.

    Args:
        argv: Command-line arguments; defaults to ``sys.argv[1:]``.

    Returns:
        Process exit code.
    """
    parser = argparse.ArgumentParser(description="Regenerate B1a identity fixtures.")
    parser.add_argument(
        "--examples",
        type=Path,
        default=None,
        help="Directory holding the example books (default: <repo>/data/examples)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Fixture output directory (default: android/app/src/test/resources/identity)",
    )
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    fixture_dir = args.out or default_fixture_dir()
    examples_dir = args.examples or default_fixture_dir().parents[5] / "data" / "examples"

    for path in write_synthetic_fixtures(fixture_dir):
        logger.info("wrote %s", path.name)
    if examples_dir.is_dir():
        for path in write_example_fixtures(fixture_dir, examples_dir):
            logger.info("wrote %s", path.name)
    else:
        logger.warning("No example directory at %s — real-book fixtures skipped", examples_dir)
    return 0


if __name__ == "__main__":  # pragma: no cover - module entry point
    sys.exit(main())
