"""Generate the cross-language byte-identity vector for the Kotlin EPUB writer (B3).

`EpubWriter` must produce an archive **byte-identical** to `berilo.assemble.build_epub`
for the same `Book`. That is not a nicety: `build_epub` is deterministic by construction
(fixed `dcterms:modified`, UUID5 `dc:identifier`, DOS-epoch zip timestamps, fixed entry
order), so one differing byte means the two implementations diverged somewhere, and a
whole-archive digest is the cheapest possible detector.

This script builds a handful of **synthetic** books — nothing from the copyrighted
`data/` corpus — runs `build_epub` on each, and writes what the Kotlin suite needs to
prove agreement without ever shipping an EPUB as a test resource:

* `sha256` of the whole archive — the byte-identity gate itself;
* one record per zip entry (name, method, CRC-32, sizes) — so a failure says *which*
  entry drifted and whether the STORED/DEFLATED split or the entry order broke;
* the decoded text of every text entry — so a rendering failure points at the exact
  markup rather than at a digest.

Run from the repository root::

    PYTHONPATH=translator python3 contracts/gen/generate_assemble_vectors.py

Regenerate whenever `berilo/assemble.py` changes; the Kotlin gate then fails until the
port follows.

This script used to live at `android/tools/` in this repository, which is where it could
reach both the Python it executes and the Kotlin test resources it wrote. The Android
split separated those, leaving it able to reach neither: it imports `berilo.assemble`,
which went with the translator, and wrote into `android/`, which went with the app. It
now sits beside the reference it runs and writes to `contracts/vectors/`, from which each
port vendors a copy — see `../conformance.md`.
"""

from __future__ import annotations

import base64
import hashlib
import json
import sys
import tempfile
import uuid
import zipfile
from pathlib import Path
from typing import Any

from berilo.assemble import _ID_NAMESPACE, build_epub
from berilo.identity_fixture import build_synthetic_book
from berilo.models import Book, ImageResource, Segment, SegmentType, make_segment_id

#: Bumped whenever the vector *shape* changes; the Kotlin reader pins it so a silent
#: drift between generator and port fails a test rather than mis-parsing.
VECTOR_VERSION = 1

#: Where the committed vector lands, relative to the repository root.
VECTOR_PATH_PARTS = ("contracts", "vectors", "v1", "assemble", "python_assemble.json")

#: Classpath resource holding the synthetic book B1a already committed. Referenced rather
#: than copied: two statements of the same book would drift apart silently.
SYNTHETIC_BOOK_RESOURCE = "identity/synthetic.book.json"

#: A 1x1 PNG and a 1x1 GIF, so image entries carry real bytes from no copyrighted source.
_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmM"
    "IQAAAABJRU5ErkJggg=="
)
_GIF = base64.b64decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

#: A minimal SVG: `image/svg+xml` is NOT in `_STORED_IMAGE_TYPES`, so it must deflate.
_SVG = b'<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"><rect width="1" height="1"/></svg>'

#: Bytes under a media type `_IMAGE_EXTENSIONS` does not know: `.img` fallback, deflated.
_UNKNOWN = bytes(range(256)) * 4

#: A JPEG-typed payload long enough that "stored" is visibly not "deflated".
_JPEG = bytes(range(256)) * 8


def _segment(
    chapter_index: int,
    position: int,
    text: str,
    segment_type: SegmentType = SegmentType.PARAGRAPH,
    chapter_title: str | None = None,
    heading_level: int | None = None,
) -> Segment:
    """Build one segment with the id `normalize_epub` would have given it."""
    return Segment(
        id=make_segment_id(text, chapter_index, position),
        type=segment_type,
        text=text,
        chapter_index=chapter_index,
        chapter_title=chapter_title,
        position=position,
        heading_level=heading_level,
    )


def _inline_book() -> Book:
    """A book that exercises every rendering branch of `_render_chapter_body`.

    Heading-level defaulting and clamping, the inline whitelist and its all-or-nothing
    escape fallback, the characters `_escape_text` deliberately leaves alone (`>`, quotes),
    the class-tagged CAPTION/OTHER round trip, list grouping across an interruption, and a
    chapter with no title at all.
    """
    blocks: list[tuple[SegmentType, str, int | None]] = [
        (SegmentType.HEADING, "Brez ravni: prvi je h1", None),
        (SegmentType.HEADING, "Brez ravni: drugi je h2", None),
        (SegmentType.HEADING, "Raven 7 se obreze na 3", 7),
        (SegmentType.HEADING, "Raven 0 se dvigne na 1", 0),
        (SegmentType.PARAGRAPH, "Uravnotezeno <em>poudarjeno</em> besedilo", None),
        (SegmentType.PARAGRAPH, "Ugnezdeno <strong><em>oboje</em></strong> tukaj", None),
        (SegmentType.PARAGRAPH, "Velike crke <EM>se pomanjsajo</EM> nazaj", None),
        (SegmentType.PARAGRAPH, "Nezaprto <em>ubeži cel segment", None),
        (SegmentType.PARAGRAPH, "Neujemajoce <em>ubeži</strong> cel segment", None),
        (SegmentType.PARAGRAPH, "Odvecni zapiralnik</em> ubeži cel segment", None),
        # The discriminating case for "escape the WHOLE segment": a valid pair followed by a
        # stray closer. Escaping only the stray tag would keep the valid <em> as markup, which
        # is a different string from the full escape — unlike a segment whose only tag is the
        # stray one, where the two strategies happen to coincide.
        (SegmentType.PARAGRAPH, "Veljaven <em>par</em> nato odvecni</strong> zapiralnik", None),
        (SegmentType.PARAGRAPH, 'Neubrani znaki: 5 > 3 in "narekovaji" in \'apostrofi\'', None),
        (SegmentType.PARAGRAPH, "Ubrani znaki: AT&T in a < b", None),
        (SegmentType.LIST_ITEM, "Prva postavka", None),
        (SegmentType.LIST_ITEM, "Druga postavka", None),
        (SegmentType.PARAGRAPH, "Odstavek prekine seznam", None),
        (SegmentType.LIST_ITEM, "Tretja postavka po prekinitvi", None),
        (SegmentType.BLOCKQUOTE, "Navedek se zavije v odstavek", None),
        (SegmentType.CAPTION, "Podnapis nosi razred caption", None),
        (SegmentType.OTHER, "Ostanek nosi razred other", None),
    ]
    segments = [
        _segment(0, position, text, segment_type, "Prvo poglavje", level)
        for position, (segment_type, text, level) in enumerate(blocks)
    ]
    # A second chapter with no title at all: the label falls back to "Chapter 2".
    segments.append(_segment(1, len(blocks), "Poglavje brez naslova.", chapter_title=None))
    return Book(
        title="Vzorci & <oznake>",
        authors=["Ana Novak", "Boris Kovač"],
        language="sl",
        source_path="inline.epub",
        source_format="epub",
        segments=segments,
    )


def _image_book() -> Book:
    """A book that exercises every image placement and both compression branches.

    Includes the review-finding-15 case: an image anchored to a segment that is not in its
    own chapter, which must land at the end of that chapter rather than vanish.
    """
    segments = [
        _segment(0, 0, "Prvi odstavek prvega poglavja.", chapter_title="Slike"),
        _segment(0, 1, "Postavka seznama", SegmentType.LIST_ITEM, "Slike"),
        _segment(0, 2, "Druga postavka seznama", SegmentType.LIST_ITEM, "Slike"),
        _segment(1, 3, "Edini odstavek drugega poglavja.", chapter_title="Druge slike"),
    ]
    images = [
        # Leading: no anchor at all, so it opens the chapter.
        ImageResource(
            id="img0001",
            media_type="image/png",
            data=_PNG,
            source_href="a.png",
            chapter_index=0,
            anchor_segment_id=None,
            alt='Alt z "narekovaji" & <oznakami> in 5 > 3',
        ),
        # Anchored to a paragraph: emitted straight after it.
        ImageResource(
            id="img0002",
            media_type="image/jpeg",
            data=_JPEG,
            source_href="b.jpg",
            chapter_index=0,
            anchor_segment_id=segments[0].id,
            alt=None,
        ),
        # Anchored to a list item: emitted after the whole <ul>, never between <li>s.
        ImageResource(
            id="img0003",
            media_type="image/svg+xml",
            data=_SVG,
            source_href="c.svg",
            chapter_index=0,
            anchor_segment_id=segments[1].id,
            alt="",
        ),
        # Anchored to a segment of ANOTHER chapter: orphaned, emitted at chapter end.
        ImageResource(
            id="img0004",
            media_type="image/webp",
            data=_GIF,
            source_href="d.webp",
            chapter_index=1,
            anchor_segment_id=segments[0].id,
            alt="Sirota",
        ),
        # Media type nothing knows: `.img` extension, deflated.
        ImageResource(
            id="img0005",
            media_type="image/heic",
            data=_UNKNOWN,
            source_href="e.heic",
            chapter_index=1,
            anchor_segment_id=segments[3].id,
            alt=None,
        ),
    ]
    return Book(
        title="Knjiga s slikami",
        authors=[],
        language="sl",
        source_path="images.epub",
        source_format="epub",
        segments=segments,
        images=images,
    )


def _chapter_order_book() -> Book:
    """A book whose `chapter_index` values are neither sorted nor contiguous.

    `_group_chapters` orders chapters by **first appearance in `book.segments`**, not by index,
    and a chapter's title comes from its own first segment. Sorting instead would be invisible
    on every book `normalize_epub` produces (it numbers chapters sequentially) and wrong for
    anything that reorders or filters segments downstream.
    """
    rows = [
        (5, "Peto poglavje se pojavi prvo.", "Peto"),
        (2, "Drugo poglavje se pojavi drugo.", "Drugo"),
        (5, "Nadaljevanje petega poglavja.", "Peto"),
        (9, "Deveto poglavje je zadnje.", "Deveto"),
    ]
    segments = [
        _segment(chapter_index, position, text, chapter_title=title)
        for position, (chapter_index, text, title) in enumerate(rows)
    ]
    return Book(
        title="Vrstni red poglavij",
        authors=["Ana Novak"],
        language="sl",
        source_path="order.epub",
        source_format="epub",
        segments=segments,
    )


def _bilingual_pair() -> tuple[Book, Book]:
    """A translated book and its aligned source, for the `bilingual=True` branch."""
    target_texts = [
        ("Naslov poglavja", SegmentType.HEADING),
        ("Prevedeni odstavek s <em>poudarkom</em>.", SegmentType.PARAGRAPH),
        ("Prevedena postavka", SegmentType.LIST_ITEM),
        ("Prevedeni navedek", SegmentType.BLOCKQUOTE),
    ]
    source_texts = [
        "Chapter heading",
        "Translated paragraph with <em>emphasis</em>.",
        "Translated list item",
        "Translated blockquote",
    ]
    target = [
        _segment(0, position, text, segment_type, "Poglavje", 1 if position == 0 else None)
        for position, (text, segment_type) in enumerate(target_texts)
    ]
    # Segment integrity: the source book carries the SAME ids, in the same order.
    source = [
        Segment(
            id=target[position].id,
            type=target[position].type,
            text=text,
            chapter_index=0,
            chapter_title="Chapter",
            position=position,
            heading_level=target[position].heading_level,
        )
        for position, text in enumerate(source_texts)
    ]
    common = {"authors": ["Ana Novak"], "source_path": "bilingual.epub", "source_format": "epub"}
    return (
        Book(title="Dvojezično", language="sl", segments=target, **common),
        Book(title="Bilingual", language="en", segments=source, **common),
    )


def _entry_records(path: Path) -> list[dict[str, Any]]:
    """Describe every zip entry: order, compression method, CRC-32 and both sizes."""
    with zipfile.ZipFile(path) as archive:
        return [
            {
                "name": info.filename,
                "method": "stored" if info.compress_type == zipfile.ZIP_STORED else "deflated",
                "crc32": f"{info.CRC:08x}",
                "size": info.file_size,
                "compressed_size": info.compress_size,
            }
            for info in archive.infolist()
        ]


def _documents(path: Path) -> dict[str, str]:
    """Decode every text entry, so a rendering divergence names the markup that moved."""
    with zipfile.ZipFile(path) as archive:
        return {
            info.filename: archive.read(info.filename).decode("utf-8")
            for info in archive.infolist()
            if not info.filename.startswith("OEBPS/images/")
        }


def _case(
    name: str,
    book: Book,
    *,
    book_resource: str | None = None,
    source_book: Book | None = None,
) -> dict[str, Any]:
    """Build one vector case by actually running `build_epub`."""
    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / f"{name}.epub"
        build_epub(book, output, bilingual=source_book is not None, source_book=source_book)
        raw = output.read_bytes()
        case: dict[str, Any] = {"name": name}
        if book_resource is not None:
            case["book_resource"] = book_resource
        else:
            case["book"] = json.loads(book.to_json())
        if source_book is not None:
            case["source_book"] = json.loads(source_book.to_json())
        case["bilingual"] = source_book is not None
        case["sha256"] = hashlib.sha256(raw).hexdigest()
        case["size"] = len(raw)
        case["entries"] = _entry_records(output)
        case["documents"] = _documents(output)
        return case


#: `uuid.uuid5` probes. Two namespaces (assemble's own and the RFC 4122 DNS namespace, so
#: the namespace-to-bytes conversion is pinned by more than one value), non-ASCII and astral
#: names, an empty name, and the exact seed shape `_book_identifier` builds.
_UUID5_NAMES: tuple[tuple[str, str], ...] = (
    (str(_ID_NAMESPACE), ""),
    (str(_ID_NAMESPACE), "berilo:synthetic.epub:Sintetična knjiga:sl"),
    (str(_ID_NAMESPACE), "berilo:/pot/knjiga.epub:Čebela žveji šipek 🐉:sl"),
    (str(_ID_NAMESPACE), "berilo:::"),
    (str(uuid.NAMESPACE_DNS), "python.org"),
    (str(uuid.NAMESPACE_URL), "https://berilo.app/šumniki"),
)


def _uuid5_vectors() -> list[dict[str, str]]:
    """Record `uuid.uuid5` outputs so the hand-rolled Kotlin v5 can be pinned to them."""
    return [
        {"namespace": namespace, "name": name, "uuid": str(uuid.uuid5(uuid.UUID(namespace), name))}
        for namespace, name in _UUID5_NAMES
    ]


def build_vector() -> dict[str, Any]:
    """Assemble the whole vector document."""
    target, source = _bilingual_pair()
    return {
        "version": VECTOR_VERSION,
        "generator": "contracts/gen/generate_assemble_vectors.py",
        "id_namespace": str(_ID_NAMESPACE),
        "uuid5": _uuid5_vectors(),
        "cases": [
            _case("synthetic", build_synthetic_book(), book_resource=SYNTHETIC_BOOK_RESOURCE),
            _case("inline", _inline_book()),
            _case("images", _image_book()),
            _case("chapter-order", _chapter_order_book()),
            _case("bilingual", target, source_book=source),
        ],
    }


def main() -> int:
    """Write the vector to its committed location."""
    root = Path(__file__).resolve().parents[2]  # contracts/gen/<this> -> repo root
    destination = root.joinpath(*VECTOR_PATH_PARTS)
    destination.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(build_vector(), ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    destination.write_text(text, encoding="utf-8")
    print(f"wrote {destination} ({len(text)} chars)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
