"""Normalize stage: dispatch source files to a format-specific parser.

Every format-specific normalizer returns a :class:`~berilo.models.Book` — the
canonical intermediate representation the rest of the pipeline consumes.
"""

from __future__ import annotations

from pathlib import Path

from berilo.models import Book

_EXTENSION_MODULES = {
    ".epub": "berilo.normalize.epub",
    ".pdf": "berilo.normalize.pdf",
    ".mobi": "berilo.normalize.mobi",
    ".azw3": "berilo.normalize.mobi",
}


def normalize(source_path: str | Path) -> Book:
    """Parse a source file into a :class:`~berilo.models.Book`.

    Dispatches by file extension to the matching normalizer
    (``epub``, ``pdf``, ``mobi``/``azw3``).

    Args:
        source_path: Path to the source book file.

    Returns:
        The normalized :class:`~berilo.models.Book`.

    Raises:
        ValueError: If the file extension is unsupported.
        NotImplementedError: The format-specific normalizer is a stub.
    """
    path = Path(source_path)
    extension = path.suffix.lower()
    if extension not in _EXTENSION_MODULES:
        supported = ", ".join(sorted(_EXTENSION_MODULES))
        raise ValueError(f"Unsupported source format {extension!r}; supported: {supported}")

    if extension == ".epub":
        from berilo.normalize.epub import normalize_epub

        return normalize_epub(path)
    if extension == ".pdf":
        from berilo.normalize.pdf import normalize_pdf

        return normalize_pdf(path)

    from berilo.normalize.mobi import normalize_mobi

    return normalize_mobi(path)
