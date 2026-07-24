"""Translate stage (stub). See S1.x.

Translates a :class:`~berilo.models.Book`'s segments in paragraph batches
with rolling context and a per-book glossary, resumable via a SQLite cache
keyed on ``(book_hash, segment_hash, model, lang)``. Segment integrity is
mandatory: 1:1 source-to-target mapping, no silent drops.
"""

from __future__ import annotations

from berilo.models import Book
from berilo.providers.base import LLMClient


def translate_book(book: Book, target_language: str, client: LLMClient) -> Book:
    """Translate every segment of ``book`` into ``target_language``.

    Args:
        book: The normalized source book.
        target_language: Target language code (e.g. ``"sl"``).
        client: The LLM client to use for translation calls.

    Returns:
        A new :class:`~berilo.models.Book` with translated segment text.

    Raises:
        NotImplementedError: Always, in this stub.
    """
    raise NotImplementedError("translate_book is not yet implemented")
