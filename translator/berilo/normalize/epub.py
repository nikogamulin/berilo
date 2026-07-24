"""EPUB normalizer (stub). See S1.1.

EPUB is the easy, structured path: parse directly with an EPUB library
(ebooklib/lxml) rather than going through a reflow heuristic.
"""

from __future__ import annotations

from pathlib import Path

from berilo.models import Book


def normalize_epub(path: Path) -> Book:
    """Parse an EPUB file into a :class:`~berilo.models.Book`.

    Args:
        path: Path to the ``.epub`` source file.

    Returns:
        The normalized book.

    Raises:
        NotImplementedError: Always, in this stub.
    """
    raise NotImplementedError("EPUB normalization is not yet implemented")
