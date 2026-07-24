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
from pathlib import Path
from xml.etree import ElementTree as ET

import pytest

from berilo.assemble import build_epub
from berilo.models import Book, Segment, SegmentType

EPUBCHECK = shutil.which("epubcheck")


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
