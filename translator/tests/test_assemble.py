"""Tests for the EPUB assembler (S1.6).

Fixtures are small, synthetic ``Book``/``Segment`` graphs built directly
(not via the normalizer) so these tests exercise ``berilo.assemble`` in
isolation, with no dependency on any real (copyrighted) book under
``data/``. ``epubcheck`` integration tests run the real, offline
``epubcheck`` binary and are skipped when it isn't on ``PATH``.
"""

from __future__ import annotations

import shutil
import subprocess
import zipfile
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

import pytest

from berilo.assemble import build_epub
from berilo.eval.rubric_t import align
from berilo.models import Book, Segment, SegmentType
from berilo.normalize import normalize

EPUBCHECK = shutil.which("epubcheck")

_KAPLAN_EPUB_NAME = (
    "The Revenge of Geography What the Map Tells Us About Coming Conflicts "
    "and the Battle Against Fate (Robert D. Kaplan) (z-library.sk, 1lib.sk, z-lib.sk).epub"
)
_ACTIVE_MEASURES_PDF_NAME = (
    "Active Measures The Secret History of Disinformation and Political "
    "Warfare (Thomas Rid) (z-library.sk, 1lib.sk, z-lib.sk).pdf"
)

#: S1.13: no book may concentrate this share of its segments on one chapter
#: title, on either side of the source/target pair.
MAX_TITLE_CONCENTRATION = 0.5


def _chapter_count(book: Book) -> int:
    return len({segment.chapter_index for segment in book.segments})


def _top_title_share(book: Book) -> float:
    counts = Counter(segment.chapter_title for segment in book.segments)
    return counts.most_common(1)[0][1] / len(book.segments)


def _segment(
    id_: str,
    type_: SegmentType,
    text: str,
    chapter_index: int,
    chapter_title: str | None,
    position: int,
    heading_level: int | None = None,
) -> Segment:
    return Segment(
        id=id_,
        type=type_,
        text=text,
        chapter_index=chapter_index,
        chapter_title=chapter_title,
        position=position,
        heading_level=heading_level,
    )


def _translated_segments() -> list[Segment]:
    """Two-chapter fixture covering every block type + emphasis/escaping edge cases."""
    return [
        _segment("h1", SegmentType.HEADING, "Chapter One", 0, "Chapter One", 0, 1),
        _segment(
            "p1",
            SegmentType.PARAGRAPH,
            "This has <em>emphasis</em> and <strong>bold</strong> text.",
            0,
            "Chapter One",
            1,
        ),
        _segment("li1", SegmentType.LIST_ITEM, "First item", 0, "Chapter One", 2),
        _segment("li2", SegmentType.LIST_ITEM, "Second item", 0, "Chapter One", 3),
        _segment("bq1", SegmentType.BLOCKQUOTE, "A quoted line.", 0, "Chapter One", 4),
        _segment("cap1", SegmentType.CAPTION, "Figure 1: a caption", 0, "Chapter One", 5),
        # Chapter 2 has no title -> nav/heading must fall back to "Chapter 2".
        _segment("h2", SegmentType.HEADING, "Section", 1, None, 6),
        _segment(
            "p2",
            SegmentType.PARAGRAPH,
            "Rock & Roll < 100 dB",
            1,
            None,
            7,
        ),
        _segment(
            "p3",
            SegmentType.PARAGRAPH,
            "Malformed <em>unclosed tag",
            1,
            None,
            8,
        ),
        _segment(
            "p4",
            SegmentType.PARAGRAPH,
            "Mismatched <em>a<strong>b</em>c</strong> end",
            1,
            None,
            9,
        ),
    ]


def _source_segments() -> list[Segment]:
    """Source-language counterparts, same ids/order/chapters as ``_translated_segments``."""
    return [
        _segment("h1", SegmentType.HEADING, "Poglavje Ena", 0, "Poglavje Ena", 0, 1),
        _segment(
            "p1",
            SegmentType.PARAGRAPH,
            "To ima <em>poudarek</em> in <strong>krepko</strong> besedilo.",
            0,
            "Poglavje Ena",
            1,
        ),
        _segment("li1", SegmentType.LIST_ITEM, "Prva postavka", 0, "Poglavje Ena", 2),
        _segment("li2", SegmentType.LIST_ITEM, "Druga postavka", 0, "Poglavje Ena", 3),
        _segment("bq1", SegmentType.BLOCKQUOTE, "Citirana vrstica.", 0, "Poglavje Ena", 4),
        _segment("cap1", SegmentType.CAPTION, "Slika 1: napis", 0, "Poglavje Ena", 5),
        _segment("h2", SegmentType.HEADING, "Poglavje", 1, None, 6),
        _segment("p2", SegmentType.PARAGRAPH, "Rock & Roll < 100 dB", 1, None, 7),
        _segment("p3", SegmentType.PARAGRAPH, "Nedokoncano <em>oznako", 1, None, 8),
        _segment("p4", SegmentType.PARAGRAPH, "Neujemajoce oznake konec", 1, None, 9),
    ]


def _book(segments: list[Segment], *, language: str, title: str = "Sample Book") -> Book:
    return Book(
        title=title,
        authors=["Jane Doe"],
        language=language,
        source_path="/tmp/sample.epub",
        source_format="epub",
        segments=segments,
    )


def _translated_book() -> Book:
    return _book(_translated_segments(), language="sl")


def _source_book() -> Book:
    return _book(_source_segments(), language="en")


def _read_zip_entry(epub_path: Path, name: str) -> bytes:
    with zipfile.ZipFile(epub_path) as archive:
        return archive.read(name)


def _chapter_names(epub_path: Path) -> list[str]:
    with zipfile.ZipFile(epub_path) as archive:
        return sorted(n for n in archive.namelist() if n.startswith("OEBPS/chap_"))


# --- structural / unit tests -------------------------------------------------


def test_mimetype_is_first_entry_and_stored_uncompressed(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")

    with zipfile.ZipFile(output) as archive:
        infos = archive.infolist()
        assert infos[0].filename == "mimetype"
        assert infos[0].compress_type == zipfile.ZIP_STORED
        assert archive.read("mimetype") == b"application/epub+zip"


def test_nav_lists_all_chapters_with_title_and_fallback(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")
    nav = _read_zip_entry(output, "OEBPS/nav.xhtml").decode("utf-8")

    assert "Chapter One" in nav
    assert "Chapter 2" in nav  # chapter_title=None fallback
    assert 'epub:type="toc"' in nav
    for href in _chapter_names(output):
        assert Path(href).name in nav


def test_emphasis_tags_preserved_verbatim(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")
    chapter1 = _read_zip_entry(output, "OEBPS/" + Path(_chapter_names(output)[0]).name).decode(
        "utf-8"
    )

    assert "<em>emphasis</em>" in chapter1
    assert "<strong>bold</strong>" in chapter1


def test_list_items_grouped_into_single_ul(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")
    chapter1 = _read_zip_entry(output, "OEBPS/" + Path(_chapter_names(output)[0]).name).decode(
        "utf-8"
    )

    assert "<ul><li>First item</li><li>Second item</li></ul>" in chapter1


def test_blockquote_and_caption_rendering(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")
    chapter1 = _read_zip_entry(output, "OEBPS/" + Path(_chapter_names(output)[0]).name).decode(
        "utf-8"
    )

    assert "<blockquote><p>A quoted line.</p></blockquote>" in chapter1
    assert '<p class="caption">Figure 1: a caption</p>' in chapter1


def test_ampersand_and_lt_escaped_in_prose(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")
    chapter2 = _read_zip_entry(output, "OEBPS/" + Path(_chapter_names(output)[1]).name).decode(
        "utf-8"
    )

    assert "Rock &amp; Roll &lt; 100 dB" in chapter2
    assert "Rock & Roll < 100 dB" not in chapter2


def test_malformed_inline_markup_falls_back_to_full_escaping(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")
    chapter2 = _read_zip_entry(output, "OEBPS/" + Path(_chapter_names(output)[1]).name).decode(
        "utf-8"
    )

    # Unclosed <em> and mismatched <em>/<strong> both fall back to plain,
    # fully-escaped text -- never an unbalanced tag in the output. Only &
    # and < are escaped (per spec); a bare > is valid, unescaped XML text.
    assert "Malformed &lt;em>unclosed tag" in chapter2
    assert "Mismatched &lt;em>a&lt;strong>b&lt;/em>c&lt;/strong> end" in chapter2
    assert "<em>unclosed" not in chapter2
    assert "<em>a<strong>b</em>" not in chapter2

    # The fallback must still produce well-formed XML.
    ET.fromstring(chapter2)


def test_dc_title_uses_lang_prefix_and_original_title(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "out.epub")
    opf = _read_zip_entry(output, "OEBPS/content.opf").decode("utf-8")

    assert "<dc:title>[SL] Sample Book</dc:title>" in opf
    assert "<dc:language>sl</dc:language>" in opf


def test_all_produced_xml_documents_are_well_formed(tmp_path: Path) -> None:
    output = build_epub(
        _translated_book(), tmp_path / "out.epub", bilingual=True, source_book=_source_book()
    )

    with zipfile.ZipFile(output) as archive:
        for name in archive.namelist():
            if name == "mimetype" or name.endswith(".css"):
                continue
            ET.fromstring(archive.read(name))


# --- bilingual mode -----------------------------------------------------------


def test_bilingual_without_source_book_raises(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="source_book"):
        build_epub(_translated_book(), tmp_path / "out.epub", bilingual=True)


def test_bilingual_segment_id_mismatch_raises_citing_first_mismatch(tmp_path: Path) -> None:
    mismatched_source = _source_segments()
    mismatched_source[3] = _segment(
        "li1-WRONG-ID",
        SegmentType.LIST_ITEM,
        mismatched_source[3].text,
        mismatched_source[3].chapter_index,
        mismatched_source[3].chapter_title,
        mismatched_source[3].position,
    )
    bad_source_book = _book(mismatched_source, language="en")

    with pytest.raises(ValueError, match=r"position 3.*li2.*li1-WRONG-ID"):
        build_epub(
            _translated_book(),
            tmp_path / "out.epub",
            bilingual=True,
            source_book=bad_source_book,
        )


def test_bilingual_segment_count_mismatch_raises(tmp_path: Path) -> None:
    short_source = _book(_source_segments()[:-1], language="en")

    with pytest.raises(ValueError, match="count mismatch"):
        build_epub(
            _translated_book(), tmp_path / "out.epub", bilingual=True, source_book=short_source
        )


def test_bilingual_renders_source_paragraph_after_translated_block(tmp_path: Path) -> None:
    output = build_epub(
        _translated_book(), tmp_path / "out.epub", bilingual=True, source_book=_source_book()
    )
    chapter1 = _read_zip_entry(output, "OEBPS/" + Path(_chapter_names(output)[0]).name).decode(
        "utf-8"
    )

    # Translated paragraph followed by its source counterpart, styled subtly.
    assert (
        "This has <em>emphasis</em> and <strong>bold</strong> text."
        '</p><p class="source">To ima <em>poudarek</em> in <strong>krepko</strong> besedilo.</p>'
        in chapter1
    )
    # Headings are never duplicated as a second heading tag.
    assert "<h1>Chapter One</h1>" in chapter1
    assert '<h1>Chapter One</h1><p class="source">Poglavje Ena</p>' in chapter1
    assert "<h1>Poglavje Ena</h1>" not in chapter1
    # List items carry their source text nested inside the same <li>.
    assert '<li>First item<p class="source">Prva postavka</p></li>' in chapter1


# --- determinism ----------------------------------------------------------------


def test_build_epub_is_byte_identical_across_runs(tmp_path: Path) -> None:
    first = build_epub(_translated_book(), tmp_path / "first.epub")
    second = build_epub(_translated_book(), tmp_path / "second.epub")

    assert first.read_bytes() == second.read_bytes()


def test_build_epub_bilingual_is_byte_identical_across_runs(tmp_path: Path) -> None:
    first = build_epub(
        _translated_book(), tmp_path / "first.epub", bilingual=True, source_book=_source_book()
    )
    second = build_epub(
        _translated_book(), tmp_path / "second.epub", bilingual=True, source_book=_source_book()
    )

    assert first.read_bytes() == second.read_bytes()


# --- epubcheck integration --------------------------------------------------------


@pytest.mark.skipif(EPUBCHECK is None, reason="epubcheck not installed")
def test_epubcheck_passes_monolingual_variant(tmp_path: Path) -> None:
    output = build_epub(_translated_book(), tmp_path / "mono.epub")

    result = subprocess.run([EPUBCHECK, str(output)], capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.skipif(EPUBCHECK is None, reason="epubcheck not installed")
def test_epubcheck_passes_bilingual_variant(tmp_path: Path) -> None:
    output = build_epub(
        _translated_book(), tmp_path / "bilingual.epub", bilingual=True, source_book=_source_book()
    )

    result = subprocess.run([EPUBCHECK, str(output)], capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, result.stdout + result.stderr


# --- source/target normalization symmetry (S1.13) ---------------------------------


def _round_trip(book: Book, tmp_path: Path, name: str) -> Book:
    """Assemble *book* and normalize the result back into a Book."""
    return normalize(build_epub(book, tmp_path / name))


def test_assembled_epub_round_trips_to_an_aligning_book(tmp_path: Path) -> None:
    """S1.13: normalize(assemble(book)) aligns 1:1 with ``book``.

    ``rubric_t.align`` fingerprints on (chapter, type, heading_level), so an
    assembled EPUB that re-normalizes to different types or chapter grouping
    makes every ``berilo eval`` run fail. This pins the invariant across the
    whole segment-type spectrum, including the heading levels the PDF
    normalizer emits and the class-tagged CAPTION/OTHER round-trip.
    """
    segments = [
        _segment("s0", SegmentType.HEADING, "Chapter One", 0, "Chapter One", 0, 1),
        _segment(
            "s1", SegmentType.PARAGRAPH, "Prose with <em>emphasis</em> inside.", 0, "Chapter One", 1
        ),
        _segment("s2", SegmentType.HEADING, "A Sub Heading", 0, "Chapter One", 2, 2),
        _segment("s3", SegmentType.BLOCKQUOTE, "A quoted passage.", 0, "Chapter One", 3),
        _segment("s4", SegmentType.CAPTION, "Figure 1: a caption.", 0, "Chapter One", 4),
        _segment("s5", SegmentType.OTHER, "A dateline, retyped not dropped.", 0, "Chapter One", 5),
        _segment("s6", SegmentType.LIST_ITEM, "First item", 0, "Chapter One", 6),
        _segment("s7", SegmentType.LIST_ITEM, "Second item", 0, "Chapter One", 7),
        _segment("s8", SegmentType.HEADING, "Notes", 1, "Notes", 8, 3),
        _segment("s9", SegmentType.PARAGRAPH, "A citation fragment.", 1, "Notes", 9),
    ]
    book = Book(
        title="Test Book",
        authors=["Test Author"],
        language="sl",
        source_path="/nonexistent/source.epub",
        source_format="epub",
        segments=segments,
    )

    rebuilt = _round_trip(book, tmp_path, "round_trip.epub")

    alignment = align(book, rebuilt)
    assert all(target is not None for _, target in alignment.pairs)
    assert len(alignment.pairs) == len(segments)
    assert [s.type for s in rebuilt.segments] == [s.type for s in segments]
    assert [s.heading_level for s in rebuilt.segments] == [s.heading_level for s in segments]
    assert [s.chapter_index for s in rebuilt.segments] == [s.chapter_index for s in segments]
    # Chapter titles survive the nav document, so the front/back-matter fold
    # still sees "Notes" on the translated side.
    assert [s.chapter_title for s in rebuilt.segments] == [s.chapter_title for s in segments]


@pytest.mark.parametrize("filename", [_KAPLAN_EPUB_NAME, _ACTIVE_MEASURES_PDF_NAME])
def test_real_book_round_trips_to_an_aligning_book(
    filename: str, tmp_path: Path, example_book
) -> None:
    """S1.13: the round-trip invariant holds on a real EPUB- and PDF-sourced book.

    Uses a freshly assembled EPUB rather than the ``.sl.epub`` artifacts under
    ``data/examples``: those are outputs of earlier runs, and a stale one
    proves nothing about the current assembler.
    """
    source_path = example_book(filename)
    if source_path is None:
        pytest.skip(f"example book not available: {filename}")

    source = normalize(source_path)
    rebuilt = _round_trip(source, tmp_path, "real_round_trip.epub")

    alignment = align(source, rebuilt)
    assert all(target is not None for _, target in alignment.pairs)
    assert len(alignment.pairs) == len(source.segments)
    assert _chapter_count(rebuilt) == _chapter_count(source)
    # The translated side must resolve chapter titles too, or rubric T's
    # front/back-matter fold is inert on exactly the book it is scoring.
    assert _top_title_share(rebuilt) < MAX_TITLE_CONCENTRATION
    assert _top_title_share(rebuilt) == pytest.approx(_top_title_share(source))
