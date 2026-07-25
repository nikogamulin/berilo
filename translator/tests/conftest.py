"""Shared pytest fixtures for translator tests.

``epub_builder`` writes minimal, synthetic EPUB packages (container.xml +
content.opf + toc.ncx + xhtml spine documents) directly with ``zipfile``,
mirroring the structure ``berilo.normalize.epub`` parses, without depending
on any real (copyrighted) book under ``data/``.
"""

from __future__ import annotations

import os
import struct
import zipfile
import zlib
from pathlib import Path
from typing import Any

import pytest


def _example_book_path(filename: str) -> Path | None:
    """Locate an example book via ``BERILO_EXAMPLE_DIR`` or a repo ``data/`` dir.

    ``data/`` holds copyrighted books, is gitignored, and therefore exists
    only in the main checkout — agent worktrees and CI must skip every test
    that needs it rather than fail.

    Args:
        filename: Bare filename of the example book.

    Returns:
        The located path, or ``None`` when the book is not available.
    """
    candidates: list[Path] = []
    env_dir = os.environ.get("BERILO_EXAMPLE_DIR")
    if env_dir:
        candidates.append(Path(env_dir) / filename)
    for parent in Path(__file__).resolve().parents:
        candidates.append(parent / "data" / "examples" / filename)
    return next((candidate for candidate in candidates if candidate.exists()), None)


_CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="{opf_path}" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""


def _content_opf(
    title: str,
    authors: list[str],
    language: str,
    items: list[dict[str, Any]],
    include_ncx: bool,
    nav_href: str | None,
    ncx_href: str = "toc.ncx",
    image_items: list[dict[str, Any]] | None = None,
) -> str:
    creators = "".join(f"<dc:creator>{author}</dc:creator>" for author in authors)
    manifest_entries = "\n".join(
        [
            f'    <item id="{item["id"]}" href="{item["href"]}" '
            'media-type="application/xhtml+xml"/>'
            for item in items
        ]
        + [
            f'    <item id="{image["id"]}" href="{image["href"]}" '
            f'media-type="{image["media_type"]}"/>'
            for image in image_items or []
        ]
    )
    spine_entries = "\n".join(f'    <itemref idref="{item["id"]}"/>' for item in items)
    ncx_entry = (
        f'    <item id="ncx" href="{ncx_href}" media-type="application/x-dtbncx+xml"/>\n'
        if include_ncx
        else ""
    )
    nav_entry = (
        f'    <item id="nav" href="{nav_href}" media-type="application/xhtml+xml"'
        ' properties="nav"/>\n'
        if nav_href
        else ""
    )
    spine_toc_attr = ' toc="ncx"' if include_ncx else ""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"
         unique-identifier="bookid" version="3.0">
  <metadata>
    <dc:title>{title}</dc:title>
    {creators}
    <dc:language>{language}</dc:language>
    <dc:identifier id="bookid">urn:uuid:test-book</dc:identifier>
  </metadata>
  <manifest>
{ncx_entry}{nav_entry}{manifest_entries}
  </manifest>
  <spine{spine_toc_attr}>
{spine_entries}
  </spine>
</package>
"""


def _toc_ncx(items: list[dict[str, Any]]) -> str:
    nav_points = []
    for order, item in enumerate(items, start=1):
        nav_title = item.get("nav_title")
        if nav_title is None:
            continue
        nav_points.append(
            f'    <navPoint id="navpoint-{item["id"]}" playOrder="{order}">\n'
            f"      <navLabel><text>{nav_title}</text></navLabel>\n"
            f'      <content src="{item["href"]}"/>\n'
            "    </navPoint>"
        )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:test-book"/></head>
  <docTitle><text>Test Book</text></docTitle>
  <navMap>
{chr(10).join(nav_points)}
  </navMap>
</ncx>
"""


def _xhtml_doc(doc_title: str, body_html: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>{doc_title}</title></head>
  <body>
{body_html}
  </body>
</html>
"""


def _nav_toc_doc(entries: list[tuple[str, str]]) -> str:
    """Build an EPUB3 nav document with a ``epub:type="toc"`` list.

    Args:
        entries: ``(href, title)`` pairs for the table of contents.
    """
    list_items = "\n".join(
        f'        <li><a href="{href}">{text}</a></li>' for href, text in entries
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <head><title>Contents</title></head>
  <body>
    <nav epub:type="toc">
      <h1>Contents</h1>
      <ol>
{list_items}
      </ol>
    </nav>
  </body>
</html>
"""


def write_epub(
    destination: Path,
    items: list[dict[str, Any]],
    title: str = "Test Book",
    authors: list[str] | None = None,
    language: str = "en",
    include_ncx: bool = True,
    nav_toc: list[tuple[str, str]] | None = None,
    opf_dir: str = "",
    declared_ncx_href: str | None = None,
    image_items: list[dict[str, Any]] | None = None,
) -> Path:
    """Write a minimal synthetic EPUB at *destination*.

    Args:
        destination: Path to write the ``.epub`` file to.
        items: One dict per spine document, in document order. Keys:
            ``id`` (manifest id), ``href`` (xhtml filename), ``body``
            (inner XHTML for ``<body>``), ``nav_title`` (optional TOC
            label; a ``None``/absent value omits the document from
            ``toc.ncx``), ``doc_title`` (optional ``<title>`` fallback,
            defaults to ``href``).
        title: Book title (``dc:title``).
        authors: Book authors (``dc:creator`` entries).
        language: Book language (``dc:language``).
        include_ncx: If False, omit ``toc.ncx`` from the manifest entirely
            (to exercise the EPUB3 nav-document TOC fallback).
        nav_toc: ``(href, title)`` pairs to write into an EPUB3 nav
            document (``nav.xhtml``, ``properties="nav"``) used as the TOC
            source when *include_ncx* is False.
        opf_dir: Zip-internal directory holding the OPF and every document it
            names (``""`` puts them at the archive root). Manifest hrefs stay
            relative to the OPF, so this exercises href resolution.
        declared_ncx_href: NCX href written into the manifest when it must
            differ from the archive entry (``toc.ncx``) — repackaged books
            name a TOC document that is not there under that name.
        image_items: One dict per image resource. Keys: ``id`` (manifest id),
            ``href`` (path relative to the OPF), ``media_type``, ``data``
            (raw bytes written into the archive).

    Returns:
        *destination*, for convenient chaining.
    """
    authors = authors if authors is not None else ["Test Author"]
    nav_href = "nav.xhtml" if nav_toc else None
    prefix = f"{opf_dir}/" if opf_dir else ""
    with zipfile.ZipFile(destination, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip")
        archive.writestr(
            "META-INF/container.xml", _CONTAINER_XML.format(opf_path=f"{prefix}content.opf")
        )
        archive.writestr(
            f"{prefix}content.opf",
            _content_opf(
                title,
                authors,
                language,
                items,
                include_ncx,
                nav_href,
                ncx_href=declared_ncx_href or "toc.ncx",
                image_items=image_items,
            ),
        )
        for image in image_items or []:
            archive.writestr(f"{prefix}{image['href']}", image["data"])
        if include_ncx:
            archive.writestr(f"{prefix}toc.ncx", _toc_ncx(items))
        if nav_href:
            archive.writestr(f"{prefix}{nav_href}", _nav_toc_doc(nav_toc or []))
        for item in items:
            archive.writestr(
                f"{prefix}{item['href']}",
                _xhtml_doc(item.get("doc_title", item["href"]), item["body"]),
            )
    return destination


def _png_bytes(width: int, height: int, color: tuple[int, int, int] = (90, 90, 90)) -> bytes:
    """Encode a solid-color RGB PNG entirely from the standard library.

    Synthetic rather than a checked-in binary: image fixtures must not depend
    on (copyrighted) books under ``data/``, and a generated image lets a test
    pick the exact pixel dimensions a threshold is being probed with.

    Args:
        width: Image width in pixels.
        height: Image height in pixels.
        color: Solid ``(r, g, b)`` fill.

    Returns:
        The encoded PNG bytes.
    """
    raw = b"".join(b"\x00" + bytes(color) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


@pytest.fixture
def png_image():
    """Return :func:`_png_bytes`, a ``(width, height, color) -> bytes`` encoder."""
    return _png_bytes


@pytest.fixture
def example_book():
    """Return the example-book locator (see :func:`_example_book_path`).

    Exposed as a fixture rather than an import: a foreign ``tests`` package on
    this box's ``sys.path`` shadows ``tests.conftest``.
    """
    return _example_book_path


@pytest.fixture
def epub_builder(tmp_path: Path):
    """Return a callable building a synthetic EPUB under ``tmp_path``.

    Usage: ``path = epub_builder(items=[{"id": "c1", "href": "c1.xhtml",
    "body": "<p>Hello</p>", "nav_title": "Chapter 1"}])``.
    """
    counter = {"n": 0}

    def _build(items: list[dict[str, Any]], **kwargs: Any) -> Path:
        counter["n"] += 1
        destination = tmp_path / f"book_{counter['n']}.epub"
        return write_epub(destination, items, **kwargs)

    return _build
