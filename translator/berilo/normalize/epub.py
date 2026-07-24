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


def _read_toc_titles(archive: zipfile.ZipFile, opf_root: ET.Element) -> dict[str, str]:
    """Build a ``{document path: chapter title}`` map from ``toc.ncx`` or the nav doc."""
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
        try:
            return _parse_ncx_titles(archive.read(ncx_href), posixpath.dirname(ncx_href))
        except (KeyError, ET.ParseError) as exc:
            logger.warning("Could not parse toc.ncx at %s: %s", ncx_href, exc)

    nav_href = next(
        (
            item.get("href")
            for item in manifest_items
            if "nav" in (item.get("properties") or "").split()
        ),
        None,
    )
    if nav_href:
        try:
            return _parse_nav_titles(archive.read(nav_href), posixpath.dirname(nav_href))
        except (KeyError, ET.ParseError) as exc:
            logger.warning("Could not parse nav document at %s: %s", nav_href, exc)

    return {}


def _find_body(root: ET.Element) -> ET.Element | None:
    """Return the ``<body>`` element of an XHTML document tree, if present."""
    for element in root.iter():
        if _local(element.tag) == "body":
            return element
    return None


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
            if tag == "p":
                # Round-trip class-tagged types emitted by assemble.py so
                # eval alignment fingerprints survive PDF→EPUB rebuilds.
                css_class = child.get("class", "")
                if "caption" in css_class.split():
                    block_type = SegmentType.CAPTION
                elif "other" in css_class.split():
                    block_type = SegmentType.OTHER
            yield block_type, _HEADING_LEVELS.get(tag), child
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


def normalize_epub(path: Path) -> Book:
    """Parse an EPUB file into a :class:`~berilo.models.Book`.

    Resolves the OPF spine for document order and metadata, uses
    ``toc.ncx`` (or the EPUB3 nav document) for chapter titles, then walks
    each spine document for block-level content. A spine document that
    yields no non-empty segments (a cover page, an image-only ad page, the
    nav/TOC document itself) is skipped and consumes no chapter slot.

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
        chapter_titles = _read_toc_titles(archive, opf_root)

        segments: list[Segment] = []
        chapter_index = 0
        position = 0
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

            chapter_title = (
                chapter_titles.get(href)
                or _fallback_title(doc_root)
                or next(
                    (text for seg_type, _, text in block_texts if seg_type == SegmentType.HEADING),
                    None,
                )
                or "Untitled"
            )

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

    return Book(
        title=title,
        authors=authors,
        language=language,
        source_path=str(path),
        source_format="epub",
        segments=segments,
    )
