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
5. **Assign chapters.** When the PDF carries an embedded table of contents,
   its content entries (front/back matter filtered out) are the authoritative
   chapter starts. Otherwise chapter minting is bracketed to the body — front
   matter ends at the last "Contents" page, back matter begins at the first
   Notes/Index running header — and only high-confidence headings inside that
   bracket open a chapter, so OCR debris on scanned notes/index pages and
   cover/praise front matter cannot inflate the chapter count.
6. **Carry images.** Figure-sized embedded images are extracted with their
   page geometry and attached as book-level resources anchored to the segment
   they follow — never as segments, which would shift every later segment id.

The result is a :class:`~berilo.models.Book` whose segments carry a 1:1
mapping to the logical paragraphs and headings of the source — no segment is
a bare page number, running header, or empty string.

Segment typing marks non-prose so extraction-quality screening can skip it:
``CAPTION`` for figure/source-credit lines and ``OTHER`` for scan residue —
illustration/cover OCR gibberish that is not real text. ``OTHER`` segments are
kept in the book and still translated (segment integrity holds); they are only
excluded from the clean-prose sample.
"""

from __future__ import annotations

import bisect
import hashlib
import logging
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from statistics import median

import fitz

from berilo.models import Book, ImageResource, Segment, SegmentType, make_segment_id

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

# Chapter minting for books WITHOUT an embedded TOC is bracketed to the body:
# the front-matter boundary is the last "Contents" page in the opening
# fraction; the back-matter boundary is the first page in the closing fraction
# whose running header names a back-matter section (Notes/Index/...). Outside
# the bracket no new chapters are minted, so the OCR debris on scanned
# notes/index pages and the cover/praise front matter cannot inflate the count.
FRONT_MATTER_SCAN_FRACTION = 0.25
BACK_MATTER_SCAN_FRACTION = 0.5

# A font-outlier heading may mint a chapter only if it is a well-formed title:
# no more than this many digits, at least this fraction of its words
# capitalized (Title Case / ALL CAPS), and free of junk symbols. This is what
# separates real OCR chapter titles ("Closet of Secrets") from OCR gibberish
# ("oats tan each tea aarane>", "AMES AS oot gh and >").
STRONG_TITLE_MAX_DIGITS = 2
TITLE_CASE_MIN_RATIO = 0.6
# A descriptive title dominated by <=3-char words is OCR debris, not a title.
SHORT_WORD_MAX_LEN = 3
SHORT_WORD_MAX_RATIO = 0.5

# A short line that is wholly parenthetical, or a line (up to a moderate length)
# ending in a capitalized parenthetical source credit with no closing sentence
# period, is an image/figure caption, not body prose.
CAPTION_MAX_WORDS = 12
CAPTION_CREDIT_MAX_WORDS = 40

# Canonical titles given to the folded back- and front-matter chapters so
# downstream code (prose sampling, translation skip) can recognize them.
BACK_MATTER_CHAPTER_TITLE = "Notes"
FRONT_MATTER_CHAPTER_TITLE = "Front Matter"

# Front-matter OCR-gibberish drop: a folded front-matter block is discarded when
# fewer than this fraction of its tokens are real words (a >=4-letter token with
# a vowel and no gibberish letter-runs). Scoped to front-matter pages only, so
# body prose is never touched; real front-matter text (copyright/publisher
# lines) is kept because it clears the bar.
GIBBERISH_MIN_REAL_WORD_RATIO = 0.25
GIBBERISH_REAL_WORD_MIN_LEN = 4

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

# Strong chapter-label prefixes. A heading opening with one of these is a
# high-confidence chapter/section start (used to bracket and mint chapters in
# the no-embedded-TOC path).
_CHAPTER_LABEL_RE = re.compile(
    r"^(?:prologue|epilogue|aftermath|afterword|foreword|preface|introduction"
    r"|conclusion|appendix|author|part|chapter|book)\b",
    re.IGNORECASE,
)

# Normalized front-matter titles, excluded when reading an embedded TOC as the
# chapter list. Prefixes catch "Copyright Notice", "Praise for ...", etc.
_FRONT_MATTER_TITLES = frozenset(
    {
        "titlepage",
        "halftitle",
        "contents",
        "tableofcontents",
        "dedication",
        "epigraph",
        "frontispiece",
    }
)
_FRONT_MATTER_PREFIXES = ("copyright", "praise", "alsoby")

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
# Substrings that mark a back-matter section title even when embedded in a
# longer, punctuated TOC entry ("Notes: What Is Disinformation?", "Endnotes").
_BACK_MATTER_SUBSTRINGS = ("notes", "index", "bibliography", "references", "acknowledg")

# Segments that must never survive: bare arabic page numbers and bare roman
# numerals (any case — OCR renders "xix" as "Xix").
_PAGE_NUMBER_RE = re.compile(r"^\d+\.?$")
_ROMAN_NUMERAL_RE = re.compile(r"^[ivxlcdm]+$", re.IGNORECASE)
# A bare URI printed in a margin band is a scan-provenance stamp (archive.org,
# a library watermark), not book prose — furniture by the same argument as a
# running head. Body lines are never tested against this.
_URI_TOKEN_RE = re.compile(r"^(?:https?|ftp)://\S+$", re.IGNORECASE)
_NON_WORD_RE = re.compile(r"[^\w]", re.UNICODE)
_DIGITS_RE = re.compile(r"\d+")
_WHITESPACE_RE = re.compile(r"\s+")
_WORD_RE = re.compile(r"\w", re.UNICODE)
# A run of 3+ letters (Unicode-aware, excluding digits/underscore) — the mark
# of a real word, used to tell headings from OCR debris.
_WORD_RUN_RE = re.compile(r"[^\W\d_]{3,}", re.UNICODE)
# Alphabetic words (for title-case scoring) and junk symbols that never appear
# in a real chapter title but litter OCR debris.
_ALPHA_WORD_RE = re.compile(r"[^\W\d_]+", re.UNICODE)
_JUNK_SYMBOL_RE = re.compile(r"[=<>©®+&~|/\\{}\[\]]")
# A parenthetical group at the end of a line (figure-caption source credit).
_CAPTION_TAIL_RE = re.compile(r"\([^)]{2,}\)\s*$")
# A trailing parenthetical made only of 1-4 capitalized words — a photo/source
# credit ("(National Diet Library)", "(Elizabeth Spaulding)").
_CAPTION_CREDIT_RE = re.compile(r"\([A-Z][\w.'’-]*(?:\s+[A-Z][\w.'’-]*){0,3}\)\s*$")
# Photo-position markers ("Strauss (right), speaking with …") mark figure
# captions; body prose essentially never parenthesizes these bare words.
_CAPTION_POSITION_RE = re.compile(r"\((?:right|left|center|centre|top|bottom|above|below)\)", re.I)
# Plate-section captions without credits open with a depiction phrase.
_CAPTION_LEAD_RE = re.compile(
    r"^(?:Image|Photograph|Photo|Portrait|Cartoon|Illustration|Map|Poster|Facsimile|Front page"
    r"|Cover) of\b"
)
# A lone token with digits ("CINCUSAREUR.20", "p=17628.", "05:36:11") reflowed
# into a block of its own is an endnote-marker/citation artifact, never a prose
# paragraph. Measured on both example PDFs: 27 blocks are dropped by this rule
# and every one of them is citation debris (see the module note on _is_droppable
# versus _is_garbled_page_number).
_SINGLE_TOKEN_WITH_DIGIT_RE = re.compile(r"^\S*\d\S*$")
# Maximal runs of letters inside such a token. A GARBLED PAGE NUMBER ("40OI1"
# for 401, "XXV1" for XXVI) is built from digits and digit-lookalike glyphs, so
# every letter run in it is either too short to be a word or a roman numeral —
# whereas "COVID-19" or "MiG-29" carries a real word and is content.
_LETTER_RUN_RE = re.compile(r"[^\W\d_]+", re.UNICODE)
# A letter run of at least this length is a word rather than a digit lookalike.
_LOOKALIKE_RUN_MAX_CHARS = 2
# Chapter-opening scene setters: a bare date ("November 2020") or a
# "Place, Region" line ("Fort Meade, Maryland").
SCENE_SETTER_MAX_WORDS = 6
_DATELINE_RE = re.compile(
    r"^(?:January|February|March|April|May|June|July|August|September|October|November"
    r"|December)(?:\s+\d{1,2},?)?\s+\d{4}$"
)
_PLACELINE_RE = re.compile(r"^[A-Z][\w.'’-]*(?:\s+[A-Z][\w.'’-]*){0,2},\s+[A-Z][\w.'’ -]+$")
# Sentence-terminal characters, checked after stripping trailing quotes/brackets.
_TERMINAL_PUNCT = ".!?…"
_TRAILING_CLOSERS = "”’\"')]»"
# Letter-run shapes that mark OCR gibberish rather than a real title word.
_VOWEL_RUN_RE = re.compile(r"[aeiou]{3,}")
_CONSONANT_RUN_RE = re.compile(r"[bcdfghjklmnpqrstvwxz]{4,}")

# --- Image extraction constants ---------------------------------------------

# An embedded image smaller than this on either axis is decoration (a rule, a
# drop-cap ornament, a bullet glyph), not a figure worth carrying.
MIN_IMAGE_DIMENSION_PX = 64

# An image covering at least this share of the page area is a full-page SCAN,
# not a figure: OCR-sourced PDFs place the whole page bitmap behind the text
# layer, and carrying those would embed a photographic copy of the whole book
# (untranslated English) alongside the translated text.
#
# Measured on the two example PDFs, which look alike (both ~530 pages, both
# with a text layer — a text layer does NOT discriminate) and are opposites:
#   Active Measures  109 placements, coverage p50=0.21 p90=0.32, ONE at >=0.9
#   World Ends      1064 placements, coverage min=max=1.000, ALL at >=0.9
# The two populations do not overlap, so any cut in (0.32, 1.0) separates
# them; 0.9 keeps a genuine near-full-page plate on the figure side.
FULL_PAGE_IMAGE_AREA_RATIO = 0.9

# Book-level scan detector, applied before any image is decoded. A book where
# this share of pages carries a page-sized image is a scan front to back, so
# image extraction is skipped wholesale rather than per instance (CLAUDE.md §9:
# fix quality gates by CLASS). Deliberately uses a LOWER per-image bar than
# FULL_PAGE_IMAGE_AREA_RATIO so a scanned book whose rasters sit inside a
# margin — and would individually slip under the full-page test — is still
# caught as a book.
# Measured: World Ends 532/532 pages (100%); Active Measures 1/522 (0.2%).
SCANNED_PAGE_IMAGE_AREA_RATIO = 0.5
SCANNED_BOOK_PAGE_SHARE = 0.5

# The same image appearing on at least this many pages is furniture (a logo,
# a watermark, a chapter ornament) — mirrors RUNNING_HEAD_MIN_PAGES.
RECURRING_IMAGE_MIN_PAGES = 3

# A caption belongs to an image when it starts within this many body lines of
# the image's vertical extent — below the plate (the common case) or above it
# (older illustrated books print the figure line first).
CAPTION_PROXIMITY_LINES = 4

# EPUB 3 core image media types: anything else needs a manifest fallback to
# validate, so non-core formats are re-encoded to PNG instead of passed through.
_PDF_IMAGE_MEDIA_TYPES = {
    "png": "image/png",
    "jpeg": "image/jpeg",
    "jpg": "image/jpeg",
    "gif": "image/gif",
    "webp": "image/webp",
}
_PNG_MEDIA_TYPE = "image/png"


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


@dataclass(frozen=True)
class _PdfImage:
    """One embedded image with the geometry anchoring needs.

    Attributes:
        data: Encoded image bytes, as stored in the PDF (or re-encoded PNG).
        media_type: IANA media type of ``data``.
        page_index: Zero-based source page index.
        y0: Top edge of the image's bounding box on the page.
        y1: Bottom edge of the image's bounding box on the page.
        digest: sha1 of ``data``, used to de-duplicate repeated artwork.
    """

    data: bytes
    media_type: str
    page_index: int
    y0: float
    y1: float
    digest: str


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
        page_images = (
            []
            if _is_scanned_book(doc)
            else [_extract_page_images(doc[i], i) for i in range(doc.page_count)]
        )
        toc = doc.get_toc(simple=True) or []
        metadata = doc.metadata or {}
    title = metadata.get("title") or path.stem
    author = metadata.get("author") or ""

    running_heads = _detect_running_heads(pages)
    body_pages = [_strip_page_furniture(page, running_heads) for page in pages]
    body_size = _body_font_size(body_pages)
    blocks = _iter_blocks(body_pages, body_size, _outline_titles(toc))

    toc_starts = _toc_content_starts(toc)
    if toc_starts:
        back_start_page = _toc_back_matter_start(toc, toc_starts)
        segments, kept_blocks = _assign_toc_chapters(blocks, toc_starts, back_start_page, body_size)
    else:
        front_end = _front_matter_end_page(pages)
        back_start = _back_matter_start_page(pages)
        segments, kept_blocks = _assign_heuristic_chapters(blocks, body_size, front_end, back_start)

    images = _anchor_images(_select_images(page_images), kept_blocks, segments, body_size)

    authors = [author] if author else []
    logger.info(
        "normalized PDF %s: %d segments across %d chapters, %d images",
        path.name,
        len(segments),
        len({seg.chapter_index for seg in segments}),
        len(images),
    )
    return Book(
        title=title,
        authors=authors,
        language="en",
        source_path=str(path),
        source_format="pdf",
        segments=segments,
        images=images,
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


def _image_media_type(data: bytes, ext: str) -> tuple[bytes, str] | None:
    """Return ``(bytes, media type)`` for an extracted image, or ``None``.

    Formats outside the EPUB 3 core set (JPEG 2000, TIFF, ...) would need a
    manifest fallback to validate, so they are re-encoded to PNG instead.

    Args:
        data: Encoded image bytes as stored in the PDF.
        ext: PyMuPDF's format name for those bytes (``"png"``, ``"jpeg"``...).

    Returns:
        The (possibly re-encoded) bytes and their media type, or ``None`` if
        the image could not be decoded.
    """
    media_type = _PDF_IMAGE_MEDIA_TYPES.get(ext.lower())
    if media_type:
        return data, media_type
    try:
        pixmap = fitz.Pixmap(data)
        if pixmap.colorspace is not None and pixmap.n - pixmap.alpha > 3:
            pixmap = fitz.Pixmap(fitz.csRGB, pixmap)
        return pixmap.tobytes("png"), _PNG_MEDIA_TYPE
    except (RuntimeError, ValueError) as exc:
        logger.warning("Could not re-encode a %s image to PNG: %s", ext, exc)
        return None


def _is_scanned_book(doc: fitz.Document) -> bool:
    """True if *doc* is a page-by-page scan rather than a text PDF with figures.

    Surveys geometry only (``page.get_image_info()`` returns bounding boxes
    without materializing pixel data), so a 532-page scan costs ~0.3s to
    reject instead of decoding a thousand full-page rasters. A text layer does
    NOT discriminate — both example PDFs have one; page coverage does.

    Args:
        doc: The open PyMuPDF document.

    Returns:
        True if at least :data:`SCANNED_BOOK_PAGE_SHARE` of pages carry a
        page-sized image, in which case no image is worth extracting.
    """
    if not doc.page_count:
        return False
    scanned_pages = 0
    for index in range(doc.page_count):
        page = doc[index]
        page_area = abs(page.rect.get_area()) or 1.0
        if any(
            abs(fitz.Rect(info["bbox"]).get_area()) / page_area >= SCANNED_PAGE_IMAGE_AREA_RATIO
            for info in page.get_image_info()
        ):
            scanned_pages += 1
    share = scanned_pages / doc.page_count
    if share < SCANNED_BOOK_PAGE_SHARE:
        return False
    logger.info(
        "skipping image extraction: %.0f%% of pages (%d/%d) carry a page-sized "
        "image, so this PDF is a scan, not an illustrated text",
        share * 100,
        scanned_pages,
        doc.page_count,
    )
    return True


def _extract_page_images(page: fitz.Page, page_index: int) -> list[_PdfImage]:
    """Extract the figure-sized images embedded on one page.

    Decorative sub-:data:`MIN_IMAGE_DIMENSION_PX` artwork and full-page scans
    (see :data:`FULL_PAGE_IMAGE_AREA_RATIO`) are dropped here; cross-page
    de-duplication happens later in :func:`_select_images`.

    Args:
        page: The PyMuPDF page.
        page_index: Zero-based index of this page in the document.

    Returns:
        Kept images in reading order (top-to-bottom).
    """
    page_area = abs(page.rect.get_area()) or 1.0
    images: list[_PdfImage] = []
    for block in page.get_text("dict").get("blocks", []):
        if block.get("type") != 1:
            continue
        data = block.get("image")
        if not data:
            continue
        if min(block.get("width", 0), block.get("height", 0)) < MIN_IMAGE_DIMENSION_PX:
            continue
        bbox = fitz.Rect(block["bbox"])
        if abs(bbox.get_area()) / page_area >= FULL_PAGE_IMAGE_AREA_RATIO:
            continue
        encoded = _image_media_type(data, block.get("ext", ""))
        if encoded is None:
            continue
        payload, media_type = encoded
        images.append(
            _PdfImage(
                data=payload,
                media_type=media_type,
                page_index=page_index,
                y0=bbox.y0,
                y1=bbox.y1,
                digest=hashlib.sha1(payload).hexdigest(),
            )
        )
    images.sort(key=lambda image: (round(image.y0, 1), image.digest))
    return images


def _select_images(pages: list[list[_PdfImage]]) -> list[_PdfImage]:
    """Drop recurring furniture and repeats, keeping one copy of each image.

    An image whose bytes recur on :data:`RECURRING_IMAGE_MIN_PAGES` or more
    distinct pages is a logo/watermark and is dropped entirely; every other
    image is kept once, at its first occurrence.

    Args:
        pages: Per-page extracted images, in page order.

    Returns:
        The kept images in document order.
    """
    page_counts: Counter[str] = Counter()
    for page in pages:
        page_counts.update({image.digest for image in page})
    furniture = {
        digest for digest, count in page_counts.items() if count >= RECURRING_IMAGE_MIN_PAGES
    }

    kept: list[_PdfImage] = []
    seen: set[str] = set()
    for page in pages:
        for image in page:
            if image.digest in furniture or image.digest in seen:
                continue
            seen.add(image.digest)
            kept.append(image)
    if furniture:
        logger.info("dropped %d recurring image(s) as page furniture", len(furniture))
    return kept


def _caption_block_below(image: _PdfImage, blocks: list[_Block], body_size: float) -> int | None:
    """Index of the one caption block that unambiguously belongs to *image*.

    A caption belongs to an image when it is the ONLY caption on that page
    starting within :data:`CAPTION_PROXIMITY_LINES` body lines of the image's
    vertical extent. With zero or several candidates the geometry is
    ambiguous and nothing is re-attached.

    Captions are segments and are never moved (moving one would change its
    position and therefore its id); it is the image that moves to sit in
    front of its caption.

    Args:
        image: The image to attach a caption to.
        blocks: Kept blocks, parallel to the book's segments.
        body_size: Estimated body font size (the line-height unit).

    Returns:
        The index into *blocks* of the matching caption, or ``None``.
    """
    slack = body_size * CAPTION_PROXIMITY_LINES
    candidates = [
        index
        for index, block in enumerate(blocks)
        if block.kind is SegmentType.CAPTION
        and block.page_index == image.page_index
        and image.y0 - slack <= block.y0 <= image.y1 + slack
    ]
    return candidates[0] if len(candidates) == 1 else None


def _anchor_images(
    images: list[_PdfImage],
    blocks: list[_Block],
    segments: list[Segment],
    body_size: float,
) -> list[ImageResource]:
    """Bind each extracted image to the segment it follows.

    The default anchor is the last block starting at or above the image. When
    a caption sits just below the image (see :func:`_caption_block_below`) the
    anchor moves to the block BEFORE that caption, so the rendered caption
    once again follows its own figure.

    Args:
        images: Kept images in document order.
        blocks: Kept blocks, index-parallel to *segments*.
        segments: The book's segments in document order.
        body_size: Estimated body font size.

    Returns:
        Book-level image resources in document order.
    """
    if not segments:
        return []
    keys = [(block.page_index, block.y0) for block in blocks]
    resources: list[ImageResource] = []
    for image in images:
        anchor = bisect.bisect_right(keys, (image.page_index, image.y0)) - 1
        caption = _caption_block_below(image, blocks, body_size)
        if caption is not None:
            anchor = caption - 1
        leading = anchor < 0
        # A leading image belongs to the chapter of the segment it precedes.
        chapter_owner = segments[0] if leading else segments[anchor]
        resources.append(
            ImageResource(
                id=f"img{len(resources) + 1:04d}",
                media_type=image.media_type,
                data=image.data,
                source_href=f"page{image.page_index + 1}-{image.digest[:8]}",
                chapter_index=chapter_owner.chapter_index,
                anchor_segment_id=None if leading else chapter_owner.id,
                alt=None,
            )
        )
    return resources


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


def _is_garbled_page_number(text: str) -> bool:
    """Return True if *text* is a lone token that can only be a page number.

    Applied inside the margin bands only. OCR renders a folio as a token of
    digits and digit-lookalike glyphs ("40OI1" for 401, "Il0" for 110, "XXV1"
    for XXVI), which evades both :data:`_PAGE_NUMBER_RE` and
    :data:`_ROMAN_NUMERAL_RE`. The discriminator is *lexical content*, not
    shape: every letter run in the token must be too short to be a word, or a
    roman numeral. The predecessor of this function matched any whitespace-free
    token containing a digit, so a band line reading "COVID-19", "MiG-29" or
    "9/11-era" was destroyed with it (review finding 1).

    Args:
        text: One line's text, already stripped.

    Returns:
        True if the token is a page-number rendering and must be dropped.
    """
    if not _SINGLE_TOKEN_WITH_DIGIT_RE.match(text):
        return False
    return all(
        len(run) <= _LOOKALIKE_RUN_MAX_CHARS or _ROMAN_NUMERAL_RE.match(run)
        for run in _LETTER_RUN_RE.findall(text)
    )


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
    normalized key is a known running head, it reduces to nothing but
    digits/punctuation, it is a garbled OCR folio, or it is a bare URI
    (a scan-provenance stamp). Body lines are always kept.

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
            stripped = line.text.strip()
            if (
                _is_page_number(line.text)
                or not key
                or key in running_heads
                or _is_garbled_page_number(stripped)
                or _URI_TOKEN_RE.match(stripped)
            ):
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


def _looks_like_heading(
    line: _Line, body_size: float, outline_titles: frozenset[str] = frozenset()
) -> bool:
    """Decide whether a line is a chapter/section heading.

    A heading is either a short font-size outlier or a short line matching a
    chapter keyword / numbered-title pattern.

    Args:
        line: The candidate line.
        body_size: Estimated body font size.
        outline_titles: Normalized titles declared by the PDF's own outline
            (:func:`_outline_titles`). A line the document itself declares to
            be a chapter title is exempt from the word-run guard below —
            structural evidence from the document outranks a shape heuristic.

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
    # A purely numeric chapter title ("1984", "1917") has no real word either,
    # so it is admitted only on the document outline's own say-so — the sole
    # signal that separates such a title from a folio (review finding 1).
    if not _WORD_RUN_RE.search(text) and _outline_key(text) not in outline_titles:
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


def _outline_key(text: str) -> str:
    """Normalize a title for outline comparison: collapse space, casefold."""
    return _WHITESPACE_RE.sub(" ", text).strip().casefold()


def _outline_titles(toc: list) -> frozenset[str]:
    """Normalized titles declared by the PDF's own outline (bookmarks).

    Args:
        toc: ``doc.get_toc(simple=True)`` entries, ``[level, title, page]``.

    Returns:
        Every non-empty outline title, normalized by :func:`_outline_key`.
    """
    return frozenset(
        key for entry in toc if len(entry) >= 2 and (key := _outline_key(str(entry[1])))
    )


def _is_droppable(text: str) -> bool:
    """Return True if a reflowed BODY block must be discarded.

    Scoped to body blocks on purpose. Beyond the empty/page-number/punctuation
    cases it also drops a lone digit-bearing token, because a reflowed block
    consisting of one such token is an endnote marker or a citation fragment
    ("CINCUSAREUR.20", "p=17628.", "05:36:11", a bare download URL) — measured
    across both example PDFs, all 27 blocks it removes are of that kind.

    It must NOT be used to re-judge a line that :func:`_looks_like_heading`
    has already admitted: that line has passed a font-size-outlier or
    chapter-pattern test and carries a real word, which is evidence a body
    block does not have. Applying this predicate there is what silently lost a
    chapter titled "COVID-19" or "9/11" (review finding 1); heading admission
    uses :func:`_is_contentless` instead.
    """
    stripped = text.strip()
    if _is_contentless(stripped):
        return True
    if _is_page_number(stripped):
        return True
    return bool(_SINGLE_TOKEN_WITH_DIGIT_RE.match(stripped))


def _is_contentless(text: str) -> bool:
    """Return True if *text* carries no word character at all (or is empty)."""
    stripped = text.strip()
    return not stripped or not _WORD_RE.search(stripped)


def _heading_level(size: float, body_size: float) -> int:
    """Map a heading's font size to a level (1 for the largest, else 2)."""
    return 1 if size >= body_size * HEADING_LEVEL1_RATIO else 2


def _is_back_matter_title(title: str | None) -> bool:
    """Return True if a title names a back-matter section.

    Substring matching folds punctuated / multi-word entries such as
    ``"Notes: What Is Disinformation?"`` and ``"Endnotes"`` alongside the bare
    ``"Notes"`` / ``"Index"`` forms.
    """
    key = _normalize_head_key(title or "")
    if not key:
        return False
    return any(sub in key for sub in _BACK_MATTER_SUBSTRINGS) or key.startswith(
        _BACK_MATTER_PREFIXES
    )


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


@dataclass(frozen=True)
class _Block:
    """A logical block produced by reflow, before chapter assignment.

    Attributes:
        kind: :class:`SegmentType.HEADING` or :class:`SegmentType.PARAGRAPH`.
        text: Cleaned block text.
        page_index: Source page where the block starts.
        size: Font size (largest span) for headings; body size for paragraphs.
        y0: Top edge of the block's first line, for image anchoring.
    """

    kind: SegmentType
    text: str
    page_index: int
    size: float
    y0: float = 0.0


def _iter_blocks(
    pages: list[list[_Line]], body_size: float, outline_titles: frozenset[str] = frozenset()
) -> list[_Block]:
    """Reflow body lines into ordered heading/paragraph blocks (no chapters).

    Paragraph boundaries are drawn on headings, first-line indents, and large
    vertical gaps; everything else is merged, joining line-break hyphens.
    Paragraphs merge across page boundaries.

    Args:
        pages: Body lines per page (after furniture removal).
        body_size: Estimated body font size.
        outline_titles: Normalized titles from the PDF outline, passed through
            to :func:`_looks_like_heading`.

    Returns:
        Ordered blocks, each tagged with its start page and font size.
    """
    # Indent threshold is computed per page parity (recto/verso): OCR'd books
    # often set the two page sides at different left margins (e.g. verso x=16,
    # recto x=28), so a single global margin would read every recto body line
    # as a first-line indent and shatter paragraphs.
    parity_margins = _column_left_margins_by_parity(pages)
    indent_thresholds = {
        parity: margin + body_size * INDENT_RATIO for parity, margin in parity_margins.items()
    }

    blocks: list[_Block] = []
    buffer: list[str] = []
    buffer_page = 0
    buffer_y0 = 0.0

    def flush() -> None:
        nonlocal buffer
        if not buffer:
            return
        text = _clean_segment_text(" ".join(buffer))
        buffer = []
        if _is_droppable(text):
            return
        if _is_caption(text):
            kind = SegmentType.CAPTION
        elif _is_scene_setter(text) or _is_ocr_gibberish(text):
            # Scan residue (illustration OCR) is marked OTHER — kept in the book
            # and translated, but never sampled as prose.
            kind = SegmentType.OTHER
        else:
            kind = SegmentType.PARAGRAPH
        blocks.append(_Block(kind, text, buffer_page, body_size, buffer_y0))

    for page in pages:
        page_gap = _median_line_gap(page)
        prev_y: float | None = None
        for line in page:
            gap = None if prev_y is None else line.y0 - prev_y
            prev_y = line.y0

            if _looks_like_heading(line, body_size, outline_titles):
                flush()
                title = _clean_segment_text(line.text)
                # A line already admitted as a heading is NOT re-judged by
                # _is_droppable's body-block artifact rules; only a title with
                # no word character at all is discarded (review finding 1).
                if not _is_contentless(title):
                    blocks.append(
                        _Block(SegmentType.HEADING, title, line.page_index, line.size, line.y0)
                    )
                continue

            indent_threshold = indent_thresholds[line.page_index % 2]
            starts_new = _starts_new_paragraph(line, indent_threshold, gap, page_gap, bool(buffer))
            # Continuation override: an indent/gap that would break the paragraph
            # is ignored when the running text is clearly unfinished (no terminal
            # punctuation) and this line resumes it (starts lowercase). This
            # re-joins block quotes and prose that wraps across page/column
            # boundaries into coherent segments instead of per-line fragments.
            if (
                starts_new
                and buffer
                and _looks_incomplete(buffer[-1])
                and (_starts_lowercase(line) or buffer[-1].rstrip().endswith(","))
            ):
                starts_new = False
            if starts_new:
                flush()

            if not buffer:
                buffer_page = line.page_index
                buffer_y0 = line.y0
            elif (joined := _dehyphenate(buffer[-1], line.text)) is not None:
                buffer[-1] = joined
                continue
            buffer.append(line.text)

    flush()
    return blocks


def _is_caption(text: str) -> bool:
    """Return True if a block is a figure/source caption, not body prose.

    Two shapes qualify: a short wholly-parenthetical line
    (``"(National Diet Library)"``), or a moderate-length line ending in a
    capitalized parenthetical source credit with no closing period
    (``"...voices on disinformation. (Elizabeth Spaulding)"``). Body paragraphs
    end with sentence punctuation, so the credit shape does not catch prose.
    """
    stripped = text.strip()
    word_count = len(stripped.split())
    if word_count <= CAPTION_MAX_WORDS:
        if stripped.startswith("(") and stripped.endswith(")"):
            return True
        if _CAPTION_TAIL_RE.search(stripped):
            return True
    if word_count <= CAPTION_CREDIT_MAX_WORDS and _CAPTION_CREDIT_RE.search(stripped):
        return True
    if word_count <= CAPTION_CREDIT_MAX_WORDS and _CAPTION_POSITION_RE.search(stripped):
        return True
    if word_count <= CAPTION_CREDIT_MAX_WORDS and _CAPTION_LEAD_RE.match(stripped):
        return True
    return False


def _is_scene_setter(text: str) -> bool:
    """Return True for chapter-opening dateline/place lines, not prose.

    Examples: ``"November 2020"``, ``"Fort Meade, Maryland"``. Short, no
    terminal punctuation, and shaped like a bare date or ``Place, Region``
    line. Typed OTHER: kept (and translated) but excluded from prose QA
    sampling.
    """
    stripped = text.strip()
    if len(stripped.split()) > SCENE_SETTER_MAX_WORDS or not _looks_incomplete(stripped):
        return False
    return bool(_DATELINE_RE.match(stripped) or _PLACELINE_RE.match(stripped))


def _looks_incomplete(text: str) -> bool:
    """Return True if ``text`` does not end a sentence (mid-sentence wrap)."""
    stripped = text.rstrip().rstrip(_TRAILING_CLOSERS).rstrip()
    return bool(stripped) and stripped[-1] not in _TERMINAL_PUNCT


def _starts_lowercase(line: _Line) -> bool:
    """Return True if the line's first alphabetic character is lowercase."""
    for char in line.text:
        if char.isalpha():
            return char.islower()
    return False


def _is_front_matter_title(title: str) -> bool:
    """Return True if a title is front matter (excluded from TOC chapters).

    The empty-key case is deliberately split: ``_normalize_head_key`` drops
    digits, so a chapter genuinely titled "1984" or "1921–1945" normalizes to
    the empty key exactly like an untitled entry does. Only a title with no
    word character AT ALL is treated as untitled (review finding 1, third
    instance of the same class as the two drop paths).
    """
    if _is_contentless(title):
        return True
    key = _normalize_head_key(title)
    if not key:
        return False
    return key in _FRONT_MATTER_TITLES or key.startswith(_FRONT_MATTER_PREFIXES)


def _toc_content_starts(toc: list) -> list[tuple[int, str]]:
    """Extract content chapter starts from an embedded TOC.

    Front- and back-matter entries (title/copyright pages, notes, index, ...)
    are dropped so the count reflects reading chapters. Entries are returned as
    ``(zero_based_start_page, title)`` sorted by page, one per page.

    Args:
        toc: ``doc.get_toc(simple=True)`` output — ``[level, title, page]`` rows
            with 1-based page numbers.

    Returns:
        Content chapter starts, or an empty list if the PDF has no TOC.
    """
    starts: list[tuple[int, str]] = []
    seen_pages: set[int] = set()
    for entry in toc:
        title = str(entry[1]).strip()
        page = int(entry[2])
        if page < 1 or _is_front_matter_title(title) or _is_back_matter_title(title):
            continue
        start = page - 1
        if start in seen_pages:
            continue
        seen_pages.add(start)
        starts.append((start, title))
    starts.sort(key=lambda item: item[0])
    return starts


def _toc_back_matter_start(toc: list, content_starts: list[tuple[int, str]]) -> int | None:
    """Return the zero-based page where TOC back matter begins, or None.

    Considers only back-matter entries that fall after the last content chapter
    start, so a stray earlier match cannot swallow the body.

    Args:
        toc: ``doc.get_toc(simple=True)`` rows.
        content_starts: Content chapter starts (sorted by page).

    Returns:
        The earliest qualifying back-matter start page, or ``None`` if none.
    """
    content_max = content_starts[-1][0] if content_starts else -1
    back_pages = [
        int(entry[2]) - 1
        for entry in toc
        if int(entry[2]) >= 1
        and _is_back_matter_title(str(entry[1]))
        and int(entry[2]) - 1 > content_max
    ]
    return min(back_pages) if back_pages else None


def _front_matter_end_page(pages: list[list[_Line]]) -> int:
    """Return the first body page index, past any front-matter Contents pages.

    Args:
        pages: Extracted lines per page (before furniture removal).

    Returns:
        The index after the last "Contents" page in the opening fraction of the
        document, or 0 if none is found.
    """
    scan_limit = len(pages) * FRONT_MATTER_SCAN_FRACTION
    last_contents = -1
    for page_index, page in enumerate(pages):
        if page_index > scan_limit:
            break
        if any(_normalize_head_key(line.text) == "contents" for line in page):
            last_contents = page_index
    return last_contents + 1


def _back_matter_start_page(pages: list[list[_Line]]) -> int:
    """Return the page index where back matter (Notes/Index/...) begins.

    Detected by a running header naming a back-matter section in the closing
    fraction of the document; falls back to the page count (no back matter).

    Args:
        pages: Extracted lines per page (before furniture removal).

    Returns:
        The zero-based start page of back matter, or ``len(pages)`` if none.
    """
    scan_start = len(pages) * BACK_MATTER_SCAN_FRACTION
    markers = _BACK_MATTER_TITLES
    for page_index, page in enumerate(pages):
        if page_index < scan_start:
            continue
        for line in page:
            if line.in_band and _normalize_head_key(line.text) in markers:
                return page_index
    return len(pages)


def _is_strong_chapter(text: str, size: float, body_size: float) -> bool:
    """Return True if a heading is a high-confidence chapter/section start.

    Strong signals: a chapter-label prefix (PART/CHAPTER/PROLOGUE/...), a
    numbered title (``12. Dirty Business``), or a multi-word font-size outlier
    that is not OCR "digit soup".

    Args:
        text: Heading text.
        size: Heading font size.
        body_size: Estimated body font size.
    """
    if _CHAPTER_LABEL_RE.match(text) or _NUMBERED_TITLE_RE.match(text):
        return True
    words = text.split()
    if not (size >= body_size * HEADING_SIZE_RATIO and 2 <= len(words) <= HEADING_MAX_WORDS):
        return False
    if sum(char.isdigit() for char in text) > STRONG_TITLE_MAX_DIGITS:
        return False
    if _JUNK_SYMBOL_RE.search(text) or text.endswith((".", "!", "?")):
        return False
    return _is_title_cased(text) and _is_wordlike_title(text)


def _is_title_cased(text: str) -> bool:
    """Return True if most alphabetic words start uppercase (Title Case/CAPS)."""
    words = _ALPHA_WORD_RE.findall(text)
    if len(words) < 2:
        return False
    capitalized = sum(1 for word in words if word[0].isupper())
    return capitalized / len(words) >= TITLE_CASE_MIN_RATIO


def _is_wordlike_title(text: str) -> bool:
    """Return True if every word looks like a real word (not OCR gibberish).

    Rejects titles containing a multi-letter vowelless token ("Tg", "Pst"), a
    run of 3+ vowels ("Uiindicaerces"), or 4+ consonants ("dhstenus") — patterns
    that do not occur in real English titles but pepper OCR debris.
    """
    words = _ALPHA_WORD_RE.findall(text)
    for word in words:
        lowered = word.lower()
        if len(lowered) >= 2 and not any(vowel in lowered for vowel in "aeiouy"):
            return False
        if _VOWEL_RUN_RE.search(lowered) or _CONSONANT_RUN_RE.search(lowered):
            return False
    # A real multi-word title has substance: reject when most words are tiny
    # (OCR debris such as "Or iar Ta", "As Sle al").
    short = sum(1 for word in words if len(word) <= SHORT_WORD_MAX_LEN)
    return not (words and short / len(words) > SHORT_WORD_MAX_RATIO)


def _prefer_title(current: str | None, candidate: str) -> str:
    """Pick the more descriptive of two adjacent heading titles.

    A bare label ("CHAPTER 2", "PART I") is superseded by a following
    descriptive title ("The Fucking Salmon"); otherwise the current title
    stands.
    """
    if current and _CHAPTER_LABEL_RE.match(current) and not _CHAPTER_LABEL_RE.match(candidate):
        return candidate
    return current or candidate


def _assign_toc_chapters(
    blocks: list[_Block],
    starts: list[tuple[int, str]],
    back_start: int | None,
    body_size: float,
) -> tuple[list[Segment], list[_Block]]:
    """Assign chapters from an embedded TOC: each block inherits its page's start.

    Blocks before the first content start are chapter 0 (front matter); a block
    on or after the i-th content start (1-based) is chapter i with that title;
    blocks from ``back_start`` onward fold into one trailing back-matter chapter
    (so notes/index prose is recognizable and excluded from prose sampling).

    Args:
        blocks: Ordered reflow blocks.
        starts: Content chapter starts from :func:`_toc_content_starts`.
        back_start: Page where back matter begins, or ``None`` for no back matter.
        body_size: Estimated body font size (for heading levels).

    Returns:
        Ordered segments with TOC-driven chapter indices and titles, plus the
        blocks that produced them (index-parallel, for image anchoring).
    """
    start_pages = [page for page, _ in starts]
    back_chapter_index = len(starts) + 1
    segments: list[Segment] = []
    kept_blocks: list[_Block] = []
    for block in blocks:
        if back_start is not None and block.page_index >= back_start:
            chapter_index, chapter_title = back_chapter_index, BACK_MATTER_CHAPTER_TITLE
        else:
            index = bisect.bisect_right(start_pages, block.page_index)
            if index == 0:
                # Front matter: fold under a canonical title and drop OCR debris.
                if _is_ocr_gibberish(block.text):
                    continue
                chapter_index, chapter_title = 0, FRONT_MATTER_CHAPTER_TITLE
            else:
                chapter_index, chapter_title = index, starts[index - 1][1]
        segments.append(
            _segment_from_block(block, chapter_index, chapter_title, len(segments), body_size)
        )
        kept_blocks.append(block)
    return segments, kept_blocks


def _assign_heuristic_chapters(
    blocks: list[_Block], body_size: float, front_end: int, back_start: int
) -> tuple[list[Segment], list[_Block]]:
    """Assign chapters without a TOC, minting only inside the body bracket.

    Front matter (before ``front_end``) is chapter 0; back matter (from
    ``back_start``) folds into a single trailing chapter; between them, each
    high-confidence heading opens a new chapter.

    Args:
        blocks: Ordered reflow blocks.
        body_size: Estimated body font size.
        front_end: First body page index (front matter ends before it).
        back_start: Page index where back matter begins.

    Returns:
        Ordered segments with bracketed chapter indices and titles, plus the
        blocks that produced them (index-parallel, for image anchoring).
    """
    segments: list[Segment] = []
    kept_blocks: list[_Block] = []
    chapter_index = 0
    chapter_title: str | None = FRONT_MATTER_CHAPTER_TITLE
    phase = "front"
    # A chapter number ("CHAPTER 2") and its title ("The Fucking Salmon") are
    # separate heading lines; ``just_minted`` (no paragraph seen since the last
    # mint) merges the pair into one chapter instead of counting both.
    just_minted = False
    for block in blocks:
        if phase != "back" and block.page_index >= back_start:
            phase = "back"
            chapter_index += 1
            chapter_title = BACK_MATTER_CHAPTER_TITLE
            just_minted = False
        is_chapter = block.kind is SegmentType.HEADING and _is_strong_chapter(
            block.text, block.size, body_size
        )
        if is_chapter and phase == "front" and block.page_index >= front_end:
            phase = "body"
            chapter_index += 1
            chapter_title = block.text
            just_minted = True
        elif is_chapter and phase == "body":
            if just_minted:
                chapter_title = _prefer_title(chapter_title, block.text)
            else:
                chapter_index += 1
                chapter_title = block.text
                just_minted = True
        # Drop cover-art OCR debris from the folded front-matter chapter (kept
        # real front-matter text stays; body prose is never in this phase).
        if phase == "front" and _is_ocr_gibberish(block.text):
            continue
        segments.append(
            _segment_from_block(block, chapter_index, chapter_title, len(segments), body_size)
        )
        kept_blocks.append(block)
        if block.kind is SegmentType.PARAGRAPH:
            just_minted = False
    return segments, kept_blocks


def _is_real_word(token: str) -> bool:
    """Return True if ``token`` looks like a real word (not OCR debris).

    A real word has at least :data:`GIBBERISH_REAL_WORD_MIN_LEN` letters, a
    vowel, and no gibberish letter-runs (3+ vowels / 4+ consonants).
    """
    letters = "".join(char for char in token if char.isalpha()).lower()
    if len(letters) < GIBBERISH_REAL_WORD_MIN_LEN:
        return False
    if not any(vowel in letters for vowel in "aeiouy"):
        return False
    return not (_VOWEL_RUN_RE.search(letters) or _CONSONANT_RUN_RE.search(letters))


def _is_ocr_gibberish(text: str) -> bool:
    """Return True if a block is OCR debris rather than real text.

    Used ONLY to prune folded front-matter chapters (cover-art scan noise like
    ``"Pel OO ays por Umi"``, ``"wa"``). Real front-matter lines
    (``"Bloomsbury Publishing Inc."``) clear the real-word bar and are kept.
    Never applied to body prose.
    """
    tokens = text.split()
    if not tokens:
        return True
    real_words = sum(1 for token in tokens if _is_real_word(token))
    return real_words < max(1, len(tokens) * GIBBERISH_MIN_REAL_WORD_RATIO)


def _segment_from_block(
    block: _Block,
    chapter_index: int,
    chapter_title: str | None,
    position: int,
    body_size: float,
) -> Segment:
    """Build a :class:`Segment` from a block and its assigned chapter."""
    heading_level = (
        _heading_level(block.size, body_size) if block.kind is SegmentType.HEADING else None
    )
    return _build_segment(
        block.kind, block.text, chapter_index, chapter_title, position, heading_level
    )


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


def _column_left_margins_by_parity(pages: list[list[_Line]]) -> dict[int, float]:
    """Estimate the modal left margin separately for even/odd (verso/recto) pages.

    Aggregating by page parity across the whole document gives a robust modal
    ``x0`` per side even when individual pages have few lines, and captures the
    asymmetric margins some OCR'd books use. Falls back to the document-wide
    margin for a parity with no lines.

    Args:
        pages: Body lines per page.

    Returns:
        A ``{0: margin, 1: margin}`` map keyed by ``page_index % 2``.
    """
    counts: dict[int, Counter[float]] = {0: Counter(), 1: Counter()}
    for page in pages:
        for line in page:
            counts[line.page_index % 2][round(line.x0)] += 1
    fallback = _column_left_margin(pages)
    return {
        parity: (float(counter.most_common(1)[0][0]) if counter else fallback)
        for parity, counter in counts.items()
    }
