"""Assemble stage: render a translated :class:`~berilo.models.Book` to EPUB.

Builds an EPUB 3 package by hand with the standard library only
(``zipfile`` + string-templated XHTML/OPF, no third-party EPUB writer): a
stored ``mimetype`` entry first, ``META-INF/container.xml``,
``OEBPS/content.opf`` (metadata + manifest + spine), an EPUB3
``OEBPS/nav.xhtml`` table of contents, one XHTML chapter file per book
chapter, and a small shared ``OEBPS/stylesheet.css``. In ``bilingual`` mode
(requires a ``source_book`` whose segment ids/order exactly match the
translated book — segment integrity is validated up front) every rendered
block is followed by its source-language counterpart as a subtly styled
``<p class="source">``.

**Determinism.** The translate cache (S1.5) depends on re-running assembly
against the same ``Book`` producing byte-identical output, so nothing here
may vary run-to-run: ``dcterms:modified`` is a fixed constant (not
"now"), the OPF ``dc:identifier`` is a UUID5 derived deterministically from
stable book metadata (not ``uuid4``), zip entry timestamps are pinned to the
DOS epoch (1980-01-01), and entries are written in a fixed order
(mimetype, container, opf, nav, stylesheet, chapters in spine order).
"""

from __future__ import annotations

import re
import uuid
import zipfile
from pathlib import Path

from berilo.models import Book, Segment, SegmentType

# Namespace UUID used to derive a stable per-book dc:identifier (arbitrary
# fixed constant, not looked up anywhere -- only its stability matters).
_ID_NAMESPACE = uuid.UUID("2c1e7b0a-2f3e-4a9b-8a3b-6a5b0c9d7e1f")

# Fixed dcterms:modified: real wall-clock time would break byte-identical
# re-assembly of the same Book (see module docstring).
_FIXED_MODIFIED = "2000-01-01T00:00:00Z"

# DOS epoch: the earliest date zipfile.ZipInfo can represent, used for every
# entry so re-runs produce identical zip bytes regardless of *when* they run.
_ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)

_MIMETYPE = b"application/epub+zip"

# Inline emphasis subset produced by the normalizer/translator (see
# berilo.normalize.epub._EMPHASIS_TAGS); anything else in Segment.text is
# literal content that must be escaped, not markup.
_INLINE_TAG_RE = re.compile(r"</?(?:em|strong|i|b|sub|sup)>", re.IGNORECASE)

_HEADING_MIN_LEVEL = 1
_HEADING_MAX_LEVEL = 3

_ChapterGroup = tuple[int, "str | None", list[Segment]]


def _escape_text(text: str) -> str:
    """XML-escape ``&`` and ``<`` in plain text content."""
    return text.replace("&", "&amp;").replace("<", "&lt;")


def _render_inline(text: str) -> str:
    """Render a segment's inline HTML subset as XML-safe XHTML.

    Literal text is escaped (``&``, ``<``); the whitelisted emphasis tags
    (``em``/``strong``/``i``/``b``/``sub``/``sup``) are passed through,
    lower-cased. If the tags in *text* are unbalanced, mismatched, or left
    unclosed, the whole segment falls back to fully-escaped plain text --
    losing the emphasis markup is preferred over risking invalid XHTML.

    Args:
        text: Raw ``Segment.text``, possibly containing inline markup.

    Returns:
        XML-safe string suitable for direct embedding in element content.
    """
    tags = _INLINE_TAG_RE.findall(text)
    if not tags:
        return _escape_text(text)

    literals = _INLINE_TAG_RE.split(text)
    stack: list[str] = []
    rendered: list[str] = []
    for index, literal in enumerate(literals):
        rendered.append(_escape_text(literal))
        if index >= len(tags):
            continue
        tag = tags[index]
        name = tag.strip("</>").lower()
        if tag.startswith("</"):
            if not stack or stack[-1] != name:
                return _escape_text(text)
            stack.pop()
            rendered.append(f"</{name}>")
        else:
            stack.append(name)
            rendered.append(f"<{name}>")

    if stack:
        return _escape_text(text)
    return "".join(rendered)


def _group_chapters(book: Book) -> list[_ChapterGroup]:
    """Group ``book.segments`` into chapters, preserving document order.

    Args:
        book: The book whose segments to group.

    Returns:
        ``(chapter_index, chapter_title, segments)`` tuples, in the order
        each ``chapter_index`` first appears in ``book.segments``.
    """
    chapters: dict[int, list[Segment]] = {}
    order: list[int] = []
    for segment in book.segments:
        if segment.chapter_index not in chapters:
            chapters[segment.chapter_index] = []
            order.append(segment.chapter_index)
        chapters[segment.chapter_index].append(segment)
    return [(index, chapters[index][0].chapter_title, chapters[index]) for index in order]


def _chapter_label(title: str | None, position: int) -> str:
    """Return *title*, or a ``"Chapter N"`` fallback when it is missing."""
    return title if title else f"Chapter {position}"


def _validate_bilingual_alignment(book: Book, source_book: Book) -> None:
    """Verify ``book`` and ``source_book`` have identical segment ids/order.

    Segment integrity is canon (CLAUDE.md §2): a bilingual EPUB must pair
    every translated segment with the exact source segment it was
    translated from, never a positional guess.

    Args:
        book: The translated book.
        source_book: The corresponding source-language book.

    Raises:
        ValueError: If the segment counts differ, or the first mismatched
            segment id, citing its position and both ids.
    """
    if len(book.segments) != len(source_book.segments):
        raise ValueError(
            "Bilingual segment count mismatch: translated book has "
            f"{len(book.segments)} segments, source_book has {len(source_book.segments)}"
        )
    for index, (target, source) in enumerate(zip(book.segments, source_book.segments)):
        if target.id != source.id:
            raise ValueError(
                f"Bilingual segment id mismatch at position {index}: "
                f"translated segment id={target.id!r} vs source_book segment id={source.id!r}"
            )


def _render_chapter_body(segments: list[Segment], source_by_id: dict[str, Segment] | None) -> str:
    """Render one chapter's XHTML ``<body>`` inner markup from its segments.

    Consecutive ``LIST_ITEM`` segments are grouped into a single ``<ul>``.
    When *source_by_id* is given (bilingual mode), every rendered block is
    followed by its aligned source segment rendered as
    ``<p class="source">`` -- nested inside the ``<li>`` for list items so
    the list stays well-formed, a sibling element everywhere else. Headings
    are never duplicated as a second heading: the translated heading tag is
    followed by a plain ``p.source``, matching every other block type.

    Args:
        segments: One chapter's segments, in document order.
        source_by_id: ``{segment.id: source Segment}`` map, or ``None`` for
            a monolingual (translated-only) render.

    Returns:
        The concatenated inner XHTML for the chapter's ``<body>``.
    """
    parts: list[str] = []
    first_heading_rendered = False
    pending_list: list[Segment] = []

    def source_paragraph(segment: Segment) -> str:
        if source_by_id is None:
            return ""
        source = source_by_id[segment.id]
        return f'<p class="source">{_render_inline(source.text)}</p>'

    def flush_list() -> None:
        if not pending_list:
            return
        items = "".join(
            f"<li>{_render_inline(item.text)}{source_paragraph(item)}</li>" for item in pending_list
        )
        parts.append(f"<ul>{items}</ul>")
        pending_list.clear()

    for segment in segments:
        if segment.type == SegmentType.LIST_ITEM:
            pending_list.append(segment)
            continue
        flush_list()

        inline = _render_inline(segment.text)
        if segment.type == SegmentType.HEADING:
            level = segment.heading_level
            if level is None:
                level = 1 if not first_heading_rendered else 2
            level = min(max(level, _HEADING_MIN_LEVEL), _HEADING_MAX_LEVEL)
            first_heading_rendered = True
            parts.append(f"<h{level}>{inline}</h{level}>")
        elif segment.type == SegmentType.BLOCKQUOTE:
            parts.append(f"<blockquote><p>{inline}</p></blockquote>")
        elif segment.type == SegmentType.CAPTION:
            parts.append(f'<p class="caption">{inline}</p>')
        else:  # PARAGRAPH, OTHER
            parts.append(f"<p>{inline}</p>")

        parts.append(source_paragraph(segment))

    flush_list()
    return "".join(parts)


def _chapter_xhtml(lang: str, title: str, body_inner: str) -> bytes:
    """Render a full chapter XHTML5 document."""
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<html xmlns="http://www.w3.org/1999/xhtml" '
        f'xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="{lang}">\n'
        "<head>\n"
        f"<title>{_escape_text(title)}</title>\n"
        '<link rel="stylesheet" type="text/css" href="stylesheet.css"/>\n'
        "</head>\n"
        f"<body>\n{body_inner}\n</body>\n"
        "</html>\n"
    ).encode()


def _nav_xhtml(book: Book, chapters: list[_ChapterGroup], chapter_hrefs: dict[int, str]) -> bytes:
    """Render the EPUB3 ``nav.xhtml`` table of contents."""
    entries = []
    for position, (chapter_index, title, _segments) in enumerate(chapters, start=1):
        label = _chapter_label(title, position)
        href = chapter_hrefs[chapter_index]
        entries.append(f'<li><a href="{href}">{_escape_text(label)}</a></li>')
    entries_xml = "".join(entries)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<html xmlns="http://www.w3.org/1999/xhtml" '
        f'xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="{book.language}">\n'
        "<head>\n"
        f"<title>{_escape_text(book.title)}</title>\n"
        '<link rel="stylesheet" type="text/css" href="stylesheet.css"/>\n'
        "</head>\n"
        "<body>\n"
        '<nav epub:type="toc" id="toc">\n'
        "<h1>Contents</h1>\n"
        f"<ol>{entries_xml}</ol>\n"
        "</nav>\n"
        "</body>\n"
        "</html>\n"
    ).encode()


def _book_identifier(book: Book) -> str:
    """Derive a stable ``urn:uuid:`` identifier for *book* from its metadata."""
    seed = f"berilo:{book.source_path}:{book.title}:{book.language}"
    return f"urn:uuid:{uuid.uuid5(_ID_NAMESPACE, seed)}"


def _content_opf(book: Book, chapters: list[_ChapterGroup], chapter_hrefs: dict[int, str]) -> bytes:
    """Render ``content.opf``: metadata, manifest, and spine."""
    lang = book.language
    dc_title = f"[{lang.upper()}] {book.title}"
    creators = "".join(
        f"<dc:creator>{_escape_text(author)}</dc:creator>" for author in book.authors
    )

    manifest_items = [
        '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
        '<item id="css" href="stylesheet.css" media-type="text/css"/>',
    ]
    spine_items = []
    for position, (chapter_index, _title, _segments) in enumerate(chapters, start=1):
        item_id = f"chap{position:04d}"
        href = chapter_hrefs[chapter_index]
        manifest_items.append(
            f'<item id="{item_id}" href="{href}" media-type="application/xhtml+xml"/>'
        )
        spine_items.append(f'<itemref idref="{item_id}"/>')

    manifest_xml = "".join(manifest_items)
    spine_xml = "".join(spine_items)

    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" '
        f'unique-identifier="bookid" xml:lang="{lang}">\n'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" '
        'xmlns:dcterms="http://purl.org/dc/terms/">\n'
        f'<dc:identifier id="bookid">{_book_identifier(book)}</dc:identifier>\n'
        f"<dc:title>{_escape_text(dc_title)}</dc:title>\n"
        f"{creators}"
        f"<dc:language>{_escape_text(lang)}</dc:language>\n"
        f'<meta property="dcterms:modified">{_FIXED_MODIFIED}</meta>\n'
        "</metadata>\n"
        f"<manifest>{manifest_xml}</manifest>\n"
        f"<spine>{spine_xml}</spine>\n"
        "</package>\n"
    ).encode()


_CONTAINER_XML = (
    b'<?xml version="1.0" encoding="UTF-8"?>\n'
    b'<container version="1.0" '
    b'xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n'
    b"<rootfiles>\n"
    b'<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>\n'
    b"</rootfiles>\n"
    b"</container>\n"
)

_STYLESHEET_CSS = (
    b"body { font-family: serif; }\n"
    b"p.caption { font-style: italic; font-size: 0.9em; }\n"
    b"p.source { color: #666666; font-size: 0.85em; margin-top: 0; margin-bottom: 1em; }\n"
)


def _write_zip(output_path: Path, entries: list[tuple[str, bytes, int]]) -> None:
    """Write *entries* to *output_path* as an EPUB-conformant zip archive.

    Every entry gets a fixed (1980-01-01) timestamp so byte-identical
    re-assembly of the same book doesn't depend on wall-clock time.

    Args:
        output_path: Destination ``.epub`` path.
        entries: ``(zip-internal name, data, compress_type)`` tuples, in the
            exact order they should be written (mimetype must be first).
    """
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, "w") as archive:
        for name, data, compress_type in entries:
            info = zipfile.ZipInfo(name, date_time=_ZIP_EPOCH)
            info.compress_type = compress_type
            archive.writestr(info, data)


def build_epub(
    book: Book,
    output_path: str | Path,
    *,
    bilingual: bool = False,
    source_book: Book | None = None,
) -> Path:
    """Render a translated ``book`` to a reader-ready EPUB 3 file.

    Args:
        book: The translated book to render (its segments' text is the
            content that ends up in the output; chapters/nav/spine order
            follow ``book.segments`` document order).
        output_path: Destination ``.epub`` path.
        bilingual: If True, include the source-language text alongside each
            translated block, styled as ``p.source``. Requires
            ``source_book``.
        source_book: The untranslated source book, required when
            ``bilingual`` is True. Its segments must have identical ids and
            order to ``book.segments`` (see :func:`_validate_bilingual_alignment`).

    Returns:
        *output_path*, as a :class:`~pathlib.Path`.

    Raises:
        ValueError: If ``bilingual`` is True and ``source_book`` is missing,
            or if ``book``/``source_book`` segments don't align 1:1.
    """
    output_path = Path(output_path)

    source_by_id: dict[str, Segment] | None = None
    if bilingual:
        if source_book is None:
            raise ValueError("bilingual=True requires source_book")
        _validate_bilingual_alignment(book, source_book)
        source_by_id = {segment.id: segment for segment in source_book.segments}

    chapters = _group_chapters(book)
    chapter_hrefs = {
        chapter_index: f"chap_{position:04d}.xhtml"
        for position, (chapter_index, _title, _segments) in enumerate(chapters, start=1)
    }

    entries: list[tuple[str, bytes, int]] = [
        ("mimetype", _MIMETYPE, zipfile.ZIP_STORED),
        ("META-INF/container.xml", _CONTAINER_XML, zipfile.ZIP_DEFLATED),
        ("OEBPS/content.opf", _content_opf(book, chapters, chapter_hrefs), zipfile.ZIP_DEFLATED),
        ("OEBPS/nav.xhtml", _nav_xhtml(book, chapters, chapter_hrefs), zipfile.ZIP_DEFLATED),
        ("OEBPS/stylesheet.css", _STYLESHEET_CSS, zipfile.ZIP_DEFLATED),
    ]
    for position, (chapter_index, title, segments) in enumerate(chapters, start=1):
        label = _chapter_label(title, position)
        body_inner = _render_chapter_body(segments, source_by_id)
        href = chapter_hrefs[chapter_index]
        entries.append(
            (
                f"OEBPS/{href}",
                _chapter_xhtml(book.language, label, body_inner),
                zipfile.ZIP_DEFLATED,
            )
        )

    _write_zip(output_path, entries)
    return output_path
