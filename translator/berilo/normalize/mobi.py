"""MOBI/AZW3 normalizer: convert via Calibre, then reuse the EPUB path.

No MOBI example is present in `data/examples/` (see `docs/findings.md`); the
MOBI path converts to EPUB via Calibre's ``ebook-convert`` and then reuses the
EPUB normalizer (:func:`berilo.normalize.epub.normalize_epub`).
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import tempfile
from pathlib import Path

from berilo.models import Book
from berilo.normalize.epub import normalize_epub

logger = logging.getLogger(__name__)

# Calibre's ebook-convert binary name, resolved via PATH.
_EBOOK_CONVERT_BINARY = "ebook-convert"

# Conversion is CPU-bound single-file work; 300s covers even large books
# without masking a genuinely hung conversion.
_CONVERT_TIMEOUT_SECONDS = 300

# Cap on how much of a failed conversion's stderr is surfaced in the raised
# error, so a runaway Calibre log doesn't flood the CLI output.
_STDERR_EXCERPT_CHARS = 2000


def _find_ebook_convert() -> str:
    """Locate the ``ebook-convert`` binary on ``PATH``.

    Returns:
        Absolute path to the ``ebook-convert`` executable.

    Raises:
        RuntimeError: If Calibre's ``ebook-convert`` is not installed/on PATH.
    """
    resolved = shutil.which(_EBOOK_CONVERT_BINARY)
    if resolved is None:
        raise RuntimeError(
            "ebook-convert not found on PATH. MOBI/AZW3 support requires "
            "Calibre (https://calibre-ebook.com/download) — install it so "
            "`ebook-convert` is on PATH, then retry."
        )
    return resolved


def _convert_to_epub(source: Path, ebook_convert: str, destination: Path) -> None:
    """Convert *source* to EPUB at *destination* via Calibre's ``ebook-convert``.

    Args:
        source: Path to the ``.mobi``/``.azw3`` source file.
        ebook_convert: Resolved path to the ``ebook-convert`` binary.
        destination: Path the converted ``.epub`` should be written to.

    Raises:
        RuntimeError: If the conversion process exits non-zero or times out.
    """
    try:
        result = subprocess.run(
            [ebook_convert, str(source), str(destination)],
            capture_output=True,
            text=True,
            timeout=_CONVERT_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(
            f"ebook-convert timed out after {_CONVERT_TIMEOUT_SECONDS}s converting " f"{source}"
        ) from exc

    if result.returncode != 0:
        stderr_excerpt = (result.stderr or "").strip()[-_STDERR_EXCERPT_CHARS:]
        raise RuntimeError(
            f"ebook-convert failed (exit {result.returncode}) converting {source}:\n"
            f"{stderr_excerpt}"
        )
    logger.info("Converted %s to EPUB via ebook-convert", source)


def normalize_mobi(path: Path) -> Book:
    """Parse a MOBI/AZW3 file into a :class:`~berilo.models.Book`.

    Converts via Calibre's ``ebook-convert`` to a temporary EPUB, then
    delegates to the EPUB normalizer. The returned book's ``source_format``
    reflects the original MOBI/AZW3 extension (not ``"epub"``), and
    ``source_path`` points at the original source file.

    Args:
        path: Path to the ``.mobi``/``.azw3`` source file.

    Returns:
        The normalized book.

    Raises:
        RuntimeError: If Calibre's ``ebook-convert`` is missing, times out, or
            fails during conversion.
    """
    ebook_convert = _find_ebook_convert()
    source_format = path.suffix.lower().lstrip(".")

    with tempfile.TemporaryDirectory() as tmp_dir:
        converted_epub = Path(tmp_dir) / f"{path.stem}.epub"
        _convert_to_epub(path, ebook_convert, converted_epub)
        book = normalize_epub(converted_epub)

    book.source_format = source_format
    book.source_path = str(path)
    return book
