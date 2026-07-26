"""Build the served book catalog by scanning a directory of EPUBs.

Pure filesystem work: no HTTP knowledge, no network. A catalog entry labels
a file from the EPUB's own package metadata rather than its filename, because
the filenames books arrive with are rarely readable ("Title (Author) (z-lib
...).epub"), and offers a clean download name built from that metadata.
"""

from __future__ import annotations

import hashlib
import logging
import re
import unicodedata
from dataclasses import dataclass, replace
from pathlib import Path

from berilo.normalize.epub import read_epub_metadata

logger = logging.getLogger(__name__)

# Length of the opaque per-book id. A truncated digest of the resolved path:
# stable across restarts (a bookmarked link keeps working) and opaque enough
# that ids reveal nothing about the filesystem.
_ID_LENGTH = 16

# Characters no filesystem or Content-Disposition header should have to carry.
_UNSAFE_FILENAME_CHARS = re.compile(r'[\\/:*?"<>|\x00-\x1f]')

_WHITESPACE = re.compile(r"\s+")

# A download filename long enough to be descriptive, short enough to survive
# every filesystem's path limit once the browser adds its own suffixes.
_MAX_FILENAME_STEM = 100


@dataclass(frozen=True)
class CatalogEntry:
    """One downloadable book.

    Attributes:
        id: Opaque, stable identifier used in the download URL.
        path: Absolute path to the EPUB on disk.
        title: Display title, from EPUB metadata or the filename stem.
        authors: Author names, empty when the package declares none.
        language: BCP-47 tag from the package, empty when undeclared.
        size_bytes: File size, for display.
        download_name: Clean ``.epub`` filename offered to the browser.
        distinguisher: Filename stem, set only when another book carries the
            same title and authors; empty otherwise. Several builds of one
            book commonly sit side by side (``Kaplan.baseline.sl.epub`` and
            ``Kaplan.revise.sl.epub`` share a title), and the page has to be
            able to tell them apart.
    """

    id: str
    path: Path
    title: str
    authors: list[str]
    language: str
    size_bytes: int
    download_name: str
    distinguisher: str = ""

    @property
    def author_line(self) -> str:
        """Authors joined for display, or an empty string when unknown."""
        return ", ".join(self.authors)


def _entry_id(path: Path) -> str:
    """Return the stable opaque id for the book at *path*."""
    return hashlib.sha256(str(path).encode("utf-8")).hexdigest()[:_ID_LENGTH]


def _clean_filename(title: str, language: str, fallback: str) -> str:
    """Build a safe, descriptive ``.epub`` download filename.

    Args:
        title: Book title from metadata; may be empty or unusable.
        language: Package language tag, appended as a suffix when present.
        fallback: Filename stem to use when *title* yields nothing.

    Returns:
        A filename ending in ``.epub``, free of path separators and control
        characters.
    """
    stem = _UNSAFE_FILENAME_CHARS.sub("", title).strip().strip(".")
    stem = _WHITESPACE.sub(" ", stem)
    if not stem:
        stem = _UNSAFE_FILENAME_CHARS.sub("", fallback).strip() or "book"
    if language and not _mentions_language(stem, language):
        stem += f" ({language})"
    return f"{stem[:_MAX_FILENAME_STEM].strip()}.epub"


def _mentions_language(stem: str, language: str) -> bool:
    """True if *stem* already carries *language* as a standalone token.

    Titles routinely arrive pre-tagged — the assembler writes ``[EN-US] …``
    and translator output ends in ``.sl`` — and appending the tag a second
    time produces "[EN-US] Title (en-US)".  Matching on a word boundary keeps
    a title like "Islands" from being read as carrying ``sl``.
    """
    return re.search(rf"\b{re.escape(language)}\b", stem, flags=re.IGNORECASE) is not None


def _read_entry(path: Path) -> CatalogEntry:
    """Build one catalog entry, falling back to the filename on unreadable metadata.

    A book that cannot be parsed is still worth serving — the bytes may be
    perfectly fine for the reader app even when the OPF trips this parser — so
    metadata failure degrades to the filename rather than dropping the book.
    """
    fallback_title = path.stem
    title, authors, language = fallback_title, [], ""
    try:
        metadata = read_epub_metadata(path)
    except Exception as error:  # noqa: BLE001 - any malformed EPUB degrades the same way
        logger.warning("Could not read EPUB metadata from %s: %s", path.name, error)
    else:
        title = metadata.title.strip() or fallback_title
        authors = [author for author in metadata.authors if author.strip()]
        language = metadata.language.strip()
    return CatalogEntry(
        id=_entry_id(path),
        path=path,
        title=unicodedata.normalize("NFC", title),
        authors=authors,
        language=language,
        size_bytes=path.stat().st_size,
        download_name=_clean_filename(title, language, fallback_title),
    )


def _disambiguate(entries: list[CatalogEntry]) -> list[CatalogEntry]:
    """Tag entries that share a title and authors with their filename stem.

    Two builds of the same book carry identical metadata, so without this the
    page shows the same title twice and both downloads land under one name.
    """
    groups: dict[tuple[str, str], int] = {}
    for entry in entries:
        groups[(entry.title.casefold(), entry.author_line.casefold())] = (
            groups.get((entry.title.casefold(), entry.author_line.casefold()), 0) + 1
        )

    resolved: list[CatalogEntry] = []
    for entry in entries:
        if groups[(entry.title.casefold(), entry.author_line.casefold())] < 2:
            resolved.append(entry)
            continue
        stem = _UNSAFE_FILENAME_CHARS.sub("", entry.path.stem).strip() or entry.id
        resolved.append(
            replace(
                entry,
                distinguisher=stem,
                download_name=f"{entry.download_name.removesuffix('.epub')} — {stem}.epub",
            )
        )
    return resolved


def scan_catalog(directory: Path) -> list[CatalogEntry]:
    """Scan *directory* for EPUBs and describe each one.

    Only files directly in *directory* are considered — no recursion, so a
    stray archive of extracted books never leaks into the listing. Entries are
    sorted by title for a stable page order.

    Args:
        directory: Directory to scan.

    Returns:
        One entry per readable ``.epub`` file, sorted by title (case-folded).
        Entries sharing a title carry a :attr:`CatalogEntry.distinguisher`.

    Raises:
        NotADirectoryError: If *directory* does not exist or is not a directory.
    """
    if not directory.is_dir():
        raise NotADirectoryError(f"Not a directory: {directory}")

    entries: list[CatalogEntry] = []
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.suffix.lower() != ".epub":
            continue
        try:
            entries.append(_read_entry(path.resolve()))
        except OSError as error:
            logger.warning("Skipping unreadable file %s: %s", path.name, error)
    return _disambiguate(sorted(entries, key=lambda entry: entry.title.casefold()))
