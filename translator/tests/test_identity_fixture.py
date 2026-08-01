"""Tests for the cross-language identity fixtures (B1a).

The Kotlin port asserts against files this module generates, so these tests
guard both ends of the contract: the fixtures must regenerate byte-identically
from the real books, and they must never carry a word of those books.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from berilo.cache import book_hash, segment_hash
from berilo.identity_fixture import (
    EXAMPLE_SOURCES,
    FIXTURE_VERSION,
    PYTHON_WHITESPACE,
    PYTHON_WHITESPACE_CODEPOINTS,
    build_id_vector_fixture,
    build_identity_fixture,
    build_legacy_book_json,
    build_synthetic_book,
    default_fixture_dir,
    render_fixture,
    resolve_example,
    write_example_fixtures,
    write_synthetic_fixtures,
)
from berilo.models import Book, make_segment_id

#: Keys that could carry prose out of a copyrighted book. ``data/`` is
#: gitignored; these fixtures are committed, so any of these appearing in one
#: is a licensing defect, not a style one.
FORBIDDEN_KEYS = frozenset(
    {"text", "title", "chapter_title", "authors", "alt", "source_path", "source_href"}
)

#: The plan's €0 baseline (``docs/plans/2026-07-26-ondevice-translation.md`` §5),
#: as ``slug -> (book_hash, segments, chapters, images)``.
EXPECTED_IDENTITY = {
    "new-rules-of-war": ("2db2bd1ca6782089f32f2af99bc2b69cbbaf259a", 2309, 27, 4),
    "revenge-of-geography": ("f30cd8f30a17f696909f446a121bd8b0eb20b91c", 1294, 47, 17),
    "sandworm": ("3f76c96f58a59016bc86bc11616b040f12038ef2", 1813, 61, 3),
    "ember-spark": ("14425e03f36e50dfbcd2c6631fbebfd90f7d5dbf", 1231, 35, 61),
}


def _fixture_dir() -> Path:
    """Locate the committed fixture directory from this test file."""
    return default_fixture_dir(Path(__file__))


def _examples_dir() -> Path | None:
    """Locate ``data/examples``, which exists only in the main checkout."""
    env_dir = os.environ.get("BERILO_EXAMPLE_DIR")
    if env_dir and Path(env_dir).is_dir():
        return Path(env_dir)
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "data" / "examples"
        if candidate.is_dir():
            return candidate
    return None


def _walk_keys(node: object) -> list[str]:
    """Collect every mapping key reachable in a decoded JSON document."""
    if isinstance(node, dict):
        return [key for k, v in node.items() for key in [k, *_walk_keys(v)]]
    if isinstance(node, list):
        return [key for item in node for key in _walk_keys(item)]
    return []


def test_python_whitespace_constant_is_exactly_str_isspace() -> None:
    """The pinned set must be what ``str.strip()`` actually removes.

    The Kotlin port hard-codes this same set instead of calling ``trim()``; if
    the constant drifts from Python's real behaviour, the port is verified
    against a fiction.
    """
    actual = tuple(codepoint for codepoint in range(0x110000) if chr(codepoint).isspace())
    assert PYTHON_WHITESPACE_CODEPOINTS == actual
    assert len(PYTHON_WHITESPACE) == len(actual)
    assert PYTHON_WHITESPACE.strip() == ""


def test_next_line_is_the_sole_divergence_from_java_whitespace() -> None:
    """U+0085 is why the port cannot use ``String.trim()``.

    Java's ``Character.isWhitespace() || Character.isSpaceChar()`` is
    ``Zs | Zl | Zp | {0009..000D, 001C..001F}``. Everything Python strips is in
    that union except U+0085, which is category ``Cc``.
    """
    import unicodedata

    java_equivalent = {
        codepoint
        for codepoint in PYTHON_WHITESPACE_CODEPOINTS
        if unicodedata.category(chr(codepoint)) in {"Zs", "Zl", "Zp"}
        or 0x09 <= codepoint <= 0x0D
        or 0x1C <= codepoint <= 0x1F
    }
    assert set(PYTHON_WHITESPACE_CODEPOINTS) - java_equivalent == {0x85}


def test_synthetic_book_is_self_consistent() -> None:
    """Every synthetic segment id is derived from its own coordinates."""
    book = build_synthetic_book()
    assert [segment.position for segment in book.segments] == list(range(len(book.segments)))
    assert book.chapter_count == 3
    for segment in book.segments:
        assert segment.id == make_segment_id(segment.text, segment.chapter_index, segment.position)


def test_identity_fixture_reproduces_the_books_hashes() -> None:
    """The fixture's derived fields agree with the hash functions themselves."""
    book = build_synthetic_book()
    fixture = build_identity_fixture(book, "synthetic")
    assert fixture["fixture_version"] == FIXTURE_VERSION
    assert fixture["book_hash"] == book_hash(book)
    assert [row["id"] for row in fixture["segments"]] == [s.id for s in book.segments]
    assert [row["segment_hash"] for row in fixture["segments"]] == [
        segment_hash(s.text) for s in book.segments
    ]
    assert fixture["segment_count"] == len(book.segments)
    assert fixture["chapter_count"] == book.chapter_count
    assert fixture["image_count"] == len(book.images)


def test_identity_fixture_carries_no_book_text() -> None:
    """A generated fixture must expose no key that could hold prose."""
    fixture = build_identity_fixture(build_synthetic_book(), "synthetic")
    assert FORBIDDEN_KEYS.isdisjoint(_walk_keys(fixture))


@pytest.mark.parametrize("slug", sorted(EXAMPLE_SOURCES) + ["synthetic"])
def test_committed_identity_fixture_carries_no_book_text(slug: str) -> None:
    """The files actually in git must expose no key that could hold prose."""
    path = _fixture_dir() / f"{slug}.identity.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert FORBIDDEN_KEYS.isdisjoint(_walk_keys(payload)), path.name


def test_id_vectors_reproduce_make_segment_id() -> None:
    """Every probe vector's digests come from the real hash functions."""
    fixture = build_id_vector_fixture()
    assert len(fixture["vectors"]) >= 15
    for vector in fixture["vectors"]:
        assert vector["id"] == make_segment_id(
            vector["text"], vector["chapter_index"], vector["position"]
        )
        assert vector["segment_hash"] == segment_hash(vector["text"])
    texts = [vector["text"] for vector in fixture["vectors"]]
    assert "" in texts, "an empty string must be covered"
    assert any("\u0085" in text for text in texts), "U+0085 must be covered"
    assert any("\u200b" in text for text in texts), "a non-stripped invisible must be covered"


def test_render_fixture_is_stable_and_line_oriented() -> None:
    """Rendering is deterministic and keeps one record per line."""
    fixture = build_identity_fixture(build_synthetic_book(), "synthetic")
    rendered = render_fixture(fixture)
    assert rendered == render_fixture(fixture)
    assert rendered.endswith("}\n")
    assert json.loads(rendered) == fixture
    # ``position`` is segment-only; images carry a chapter index but no position.
    segment_lines = [line for line in rendered.splitlines() if '"position"' in line]
    assert len(segment_lines) == fixture["segment_count"]
    image_lines = [line for line in rendered.splitlines() if '"data_sha1"' in line]
    assert len(image_lines) == fixture["image_count"]


def test_legacy_payload_still_loads() -> None:
    """A pre-S1.14 payload (no images, no heading levels) must still parse."""
    book = build_synthetic_book()
    legacy = json.loads(build_legacy_book_json(book.to_json()))
    assert "images" not in legacy
    assert all("heading_level" not in segment for segment in legacy["segments"])
    restored = Book.from_json(json.dumps(legacy))
    assert restored.images == []
    assert all(segment.heading_level is None for segment in restored.segments)
    assert book_hash(restored) == book_hash(book)


def test_synthetic_fixtures_regenerate_byte_identically(tmp_path: Path) -> None:
    """The committed synthetic fixtures must match a fresh generation."""
    committed = _fixture_dir()
    for path in write_synthetic_fixtures(tmp_path):
        expected = (committed / path.name).read_bytes()
        assert path.read_bytes() == expected, f"{path.name} is stale — regenerate"


def test_resolve_example_ignores_translator_output(tmp_path: Path) -> None:
    """Translated EPUBs sit beside the sources and must never be picked up."""
    (tmp_path / "Sandworm A New Era.epub").write_bytes(b"")
    (tmp_path / "Sandworm A New Era.sl.epub").write_bytes(b"")
    (tmp_path / "Sandworm.si.epub").write_bytes(b"")
    resolved = resolve_example(tmp_path, "Sandworm")
    assert resolved is not None
    assert resolved.name == "Sandworm A New Era.epub"
    assert resolve_example(tmp_path, "Missing Book") is None


def test_example_fixtures_regenerate_byte_identically(tmp_path: Path) -> None:
    """The four committed example fixtures must match a fresh normalize run.

    ``data/`` holds copyrighted books and exists only in the main checkout, so
    this skips rather than fails elsewhere (CLAUDE.md §9).
    """
    examples = _examples_dir()
    if examples is None:
        pytest.skip("data/examples is unavailable (agent worktree or CI)")
    written = write_example_fixtures(tmp_path, examples)
    if len(written) != len(EXAMPLE_SOURCES):
        pytest.skip("not every example book is present")

    committed = _fixture_dir()
    for path in written:
        assert path.read_bytes() == (committed / path.name).read_bytes(), f"{path.name} is stale"

    for slug, (expected_hash, segments, chapters, images) in EXPECTED_IDENTITY.items():
        payload = json.loads((tmp_path / f"{slug}.identity.json").read_text(encoding="utf-8"))
        assert payload["book_hash"] == expected_hash, slug
        assert payload["segment_count"] == segments, slug
        assert payload["chapter_count"] == chapters, slug
        assert payload["image_count"] == images, slug
