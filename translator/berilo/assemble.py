"""Assemble stage (stub). See S1.x.

Renders a translated :class:`~berilo.models.Book` back out to a reader-ready
EPUB (and optionally a bilingual source+target EPUB).
"""

from __future__ import annotations

from pathlib import Path

from berilo.models import Book


def assemble_epub(book: Book, output_path: str | Path, bilingual: bool = False) -> Path:
    """Render ``book`` to an output EPUB file.

    Args:
        book: The (translated) book to render.
        output_path: Destination ``.epub`` path.
        bilingual: If True, include source text alongside the translation.

    Returns:
        The path to the written EPUB file.

    Raises:
        NotImplementedError: Always, in this stub.
    """
    raise NotImplementedError("assemble_epub is not yet implemented")
