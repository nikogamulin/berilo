"""MOBI/AZW3 normalizer (stub). See S1.x.

No MOBI example is present in `data/examples/` (see `docs/findings.md`); the
MOBI path converts to EPUB via Calibre's `ebook-convert` and then reuses the
EPUB normalizer.
"""

from __future__ import annotations

from pathlib import Path

from berilo.models import Book


def normalize_mobi(path: Path) -> Book:
    """Parse a MOBI/AZW3 file into a :class:`~berilo.models.Book`.

    Converts via Calibre's ``ebook-convert`` to EPUB, then delegates to the
    EPUB normalizer.

    Args:
        path: Path to the ``.mobi``/``.azw3`` source file.

    Returns:
        The normalized book.

    Raises:
        NotImplementedError: Always, in this stub.
    """
    raise NotImplementedError("MOBI normalization is not yet implemented")
