"""Tests for the PDF normalizer (S1.2) and the segment-quality screen.

All LLM calls are mocked with a fake :class:`LLMClient` — no network, no cost.
Unit tests build tiny synthetic PDFs with PyMuPDF so the reflow heuristics
(header/footer stripping, de-hyphenation, paragraph merging, heading
detection) are exercised deterministically. Integration tests run against the
two real example PDFs when present (``BERILO_EXAMPLE_DIR`` or ``data/examples``)
and are skipped otherwise, since ``data/`` is gitignored.
"""

from __future__ import annotations

import os
import re
from pathlib import Path

import fitz
import pytest

from berilo.models import Book, Segment, SegmentType
from berilo.normalize.pdf import (
    _dehyphenate,
    _is_caption,
    _is_droppable,
    _is_garbled_page_number,
    _is_ocr_gibberish,
    _Line,
    _strip_page_furniture,
    normalize_pdf,
)
from berilo.providers.base import CompletionResult, LLMClient
from berilo.screen import (
    ScreenError,
    back_matter_chapter_indices,
    front_matter_chapter_indices,
    sample_segments,
    screen_segments,
)

# Font sizes used when laying out synthetic PDFs.
_BODY_SIZE = 11.0
_HEADING_SIZE = 24.0
# A4 layout coordinates: header band is the top ~8% (~67pt); body sits below.
_LEFT_MARGIN = 72.0
_INDENT_X = 90.0
_HEADER_Y = 40.0
_RUNNING_HEAD = "The Secret History"

_NUMBER_RE = re.compile(r"^\d+$")
_ROMAN_RE = re.compile(r"^[ivxlcdm]+$", re.IGNORECASE)


# --------------------------------------------------------------------------- #
# Fakes / fixtures
# --------------------------------------------------------------------------- #


class _FakeLLMClient(LLMClient):
    """Deterministic LLM stand-in: clean unless the passage contains a marker.

    Records every prompt so tests can assert the segment text was sent.
    """

    _NOT_CLEAN_MARKER = "NOT_CLEAN"

    def __init__(self, cost_per_call: float = 0.001) -> None:
        self.cost_per_call = cost_per_call
        self.prompts: list[str] = []

    def complete(
        self,
        prompt: str | None = None,
        messages: list[dict[str, str]] | None = None,
    ) -> CompletionResult:
        text = prompt or ""
        self.prompts.append(text)
        answer = "NO" if self._NOT_CLEAN_MARKER in text else "YES"
        return CompletionResult(
            text=answer,
            input_tokens=len(text.split()),
            output_tokens=1,
            cost_eur=self.cost_per_call,
            model="fake-mini",
        )


def _make_pdf(
    tmp_path: Path,
    pages: list[list[tuple[float, float, str, float]]],
    name: str = "book.pdf",
    title: str = "Test Book",
    author: str = "A. Author",
) -> Path:
    """Build a synthetic PDF from explicit (x, y, text, size) line specs."""
    doc = fitz.open()
    for page_lines in pages:
        page = doc.new_page()
        for x, y, text, size in page_lines:
            page.insert_text((x, y), text, fontsize=size)
    doc.set_metadata({"title": title, "author": author})
    path = tmp_path / name
    doc.save(str(path))
    doc.close()
    return path


def _header(page_number: int) -> list[tuple[float, float, str, float]]:
    """A running-header line plus a bare page-number line in the top band."""
    return [
        (_LEFT_MARGIN, _HEADER_Y, _RUNNING_HEAD, 8.0),
        (300.0, _HEADER_Y, str(page_number), 8.0),
    ]


def _reflow_pdf(tmp_path: Path) -> Path:
    """A 3-page fixture exercising every reflow heuristic at once."""
    page1 = _header(1) + [
        (_LEFT_MARGIN, 120.0, "CHAPTER 1", _HEADING_SIZE),
        # Paragraph A: three hard-wrapped lines, two line-break hyphens.
        (_LEFT_MARGIN, 160.0, "This is a long paragraph that should be re-", _BODY_SIZE),
        (_LEFT_MARGIN, 176.0, "flowed into a single clean segment for exam-", _BODY_SIZE),
        (_LEFT_MARGIN, 192.0, "ple purposes without any broken words.", _BODY_SIZE),
        # Paragraph B: starts indented, runs onto page 2.
        (_INDENT_X, 224.0, "A second paragraph begins here, indented so", _BODY_SIZE),
        (_LEFT_MARGIN, 240.0, "the reflow logic starts a fresh segment and", _BODY_SIZE),
    ]
    page2 = _header(2) + [
        # Non-indented continuation of Paragraph B across the page boundary.
        (_LEFT_MARGIN, 120.0, "continues across the page boundary cleanly.", _BODY_SIZE),
        (_LEFT_MARGIN, 160.0, "CHAPTER 2", _HEADING_SIZE),
        (_INDENT_X, 200.0, "Chapter two has its own paragraph of prose", _BODY_SIZE),
        (_LEFT_MARGIN, 216.0, "that reads naturally after reflow.", _BODY_SIZE),
    ]
    page3 = _header(3) + [
        # A stray roman numeral and page number leaked into the body region.
        (_INDENT_X, 120.0, "iv", _BODY_SIZE),
        (_INDENT_X, 152.0, "123", _BODY_SIZE),
        (_INDENT_X, 184.0, "Final real paragraph of the fixture book.", _BODY_SIZE),
    ]
    return _make_pdf(tmp_path, [page1, page2, page3], name="reflow.pdf")


@pytest.fixture()
def reflow_book(tmp_path: Path) -> Book:
    """Normalized :class:`Book` for the reflow fixture."""
    return normalize_pdf(_reflow_pdf(tmp_path))


# --------------------------------------------------------------------------- #
# De-hyphenation unit tests (pure function)
# --------------------------------------------------------------------------- #


def test_dehyphenate_joins_soft_wrap_lowercase() -> None:
    """A lowercase continuation drops the line-break hyphen."""
    assert _dehyphenate("exam-", "ple purposes") == "example purposes"


def test_dehyphenate_keeps_hyphen_for_proper_noun() -> None:
    """A capitalized continuation keeps the hyphen (anti- + American)."""
    assert _dehyphenate("anti-", "American forces") == "anti-American forces"


def test_dehyphenate_ignores_non_hyphenated_lines() -> None:
    """Lines without a trailing hyphen are not joined here."""
    assert _dehyphenate("a full word", "next line") is None
    assert _dehyphenate("-", "x") is None


# --------------------------------------------------------------------------- #
# Reflow / extraction tests
# --------------------------------------------------------------------------- #


def test_metadata_and_format(reflow_book: Book) -> None:
    """Title, author, and source format come through."""
    assert reflow_book.title == "Test Book"
    assert reflow_book.authors == ["A. Author"]
    assert reflow_book.source_format == "pdf"


def test_running_header_and_page_numbers_are_stripped(reflow_book: Book) -> None:
    """The repeating header and bare page numbers never become segments."""
    for seg in reflow_book.segments:
        assert _RUNNING_HEAD not in seg.text
        assert not _NUMBER_RE.match(seg.text.strip())
        assert not _ROMAN_RE.match(seg.text.strip())


def test_no_empty_segments(reflow_book: Book) -> None:
    """Reflow never emits an empty or whitespace-only segment."""
    assert reflow_book.segments
    assert all(seg.text.strip() for seg in reflow_book.segments)


def test_hyphenation_joined_into_one_paragraph(reflow_book: Book) -> None:
    """Paragraph A's three wrapped lines merge, both hyphens repaired."""
    para_a = _find_segment(reflow_book, "long paragraph")
    assert "reflowed into a single clean segment" in para_a.text
    assert "for example purposes without any broken words." in para_a.text
    assert "exam-" not in para_a.text
    assert "re- flowed" not in para_a.text
    # Not over-merged into the following paragraph.
    assert "second paragraph" not in para_a.text


def test_paragraph_merges_across_page_boundary(reflow_book: Book) -> None:
    """Paragraph B spanning pages 1→2 reflows into a single segment."""
    para_b = _find_segment(reflow_book, "second paragraph begins here")
    assert "continues across the page boundary cleanly." in para_b.text


def test_indent_starts_new_paragraph(reflow_book: Book) -> None:
    """The indented start of Paragraph B is a distinct segment from A."""
    para_a = _find_segment(reflow_book, "long paragraph")
    para_b = _find_segment(reflow_book, "second paragraph begins here")
    assert para_a.id != para_b.id


def test_headings_detected_and_chapters_indexed(reflow_book: Book) -> None:
    """Both chapter headings are found and open distinct chapters."""
    headings = [s for s in reflow_book.segments if s.type is SegmentType.HEADING]
    assert [h.text for h in headings] == ["CHAPTER 1", "CHAPTER 2"]
    assert reflow_book.chapter_count == 2
    # Paragraphs inherit the enclosing chapter title.
    para_c = _find_segment(reflow_book, "Chapter two has its own paragraph")
    assert para_c.chapter_title == "CHAPTER 2"


def test_stray_numeric_segments_dropped(reflow_book: Book) -> None:
    """Leaked 'iv' and '123' lines are dropped, surrounding prose survives."""
    texts = [seg.text for seg in reflow_book.segments]
    assert "iv" not in texts
    assert "123" not in texts
    assert any("Final real paragraph" in t for t in texts)


def test_segment_positions_are_sequential(reflow_book: Book) -> None:
    """Segment positions match document order with no gaps."""
    positions = [seg.position for seg in reflow_book.segments]
    assert positions == list(range(len(positions)))


# --------------------------------------------------------------------------- #
# Chapter assignment: embedded TOC vs no-TOC bracketing
# --------------------------------------------------------------------------- #


def test_embedded_toc_drives_chapters(tmp_path: Path) -> None:
    """When a TOC is present its content entries are the chapter authority.

    Front/back-matter TOC entries are excluded, and a stray font-outlier line
    that would otherwise mint a chapter does not add one.
    """
    doc = fitz.open()
    for _ in range(3):
        doc.new_page()
    # page 0: Chapter One; page 1: Chapter Two (+ a decoy big-font line);
    # page 2: notes, which folds into the last content chapter.
    doc[0].insert_text((_LEFT_MARGIN, 120.0), "Alpha prose on the first page.", fontsize=_BODY_SIZE)
    # A real-word big-font line that WOULD mint a chapter in the no-TOC path.
    doc[1].insert_text((_LEFT_MARGIN, 120.0), "Decoy Heading", fontsize=_HEADING_SIZE)
    doc[1].insert_text((_LEFT_MARGIN, 160.0), "Beta prose on the second page.", fontsize=_BODY_SIZE)
    doc[2].insert_text(
        (_LEFT_MARGIN, 120.0), "Gamma notes prose on the third.", fontsize=_BODY_SIZE
    )
    doc.set_toc(
        [
            [1, "Title Page", 1],
            [1, "Chapter One", 1],
            [1, "Chapter Two", 2],
            [1, "Notes", 3],
        ]
    )
    path = tmp_path / "toc.pdf"
    doc.save(str(path))
    doc.close()

    book = normalize_pdf(path)

    # Two content chapters (Title Page + Notes filtered); the decoy adds none.
    assert book.chapter_count == 2
    titles = {seg.chapter_title for seg in book.segments}
    assert titles == {"Chapter One", "Chapter Two"}
    alpha = _find_segment(book, "Alpha prose")
    beta = _find_segment(book, "Beta prose")
    gamma = _find_segment(book, "Gamma notes prose")
    assert alpha.chapter_title == "Chapter One"
    assert beta.chapter_title == "Chapter Two"
    assert gamma.chapter_title == "Chapter Two"  # Notes folds into last chapter


def test_no_toc_back_matter_does_not_spawn_chapters(tmp_path: Path) -> None:
    """No-TOC: citation/back-matter pages under a NOTES header mint no chapters.

    Big-font "chapter-like" lines on scanned notes pages (an OCR failure mode)
    must fold into a single trailing chapter, not multiply the count.
    """
    notes_header = (_LEFT_MARGIN, _HEADER_Y, "NOTES", 8.0)
    pages = [
        [(_LEFT_MARGIN, 120.0, "Front matter opening paragraph here.", _BODY_SIZE)],
        [(_LEFT_MARGIN, 120.0, "CONTENTS", _BODY_SIZE)],
        [
            (_LEFT_MARGIN, 120.0, "CHAPTER 1", _HEADING_SIZE),
            (_LEFT_MARGIN, 160.0, "Body of chapter one begins here now.", _BODY_SIZE),
        ],
        [(_LEFT_MARGIN, 120.0, "More body prose for chapter one here.", _BODY_SIZE)],
        [
            notes_header,
            (_LEFT_MARGIN, 120.0, "CHAPTER 2", _HEADING_SIZE),
            (_LEFT_MARGIN, 160.0, "Citation text referencing chapter one.", _BODY_SIZE),
        ],
        [
            notes_header,
            (_LEFT_MARGIN, 120.0, "The Godfather", _HEADING_SIZE),
            (_LEFT_MARGIN, 160.0, "Another endnote paragraph of prose here.", _BODY_SIZE),
        ],
        [
            notes_header,
            (_LEFT_MARGIN, 120.0, "Bounty Hunters", _HEADING_SIZE),
            (_LEFT_MARGIN, 160.0, "Yet another endnote paragraph follows on.", _BODY_SIZE),
        ],
    ]
    path = _make_pdf(tmp_path, pages, name="notes.pdf")

    book = normalize_pdf(path)

    # Front matter (0), the one real body chapter, and a single folded back
    # matter chapter — the notes-page display headings add nothing.
    assert book.chapter_count == 3
    # The real chapter is detected in the body bracket...
    body_chapter = _find_segment(book, "Body of chapter one begins here now.").chapter_index
    assert body_chapter == 1
    # ...and all three notes-page display headings collapse into one chapter,
    # distinct from (and after) the body chapter.
    notes_heading_chapters = {
        seg.chapter_index
        for seg in book.segments
        if seg.text in {"CHAPTER 2", "The Godfather", "Bounty Hunters"}
    }
    assert notes_heading_chapters == {2}


# --------------------------------------------------------------------------- #
# Screen-gate fixes: captions, cross-page merge, back-matter exclusion
# --------------------------------------------------------------------------- #


def test_is_caption_recognizes_source_credits() -> None:
    """Parenthetical caption/source-credit lines are captions, prose is not."""
    assert _is_caption("(National Diet Library)")
    assert _is_caption("KGB disinformation targeted the SAC. (U.S. Air Force)")
    assert _is_caption("...important voices on disinformation. (Elizabeth Spaulding)")
    # Real prose — ends with a sentence period, or an un-credited parenthetical.
    assert not _is_caption("He returned to Moscow that winter and never left.")
    assert not _is_caption("They met in Moscow (the capital) during the thaw.")
    assert not _is_caption("See chapter three for the full account (page 3).")
    # Positional markers and depiction-phrase leads are captions too.
    assert _is_caption("Franz-Josef Strauss (right), speaking with the delegation")
    assert _is_caption("Image of a lynched man from an inauthentic pamphlet titled")
    # But prose merely mentioning an image is not.
    assert not _is_caption("The image of the west that the pamphlet projected was grim.")


def test_scene_setter_lines_typed_other() -> None:
    """Dateline/place chapter openers are OTHER, not prose paragraphs."""
    from berilo.normalize.pdf import _is_scene_setter

    assert _is_scene_setter("November 2020")
    assert _is_scene_setter("June 13, 2017")
    assert _is_scene_setter("Fort Meade, Maryland")
    assert not _is_scene_setter("November 2020 was the coldest month on record.")
    assert not _is_scene_setter("He left for Fort Meade, Maryland.")


def test_comma_continuation_merges_across_break() -> None:
    """A block ending with a comma merges with an uppercase continuation."""
    from berilo.normalize.pdf import _looks_incomplete

    assert _looks_incomplete("moved the operation from Australia to")
    assert _looks_incomplete("hit ports in Rotterdam,")
    assert not _looks_incomplete("The operation ended.")


def test_single_token_with_digit_is_droppable() -> None:
    """Endnote-marker artifacts like 'CINCUSAREUR.20' and '28.' are junk."""
    from berilo.normalize.pdf import _is_droppable

    assert _is_droppable("CINCUSAREUR.20")
    assert _is_droppable("28.")
    assert _is_droppable("28")
    assert not _is_droppable("Moscow.")
    assert not _is_droppable("The 20 divisions moved west.")


def test_caption_line_is_typed_caption_and_excluded_from_sampling(tmp_path: Path) -> None:
    """A parenthetical caption becomes a CAPTION segment, not a sampled paragraph."""
    pages = [
        _header(1)
        + [
            (_LEFT_MARGIN, 120.0, "CHAPTER 1", _HEADING_SIZE),
            (_LEFT_MARGIN, 160.0, "A real paragraph of ordinary book prose here.", _BODY_SIZE),
            (_LEFT_MARGIN, 200.0, "(National Diet Library)", _BODY_SIZE),
        ]
    ]
    book = normalize_pdf(_make_pdf(tmp_path, pages, name="caption.pdf"))

    caption = _find_segment(book, "National Diet Library")
    assert caption.type is SegmentType.CAPTION
    # Captions are never offered to the prose screen.
    assert all(seg.type is SegmentType.PARAGRAPH for seg in sample_segments(book, 30, 42))
    assert not any("National Diet Library" in seg.text for seg in sample_segments(book, 30, 42))


def test_cross_page_blockquote_lines_merge_into_one_paragraph(tmp_path: Path) -> None:
    """Indented block-quote lines that wrap mid-sentence merge, not fragment.

    Each quote line is indented (which normally starts a new paragraph) and ends
    mid-sentence; the continuation override rejoins them into one segment.
    """
    page1 = _header(1) + [
        (_LEFT_MARGIN, 120.0, "CHAPTER 1", _HEADING_SIZE),
        (
            _INDENT_X,
            160.0,
            "U.S. involvement in these less-developed nations threatened by",
            _BODY_SIZE,
        ),
        (
            _INDENT_X,
            176.0,
            "insurgency is part of the world-wide U.S. involvement in the",
            _BODY_SIZE,
        ),
    ]
    page2 = _header(2) + [
        (_INDENT_X, 120.0, "struggle against Communism everywhere in the world.", _BODY_SIZE),
    ]
    book = normalize_pdf(_make_pdf(tmp_path, [page1, page2], name="blockquote.pdf"))

    merged = _find_segment(book, "less-developed nations threatened by")
    assert "insurgency is part of the world-wide" in merged.text
    assert "struggle against Communism everywhere" in merged.text  # spans the page break


def test_back_matter_chapter_excluded_from_prose_sampling() -> None:
    """sample_segments drops paragraphs in notes/index chapters."""
    body = [
        Segment(
            id=f"body-{i}",
            type=SegmentType.PARAGRAPH,
            text=f"Body prose paragraph number {i}.",
            chapter_index=1,
            chapter_title="1. The Trust",
            position=i,
        )
        for i in range(20)
    ]
    notes = [
        Segment(
            id=f"note-{i}",
            type=SegmentType.PARAGRAPH,
            text=f'(2007), p. 6. {i}. "Ein Agent," Der Spiegel.',
            chapter_index=2,
            chapter_title="Notes",
            position=20 + i,
        )
        for i in range(20)
    ]
    book = Book(
        title="B",
        authors=[],
        language="en",
        source_path="mem.pdf",
        source_format="pdf",
        segments=body + notes,
    )

    assert back_matter_chapter_indices(book) == {2}
    sampled = sample_segments(book, n=30, seed=42)
    assert sampled  # body paragraphs remain
    assert all(seg.chapter_index == 1 for seg in sampled)
    assert not any("Der Spiegel" in seg.text for seg in sampled)


def test_front_matter_folds_drops_gibberish_and_excludes_from_sampling(tmp_path: Path) -> None:
    """No-TOC front matter folds into a 'Front Matter' chapter: cover-art OCR
    gibberish is dropped, real copyright text is kept, and none of it is sampled.
    """
    page0 = [  # front matter: cover-art gibberish, then (after a gap) copyright
        (_LEFT_MARGIN, 120.0, "Pel OO ays por Umi", _BODY_SIZE),
        (_LEFT_MARGIN, 136.0, "wa", _BODY_SIZE),  # merges with the gibberish above
        # Large vertical gap => a new paragraph: the real copyright block.
        (
            _LEFT_MARGIN,
            220.0,
            "Bloomsbury Publishing Inc. and its logos are trademarks.",
            _BODY_SIZE,
        ),
        (
            _LEFT_MARGIN,
            236.0,
            "This paperback edition published 2023 by the publisher.",
            _BODY_SIZE,
        ),
    ]
    page1 = [  # body
        (_LEFT_MARGIN, 120.0, "CHAPTER 1", _HEADING_SIZE),
        (
            _LEFT_MARGIN,
            160.0,
            "A clean body paragraph of real prose opens the first chapter here.",
            _BODY_SIZE,
        ),
    ]
    book = normalize_pdf(_make_pdf(tmp_path, [page0, page1], name="frontmatter.pdf"))

    # Cover-art gibberish is dropped entirely.
    assert not any("Pel OO" in seg.text for seg in book.segments)
    assert not any("Umi" in seg.text for seg in book.segments)
    # Real copyright text survives, folded under the Front Matter chapter.
    copyright_seg = _find_segment(book, "Bloomsbury Publishing Inc.")
    assert copyright_seg.chapter_title == "Front Matter"
    assert "This paperback edition published 2023" in copyright_seg.text
    assert front_matter_chapter_indices(book) == {copyright_seg.chapter_index}
    # Front matter is never offered to the prose screen; the body chapter is.
    sampled = sample_segments(book, n=30, seed=42)
    assert sampled
    assert all(seg.chapter_index not in front_matter_chapter_indices(book) for seg in sampled)
    assert any("clean body paragraph" in seg.text for seg in sampled)


def test_is_ocr_gibberish_keeps_real_short_sentences() -> None:
    """The gibberish guard flags scan residue but never a real short sentence."""
    assert _is_ocr_gibberish("Hf j")
    assert _is_ocr_gibberish(": ~tg aie")
    assert not _is_ocr_gibberish("To whom would they not?")
    assert not _is_ocr_gibberish("How would those exploits be used?")


def test_body_scan_residue_is_typed_other_not_dropped(tmp_path: Path) -> None:
    """A zero-real-word body fragment ("Hf j") becomes OTHER and stays in the
    Book (translated, not sampled); a real short sentence stays PARAGRAPH."""
    # A long first paragraph fixes the median line gap small; blank-gap breaks
    # then isolate the two short blocks that follow.
    long_para = [
        (_LEFT_MARGIN, 150.0 + 16.0 * i, line, _BODY_SIZE)
        for i, line in enumerate(
            [
                "The investigators kept circling the same unanswered question",
                "that had haunted them for months, unable to move past it or",
                "to let it go, and every meeting returned to the same nagging",
                "doubt about who was really pulling the strings behind all of",
                "the events, and the room fell silent whenever it came up",
                "again in this widening and increasingly dangerous affair.",
            ]
        )
    ]
    page0 = [
        (_LEFT_MARGIN, 120.0, "CHAPTER 1", _HEADING_SIZE),
        *long_para,
        (_LEFT_MARGIN, 296.0, "To whom would they not?", _BODY_SIZE),  # real short sentence
        (_LEFT_MARGIN, 346.0, "Hf j", _BODY_SIZE),  # zero-real-word scan residue
        (
            _LEFT_MARGIN,
            396.0,
            "A final grounding paragraph of clean body prose ends it.",
            _BODY_SIZE,
        ),
    ]
    book = normalize_pdf(_make_pdf(tmp_path, [page0], name="residue.pdf"))

    question = _find_segment(book, "To whom would they not?")
    assert question.type is SegmentType.PARAGRAPH
    residue = _find_segment(book, "Hf j")
    assert residue.type is SegmentType.OTHER  # reclassified, not dropped
    assert residue in book.segments  # integrity: kept in the book
    # OTHER residue is excluded from the prose sample; real prose is not.
    sampled = sample_segments(book, n=30, seed=42)
    assert not any(seg.text == "Hf j" for seg in sampled)
    assert any("To whom would they not?" in seg.text for seg in sampled)


# --------------------------------------------------------------------------- #
# Screen tests (LLM mocked)
# --------------------------------------------------------------------------- #


def _paragraph(text: str, position: int) -> Segment:
    """Build a standalone paragraph segment for screen tests."""
    return Segment(
        id=f"seg-{position}",
        type=SegmentType.PARAGRAPH,
        text=text,
        chapter_index=0,
        chapter_title=None,
        position=position,
    )


def _book_with_paragraphs(count: int) -> Book:
    """A book of ``count`` paragraph segments plus one heading (never sampled)."""
    segments: list[Segment] = [
        Segment(
            id="h",
            type=SegmentType.HEADING,
            text="A Heading",
            chapter_index=0,
            chapter_title="A Heading",
            position=0,
            heading_level=1,
        )
    ]
    segments += [_paragraph(f"Paragraph number {i} of clean prose.", i + 1) for i in range(count)]
    return Book(
        title="B",
        authors=[],
        language="en",
        source_path="mem.pdf",
        source_format="pdf",
        segments=segments,
    )


def test_sample_segments_is_deterministic() -> None:
    """Same (n, seed) yields the identical sample; only paragraphs sampled."""
    book = _book_with_paragraphs(60)
    first = sample_segments(book, n=30, seed=42)
    second = sample_segments(book, n=30, seed=42)
    assert [s.id for s in first] == [s.id for s in second]
    assert len(first) == 30
    assert all(s.type is SegmentType.PARAGRAPH for s in first)
    # Returned in document order.
    assert [s.position for s in first] == sorted(s.position for s in first)


def test_sample_segments_different_seed_differs() -> None:
    """A different seed selects a different subset."""
    book = _book_with_paragraphs(60)
    a = sample_segments(book, n=30, seed=42)
    b = sample_segments(book, n=30, seed=7)
    assert [s.id for s in a] != [s.id for s in b]


def test_sample_segments_returns_all_when_fewer_than_n() -> None:
    """When there are fewer paragraphs than requested, all are returned."""
    book = _book_with_paragraphs(5)
    assert len(sample_segments(book, n=30, seed=42)) == 5


def test_screen_segments_computes_fraction_and_cost() -> None:
    """Clean fraction and summed cost are computed from mocked verdicts."""
    segments = [
        _paragraph("Perfectly clean book prose about history.", 1),
        _paragraph("More clean narrative prose continues here.", 2),
        _paragraph("Another clean and readable sentence.", 3),
        _paragraph("NOT_CLEAN running header leaked in.", 4),
    ]
    client = _FakeLLMClient(cost_per_call=0.002)
    report = screen_segments(segments, client)

    assert report.total == 4
    assert report.clean_count == 3
    assert report.clean_fraction == pytest.approx(0.75)
    assert report.cost_eur == pytest.approx(0.008)
    assert len(report.flagged) == 1
    assert report.flagged[0].segment.text.startswith("NOT_CLEAN")
    # Every segment's text was actually sent to the model.
    assert all(seg.text in "".join(client.prompts) for seg in segments)


def test_screen_all_clean_is_full_fraction() -> None:
    """A batch the model accepts scores 1.0 clean."""
    segments = [_paragraph(f"Clean sentence {i}.", i) for i in range(5)]
    report = screen_segments(segments, _FakeLLMClient())
    assert report.clean_fraction == 1.0
    assert not report.flagged


@pytest.mark.parametrize(
    "reply",
    ["", "Probably clean, but hard to tell.", "maybe", "   ", "42"],
    ids=["empty", "hedged", "unrelated-word", "whitespace-only", "numeric"],
)
def test_screen_raises_rather_than_defaulting_to_dirty(reply: str) -> None:
    """Review finding 13: an unparseable reply must not silently score dirty.

    ``judge.py`` raises on an unparseable verdict rather than defaulting one
    way or the other; the screen must follow that precedent instead of
    inventing a third convention (silently folding into ``clean_fraction``).
    """
    segments = [_paragraph("Some paragraph the model fails to classify.", 1)]
    client = _FakeLLMClient()
    client.complete = lambda prompt=None, messages=None: CompletionResult(
        text=reply, input_tokens=10, output_tokens=1, cost_eur=0.001, model="fake-mini"
    )
    with pytest.raises(ScreenError, match="Unparseable"):
        screen_segments(segments, client)


# --------------------------------------------------------------------------- #
# Margin/header-garble fixes (per-parity indent threshold + pre-reflow strip)
# --------------------------------------------------------------------------- #


def test_recto_verso_margins_do_not_shatter_paragraphs(tmp_path: Path) -> None:
    """A capitalized wrap onto a wider recto margin stays one paragraph.

    Verso body sits at x=16, recto body at x=29. With a single global margin the
    recto continuation reads as a first-line indent and (being capitalized, so
    the lowercase-continuation override cannot save it) becomes an orphan
    fragment. The per-parity indent threshold keeps it joined.
    """
    verso, recto, indent = 16.0, 29.0, 18.0
    page0 = [
        (72.0, _HEADER_Y, "1", 8.0),
        (verso, 120.0, "CHAPTER 1", _HEADING_SIZE),
        (
            verso + indent,
            160.0,
            "The coalition included partners from many allied nations who",
            _BODY_SIZE,
        ),
        (verso, 176.0, "coordinated operations closely and shared intelligence while", _BODY_SIZE),
        (verso, 192.0, "working with", _BODY_SIZE),  # ends mid-sentence, no punctuation
    ]
    page1 = [
        (72.0, _HEADER_Y, "2", 8.0),
        # Capitalized continuation at the recto margin — must still merge.
        (recto, 120.0, "Israeli and German engineers across the entire region.", _BODY_SIZE),
        # A genuine recto first-line indent still starts a new paragraph.
        (
            recto + indent,
            160.0,
            "A brand-new paragraph then opens on the recto side here.",
            _BODY_SIZE,
        ),
        (recto, 176.0, "and it runs onto a second line at the recto margin.", _BODY_SIZE),
    ]
    book = normalize_pdf(_make_pdf(tmp_path, [page0, page1], name="parity.pdf"))

    merged = _find_segment(book, "coalition included partners")
    assert "Israeli and German engineers across the entire region." in merged.text
    # The truly-indented recto paragraph remains distinct.
    other = _find_segment(book, "A brand-new paragraph then opens")
    assert other.id != merged.id


def test_header_band_garbled_page_number_is_stripped(tmp_path: Path) -> None:
    """A garbled in-band page number ("40OI1") never enters a segment."""
    page0 = [
        (200.0, _HEADER_Y, "40OI1", 8.0),  # garbled OCR page number in header band
        (_LEFT_MARGIN, 120.0, "CHAPTER 1", _HEADING_SIZE),
        (
            _LEFT_MARGIN,
            160.0,
            "The body paragraph must stay clean without header garble.",
            _BODY_SIZE,
        ),
    ]
    page1 = [
        (200.0, _HEADER_Y, "40OI2", 8.0),  # another garbled page number
        (_LEFT_MARGIN, 120.0, "A second clean paragraph continues the chapter here.", _BODY_SIZE),
    ]
    book = normalize_pdf(_make_pdf(tmp_path, [page0, page1], name="garble.pdf"))

    for seg in book.segments:
        assert "40OI1" not in seg.text
        assert "40OI2" not in seg.text
    # Body prose survives intact and is not prefixed by the garble token.
    body = _find_segment(book, "The body paragraph must stay clean")
    assert not body.text[0].isdigit()
    assert _find_segment(book, "A second clean paragraph continues")


# --------------------------------------------------------------------------- #
# Integration tests against the real example PDFs (skipped when absent)
# --------------------------------------------------------------------------- #

_ACTIVE_MEASURES = (
    "Active Measures The Secret History of Disinformation and Political "
    "Warfare (Thomas Rid) (z-library.sk, 1lib.sk, z-lib.sk).pdf"
)
_WORLD_ENDS = (
    "This Is How They Tell Me the World Ends The Cyberweapons Arms Race "
    "(Nicole Perlroth) (z-library.sk, 1lib.sk, z-lib.sk).pdf"
)


def _example_pdf(filename: str) -> Path | None:
    """Locate an example PDF via BERILO_EXAMPLE_DIR or a repo data/ directory."""
    candidates: list[Path] = []
    env_dir = os.environ.get("BERILO_EXAMPLE_DIR")
    if env_dir:
        candidates.append(Path(env_dir) / filename)
    for parent in Path(__file__).resolve().parents:
        candidates.append(parent / "data" / "examples" / filename)
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


@pytest.mark.parametrize("filename", [_ACTIVE_MEASURES, _WORLD_ENDS])
def test_real_pdf_extraction_is_clean(filename: str) -> None:
    """Real books extract to many chapters/segments with no furniture leaks."""
    pdf = _example_pdf(filename)
    if pdf is None:
        pytest.skip(f"example PDF not available: {filename}")

    book = normalize_pdf(pdf)

    assert book.chapter_count > 0
    assert len(book.segments) >= 200
    for seg in book.segments:
        assert seg.text.strip()
        assert not _NUMBER_RE.match(seg.text.strip())
        assert not _ROMAN_RE.match(seg.text.strip())


def test_world_ends_recto_wrap_and_header_garble_fixed() -> None:
    """Real World Ends: the page-371 recto-wrap paragraph is one segment and
    the garbled header page number never leaks."""
    pdf = _example_pdf(_WORLD_ENDS)
    if pdf is None:
        pytest.skip("example PDF not available")

    book = normalize_pdf(pdf)

    # BUG A: the paragraph that wraps across the recto margin (ending
    # "Antarctica's research stations.") extracts as exactly one segment.
    para = _find_segment(book, "Southern Ocean to Antarctica")
    assert para.text.rstrip().endswith("research stations.")
    assert "signed on for a weeks" in para.text  # an earlier sentence of the same paragraph
    # BUG B: the garbled header page number "40OI1" (page 401) never appears.
    assert not any("40OI1" in seg.text for seg in book.segments)


# --------------------------------------------------------------------------- #
# Review finding 1: numeric single-token titles and lines. Two independent
# drop paths, both keyed on token SHAPE alone with no evidence of the line's
# role — so any content shaped like a folio or an endnote marker was destroyed.
# --------------------------------------------------------------------------- #


def _body_lines() -> list[tuple[float, float, str, float]]:
    """Enough body-size prose that the median line size IS the body size.

    A two-line fixture puts the median between body and heading size, so no
    line is a font-size outlier and heading detection never fires.
    """
    return [
        (
            _LEFT_MARGIN,
            160.0 + 16.0 * index,
            f"Body prose line number {index} of the fixture.",
            _BODY_SIZE,
        )
        for index in range(6)
    ]


@pytest.mark.parametrize("token", ["40OI1", "7O", "9QI", "I0O", "Il0", "419g", "XXV1", "1984"])
def test_garbled_page_numbers_are_still_dropped(token: str) -> None:
    """Every folio rendering measured in the OCR'd example PDF stays dropped.

    These eight are the exact tokens the margin-band rule removes on *This Is
    How They Tell Me the World Ends*; relaxing the rule must not resurrect any
    of them, or that book's extraction — and its `book_hash` — moves.
    """
    assert _is_garbled_page_number(token)


@pytest.mark.parametrize("token", ["COVID-19", "MiG-29", "9/11-era", "iPhone-13"])
def test_content_bearing_tokens_are_not_page_numbers(token: str) -> None:
    """The fix: a lone token carrying a real word is content, not a folio."""
    assert not _is_garbled_page_number(token)


def test_band_line_with_a_real_word_survives_the_furniture_strip() -> None:
    """Drop path 1 (``_strip_page_furniture``): a band line is not a folio
    merely because it contains a digit."""
    band = [
        _Line(text="COVID-19", x0=72.0, y0=40.0, size=8.0, page_index=0, in_band=True),
        _Line(text="40OI1", x0=300.0, y0=40.0, size=8.0, page_index=0, in_band=True),
    ]

    kept = _strip_page_furniture(band, running_heads=set())

    assert [line.text for line in kept] == ["COVID-19"]


def test_band_uri_stamp_is_furniture() -> None:
    """A bare URI in the margin band is scan provenance, not prose.

    It carries real words, so the content test above would keep it; it is
    dropped as its own furniture class instead (the archive.org stamp measured
    in the OCR'd example PDF).
    """
    band = [
        _Line(
            text="https://archive.org/details/thisishowtheytelO000nico",
            x0=72.0,
            y0=40.0,
            size=8.0,
            page_index=0,
            in_band=True,
        )
    ]

    assert _strip_page_furniture(band, running_heads=set()) == []


def test_heading_with_a_digit_bearing_title_survives(tmp_path: Path) -> None:
    """Drop path 2 (heading admission): the category error, fixed.

    ``_is_droppable`` is a BODY-block predicate — a lone digit-bearing token in
    reflowed prose is an endnote marker. Applying it to a line
    ``_looks_like_heading`` had already admitted silently deleted a chapter
    titled "COVID-19".
    """
    page = [(_LEFT_MARGIN, 120.0, "COVID-19", _HEADING_SIZE), *_body_lines()]

    book = normalize_pdf(_make_pdf(tmp_path, [page], name="numeric_heading.pdf"))

    heading = _find_segment(book, "COVID-19")
    assert heading.type == SegmentType.HEADING


def test_purely_numeric_title_is_admitted_when_the_outline_declares_it(tmp_path: Path) -> None:
    """A chapter genuinely titled "1984" carries no word at all.

    Shape cannot tell it from a folio, so the document's OWN outline is the
    evidence that admits it — structural evidence outranks a heuristic.
    """
    doc = fitz.open()
    doc.new_page()
    doc.new_page()
    doc[0].insert_text((_LEFT_MARGIN, 120.0), "Title Page", fontsize=_BODY_SIZE)
    doc[1].insert_text((_LEFT_MARGIN, 120.0), "1984", fontsize=_HEADING_SIZE)
    for x, y, text, size in _body_lines():
        doc[1].insert_text((x, y), text, fontsize=size)
    doc.set_toc([[1, "Title Page", 1], [1, "1984", 2]])
    path = tmp_path / "numeric_title.pdf"
    doc.save(str(path))
    doc.close()

    book = normalize_pdf(path)

    heading = _find_segment(book, "1984")
    assert heading.type == SegmentType.HEADING
    assert heading.chapter_title == "1984"


def test_numeric_line_without_outline_backing_is_still_dropped(tmp_path: Path) -> None:
    """The other side of the same rule: no outline entry, no admission.

    Without this the large chapter-opening folio that illustrated books set at
    heading size would mint a segment on every chapter, moving `book_hash` on
    the example PDFs.
    """
    page = [(_LEFT_MARGIN, 120.0, "1984", _HEADING_SIZE), *_body_lines()]

    book = normalize_pdf(_make_pdf(tmp_path, [page], name="numeric_no_outline.pdf"))

    assert not any(segment.type == SegmentType.HEADING for segment in book.segments)


@pytest.mark.parametrize("text", ["CINCUSAREUR.20", "p=17628.", "05:36:11", "68–69.", "#3160712,”"])
def test_endnote_debris_blocks_are_still_dropped(text: str) -> None:
    """The body-block rule is deliberately NOT relaxed.

    Measured across both example PDFs: all 27 blocks ``_is_droppable`` removes
    are citation/endnote debris of exactly this kind, and a content-aware
    relaxation would resurrect four of them. Keeping this path strict is what
    holds the two PDF baselines still.
    """
    assert _is_droppable(text)


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


def _find_segment(book: Book, needle: str) -> Segment:
    """Return the single segment whose text contains ``needle``."""
    matches = [seg for seg in book.segments if needle in seg.text]
    assert (
        len(matches) == 1
    ), f"expected exactly one segment containing {needle!r}, got {len(matches)}"
    return matches[0]
