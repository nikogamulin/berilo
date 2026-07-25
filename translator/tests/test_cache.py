"""Tests for the translation cache's prompt-version key and its migration (S1.10).

Before S1.10 the ``translations`` primary key was
``(book_hash, segment_hash, model, lang)``. Re-translating a book under a
different prompt therefore hit the cache and returned the *old* text at zero
cost — a prompt experiment or a new default prompt would report "no change"
while never calling the model. These tests pin the fix and the migration that
keeps the five already-translated books' caches valid.

Everything runs against temporary SQLite files; no LLM client is constructed.
"""

from __future__ import annotations

import sqlite3

from berilo import prompts
from berilo.cache import (
    BASELINE_PROMPT_VERSION,
    CallRecord,
    SegmentTranslation,
    TranslationCache,
)

_BOOK = "book-hash-1"
_SEGMENT = "segment-hash-1"
_MODEL = "gpt-5-mini"
_LANG = "sl"


def _call() -> CallRecord:
    return CallRecord(kind="batch", input_tokens=10, output_tokens=20, cost_eur=0.001)


def _store(cache: TranslationCache, text: str, prompt_version: str) -> None:
    cache.store_batch(
        _BOOK,
        _MODEL,
        _LANG,
        [SegmentTranslation(segment_hash=_SEGMENT, text=text, cost_eur=0.0)],
        _call(),
        prompt_version,
    )


def _write_pre_migration_db(path: str) -> None:
    """Create a database with the exact pre-S1.10 ``translations`` schema."""
    conn = sqlite3.connect(path)
    with conn:
        conn.executescript("""
            CREATE TABLE translations (
                book_hash    TEXT NOT NULL,
                segment_hash TEXT NOT NULL,
                model        TEXT NOT NULL,
                lang         TEXT NOT NULL,
                text         TEXT NOT NULL,
                cost_eur     REAL NOT NULL DEFAULT 0,
                created_at   REAL NOT NULL,
                PRIMARY KEY (book_hash, segment_hash, model, lang)
            );
            CREATE TABLE calls (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                book_hash     TEXT NOT NULL,
                model         TEXT NOT NULL,
                lang          TEXT NOT NULL,
                kind          TEXT NOT NULL,
                input_tokens  INTEGER NOT NULL,
                output_tokens INTEGER NOT NULL,
                cost_eur      REAL NOT NULL,
                created_at    REAL NOT NULL
            );
            CREATE TABLE glossaries (
                book_hash  TEXT NOT NULL,
                model      TEXT NOT NULL,
                lang       TEXT NOT NULL,
                terms_json TEXT NOT NULL,
                created_at REAL NOT NULL,
                PRIMARY KEY (book_hash, model, lang)
            );
            """)
        conn.execute(
            "INSERT INTO translations "
            "(book_hash, segment_hash, model, lang, text, cost_eur, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (_BOOK, _SEGMENT, _MODEL, _LANG, "stari prevod", 0.002, 1.0),
        )
        conn.execute(
            "INSERT INTO calls "
            "(book_hash, model, lang, kind, input_tokens, output_tokens, cost_eur, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (_BOOK, _MODEL, _LANG, "batch", 100, 200, 0.002, 1.0),
        )
    conn.close()


def test_baseline_version_matches_the_registry() -> None:
    """The migration's default is exactly the registry's baseline version string."""
    assert BASELINE_PROMPT_VERSION == prompts.BASELINE.version


def test_two_prompt_versions_store_two_rows_and_read_back_their_own_text() -> None:
    """The same segment under two prompts is two cache rows, not one."""
    with TranslationCache(":memory:") as cache:
        _store(cache, "osnovni prevod", "baseline_v1")
        _store(cache, "slogovni prevod", "sl_style_v1")

        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "baseline_v1") == (
            "osnovni prevod"
        )
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "sl_style_v1") == (
            "slogovni prevod"
        )
        rows = cache._conn.execute(
            "SELECT prompt_version, text FROM translations ORDER BY prompt_version"
        ).fetchall()
        assert [(r["prompt_version"], r["text"]) for r in rows] == [
            ("baseline_v1", "osnovni prevod"),
            ("sl_style_v1", "slogovni prevod"),
        ]


def test_unknown_prompt_version_misses_instead_of_serving_another_prompts_text() -> None:
    """A never-run style must MISS — this is the defect that made A/B a no-op."""
    with TranslationCache(":memory:") as cache:
        _store(cache, "osnovni prevod", "baseline_v1")
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "revise_v1") is None
        assert cache.cached_hashes(_BOOK, _MODEL, _LANG, "revise_v1") == set()
        assert cache.cached_hashes(_BOOK, _MODEL, _LANG, "baseline_v1") == {_SEGMENT}


def test_pre_migration_database_opens_and_reads_as_baseline(tmp_path) -> None:
    """An old cache DB migrates in place; its rows are readable as baseline_v1."""
    db = tmp_path / "translations.db"
    _write_pre_migration_db(str(db))

    with TranslationCache(db) as cache:
        columns = {row["name"] for row in cache._conn.execute("PRAGMA table_info(translations)")}
        assert "prompt_version" in columns
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "baseline_v1") == (
            "stari prevod"
        )
        # Cost and timestamp survive the rebuild; call accounting is untouched.
        row = cache._conn.execute("SELECT cost_eur, created_at FROM translations").fetchone()
        assert row["cost_eur"] == 0.002
        assert row["created_at"] == 1.0
        assert cache._conn.execute("SELECT COUNT(*) AS n FROM calls").fetchone()["n"] == 1
        # A variant still misses, so the new prompt actually gets called.
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "sl_style_v1") is None

    # Re-opening a migrated database is a no-op and keeps the data.
    with TranslationCache(db) as cache:
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG) == "stari prevod"
        assert (
            cache._conn.execute(
                "SELECT COUNT(*) AS n FROM sqlite_master WHERE name = 'translations_migrating'"
            ).fetchone()["n"]
            == 0
        )


def test_migration_leaves_the_new_primary_key_in_force(tmp_path) -> None:
    """After migration the key really is 5-column: a variant row can be inserted."""
    db = tmp_path / "translations.db"
    _write_pre_migration_db(str(db))

    with TranslationCache(db) as cache:
        _store(cache, "nov prevod", "sl_style_v1")
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "baseline_v1") == (
            "stari prevod"
        )
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "sl_style_v1") == "nov prevod"


def test_book_context_memo_round_trips_per_prompt_version() -> None:
    """The per-book style memo is memoized per prompt version, not globally."""
    with TranslationCache(":memory:") as cache:
        assert cache.get_book_context(_BOOK, _MODEL, _LANG, "book_context_v1") is None
        cache.store_book_context(
            _BOOK,
            _MODEL,
            _LANG,
            "book_context_v1",
            "Reportorial nonfiction; keep sentences short.",
            CallRecord(kind="book_context", input_tokens=5, output_tokens=6, cost_eur=0.0001),
        )
        assert cache.get_book_context(_BOOK, _MODEL, _LANG, "book_context_v1") == (
            "Reportorial nonfiction; keep sentences short."
        )
        assert cache.get_book_context(_BOOK, _MODEL, _LANG, "some_other_v1") is None
        kinds = [row["kind"] for row in cache._conn.execute("SELECT kind FROM calls ORDER BY id")]
        assert kinds == ["book_context"]
