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
    normalize_pdf,
)
from berilo.providers.base import CompletionResult, LLMClient
from berilo.screen import sample_segments, screen_segments

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
