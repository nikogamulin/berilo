"""PDF normalizer: PyMuPDF extraction plus reflow heuristics.

The PDF path is the hard one. Raw ``page.get_text`` yields one string per
*visual* line, polluted with running headers, footers, and page numbers, and
with paragraphs hard-wrapped at the page margin and words split by
line-break hyphens. This module reconstructs the logical document:

1. **Extract** every text line with its geometry (position, font size).
2. **Strip running headers/footers/page numbers** by repeating-pattern
   detection across pages (not a fixed regex) plus bare page-number / roman
   numeral removal — some example PDFs are OCR-sourced and leak inline
   headers like ``"PROLOGUE \\nXix \\n"`` (see ``docs/findings.md``).
3. **De-hyphenate** words split across a line break (``exam-\\nple`` →
   ``example``) while keeping genuine mid-line hyphens.
4. **Reflow** hard-wrapped lines back into paragraphs using first-line indent
   and vertical-gap signals, merging across page boundaries.
5. **Detect chapter headings** by font-size outliers and standalone
   chapter-pattern lines, opening a new chapter for each.

The result is a :class:`~berilo.models.Book` whose segments carry a 1:1
mapping to the logical paragraphs and headings of the source — no segment is
a bare page number, running header, or empty string.
"""

from __future__ import annotations

import logging
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from statistics import median

import fitz

from berilo.models import Book, Segment, SegmentType, make_segment_id

logger = logging.getLogger(__name__)

# --- Geometry / detection constants -----------------------------------------

# Fraction of page height treated as the top (header) and bottom (footer)
# margin bands. Lines inside these bands are candidates for removal; body text
# on the example PDFs starts at ~8% and ends by ~94%.
HEADER_BAND_FRACTION = 0.08
FOOTER_BAND_FRACTION = 0.92

# A normalized band line must recur on at least this many pages to count as a
# running header/footer. Real running heads repeat on dozens of pages; a
# one-off line that merely strays into the margin band is left alone.
RUNNING_HEAD_MIN_PAGES = 3

# A line whose largest span is at least this multiple of the body font size
# (and which is short) is treated as a heading.
HEADING_SIZE_RATIO = 1.45
HEADING_MAX_WORDS = 12

# The keyword / numbered-title fallback (for headings that are *not* font-size
# outliers) is far more prone to false positives — body prose often opens with
# "Chapter" or "1." — so it is restricted to very short, label-like lines that
# do not read as full sentences.
HEADING_KEYWORD_MAX_WORDS = 6

# A line whose left edge is indented more than this multiple of the body font
# size past the column's left margin starts a new paragraph.
INDENT_RATIO = 0.8

# A vertical gap larger than this multiple of the page's median line gap marks
# a paragraph / scene break even without an indent.
PARA_GAP_RATIO = 1.6

# Font size at or above ``body_size * HEADING_LEVEL1_RATIO`` is a top-level
# (part) heading; smaller outliers are chapter-level.
HEADING_LEVEL1_RATIO = 1.9

# A back-matter section title only folds the chapter count when it appears in
# the final stretch of the document — otherwise a "Copyright" page in the
# front matter would swallow the entire body.
BACK_MATTER_MIN_FRACTION = 0.55

# Chapter-heading text patterns (matched case-insensitively on the stripped
# line). Numbered titles (``12. Dirty Business``) are matched separately.
_HEADING_KEYWORDS = (
    "chapter",
    "part",
    "prologue",
    "epilogue",
    "introduction",
    "foreword",
    "preface",
    "afterword",
    "appendix",
    "conclusion",
)
_HEADING_KEYWORD_RE = re.compile(
    r"^(?:" + "|".join(_HEADING_KEYWORDS) + r")\b",
    re.IGNORECASE,
)
_NUMBERED_TITLE_RE = re.compile(r"^\d{1,3}[.)]\s+\S")

# Normalized titles that mark the start of back matter. Everything from the
# first such heading onward is kept (segment integrity) but folded into a
# single trailing chapter, so endnote/index sub-headers — which in these books
# reuse the chapter titles at display size — do not inflate the chapter count.
# Exact keys guard against prefix collisions ("index" vs "indexed"); phrase
# prefixes catch multi-word variants ("Also by ...", "A Note About ...").
_BACK_MATTER_TITLES = frozenset(
    {
        "notes",
        "index",
        "bibliography",
        "references",
        "acknowledgments",
        "acknowledgements",
        "copyright",
    }
)
_BACK_MATTER_PREFIXES = (
    "alsoby",
    "anoteabout",
    "abouttheauthor",
    "furtherreading",
    "newslettersign",
)

# Segments that must never survive: bare arabic page numbers and bare roman
# numerals (any case — OCR renders "xix" as "Xix").
_PAGE_NUMBER_RE = re.compile(r"^\d+$")
_ROMAN_NUMERAL_RE = re.compile(r"^[ivxlcdm]+$", re.IGNORECASE)
_NON_WORD_RE = re.compile(r"[^\w]", re.UNICODE)
_DIGITS_RE = re.compile(r"\d+")
_WHITESPACE_RE = re.compile(r"\s+")
_WORD_RE = re.compile(r"\w", re.UNICODE)
# A run of 3+ letters (Unicode-aware, excluding digits/underscore) — the mark
# of a real word, used to tell headings from OCR debris.
_WORD_RUN_RE = re.compile(r"[^\W\d_]{3,}", re.UNICODE)


@dataclass(frozen=True)
class _Line:
    """A single extracted text line with the geometry reflow needs.

    Attributes:
        text: The line's text (leading/trailing whitespace stripped).
        x0: Left edge of the line's bounding box.
        y0: Top edge of the line's bounding box.
        size: Largest span font size on the line.
        page_index: Zero-based source page index.
        in_band: True if the line sits in the top or bottom margin band.
    """

    text: str
    x0: float
    y0: float
    size: float
    page_index: int
    in_band: bool


def normalize_pdf(path: Path) -> Book:
    """Parse a PDF file into a :class:`~berilo.models.Book`.

    Runs the extract → strip-headers → de-hyphenate → reflow → detect-headings
    pipeline described in the module docstring.

    Args:
        path: Path to the ``.pdf`` source file.

    Returns:
        The normalized book with paragraph and heading segments in document
        order and a 1:1 mapping to the source's logical blocks.
    """
    path = Path(path)
    with fitz.open(path) as doc:
        pages = [_extract_page_lines(doc[i], i) for i in range(doc.page_count)]
        metadata = doc.metadata or {}
    title = metadata.get("title") or path.stem
    author = metadata.get("author") or ""

    running_heads = _detect_running_heads(pages)
    body_pages = [_strip_page_furniture(page, running_heads) for page in pages]
    body_size = _body_font_size(body_pages)
    segments = _reflow_to_segments(body_pages, body_size)

    authors = [author] if author else []
    logger.info(
        "normalized PDF %s: %d segments across %d chapters",
        path.name,
        len(segments),
        len({seg.chapter_index for seg in segments}),
    )
    return Book(
        title=title,
        authors=authors,
        language="en",
        source_path=str(path),
        source_format="pdf",
        segments=segments,
    )


def _extract_page_lines(page: fitz.Page, page_index: int) -> list[_Line]:
    """Extract text lines from one page, tagged with geometry and margin band.

    Args:
        page: The PyMuPDF page.
        page_index: Zero-based index of this page in the document.

    Returns:
        Text lines in reading order (top-to-bottom, then left-to-right).
    """
    height = page.rect.height or 1.0
    top_limit = height * HEADER_BAND_FRACTION
    bottom_limit = height * FOOTER_BAND_FRACTION

    lines: list[_Line] = []
    data = page.get_text("dict")
    for block in data.get("blocks", []):
        if block.get("type") != 0:  # skip image blocks
            continue
        for line in block.get("lines", []):
            spans = line.get("spans", [])
            text = "".join(span.get("text", "") for span in spans).strip()
            if not text:
                continue
            bbox = line["bbox"]
            y0 = bbox[1]
            size = max((span.get("size", 0.0) for span in spans), default=0.0)
            lines.append(
                _Line(
                    text=text,
                    x0=bbox[0],
                    y0=y0,
                    size=size,
                    page_index=page_index,
                    in_band=y0 < top_limit or y0 > bottom_limit,
                )
            )
    lines.sort(key=lambda ln: (round(ln.y0, 1), ln.x0))
    return lines


def _normalize_head_key(text: str) -> str:
    """Reduce a line to a comparison key for running-header detection.

    Digits and punctuation are dropped and case folded so page-numbered
    variants of the same running head (``"16 THE WORLD ENDS"`` /
    ``"18 THE WORLD ENDS"``) collapse to one key.

    Args:
        text: Raw line text.

    Returns:
        A lowercase alphabetic-only key, possibly empty.
    """
    return _NON_WORD_RE.sub("", _DIGITS_RE.sub("", text)).lower()


def _is_page_number(text: str) -> bool:
    """Return True if the line is a bare arabic or roman page number."""
    stripped = text.strip()
    return bool(_PAGE_NUMBER_RE.match(stripped) or _ROMAN_NUMERAL_RE.match(stripped))


def _detect_running_heads(pages: list[list[_Line]]) -> set[str]:
    """Find normalized header/footer keys that recur across pages.

    Only lines inside the margin bands are considered, and a key must appear
    on at least :data:`RUNNING_HEAD_MIN_PAGES` distinct pages to qualify.

    Args:
        pages: Extracted lines per page.

    Returns:
        The set of normalized keys judged to be running headers/footers.
    """
    page_counts: Counter[str] = Counter()
    for page in pages:
        keys = {
            _normalize_head_key(line.text)
            for line in page
            if line.in_band and _normalize_head_key(line.text)
        }
        page_counts.update(keys)
    return {key for key, count in page_counts.items() if count >= RUNNING_HEAD_MIN_PAGES}


def _strip_page_furniture(page: list[_Line], running_heads: set[str]) -> list[_Line]:
    """Drop running headers/footers and bare page numbers from one page.

    A band line is removed when it is a bare page number/roman numeral, its
    normalized key is a known running head, or it reduces to nothing but
    digits/punctuation. Body lines are always kept.

    Args:
        page: Extracted lines for one page.
        running_heads: Normalized keys detected as running headers/footers.

    Returns:
        The page's body lines, in order.
    """
    kept: list[_Line] = []
    for line in page:
        if line.in_band:
            key = _normalize_head_key(line.text)
            if _is_page_number(line.text) or not key or key in running_heads:
                continue
        kept.append(line)
    return kept


def _body_font_size(pages: list[list[_Line]]) -> float:
    """Estimate the body font size as the median line font size.

    Args:
        pages: Body lines per page (after furniture removal).

    Returns:
        The median font size across all body lines, or 12.0 if there are none.
    """
    sizes = [line.size for page in pages for line in page if line.size > 0]
    return median(sizes) if sizes else 12.0


def _looks_like_heading(line: _Line, body_size: float) -> bool:
    """Decide whether a line is a chapter/section heading.

    A heading is either a short font-size outlier or a short line matching a
    chapter keyword / numbered-title pattern.

    Args:
        line: The candidate line.
        body_size: Estimated body font size.

    Returns:
        True if the line should become a :class:`SegmentType.HEADING` segment.
    """
    text = line.text.strip()
    word_count = len(text.split())
    if word_count == 0 or word_count > HEADING_MAX_WORDS:
        return False
    if text.endswith((",", ";", ":")):
        return False
    # Title-shape guard: a real heading contains at least one real word (a run
    # of 3+ letters). This rejects OCR debris on scanned pages ("7 me ah 1%",
    # "aT", "ia") while keeping numeric section titles ("1975–1989: Escalate").
    if not _WORD_RUN_RE.search(text):
        return False
    # Primary signal: a short font-size outlier.
    if line.size >= body_size * HEADING_SIZE_RATIO:
        return True
    # Fallback for headings rendered at body size: a short, label-like line
    # (no terminal sentence punctuation) matching a chapter keyword, a
    # numbered title, or a back-matter section name (some books set "NOTES"
    # just below the outlier threshold). Kept strict to avoid swallowing prose
    # that merely opens with "Chapter" or a list number.
    if word_count > HEADING_KEYWORD_MAX_WORDS or text.endswith((".", "!", "?")):
        return False
    return bool(
        _HEADING_KEYWORD_RE.match(text)
        or _NUMBERED_TITLE_RE.match(text)
        or _is_back_matter_title(text)
    )


def _dehyphenate(prev: str, nxt: str) -> str | None:
    """Join two lines across a line-break hyphen, or return None if not one.

    A trailing hyphen preceded by a letter and followed by an alphabetic line
    is treated as a soft wrap: the hyphen is dropped when the continuation is
    lowercase (``exam-`` + ``ple`` → ``example``) but kept for proper-noun
    compounds (``anti-`` + ``American`` → ``anti-American``).

    Args:
        prev: The line that ends with a candidate hyphen.
        nxt: The following line.

    Returns:
        The joined string, or ``None`` if ``prev`` is not a hyphenated wrap.
    """
    if not prev.endswith("-") or len(prev) < 2:
        return None
    if not prev[-2].isalpha() or not nxt[:1].isalpha():
        return None
    stem = prev[:-1]
    return f"{stem}-{nxt}" if nxt[:1].isupper() else f"{stem}{nxt}"


def _clean_segment_text(text: str) -> str:
    """Collapse internal whitespace and strip a segment's edges."""
    return _WHITESPACE_RE.sub(" ", text).strip()


def _is_droppable(text: str) -> bool:
    """Return True if a segment must be discarded (empty/number/punctuation)."""
    stripped = text.strip()
    if not stripped:
        return True
    if _is_page_number(stripped):
        return True
    return not _WORD_RE.search(stripped)


def _heading_level(size: float, body_size: float) -> int:
    """Map a heading's font size to a level (1 for the largest, else 2)."""
    return 1 if size >= body_size * HEADING_LEVEL1_RATIO else 2


def _is_back_matter_title(title: str) -> bool:
    """Return True if a heading title marks the start of back matter."""
    key = _normalize_head_key(title)
    if not key:
        return False
    return key in _BACK_MATTER_TITLES or key.startswith(_BACK_MATTER_PREFIXES)


def _median_line_gap(page: list[_Line]) -> float:
    """Median vertical gap between consecutive lines on a page (0 if <2 lines)."""
    gaps = [page[i].y0 - page[i - 1].y0 for i in range(1, len(page)) if page[i].y0 > page[i - 1].y0]
    return median(gaps) if gaps else 0.0


def _starts_new_paragraph(
    line: _Line,
    indent_threshold: float,
    gap: float | None,
    page_gap: float,
    has_prev: bool,
) -> bool:
    """Return True if ``line`` begins a new paragraph.

    Args:
        line: The current line.
        indent_threshold: Left-edge offset (past the column margin) that marks
            a first-line indent.
        gap: Vertical gap from the previous line on this page, if any.
        page_gap: Median line gap for the page.
        has_prev: Whether a paragraph is currently being accumulated.
    """
    if not has_prev:
        return False
    if line.x0 > indent_threshold:
        return True
    return gap is not None and page_gap > 0 and gap > page_gap * PARA_GAP_RATIO


def _build_segment(
    seg_type: SegmentType,
    text: str,
    chapter_index: int,
    chapter_title: str | None,
    position: int,
    heading_level: int | None = None,
) -> Segment:
    """Construct a :class:`Segment` with a stable content-hash ID."""
    return Segment(
        id=make_segment_id(text, chapter_index, position),
        type=seg_type,
        text=text,
        chapter_index=chapter_index,
        chapter_title=chapter_title,
        position=position,
        heading_level=heading_level,
    )


def _reflow_to_segments(pages: list[list[_Line]], body_size: float) -> list[Segment]:
    """Reflow body lines into ordered paragraph and heading segments.

    Paragraph boundaries are drawn on headings, first-line indents, and large
    vertical gaps; everything else is merged, joining line-break hyphens.
    Paragraphs merge across page boundaries. Each detected heading opens a new
    chapter.

    Args:
        pages: Body lines per page (after furniture removal).
        body_size: Estimated body font size.

    Returns:
        Ordered segments with stable IDs, chapter indices, and titles.
    """
    indent_threshold = _column_left_margin(pages) + body_size * INDENT_RATIO
    back_matter_page = len(pages) * BACK_MATTER_MIN_FRACTION

    segments: list[Segment] = []
    buffer: list[str] = []
    state = {
        "chapter_index": 0,
        "chapter_title": None,
        "emitted": False,
        "in_back_matter": False,
    }

    def flush() -> None:
        if not buffer:
            return
        text = _clean_segment_text(" ".join(buffer))
        buffer.clear()
        if _is_droppable(text):
            return
        segments.append(
            _build_segment(
                SegmentType.PARAGRAPH,
                text,
                state["chapter_index"],
                state["chapter_title"],
                len(segments),
            )
        )
        state["emitted"] = True

    for page in pages:
        page_gap = _median_line_gap(page)
        prev_y: float | None = None
        for line in page:
            gap = None if prev_y is None else line.y0 - prev_y
            prev_y = line.y0

            if _looks_like_heading(line, body_size):
                flush()
                title = _clean_segment_text(line.text)
                if _is_droppable(title):
                    continue
                entering_back_matter = (
                    not state["in_back_matter"]
                    and line.page_index >= back_matter_page
                    and _is_back_matter_title(title)
                )
                # A new chapter opens for each body heading, and once for the
                # start of back matter; further back-matter headings (endnote /
                # index sub-headers) stay within that single trailing chapter.
                if state["emitted"] and (not state["in_back_matter"] or entering_back_matter):
                    state["chapter_index"] += 1
                if entering_back_matter:
                    state["in_back_matter"] = True
                state["chapter_title"] = title
                segments.append(
                    _build_segment(
                        SegmentType.HEADING,
                        title,
                        state["chapter_index"],
                        state["chapter_title"],
                        len(segments),
                        heading_level=_heading_level(line.size, body_size),
                    )
                )
                state["emitted"] = True
                continue

            if _starts_new_paragraph(line, indent_threshold, gap, page_gap, bool(buffer)):
                flush()

            if buffer:
                joined = _dehyphenate(buffer[-1], line.text)
                if joined is not None:
                    buffer[-1] = joined
                    continue
            buffer.append(line.text)

    flush()
    return segments


def _column_left_margin(pages: list[list[_Line]]) -> float:
    """Estimate the body column's left margin as the modal line left edge.

    Continuation lines all share the column margin, so the most common ``x0``
    (rounded) is a robust baseline; indented first lines sit to its right.

    Args:
        pages: Body lines per page.

    Returns:
        The estimated left-margin x-coordinate, or 0.0 if there are no lines.
    """
    counts: Counter[float] = Counter(round(line.x0) for page in pages for line in page)
    if not counts:
        return 0.0
    return float(counts.most_common(1)[0][0])
