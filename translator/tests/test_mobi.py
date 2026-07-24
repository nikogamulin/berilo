"""Tests for the MOBI/AZW3 normalizer (S1.3).

Unit tests fake ``shutil.which``/``subprocess.run`` so no real Calibre
install is required. One integration test converts the real example EPUB
(under ``data/``, gitignored, never committed) to MOBI via a real
``ebook-convert`` and is skipped when either Calibre or the example file is
absent, e.g. in an agent worktree or CI.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

from berilo.normalize import mobi as mobi_module
from berilo.normalize.epub import normalize_epub
from berilo.normalize.mobi import normalize_mobi

EXAMPLE_EPUB = Path(__file__).parents[2] / "data" / "examples" / "The New Rules of War.epub"
EBOOK_CONVERT = shutil.which("ebook-convert")

# Conversion of a ~500-page book can run long; generous but bounded.
_REAL_CONVERT_TIMEOUT_SECONDS = 600
_SEGMENT_COUNT_TOLERANCE_PCT = 2.0

# Calibre's default MOBI output embeds only the legacy MOBI 6 format, which
# has no native heading/blockquote semantics: ebook-convert re-flows
# headings into styled paragraphs and uses <blockquote> as an indentation
# hack, which distorts the round-tripped segment count well past 2% on the
# example book. "both" also embeds the KF8 (AZW3-equivalent) markup that
# newer ebook-convert reads back preferentially, preserving structure —
# and matches what real Kindle-targeted MOBI exports typically contain.
_MOBI_FILE_TYPE_ARGS = ["--mobi-file-type", "both"]


def test_missing_ebook_convert_raises_actionable_error(monkeypatch: pytest.MonkeyPatch) -> None:
    """No ``ebook-convert`` on PATH raises a RuntimeError telling the user to install Calibre."""
    monkeypatch.setattr(mobi_module.shutil, "which", lambda name: None)

    with pytest.raises(RuntimeError, match="Calibre"):
        normalize_mobi(Path("book.mobi"))


def test_conversion_failure_raises_with_stderr_excerpt(monkeypatch: pytest.MonkeyPatch) -> None:
    """A nonzero ``ebook-convert`` exit raises, including the stderr excerpt."""
    monkeypatch.setattr(mobi_module.shutil, "which", lambda name: "/usr/bin/ebook-convert")

    def fake_run(cmd, **kwargs):  # noqa: ANN001, ANN003 - test double matching subprocess.run
        return subprocess.CompletedProcess(
            args=cmd, returncode=1, stdout="", stderr="ERROR: corrupt input file, aborting"
        )

    monkeypatch.setattr(mobi_module.subprocess, "run", fake_run)

    with pytest.raises(RuntimeError, match="corrupt input file"):
        normalize_mobi(Path("book.mobi"))


@pytest.mark.parametrize("suffix", [".mobi", ".azw3"])
def test_source_format_and_path_set_from_original_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, epub_builder, suffix: str
) -> None:
    """The returned Book keeps the original MOBI/AZW3 extension and path, not the EPUB's."""
    synthetic_epub = epub_builder(
        items=[
            {
                "id": "c1",
                "href": "c1.xhtml",
                "nav_title": "Chapter One",
                "body": "<h1>Chapter One</h1><p>Hello world.</p>",
            }
        ]
    )
    monkeypatch.setattr(mobi_module.shutil, "which", lambda name: "/usr/bin/ebook-convert")

    def fake_run(cmd, **kwargs):  # noqa: ANN001, ANN003 - test double matching subprocess.run
        destination = Path(cmd[2])
        shutil.copyfile(synthetic_epub, destination)
        return subprocess.CompletedProcess(args=cmd, returncode=0, stdout="", stderr="")

    monkeypatch.setattr(mobi_module.subprocess, "run", fake_run)

    source_path = tmp_path / f"book{suffix}"
    source_path.write_bytes(b"")  # placeholder; conversion is faked above
    book = normalize_mobi(source_path)

    assert book.source_format == suffix.lstrip(".")
    assert book.source_path == str(source_path)
    assert len(book.segments) == 2  # heading + paragraph, from the synthetic EPUB


@pytest.mark.slow
@pytest.mark.skipif(
    EBOOK_CONVERT is None or not EXAMPLE_EPUB.exists(),
    reason="ebook-convert or data/examples example EPUB not available",
)
def test_example_mobi_segment_count_within_tolerance_of_epub(tmp_path: Path) -> None:
    """Verify line: EPUB->MOBI->normalize segment count is within 2% of the EPUB's."""
    epub_book = normalize_epub(EXAMPLE_EPUB)

    mobi_path = tmp_path / "The New Rules of War.mobi"
    result = subprocess.run(
        [EBOOK_CONVERT, str(EXAMPLE_EPUB), str(mobi_path), *_MOBI_FILE_TYPE_ARGS],
        capture_output=True,
        text=True,
        timeout=_REAL_CONVERT_TIMEOUT_SECONDS,
    )
    assert result.returncode == 0, result.stderr

    mobi_book = normalize_mobi(mobi_path)

    epub_count = len(epub_book.segments)
    mobi_count = len(mobi_book.segments)
    delta_pct = abs(mobi_count - epub_count) / epub_count * 100
    assert (
        delta_pct <= _SEGMENT_COUNT_TOLERANCE_PCT
    ), f"epub segments={epub_count} mobi segments={mobi_count} delta={delta_pct:.2f}%"
    assert mobi_book.source_format == "mobi"
    assert mobi_book.source_path == str(mobi_path)
