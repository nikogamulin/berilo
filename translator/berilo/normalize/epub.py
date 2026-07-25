"""EPUB normalizer: parse EPUB packages into an ordered :class:`Book`.

EPUB is the structured, "easy path": it is parsed directly with the standard
library (``zipfile`` + ``xml.etree.ElementTree``), no third-party EPUB
library is required. The OPF package document's spine gives document order;
``toc.ncx`` (falling back to the EPUB3 nav document) supplies chapter
titles. Each spine document's body is walked for block-level content
(headings, paragraphs, list items, blockquotes, captions); a small inline
emphasis subset (``em``/``strong``/``i``/``b``/``sub``/``sup``) is retained
as HTML in :attr:`~berilo.models.Segment.text`, every other tag is
unwrapped to its text content.
"""

from __future__ import annotations

import logging
import posixpath
import re
import zipfile
from collections import Counter
from collections.abc import Callable
from pathlib import Path
from xml.etree import ElementTree as ET

from berilo.models import Book, Segment, SegmentType, make_segment_id

logger = logging.getLogger(__name__)

_CONTAINER_PATH = "META-INF/container.xml"

# Inline elements whose semantics are preserved as HTML in Segment.text;
# every other inline/structural tag is unwrapped to its bare text content.
_EMPHASIS_TAGS = {"em", "strong", "i", "b", "sub", "sup"}

_HEADING_LEVELS = {f"h{level}": level for level in range(1, 7)}

# Block-level elements that become their own Segment. A matched element is
# not descended into further: its full text (including nested inline
# markup) is captured in one pass by _block_text().
_BLOCK_TAG_TYPES: dict[str, SegmentType] = {
    "p": SegmentType.PARAGRAPH,
    "li": SegmentType.LIST_ITEM,
    "blockquote": SegmentType.BLOCKQUOTE,
    "figcaption": SegmentType.CAPTION,
    "caption": SegmentType.CAPTION,
    **dict.fromkeys(_HEADING_LEVELS, SegmentType.HEADING),
}

# Elements never walked for content: EPUB navigation documents (table of
# contents / landmarks) are structural, not book prose.
_SKIP_TAGS = {"nav"}

_WHITESPACE_RE = re.compile(r"\s+")

# Bold markup, as either a tag or a class token. Calibre MOBI→EPUB conversions
# emit no <h1>-<h6> at all: every heading is a <p> whose text sits inside a
# <span class="bold">. Such a paragraph is structurally a heading, so it is
# RETYPED (never dropped — the 1:1 source↔target mapping must hold) and thereby
# leaves the PARAGRAPH prose pool that rubric T2/T3 samples.
_BOLD_TAGS = {"b", "strong"}
_BOLD_CLASS_TOKENS = {"bold", "strong"}

# A wholly-bold paragraph longer than this is emphasized prose, not a heading.
_HEADING_LIKE_MAX_CHARS = 120

# Heading level given to a retyped heading-like paragraph. Fixed (rather than
# inferred) so the type round-trips through assemble.py's <h2> unchanged.
_RETYPED_HEADING_LEVEL = 2

# Emit a loud, actionable error when this share of a book's segments collapses
# onto one chapter title: that means chapter titles never resolved, and every
# downstream consumer that keys on them (rubric T's front/back-matter fold,
# prose sampling) is silently inert.
_TITLE_COLLAPSE_WARN_SHARE = 0.5


def _local(tag: str) -> str:
    """Strip an XML namespace prefix, returning the bare lowercase tag name."""
    return tag.rsplit("}", 1)[-1].lower()


def _strip_fragment(href: str) -> str:
    """Drop a ``#fragment`` suffix from an href."""
    return href.split("#", 1)[0]


def _resolve(base_dir: str, href: str) -> str:
    """Resolve *href* relative to *base_dir* into a normalized zip-internal path."""
    return posixpath.normpath(posixpath.join(base_dir, href))


def _find_opf_path(archive: zipfile.ZipFile) -> str:
    """Locate the OPF package document via ``META-INF/container.xml``.

    Args:
        archive: The open EPUB zip archive.

    Returns:
        The zip-internal path to the OPF package document.

    Raises:
        ValueError: If the container declares no OPF rootfile.
    """
    container_root = ET.fromstring(archive.read(_CONTAINER_PATH))
    for element in container_root.iter():
        if _local(element.tag) == "rootfile":
            full_path = element.get("full-path")
            if full_path:
                return full_path
    raise ValueError(f"No OPF rootfile declared in {_CONTAINER_PATH}")


def _read_metadata(opf_root: ET.Element) -> tuple[str, list[str], str]:
    """Extract title, authors, and language from an OPF ``<metadata>`` block."""
    title = ""
    authors: list[str] = []
    language = ""
    for element in opf_root.iter():
        tag = _local(element.tag)
        text = (element.text or "").strip()
        if tag == "title" and not title:
            title = text
        elif tag == "creator" and text:
            authors.append(text)
        elif tag == "language" and not language:
            language = text
    return title, authors, language


def _read_manifest(opf_root: ET.Element, opf_dir: str) -> dict[str, str]:
    """Map manifest item ``id`` to its zip-internal path, resolved against *opf_dir*."""
    manifest: dict[str, str] = {}
    for element in opf_root.iter():
        if _local(element.tag) != "item":
            continue
        item_id = element.get("id")
        href = element.get("href")
        if item_id and href:
            manifest[item_id] = _resolve(opf_dir, href)
    return manifest


def _read_spine(opf_root: ET.Element, manifest: dict[str, str]) -> list[str]:
    """Return spine document paths (document order), resolved via *manifest*."""
    hrefs: list[str] = []
    for spine in opf_root.iter():
        if _local(spine.tag) != "spine":
            continue
        for itemref in spine:
            if _local(itemref.tag) != "itemref":
                continue
            href = manifest.get(itemref.get("idref") or "")
            if href:
                hrefs.append(href)
        break
    return hrefs


def _parse_ncx_titles(data: bytes, ncx_dir: str) -> dict[str, str]:
    """Extract ``{document path: chapter title}`` from a ``toc.ncx`` document."""
    root = ET.fromstring(data)
    titles: dict[str, str] = {}
    for nav_point in root.iter():
        if _local(nav_point.tag) != "navpoint":
            continue
        label: str | None = None
        src: str | None = None
        for child in nav_point:
            child_tag = _local(child.tag)
            if child_tag == "navlabel":
                text_element = next((c for c in child if _local(c.tag) == "text"), None)
                if text_element is not None and text_element.text:
                    label = text_element.text.strip()
            elif child_tag == "content":
                src = child.get("src")
        if label and src:
            titles.setdefault(_resolve(ncx_dir, _strip_fragment(src)), label)
    return titles


def _parse_nav_titles(data: bytes, nav_dir: str) -> dict[str, str]:
    """Extract ``{document path: chapter title}`` from an EPUB3 nav document's toc."""
    root = ET.fromstring(data)
    toc_nav = None
    for element in root.iter():
        if _local(element.tag) != "nav":
            continue
        epub_type = next((v for k, v in element.attrib.items() if _local(k) == "type"), "")
        if "toc" in epub_type.split():
            toc_nav = element
            break
    if toc_nav is None:
        return {}

    titles: dict[str, str] = {}
    for anchor in toc_nav.iter():
        if _local(anchor.tag) != "a":
            continue
        href = anchor.get("href")
        text = "".join(anchor.itertext()).strip()
        if href and text:
            titles.setdefault(_resolve(nav_dir, _strip_fragment(href)), text)
    return titles


def _locate_archive_member(
    archive: zipfile.ZipFile, href: str, *, suffix: str | None = None
) -> str | None:
    """Resolve a manifest *href* to an entry that actually exists in *archive*.

    Manifests can name a TOC document that is absent from the archive under
    that name (repackaged books rename the file but not the manifest entry).
    The lookup widens in three steps: exact path, same basename anywhere in
    the archive, then — if *suffix* is given and exactly one entry has it —
    that unique entry.

    Args:
        archive: The open EPUB zip archive.
        href: Zip-internal path as declared by the OPF manifest.
        suffix: Optional lowercase filename suffix (e.g. ``".ncx"``) used for
            the last-resort unique-match lookup.

    Returns:
        The resolved zip-internal path, or ``None`` if no candidate matches.
    """
    names = archive.namelist()
    if href in names:
        return href
    basename = posixpath.basename(href).lower()
    by_basename = [name for name in names if posixpath.basename(name).lower() == basename]
    if by_basename:
        return by_basename[0]
    if suffix:
        by_suffix = [name for name in names if name.lower().endswith(suffix)]
        if len(by_suffix) == 1:
            return by_suffix[0]
    return None


def _titles_from_member(
    archive: zipfile.ZipFile,
    href: str,
    parse: Callable[[bytes, str], dict[str, str]],
    *,
    label: str,
    suffix: str | None = None,
) -> dict[str, str]:
    """Parse chapter titles out of one TOC document, tolerating a renamed file.

    Args:
        archive: The open EPUB zip archive.
        href: Zip-internal path as declared by the OPF manifest.
        parse: ``(data, base_dir) -> {document path: title}`` parser.
        label: Human label for log messages (``"toc.ncx"`` / ``"nav document"``).
        suffix: Passed through to :func:`_locate_archive_member`.

    Returns:
        The parsed title map, empty if the document is missing or unparseable.
    """
    located = _locate_archive_member(archive, href, suffix=suffix)
    if located is None:
        logger.warning("%s declared at %s is absent from the archive", label, href)
        return {}
    if located != href:
        logger.warning("%s declared at %s resolved to %s in the archive", label, href, located)
    try:
        return parse(archive.read(located), posixpath.dirname(located))
    except (KeyError, ET.ParseError) as exc:
        logger.warning("Could not parse %s at %s: %s", label, located, exc)
        return {}


def _read_toc_titles(
    archive: zipfile.ZipFile, opf_root: ET.Element, opf_dir: str
) -> dict[str, str]:
    """Build a ``{document path: chapter title}`` map from ``toc.ncx`` or the nav doc.

    Manifest hrefs are relative to the OPF package document, so they are
    resolved against *opf_dir* exactly like every other manifest item. Falls
    through to the nav document whenever ``toc.ncx`` is missing, unparseable,
    *or* yields no titles at all — an empty NCX is as useless as an absent one.
    """
    manifest_items = [element for element in opf_root.iter() if _local(element.tag) == "item"]

    ncx_href = next(
        (
            item.get("href")
            for item in manifest_items
            if item.get("media-type") == "application/x-dtbncx+xml"
        ),
        None,
    )
    if ncx_href:
        titles = _titles_from_member(
            archive,
            _resolve(opf_dir, ncx_href),
            _parse_ncx_titles,
            label="toc.ncx",
            suffix=".ncx",
        )
        if titles:
            return titles

    nav_href = next(
        (
            item.get("href")
            for item in manifest_items
            if "nav" in (item.get("properties") or "").split()
        ),
        None,
    )
    if nav_href:
        titles = _titles_from_member(
            archive, _resolve(opf_dir, nav_href), _parse_nav_titles, label="nav document"
        )
        if titles:
            return titles

    return {}


def _find_body(root: ET.Element) -> ET.Element | None:
    """Return the ``<body>`` element of an XHTML document tree, if present."""
    for element in root.iter():
        if _local(element.tag) == "body":
            return element
    return None


def _is_bold_marked(element: ET.Element) -> bool:
    """True if *element* renders its content bold, by tag or by class token."""
    if _local(element.tag) in _BOLD_TAGS:
        return True
    return bool(_BOLD_CLASS_TOKENS & set((element.get("class") or "").lower().split()))


def _unmarked_text(element: ET.Element) -> str:
    """Return *element*'s visible text that lies OUTSIDE any bold-marked subtree."""
    parts = [element.text or ""]
    for child in element:
        if not _is_bold_marked(child):
            parts.append(_unmarked_text(child))
        parts.append(child.tail or "")
    return _WHITESPACE_RE.sub(" ", "".join(parts)).strip()


def _is_heading_like(element: ET.Element) -> bool:
    """True if a ``<p>`` is structurally a heading: short and wholly bold.

    "Wholly bold" means every character of visible text sits inside a bold tag
    or a bold-classed element — an emphasized phrase inside a sentence leaves
    text outside the marked subtree and so does not qualify.
    """
    if not any(
        _is_bold_marked(descendant) for descendant in element.iter() if descendant is not element
    ):
        return False
    if _unmarked_text(element):
        return False
    return 0 < len(_block_text(element)) <= _HEADING_LIKE_MAX_CHARS


def _iter_blocks(element: ET.Element):
    """Yield ``(SegmentType, heading_level, element)`` for each block-level
    descendant of *element*, in document order.

    Does not descend into a matched block's own children (its text is fully
    captured by :func:`_block_text`) nor into ``_SKIP_TAGS`` elements (EPUB
    navigation documents).
    """
    for child in element:
        tag = _local(child.tag)
        if tag in _SKIP_TAGS:
            continue
        if tag in _BLOCK_TAG_TYPES:
            block_type = _BLOCK_TAG_TYPES[tag]
            heading_level = _HEADING_LEVELS.get(tag)
            if tag == "p":
                # Round-trip class-tagged types emitted by assemble.py so
                # eval alignment fingerprints survive PDF→EPUB rebuilds.
                css_class = child.get("class", "").split()
                if "caption" in css_class:
                    block_type = SegmentType.CAPTION
                elif "other" in css_class:
                    block_type = SegmentType.OTHER
                elif _is_heading_like(child):
                    block_type = SegmentType.HEADING
                    heading_level = _RETYPED_HEADING_LEVEL
            yield block_type, heading_level, child
            continue
        yield from _iter_blocks(child)


def _serialize_inline(element: ET.Element) -> str:
    """Recursively serialize *element*'s mixed content, whitespace un-normalized.

    Emphasis tags (see ``_EMPHASIS_TAGS``) are kept as HTML; every other
    child element is unwrapped to its own serialized text.
    """
    parts = [element.text or ""]
    for child in element:
        tag = _local(child.tag)
        inner = _serialize_inline(child)
        if tag in _EMPHASIS_TAGS:
            parts.append(f"<{tag}>{inner}</{tag}>")
        else:
            parts.append(inner)
        parts.append(child.tail or "")
    return "".join(parts)


def _block_text(element: ET.Element) -> str:
    """Serialize a block element to its normalized (whitespace-collapsed) text."""
    return _WHITESPACE_RE.sub(" ", _serialize_inline(element)).strip()


def _fallback_title(doc_root: ET.Element) -> str | None:
    """Fall back to an XHTML document's ``<title>`` when no TOC entry matches it."""
    for element in doc_root.iter():
        if _local(element.tag) == "title" and element.text and element.text.strip():
            return element.text.strip()
    return None


def _same_title(left: str | None, right: str | None) -> bool:
    """Case- and whitespace-insensitive title equality."""
    if not left or not right:
        return False
    return _WHITESPACE_RE.sub(" ", left).strip().casefold() == (
        _WHITESPACE_RE.sub(" ", right).strip().casefold()
    )


def _warn_on_title_collapse(segments: list[Segment], book_title: str) -> None:
    """Log loudly when chapter titles have collapsed onto a single value.

    A book whose TOC never resolved gives every spine document the same
    chapter title. That is invisible in the output but silently disables every
    consumer keyed on chapter titles (rubric T's front/back-matter fold, prose
    sampling), so it is reported at ERROR level with the measured share.
    """
    if not segments:
        return
    top_title, top_count = Counter(segment.chapter_title for segment in segments).most_common(1)[0]
    share = top_count / len(segments)
    if share < _TITLE_COLLAPSE_WARN_SHARE:
        return
    logger.error(
        "Chapter titles did not resolve: %.1f%% of segments (%d/%d) carry the single "
        "chapter title %r%s. Front/back-matter folding and prose sampling key on "
        "chapter titles and will be unreliable for this book — check that the OPF "
        "manifest names a toc.ncx or nav document that exists in the archive.",
        share * 100,
        top_count,
        len(segments),
        top_title,
        " (the book title)" if _same_title(top_title, book_title) else "",
    )


def normalize_epub(path: Path) -> Book:
    """Parse an EPUB file into a :class:`~berilo.models.Book`.

    Resolves the OPF spine for document order and metadata, uses
    ``toc.ncx`` (or the EPUB3 nav document) for chapter titles, then walks
    each spine document for block-level content. A spine document that
    yields no non-empty segments (a cover page, an image-only ad page, the
    nav/TOC document itself) is skipped and consumes no chapter slot.

    Chapter titles resolve in this order: the document's own TOC entry; the
    title of the preceding document (TOC entries mark chapter starts, so an
    entry-less document continues the previous chapter); and, when no TOC
    resolved at all, the document's ``<title>``, its first heading, and
    finally the book title.

    Args:
        path: Path to the ``.epub`` source file.

    Returns:
        The normalized book, with segments in document order.

    Raises:
        ValueError: If the EPUB container declares no OPF rootfile.
        KeyError: If the OPF references a spine document missing from the
            archive.
        zipfile.BadZipFile: If *path* is not a valid zip archive.
        xml.etree.ElementTree.ParseError: If the OPF or container XML is
            malformed.
    """
    with zipfile.ZipFile(path) as archive:
        opf_path = _find_opf_path(archive)
        opf_root = ET.fromstring(archive.read(opf_path))
        opf_dir = posixpath.dirname(opf_path)

        title, authors, language = _read_metadata(opf_root)
        manifest = _read_manifest(opf_root, opf_dir)
        spine_hrefs = _read_spine(opf_root, manifest)
        chapter_titles = _read_toc_titles(archive, opf_root, opf_dir)

        segments: list[Segment] = []
        chapter_index = 0
        position = 0
        toc_resolved = bool(chapter_titles)
        previous_title: str | None = None
        for href in spine_hrefs:
            try:
                doc_bytes = archive.read(href)
            except KeyError:
                logger.warning("Spine document %s missing from archive; skipping", href)
                continue
            try:
                doc_root = ET.fromstring(doc_bytes)
            except ET.ParseError as exc:
                logger.warning("Spine document %s is not well-formed XML: %s", href, exc)
                continue

            body = _find_body(doc_root)
            if body is None:
                continue

            block_texts: list[tuple[SegmentType, int | None, str]] = []
            for segment_type, heading_level, element in _iter_blocks(body):
                text = _block_text(element)
                if text:
                    block_texts.append((segment_type, heading_level, text))
            if not block_texts:
                continue

            toc_title = chapter_titles.get(href)
            if toc_title:
                chapter_title = toc_title
            elif toc_resolved and previous_title:
                # TOC entries mark chapter STARTS and the spine is reading
                # order, so a spine document with no entry of its own is a
                # continuation of the chapter the previous document opened.
                chapter_title = previous_title
            else:
                # No usable TOC: fall back to the document's own signals. A
                # <title> equal to the book title carries no chapter
                # information (Calibre stamps every split document with it),
                # so the in-document heading outranks it; the book title is
                # used only when the document genuinely has no heading.
                doc_title = _fallback_title(doc_root)
                leading_heading = next(
                    (text for seg_type, _, text in block_texts if seg_type == SegmentType.HEADING),
                    None,
                )
                chapter_title = (
                    (None if _same_title(doc_title, title) else doc_title)
                    or leading_heading
                    or doc_title
                    or "Untitled"
                )
            previous_title = chapter_title

            for segment_type, heading_level, text in block_texts:
                segments.append(
                    Segment(
                        id=make_segment_id(text, chapter_index, position),
                        type=segment_type,
                        text=text,
                        chapter_index=chapter_index,
                        chapter_title=chapter_title,
                        position=position,
                        heading_level=heading_level,
                    )
                )
                position += 1
            chapter_index += 1

    _warn_on_title_collapse(segments, title)

    return Book(
        title=title,
        authors=authors,
        language=language,
        source_path=str(path),
        source_format="epub",
        segments=segments,
    )
