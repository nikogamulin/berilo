"""PDF normalizer (stub). See S1.2/S1.3.

PDF extraction uses PyMuPDF plus reflow heuristics: headers/footers/page
numbers stripped, hyphenation joined, OCR noise cleaned (some example PDFs
are OCR-sourced — see `docs/findings.md`).
"""

from __future__ import annotations

from pathlib import Path

from berilo.models import Book


def normalize_pdf(path: Path) -> Book:
    """Parse a PDF file into a :class:`~berilo.models.Book`.

    Args:
        path: Path to the ``.pdf`` source file.

    Returns:
        The normalized book.

    Raises:
        NotImplementedError: Always, in this stub.
    """
    raise NotImplementedError("PDF normalization is not yet implemented")
