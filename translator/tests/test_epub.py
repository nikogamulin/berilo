"""Tests for the EPUB normalizer and the ``inspect`` CLI command (S1.1).

Unit tests build small synthetic EPUBs with ``epub_builder`` (see
``conftest.py``). One integration test runs against the real example book
under ``data/`` (gitignored, never committed) and is skipped when that file
is absent, e.g. in CI.
"""

from __future__ import annotations

import json
import logging
from collections import Counter
from pathlib import Path

import pytest
from click.testing import CliRunner

from berilo.cli import cli
from berilo.models import SegmentType
from berilo.normalize import normalize
from berilo.normalize.epub import normalize_epub

_EXAMPLES = Path(__file__).parents[2] / "data" / "examples"
EXAMPLE_EPUB = _EXAMPLES / "The New Rules of War.epub"
KAPLAN_EPUB = _EXAMPLES / (
    "The Revenge of Geography What the Map Tells Us About Coming Conflicts "
    "and the Battle Against Fate (Robert D. Kaplan) (z-library.sk, 1lib.sk, z-lib.sk).epub"
)

MIN_EXAMPLE_SEGMENTS = 500
MIN_EXAMPLE_CHAPTERS = 8

#: S1.13: no example book may concentrate this share of its segments on one
#: chapter title — above it, rubric T's front/back-matter fold goes inert.
MAX_TITLE_CONCENTRATION = 0.5


def test_segment_order_matches_document_order(epub_builder) -> None:
    """Segments come out in document (spine + in-body) order, across chapters."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": "<h1>Chapter One</h1><p>CH1-P1</p><p>CH1-P2</p>",
            },
            {
                "id": "c2",
                "href": "c2.xhtml",
                "nav_title": "Chapter Two",
                "body": "<h1>Chapter Two</h1><p>CH2-P1</p><p>CH2-P2</p><p>CH2-P3</p>",
            },
        ]
    )

    book = normalize_epub(path)

    expected_texts = [
        "Chapter One",
        "CH1-P1",
        "CH1-P2",
        "Chapter Two",
        "CH2-P1",
        "CH2-P2",
        "CH2-P3",
    ]
    assert [segment.text for segment in book.segments] == expected_texts
    # Positions are gapless and strictly increasing, i.e. sorting by
    # position is a no-op: segment order already IS document order.
    assert [segment.position for segment in book.segments] == list(range(len(expected_texts)))
    sorted_by_position = sorted(book.segments, key=lambda segment: segment.position)
    assert sorted_by_position == book.segments
    assert [segment.chapter_index for segment in book.segments] == [0, 0, 0, 1, 1, 1, 1]


def test_inline_emphasis_is_retained_other_tags_stripped(epub_builder) -> None:
    """em/strong/i/b/sub/sup survive as HTML; other inline tags are unwrapped."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": (
                    "<p>Plain <em>emphasis</em> and <strong>strong</strong> and "
                    '<a href="x">a link</a> and <span class="x">a span</span> '
                    "and H<sub>2</sub>O and x<sup>2</sup>.</p>"
                ),
            }
        ]
    )

    book = normalize_epub(path)

    (segment,) = [s for s in book.segments if s.type == SegmentType.PARAGRAPH]
    assert "<em>emphasis</em>" in segment.text
    assert "<strong>strong</strong>" in segment.text
    assert "<sub>2</sub>" in segment.text
    assert "<sup>2</sup>" in segment.text
    assert "a link" in segment.text and "<a" not in segment.text
    assert "a span" in segment.text and "<span" not in segment.text


def test_empty_and_whitespace_only_segments_are_excluded(epub_builder) -> None:
    """Empty/whitespace-only blocks, image-only docs, and nav docs yield 0 segments."""
    path = epub_builder(
        items=[
            {
                "id": "cover",
                "href": "cover.xhtml",
                "nav_title": None,
                "body": '<img src="cover.jpg" alt="cover"/>',
            },
            {
                "id": "toc",
                "href": "nav.xhtml",
                "nav_title": None,
                "body": (
                    '<nav epub:type="toc" '
                    'xmlns:epub="http://www.idpf.org/2007/ops">'
                    '<ol><li><a href="c1.xhtml">Chapter One</a></li></ol></nav>'
                ),
            },
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": "<h1>Chapter One</h1><p>  </p><p></p><p>Real content.</p>",
            },
        ]
    )

    book = normalize_epub(path)

    assert all(segment.text.strip() for segment in book.segments)
    assert [segment.text for segment in book.segments] == ["Chapter One", "Real content."]
    # The cover and nav documents produced no segments, so they consume no
    # chapter slot: the sole real chapter is chapter_index 0.
    assert book.chapter_count == 1
    assert {segment.chapter_index for segment in book.segments} == {0}


def test_segment_ids_are_stable_across_runs(epub_builder) -> None:
    """Re-normalizing the same EPUB produces identical segment IDs."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": "<h1>Chapter One</h1><p>Some content.</p>",
            }
        ]
    )

    first = normalize_epub(path)
    second = normalize_epub(path)

    assert [s.id for s in first.segments] == [s.id for s in second.segments]
    assert len({s.id for s in first.segments}) == len(first.segments)


def test_chapter_titles_from_toc_ncx(epub_builder) -> None:
    """Chapter titles come from toc.ncx navLabel text, not the doc's own <title>."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "The Real Title",
                "doc_title": "internal-doc-title",
                "body": "<p>Body text.</p>",
            }
        ]
    )

    book = normalize_epub(path)

    assert book.segments[0].chapter_title == "The Real Title"


def test_chapter_title_falls_back_to_doc_title_without_toc_entry(epub_builder) -> None:
    """A spine document absent from toc.ncx falls back to its own <title>."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": None,
                "doc_title": "Untitled Front Matter",
                "body": "<p>Body text.</p>",
            }
        ]
    )

    book = normalize_epub(path)

    assert book.segments[0].chapter_title == "Untitled Front Matter"


def test_toc_fallback_to_nav_document_when_ncx_absent(epub_builder) -> None:
    """When toc.ncx is missing, chapter titles come from the EPUB3 nav doc."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": None,
                "body": "<p>Body text.</p>",
            }
        ],
        include_ncx=False,
        nav_toc=[("c1.xhtml", "Chapter From Nav Doc")],
    )

    book = normalize_epub(path)

    assert book.segments[0].chapter_title == "Chapter From Nav Doc"


def test_metadata_and_heading_levels(epub_builder) -> None:
    """Title, authors, language, and heading_level are extracted correctly."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": "<h1>Top</h1><h2>Sub</h2><p>Text.</p>",
            }
        ],
        title="My Test Book",
        authors=["Author A", "Author B"],
        language="sl",
    )

    book = normalize_epub(path)

    assert book.title == "My Test Book"
    assert book.authors == ["Author A", "Author B"]
    assert book.language == "sl"
    assert book.source_format == "epub"
    headings = [s for s in book.segments if s.type == SegmentType.HEADING]
    assert [h.heading_level for h in headings] == [1, 2]


def test_dispatcher_routes_epub_to_normalize_epub(epub_builder) -> None:
    """berilo.normalize.normalize() dispatches .epub files to normalize_epub()."""
    path = epub_builder(
        items=[{"id": "c1", "href": "c1.xhtml", "nav_title": "C1", "body": "<p>Hi.</p>"}]
    )

    book = normalize(path)

    assert book.source_format == "epub"
    assert len(book.segments) == 1


def test_inspect_json_reports_expected_keys(epub_builder) -> None:
    """``berilo inspect --json`` reports the keys the story packet requires."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": "<h1>Chapter One</h1><p>Some content.</p>",
            }
        ]
    )

    runner = CliRunner()
    result = runner.invoke(cli, ["inspect", str(path), "--json"])

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    for key in (
        "title",
        "authors",
        "language",
        "source_format",
        "chapter_count",
        "segment_count",
        "empty_segment_count",
        "chapters",
    ):
        assert key in payload
    assert payload["chapter_count"] == 1
    assert payload["segment_count"] == 2
    assert payload["empty_segment_count"] == 0
    assert payload["chapters"] == [{"index": 0, "title": "Chapter One", "segment_count": 2}]


def test_inspect_human_output_shows_summary_and_preview(epub_builder) -> None:
    """The human-readable ``inspect`` output includes title, counts, and a preview."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": "<h1>Chapter One</h1><p>Some content.</p>",
            }
        ],
        title="Preview Book",
    )

    runner = CliRunner()
    result = runner.invoke(cli, ["inspect", str(path)])

    assert result.exit_code == 0, result.output
    assert "Preview Book" in result.output
    assert "1 chapters, 2 segments, 0 empty" in result.output
    assert "Chapter One" in result.output


def test_inspect_missing_file_exits_nonzero() -> None:
    """``inspect`` on a nonexistent file fails loudly instead of crashing."""
    runner = CliRunner()
    result = runner.invoke(cli, ["inspect", "does-not-exist.epub"])

    assert result.exit_code == 1
    assert "inspect:" in result.output


def test_toc_ncx_resolved_when_manifest_names_a_missing_file(epub_builder) -> None:
    """S1.13: a manifest NCX href absent from the archive still resolves.

    Repackaged books declare a TOC document under a name the archive does not
    contain. Before the fix both TOC parses failed and every chapter title
    collapsed onto the book title.
    """
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "The Real Title",
                "doc_title": "Test Book",
                "body": "<p>Body text.</p>",
            }
        ],
        declared_ncx_href="9780000000000_ncx.ncx",
    )

    book = normalize_epub(path)

    assert book.segments[0].chapter_title == "The Real Title"


def test_toc_ncx_href_is_resolved_against_the_opf_directory(epub_builder) -> None:
    """S1.13: manifest hrefs are relative to the OPF, not to the archive root."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "The Real Title",
                "doc_title": "Test Book",
                "body": "<p>Body text.</p>",
            }
        ],
        opf_dir="OEBPS",
    )

    book = normalize_epub(path)

    assert book.segments[0].chapter_title == "The Real Title"


def test_spine_document_without_toc_entry_continues_previous_chapter(epub_builder) -> None:
    """S1.13: TOC entries mark chapter starts; an entry-less document continues one.

    The chapter-opening stub ("Chapter II") is the only document in the TOC;
    the body document that follows it must inherit that title rather than the
    book title.
    """
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter II: The Real Title",
                "doc_title": "Test Book",
                "body": "<p>Chapter II</p>",
            },
            {
                "id": "c2",
                "href": "c2.xhtml",
                "nav_title": None,
                "doc_title": "Test Book",
                "body": "<p>Body text of chapter two.</p>",
            },
        ]
    )

    book = normalize_epub(path)

    assert [segment.chapter_title for segment in book.segments] == [
        "Chapter II: The Real Title",
        "Chapter II: The Real Title",
    ]


def test_chapter_title_prefers_document_heading_over_book_title(epub_builder) -> None:
    """S1.13: with no TOC at all, an in-document heading outranks the book title."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": None,
                "doc_title": "Test Book",
                "body": "<h1>A Real Heading</h1><p>Body text.</p>",
            }
        ],
        include_ncx=False,
    )

    book = normalize_epub(path)

    assert {segment.chapter_title for segment in book.segments} == {"A Real Heading"}


def test_chapter_title_falls_back_to_book_title_without_any_heading(epub_builder) -> None:
    """S1.13: the book title stays the last resort when a document has no heading."""
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": None,
                "doc_title": "Test Book",
                "body": "<p>Body text.</p>",
            }
        ],
        include_ncx=False,
    )

    book = normalize_epub(path)

    assert book.segments[0].chapter_title == "Test Book"


def test_wholly_bold_short_paragraph_is_typed_as_heading(epub_builder) -> None:
    """S1.13: a <p> whose whole text is bold is a heading, not prose.

    Calibre MOBI→EPUB conversions emit no <h1>-<h6>; every heading is a
    bold-classed <p>. Retyping (never dropping) keeps the 1:1 mapping while
    taking the heading out of the PARAGRAPH prose pool.
    """
    path = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": (
                    '<p class="calibre5"><span class="calibre13">'
                    '<span class="bold">THE REVENGE OF GEOGRAPHY</span></span></p>'
                    "<p><b>Chapter II</b></p>"
                    "<p>Ordinary prose with a <b>bold phrase</b> inside it.</p>"
                    f"<p><b>{'Bold but far too long to be a heading. ' * 5}</b></p>"
                ),
            }
        ]
    )

    book = normalize_epub(path)

    types = [(segment.type, segment.text[:24]) for segment in book.segments]
    assert types[0] == (SegmentType.HEADING, "THE REVENGE OF GEOGRAPHY")
    assert types[1] == (SegmentType.HEADING, "<b>Chapter II</b>")
    assert types[2][0] == SegmentType.PARAGRAPH
    assert types[3][0] == SegmentType.PARAGRAPH
    # Retyped, never dropped: every block still yields exactly one segment.
    assert len(book.segments) == 4
    assert {segment.heading_level for segment in book.segments[:2]} == {2}


def test_collapsed_chapter_titles_are_logged_loudly(epub_builder, caplog) -> None:
    """S1.13: a title collapse is reported at ERROR level, not silently."""
    path = epub_builder(
        items=[
            {
                "id": f"c{index}",
                "href": f"c{index}.xhtml",
                "nav_title": None,
                "doc_title": "Test Book",
                "body": f"<p>Body text {index}.</p>",
            }
            for index in range(3)
        ],
        include_ncx=False,
    )

    with caplog.at_level(logging.ERROR, logger="berilo.normalize.epub"):
        normalize_epub(path)

    assert any(
        record.levelno == logging.ERROR and "Chapter titles did not resolve" in record.getMessage()
        for record in caplog.records
    ), caplog.text
    assert "(the book title)" in caplog.text


def test_resolved_chapter_titles_log_no_error(epub_builder, caplog) -> None:
    """S1.13: the loud warning stays quiet when chapter titles resolve normally."""
    path = epub_builder(
        items=[
            {
                "id": f"c{index}",
                "href": f"c{index}.xhtml",
                "nav_title": f"Chapter {index}",
                "body": f"<p>Body text {index}.</p>",
            }
            for index in range(3)
        ]
    )

    with caplog.at_level(logging.ERROR, logger="berilo.normalize.epub"):
        normalize_epub(path)

    assert caplog.records == []


@pytest.mark.skipif(
    not KAPLAN_EPUB.exists(), reason="data/examples Kaplan EPUB not present (worktree/CI)"
)
def test_kaplan_chapter_titles_do_not_collapse() -> None:
    """S1.13 Verify line: no example book concentrates titles on one bucket.

    Kaplan sat at 94.9% before the fix, which made rubric T v1.1's
    front/back-matter fold inert.
    """
    book = normalize_epub(KAPLAN_EPUB)

    _, top_count = Counter(s.chapter_title for s in book.segments).most_common(1)[0]
    assert top_count / len(book.segments) < MAX_TITLE_CONCENTRATION
    # The book has no <h1>-<h6> at all: its headings are bold-classed <p>.
    assert any(segment.type == SegmentType.HEADING for segment in book.segments)


@pytest.mark.skipif(not EXAMPLE_EPUB.exists(), reason="data/examples example EPUB not present")
def test_example_epub_meets_verify_thresholds() -> None:
    """Verify line: the real example EPUB clears the S1.1 segment/chapter thresholds."""
    runner = CliRunner()
    result = runner.invoke(cli, ["inspect", str(EXAMPLE_EPUB), "--json"])

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["segment_count"] >= MIN_EXAMPLE_SEGMENTS
    assert payload["chapter_count"] >= MIN_EXAMPLE_CHAPTERS
    assert payload["empty_segment_count"] == 0
