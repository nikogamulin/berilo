"""Tests for the translation cache's key and its migrations (S1.10, A2).

Before S1.10 the ``translations`` primary key was
``(book_hash, segment_hash, model, lang)``. Re-translating a book under a
different prompt therefore hit the cache and returned the *old* text at zero
cost — a prompt experiment or a new default prompt would report "no change"
while never calling the model. A2 closed the same hole for the glossary, which
is injected into every prompt but was likewise absent from the key.

These tests pin both keys, and the migration that must carry the real
~13k-row cache across without invalidating a single paid row. The migration is
also proven crash-atomic by killing a real process between the ``DROP`` and the
``RENAME``.

Everything runs against temporary SQLite files; no LLM client is constructed.
"""

from __future__ import annotations

import os
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest

from berilo import prompts
from berilo.cache import (
    BASELINE_GLOSSARY_PROMPT_VERSION,
    BASELINE_PROMPT_VERSION,
    EMPTY_GLOSSARY_HASH,
    CallRecord,
    SegmentTranslation,
    TranslationCache,
)
from berilo.glossary import GLOSSARY_PROMPT_VERSION, Glossary, glossary_identity

_BOOK = "book-hash-1"
_SEGMENT = "segment-hash-1"
_MODEL = "gpt-5-mini"
_LANG = "sl"

#: The production cache. Machine-local and gitignored, like ``data/`` — every
#: test that reads it must skip gracefully in a worktree or in CI.
_REAL_CACHE = Path.home() / ".cache" / "berilo" / "translations.db"

_PACKAGE_ROOT = str(Path(__file__).resolve().parents[1])


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


def _write_pre_glossary_key_db(path: str, terms: dict[str, str]) -> None:
    """Create a database with the S1.10 schema: prompt_version, no glossary_hash."""
    conn = sqlite3.connect(path)
    with conn:
        conn.executescript("""
            CREATE TABLE translations (
                book_hash      TEXT NOT NULL,
                segment_hash   TEXT NOT NULL,
                model          TEXT NOT NULL,
                lang           TEXT NOT NULL,
                prompt_version TEXT NOT NULL,
                text           TEXT NOT NULL,
                cost_eur       REAL NOT NULL DEFAULT 0,
                created_at     REAL NOT NULL,
                PRIMARY KEY (book_hash, segment_hash, model, lang, prompt_version)
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
            "(book_hash, segment_hash, model, lang, prompt_version, text, cost_eur, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (_BOOK, _SEGMENT, _MODEL, _LANG, "revise_v1", "placani prevod", 0.002, 1.0),
        )
        import json as _json

        conn.execute(
            "INSERT INTO glossaries (book_hash, model, lang, terms_json, created_at) "
            "VALUES (?, ?, ?, ?, ?)",
            (_BOOK, _MODEL, _LANG, _json.dumps(terms, ensure_ascii=False), 1.0),
        )
    conn.close()


def test_migration_attributes_rows_to_the_glossary_they_were_translated_with(
    tmp_path,
) -> None:
    """Adding glossary_hash to the key must not invalidate one paid row.

    Existing rows inherit the identity of the glossary stored for their
    ``(book, model, lang)`` — the glossary they were actually translated with —
    so the key the current code computes at run time still resolves.
    """
    db = tmp_path / "translations.db"
    terms = {"Kaplan": "Kaplan", "Heartland": "Osrcje"}
    _write_pre_glossary_key_db(str(db), terms)

    with TranslationCache(db) as cache:
        columns = {row["name"] for row in cache._conn.execute("PRAGMA table_info(translations)")}
        assert "glossary_hash" in columns

        # The runtime key: the cached glossary for this book, hashed as the
        # translate path hashes it.
        cached_terms = cache.get_glossary(_BOOK, _MODEL, _LANG, GLOSSARY_PROMPT_VERSION)
        assert cached_terms == terms
        identity = glossary_identity(Glossary(terms=cached_terms))
        assert (
            cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "revise_v1", identity)
            == "placani prevod"
        )
        # And a *different* glossary correctly misses.
        other = glossary_identity(Glossary(terms={"Kaplan": "Kaplanova"}))
        assert cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "revise_v1", other) is None

    # Idempotent: a second open changes nothing and leaves no scratch tables.
    with TranslationCache(db) as cache:
        assert (
            cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "revise_v1", identity)
            == "placani prevod"
        )
        scratch = cache._conn.execute(
            "SELECT COUNT(*) AS n FROM sqlite_master WHERE name LIKE '%_migrating'"
        ).fetchone()["n"]
        assert scratch == 0


def test_migration_attributes_glossaries_to_the_frozen_prompt_version(tmp_path) -> None:
    """Pre-existing glossaries keep resolving under today's derived version."""
    db = tmp_path / "translations.db"
    _write_pre_glossary_key_db(str(db), {"Kaplan": "Kaplan"})
    with TranslationCache(db) as cache:
        assert cache.get_glossary(_BOOK, _MODEL, _LANG, BASELINE_GLOSSARY_PROMPT_VERSION) == {
            "Kaplan": "Kaplan"
        }
        assert cache.get_glossary(_BOOK, _MODEL, _LANG, "glossary_hypothetical_next") is None


def test_rows_without_a_glossary_keep_the_empty_glossary_identity(tmp_path) -> None:
    """A book translated with no glossary resolves under the empty identity."""
    db = tmp_path / "translations.db"
    _write_pre_migration_db(str(db))  # its glossaries table is empty
    with TranslationCache(db) as cache:
        assert (
            cache.get_translation(
                _BOOK, _SEGMENT, _MODEL, _LANG, BASELINE_PROMPT_VERSION, EMPTY_GLOSSARY_HASH
            )
            == "stari prevod"
        )


# --------------------------------------------------------------------------
# Crash atomicity (review finding 9).
# --------------------------------------------------------------------------

#: Run in a child process: patch the connection so the child is *killed* — with
#: ``os._exit``, so no rollback, no atexit, no destructor — the instant the
#: migration's ``DROP TABLE translations`` returns. Before the migration became
#: one explicit transaction, that DROP was autocommitted, so the next open found
#: no ``translations``, created a fresh empty one, and orphaned every migrated
#: row in the scratch table: the whole cache, for every book, silently gone.
_KILL_MID_MIGRATION = """
import os, sqlite3, sys
sys.path.insert(0, {package_root!r})


class KillOnDrop:
    def __init__(self, conn):
        object.__setattr__(self, "_conn", conn)

    def __getattr__(self, name):
        return getattr(self._conn, name)

    def __setattr__(self, name, value):
        setattr(self._conn, name, value)

    def __enter__(self):
        return self._conn.__enter__()

    def __exit__(self, *exc):
        return self._conn.__exit__(*exc)

    def execute(self, sql, *args):
        cursor = self._conn.execute(sql, *args)
        if sql.strip() == "DROP TABLE translations":
            os._exit(9)
        return cursor


_connect = sqlite3.connect
sqlite3.connect = lambda *a, **kw: KillOnDrop(_connect(*a, **kw))

from berilo.cache import TranslationCache

TranslationCache({db!r})
raise SystemExit("the migration never reached DROP TABLE translations")
"""


def test_migration_killed_between_drop_and_rename_loses_nothing(tmp_path) -> None:
    """A process killed mid-migration must leave every paid row recoverable."""
    db = tmp_path / "translations.db"
    _write_pre_glossary_key_db(str(db), {"Kaplan": "Kaplan"})

    script = _KILL_MID_MIGRATION.format(package_root=_PACKAGE_ROOT, db=str(db))
    killed = subprocess.run(
        [sys.executable, "-c", script],
        capture_output=True,
        text=True,
        timeout=60,
        env={**os.environ, "PYTHONPATH": _PACKAGE_ROOT},
    )
    assert (
        killed.returncode == -9 or killed.returncode == 9
    ), f"child did not die at the DROP: rc={killed.returncode} err={killed.stderr}"

    # Next open: the killed transaction rolled back, the migration runs again,
    # and the row is there under the key the current code computes.
    with TranslationCache(db) as cache:
        identity = glossary_identity(Glossary(terms={"Kaplan": "Kaplan"}))
        assert (
            cache.get_translation(_BOOK, _SEGMENT, _MODEL, _LANG, "revise_v1", identity)
            == "placani prevod"
        )
        assert cache._conn.execute("SELECT COUNT(*) AS n FROM translations").fetchone()["n"] == 1


# --------------------------------------------------------------------------
# Non-invalidation against the real cache (S1.10 precedent, CLAUDE.md §9).
# --------------------------------------------------------------------------


@pytest.mark.skipif(not _REAL_CACHE.exists(), reason="no production cache on this machine")
def test_real_cache_migrates_without_invalidating_a_single_row(tmp_path) -> None:
    """Every paid row in the real cache still resolves under the new key.

    Operates on a COPY — the production cache is never opened for writing.
    """
    copy = tmp_path / "translations.db"
    shutil.copy2(_REAL_CACHE, copy)

    before = sqlite3.connect(f"file:{copy}?mode=ro", uri=True)
    before.row_factory = sqlite3.Row
    original = {
        (r["book_hash"], r["segment_hash"], r["model"], r["lang"], r["prompt_version"]): r["text"]
        for r in before.execute(
            "SELECT book_hash, segment_hash, model, lang, prompt_version, text FROM translations"
        )
    }
    glossary_count = before.execute("SELECT COUNT(*) AS n FROM glossaries").fetchone()["n"]
    call_count = before.execute("SELECT COUNT(*) AS n FROM calls").fetchone()["n"]
    before.close()
    assert original, "production cache is empty — nothing to prove"

    # Two opens: the second proves the migration is idempotent.
    with TranslationCache(copy):
        pass
    with TranslationCache(copy) as cache:
        assert cache._conn.execute("SELECT COUNT(*) AS n FROM translations").fetchone()["n"] == len(
            original
        )
        assert (
            cache._conn.execute("SELECT COUNT(*) AS n FROM glossaries").fetchone()["n"]
            == glossary_count
        )
        assert cache._conn.execute("SELECT COUNT(*) AS n FROM calls").fetchone()["n"] == call_count

        # The identity the *runtime* would compute for each book/model/lang.
        identities = {
            (book, model, lang): glossary_identity(
                Glossary(terms=cache.get_glossary(book, model, lang, GLOSSARY_PROMPT_VERSION) or {})
            )
            for book, model, lang in {(b, m, ln) for b, _, m, ln, _ in original}
        }
        for (book, segment, model, lang, version), text in original.items():
            found = cache.get_translation(
                book, segment, model, lang, version, identities[(book, model, lang)]
            )
            assert found == text, f"row {book[:8]}/{segment[:8]}/{version} no longer resolves"


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
