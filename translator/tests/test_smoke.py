"""Smoke tests for the translator package skeleton (S0.2).

Verifies the package imports cleanly, the CLI exposes the required
subcommands and exits correctly, and the core ``models`` types round-trip
through JSON.
"""

from __future__ import annotations

import subprocess
import sys

from click.testing import CliRunner

import berilo
from berilo.cli import cli
from berilo.models import Book, Segment, SegmentType, make_segment_id


def test_package_imports_and_has_version() -> None:
    """The package imports and exposes a version string."""
    assert berilo.__version__ == "0.1.0"


def test_cli_help_exits_zero_and_lists_subcommands() -> None:
    """``berilo --help`` exits 0 and lists the required subcommands."""
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0
    for subcommand in ("translate", "inspect", "eval"):
        assert subcommand in result.output


def test_cli_help_via_subprocess_entry_point() -> None:
    """The ``berilo`` console script (invoked as a module) exits 0 on --help."""
    completed = subprocess.run(
        [sys.executable, "-m", "berilo.cli", "--help"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0
    for subcommand in ("translate", "inspect", "eval"):
        assert subcommand in completed.stdout


def test_stub_subcommands_exit_nonzero() -> None:
    """Stub subcommands print 'not implemented' and exit 1.

    ``inspect`` is implemented (S1.1) and is covered separately in
    ``test_epub.py``, not by this stub-behavior check.
    """
    runner = CliRunner()
    for subcommand in ("translate", "eval", "doctor"):
        args = [subcommand] + (["dummy.epub"] if subcommand in ("translate", "eval") else [])
        result = runner.invoke(cli, args)
        assert result.exit_code == 1
        assert "not implemented" in result.output


def test_make_segment_id_is_stable_and_content_sensitive() -> None:
    """Segment IDs are deterministic and change when inputs change."""
    id_a = make_segment_id("Hello world.", chapter_index=0, position=0)
    id_b = make_segment_id("Hello world.", chapter_index=0, position=0)
    id_c = make_segment_id("Hello world.", chapter_index=0, position=1)
    assert id_a == id_b
    assert id_a != id_c


def test_book_to_json_from_json_round_trip() -> None:
    """A Book with segments survives a to_json/from_json round trip."""
    segment = Segment(
        id=make_segment_id("Chapter One", chapter_index=0, position=0),
        type=SegmentType.HEADING,
        text="Chapter One",
        chapter_index=0,
        chapter_title="Chapter One",
        position=0,
        heading_level=1,
    )
    book = Book(
        title="Example Book",
        authors=["Author One"],
        language="en",
        source_path="/tmp/example.epub",
        source_format="epub",
        segments=[segment],
    )

    restored = Book.from_json(book.to_json())

    assert restored == book
    assert restored.chapter_count == 1
    assert restored.segments[0].type is SegmentType.HEADING
