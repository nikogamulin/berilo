"""SQLite translation cache: resumable, idempotent, per-segment memoization.

The cache is the mechanism behind two hard guarantees of the translate stage
(CLAUDE.md §2, ``docs/project_spec.md`` §4.2):

* **Idempotent & resumable.** Every batch's results are written in one
  transaction *immediately* after the batch returns, so a process killed
  mid-run loses at most the single in-flight batch — already-translated
  segments are never re-sent (never re-billed) on the next run.
* **Deterministic re-runs.** A fully cached book re-translates with zero API
  calls and yields byte-identical text, because translations are keyed on
  stable content hashes, not list position.

The primary table is keyed
``(book_hash, segment_hash, model, lang, prompt_version, glossary_hash)``:

* ``book_hash`` — sha1 over the book's ordered segment IDs, pinning a cache
  entry to one specific book.
* ``segment_hash`` — sha1 over the segment's stripped source text, so two
  segments with identical source text share one translation (extra dedup) and
  a resumed run recognises already-translated text regardless of position.
* ``prompt_version`` — the :class:`~berilo.prompts.TranslationStyle` version
  that produced the text. Without it, re-translating a book under a different
  prompt would silently return the *old* text at zero cost, so a prompt
  experiment (or a new default prompt) would report "no change" while never
  calling the model. Databases written before this column existed are migrated
  in place, their rows attributed to :data:`BASELINE_PROMPT_VERSION`.
* ``glossary_hash`` — sha1 over the *exact* glossary block injected into every
  batch prompt (see :func:`glossary_hash`). The glossary is prompt input just
  as much as the style is, so leaving it out of the key reproduced the
  prompt-version defect one subsystem over: improve the glossary, re-run, and
  every already-translated segment is served from cache under the unchanged
  key while the model is never called (CLAUDE.md §9). Because the identity is
  *derived* from the rendered block rather than declared by hand, it cannot
  drift away from what the model actually saw.

Companion tables: ``calls`` records per-call token/cost accounting for
reporting, ``glossaries`` memoizes the per-book glossary pass (keyed by the
glossary prompt's own version, so changing the extraction prompt or its
sampling re-derives instead of returning the old terms), and ``book_contexts``
memoizes the per-book style memo used by book-context styles (keyed by prompt
version as well, and memoized so a resumed run neither re-bills the memo call
nor silently switches to a differently-worded memo mid-book).
"""

from __future__ import annotations

import hashlib
import json
import logging
import sqlite3
import time
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

from berilo.models import Book

logger = logging.getLogger(__name__)

#: Default on-disk location for the shared translation cache.
DEFAULT_CACHE_DIR = Path.home() / ".cache" / "berilo"
DEFAULT_CACHE_PATH = DEFAULT_CACHE_DIR / "translations.db"

_IN_MEMORY = ":memory:"

#: Prompt version attributed to rows written before the ``prompt_version``
#: column existed. Those rows were produced by the pre-registry prompts, which
#: :data:`berilo.prompts.BASELINE` reproduces byte-identically — the equality is
#: pinned by ``tests/test_cache.py`` so a rename cannot silently invalidate five
#: books' worth of cached translations.
BASELINE_PROMPT_VERSION = "baseline_v1"

#: DDL for the primary table. Held as a constant because the migration in
#: :meth:`TranslationCache._migrate_schema` rebuilds the table from it.
_TRANSLATIONS_DDL = """
    CREATE TABLE IF NOT EXISTS {table} (
        book_hash      TEXT NOT NULL,
        segment_hash   TEXT NOT NULL,
        model          TEXT NOT NULL,
        lang           TEXT NOT NULL,
        prompt_version TEXT NOT NULL,
        glossary_hash  TEXT NOT NULL,
        text           TEXT NOT NULL,
        cost_eur       REAL NOT NULL DEFAULT 0,
        created_at     REAL NOT NULL,
        PRIMARY KEY (book_hash, segment_hash, model, lang, prompt_version, glossary_hash)
    );
"""

#: DDL for the glossary memo table; rebuilt by the same migration.
_GLOSSARIES_DDL = """
    CREATE TABLE IF NOT EXISTS {table} (
        book_hash      TEXT NOT NULL,
        model          TEXT NOT NULL,
        lang           TEXT NOT NULL,
        prompt_version TEXT NOT NULL,
        terms_json     TEXT NOT NULL,
        created_at     REAL NOT NULL,
        PRIMARY KEY (book_hash, model, lang, prompt_version)
    );
"""

#: Columns that must be present in ``translations`` for the current key. A
#: database missing any of them is rebuilt (once, atomically) on open.
_TRANSLATION_KEY_COLUMNS = frozenset({"prompt_version", "glossary_hash"})

#: Scratch table names used while rebuilding during migration.
_MIGRATION_TABLE = "translations_migrating"
_GLOSSARIES_MIGRATION_TABLE = "glossaries_migrating"


def book_hash(book: Book) -> str:
    """Derive a stable per-book hash from the ordered segment IDs.

    Args:
        book: The book whose identity to hash.

    Returns:
        A 40-character hexadecimal sha1 digest over the newline-joined,
        document-ordered segment IDs.
    """
    payload = "\n".join(segment.id for segment in book.segments).encode("utf-8")
    return hashlib.sha1(payload).hexdigest()


def segment_hash(text: str) -> str:
    """Derive a stable content hash for a segment's source text.

    Keyed on stripped source text (not position) so identical text shares a
    cached translation and resumed runs recognise already-translated content.

    Args:
        text: The segment's source text.

    Returns:
        A 40-character hexadecimal sha1 digest over the stripped text.
    """
    return hashlib.sha1(text.strip().encode("utf-8")).hexdigest()


def glossary_hash(prompt_block: str) -> str:
    """Derive the cache-key component for a glossary from its prompt block.

    Hashing the *rendered block* — the literal text injected into every batch
    prompt — rather than a declared version means the key cannot fall out of
    step with what the model was shown. An empty block (no glossary, or a
    glossary with no terms) hashes to :data:`EMPTY_GLOSSARY_HASH`.

    Args:
        prompt_block: The glossary's rendered prompt block, possibly empty.

    Returns:
        A 40-character hexadecimal sha1 digest over the stripped block.
    """
    return hashlib.sha1(prompt_block.strip().encode("utf-8")).hexdigest()


#: Identity of "no glossary was injected into the prompt".
EMPTY_GLOSSARY_HASH = glossary_hash("")

#: Glossary-prompt version attributed to ``glossaries`` rows written before the
#: column existed. Frozen deliberately: those rows were produced by the glossary
#: prompt as it stood when this column was introduced, so attributing them to
#: this literal keeps them resolving at zero cost while any *later* prompt edit
#: correctly misses. ``tests/test_glossary_cache.py`` pins today's derived version to
#: this value — when you intentionally change the glossary prompt, update the
#: expectation in that test, never this constant.
BASELINE_GLOSSARY_PROMPT_VERSION = "glossary_3512dce61808"


@dataclass(frozen=True)
class SegmentTranslation:
    """One translated segment ready to be persisted.

    Attributes:
        segment_hash: Content hash of the source text (see :func:`segment_hash`).
        text: Translated text for the segment.
        cost_eur: Share of the batch's cost attributed to this segment.
    """

    segment_hash: str
    text: str
    cost_eur: float


@dataclass(frozen=True)
class CallRecord:
    """Accounting for one or more API calls, aggregated per stored batch.

    Attributes:
        kind: Call category (``"batch"``, ``"glossary"``, ``"book_context"``).
        input_tokens: Total input (prompt) tokens billed.
        output_tokens: Total output (completion) tokens billed.
        cost_eur: Total EUR cost of the call(s).
    """

    kind: str
    input_tokens: int
    output_tokens: int
    cost_eur: float


class TranslationCache:
    """SQLite-backed store for segment translations, call accounting, glossaries.

    Safe to open the same on-disk database from multiple sequential runs; the
    schema is created on demand. Use as a context manager, or call
    :meth:`close` when finished.
    """

    def __init__(self, db_path: str | Path = DEFAULT_CACHE_PATH) -> None:
        """Open (creating if needed) the cache database.

        Args:
            db_path: Filesystem path to the SQLite database, or ``":memory:"``
                for an ephemeral in-process cache. Parent directories are
                created for on-disk paths.
        """
        self.db_path = db_path
        if str(db_path) != _IN_MEMORY:
            Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(db_path))
        self._conn.row_factory = sqlite3.Row
        self._migrate_schema()
        self._init_schema()

    def _columns(self, table: str) -> set[str]:
        """Return the column names of ``table``, empty when it does not exist."""
        return {row["name"] for row in self._conn.execute(f"PRAGMA table_info({table})")}

    def _migrate_schema(self) -> None:
        """Bring an older cache up to the current key, atomically.

        SQLite cannot extend a primary key in place, so each affected table is
        rebuilt. **The entire migration — every CREATE, INSERT, DROP and
        RENAME, across both tables — runs inside one explicit transaction.**
        SQLite's DDL is transactional, so a process killed at *any* point rolls
        back on the next open and the migration simply runs again against
        untouched data. (Before this was explicit, ``DROP TABLE translations``
        and the following ``ALTER ... RENAME`` were two autocommitted
        statements: a kill between them left every migrated row orphaned in the
        scratch table while the next open created a fresh empty
        ``translations`` — the whole cache, for every book, silently lost.)

        Attribution of pre-existing rows is chosen so nothing re-bills:
        ``prompt_version`` defaults to :data:`BASELINE_PROMPT_VERSION` and
        ``glossary_hash`` is recovered from the ``glossaries`` table — the
        glossary those rows were actually translated with — through the same
        :func:`berilo.glossary.glossary_identity` the runtime lookup uses.
        """
        translation_columns = self._columns("translations")
        glossary_columns = self._columns("glossaries")
        needs_translations = bool(translation_columns) and not (
            _TRANSLATION_KEY_COLUMNS <= translation_columns
        )
        needs_glossaries = bool(glossary_columns) and "prompt_version" not in glossary_columns
        if not (needs_translations or needs_glossaries):
            return

        logger.info("Migrating cache %s to the current key (atomic).", self.db_path)
        self._conn.execute("BEGIN IMMEDIATE")
        try:
            if needs_glossaries:
                self._rebuild_glossaries()
            if needs_translations:
                self._rebuild_translations(translation_columns)
            self._conn.execute("COMMIT")
        except BaseException:
            self._conn.execute("ROLLBACK")
            raise

    def _rebuild_glossaries(self) -> None:
        """Rebuild ``glossaries`` with ``prompt_version`` in its primary key."""
        self._conn.execute(f"DROP TABLE IF EXISTS {_GLOSSARIES_MIGRATION_TABLE}")
        self._conn.execute(_GLOSSARIES_DDL.format(table=_GLOSSARIES_MIGRATION_TABLE))
        self._conn.execute(
            f"INSERT OR IGNORE INTO {_GLOSSARIES_MIGRATION_TABLE} "
            "(book_hash, model, lang, prompt_version, terms_json, created_at) "
            "SELECT book_hash, model, lang, ?, terms_json, created_at FROM glossaries",
            (BASELINE_GLOSSARY_PROMPT_VERSION,),
        )
        self._conn.execute("DROP TABLE glossaries")
        self._conn.execute(f"ALTER TABLE {_GLOSSARIES_MIGRATION_TABLE} RENAME TO glossaries")

    def _rebuild_translations(self, existing_columns: set[str]) -> None:
        """Rebuild ``translations`` with the full six-column primary key.

        Args:
            existing_columns: Column names of the table being replaced, used to
                tell a pre-registry database (no ``prompt_version``) from one
                that only lacks ``glossary_hash``.
        """
        from berilo.glossary import Glossary, glossary_identity

        self._conn.execute(f"DROP TABLE IF EXISTS {_MIGRATION_TABLE}")
        self._conn.execute(_TRANSLATIONS_DDL.format(table=_MIGRATION_TABLE))

        has_prompt_version = "prompt_version" in existing_columns
        prompt_expr = "prompt_version" if has_prompt_version else "?"
        prompt_params: tuple[str, ...] = () if has_prompt_version else (BASELINE_PROMPT_VERSION,)
        self._conn.execute(
            f"INSERT OR IGNORE INTO {_MIGRATION_TABLE} "
            "(book_hash, segment_hash, model, lang, prompt_version, glossary_hash, "
            "text, cost_eur, created_at) "
            f"SELECT book_hash, segment_hash, model, lang, {prompt_expr}, ?, "
            "text, cost_eur, created_at FROM translations",
            (*prompt_params, EMPTY_GLOSSARY_HASH),
        )

        # Attribute each already-translated (book, model, lang) to the glossary
        # it was translated with; anything with no glossary row keeps the
        # empty-glossary identity, which is what the runtime lookup computes.
        if self._columns("glossaries"):
            groups = self._conn.execute(
                "SELECT book_hash, model, lang, terms_json FROM glossaries"
            ).fetchall()
            for row in groups:
                loaded = json.loads(row["terms_json"])
                terms = {str(key): str(value) for key, value in loaded.items()}
                identity = glossary_identity(Glossary(terms=terms))
                self._conn.execute(
                    f"UPDATE {_MIGRATION_TABLE} SET glossary_hash = ? "
                    "WHERE book_hash = ? AND model = ? AND lang = ? AND glossary_hash = ?",
                    (identity, row["book_hash"], row["model"], row["lang"], EMPTY_GLOSSARY_HASH),
                )

        self._conn.execute("DROP TABLE translations")
        self._conn.execute(f"ALTER TABLE {_MIGRATION_TABLE} RENAME TO translations")

    def _init_schema(self) -> None:
        """Create the cache tables if they do not already exist."""
        with self._conn:
            self._conn.executescript(
                _TRANSLATIONS_DDL.format(table="translations")
                + _GLOSSARIES_DDL.format(table="glossaries")
                + """
                CREATE TABLE IF NOT EXISTS calls (
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
                CREATE TABLE IF NOT EXISTS book_contexts (
                    book_hash      TEXT NOT NULL,
                    model          TEXT NOT NULL,
                    lang           TEXT NOT NULL,
                    prompt_version TEXT NOT NULL,
                    memo           TEXT NOT NULL,
                    created_at     REAL NOT NULL,
                    PRIMARY KEY (book_hash, model, lang, prompt_version)
                );
                """
            )

    def get_translation(
        self,
        book_hash_: str,
        segment_hash_: str,
        model: str,
        lang: str,
        prompt_version: str = BASELINE_PROMPT_VERSION,
        glossary_hash_: str = EMPTY_GLOSSARY_HASH,
    ) -> str | None:
        """Return the cached translation for a segment, or ``None`` if absent.

        Args:
            book_hash_: The owning book's hash.
            segment_hash_: The segment's source-text hash.
            model: Model identifier the translation was produced with.
            lang: Target language code.
            prompt_version: Translation style version that produced the text.
                Defaults to the baseline, which is also what migrated
                pre-registry rows are attributed to.
            glossary_hash_: Identity of the glossary injected into the prompt
                (see :func:`berilo.glossary.glossary_identity`). Defaults to
                :data:`EMPTY_GLOSSARY_HASH`, i.e. "no glossary was injected".

        Returns:
            The cached translated text, or ``None`` on a miss.
        """
        row = self._conn.execute(
            "SELECT text FROM translations "
            "WHERE book_hash = ? AND segment_hash = ? AND model = ? AND lang = ? "
            "AND prompt_version = ? AND glossary_hash = ?",
            (book_hash_, segment_hash_, model, lang, prompt_version, glossary_hash_),
        ).fetchone()
        return row["text"] if row is not None else None

    def cached_hashes(
        self,
        book_hash_: str,
        model: str,
        lang: str,
        prompt_version: str = BASELINE_PROMPT_VERSION,
        glossary_hash_: str = EMPTY_GLOSSARY_HASH,
    ) -> set[str]:
        """Return the set of segment hashes already translated for this key.

        Args:
            book_hash_: The owning book's hash.
            model: Model identifier.
            lang: Target language code.
            prompt_version: Translation style version that produced the text.
            glossary_hash_: Identity of the glossary injected into the prompt.

        Returns:
            Every ``segment_hash`` present in the cache for the key.
        """
        rows = self._conn.execute(
            "SELECT segment_hash FROM translations "
            "WHERE book_hash = ? AND model = ? AND lang = ? AND prompt_version = ? "
            "AND glossary_hash = ?",
            (book_hash_, model, lang, prompt_version, glossary_hash_),
        ).fetchall()
        return {row["segment_hash"] for row in rows}

    def store_batch(
        self,
        book_hash_: str,
        model: str,
        lang: str,
        translations: Sequence[SegmentTranslation],
        call: CallRecord,
        prompt_version: str = BASELINE_PROMPT_VERSION,
        glossary_hash_: str = EMPTY_GLOSSARY_HASH,
    ) -> None:
        """Persist a batch's translations and its call accounting atomically.

        Written in a single transaction so a crash cannot leave a batch
        half-committed. Existing rows are left untouched (``INSERT OR IGNORE``)
        to keep re-runs deterministic.

        Args:
            book_hash_: The owning book's hash.
            model: Model identifier.
            lang: Target language code.
            translations: The batch's segment translations.
            call: Aggregate token/cost accounting for the batch's API call(s).
            prompt_version: Translation style version that produced the text.
            glossary_hash_: Identity of the glossary injected into the prompt
                that produced the text. Must match what the reader computes, or
                the rows are unreachable.
        """
        now = time.time()
        with self._conn:
            self._conn.executemany(
                "INSERT OR IGNORE INTO translations "
                "(book_hash, segment_hash, model, lang, prompt_version, glossary_hash, "
                "text, cost_eur, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    (
                        book_hash_,
                        t.segment_hash,
                        model,
                        lang,
                        prompt_version,
                        glossary_hash_,
                        t.text,
                        t.cost_eur,
                        now,
                    )
                    for t in translations
                ],
            )
            self._conn.execute(
                "INSERT INTO calls "
                "(book_hash, model, lang, kind, input_tokens, output_tokens, cost_eur, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    book_hash_,
                    model,
                    lang,
                    call.kind,
                    call.input_tokens,
                    call.output_tokens,
                    call.cost_eur,
                    now,
                ),
            )

    def get_glossary(
        self,
        book_hash_: str,
        model: str,
        lang: str,
        prompt_version: str = BASELINE_GLOSSARY_PROMPT_VERSION,
    ) -> dict[str, str] | None:
        """Return the cached glossary term map, or ``None`` if not built yet.

        Args:
            book_hash_: The owning book's hash.
            model: Model identifier the glossary was built with.
            lang: Target language code.
            prompt_version: Identity of the glossary-extraction prompt and its
                sampling (see :func:`berilo.glossary.glossary_prompt_version`).

        Returns:
            The ``source term -> fixed rendering`` map, or ``None``.
        """
        row = self._conn.execute(
            "SELECT terms_json FROM glossaries "
            "WHERE book_hash = ? AND model = ? AND lang = ? AND prompt_version = ?",
            (book_hash_, model, lang, prompt_version),
        ).fetchone()
        if row is None:
            return None
        loaded = json.loads(row["terms_json"])
        return {str(key): str(value) for key, value in loaded.items()}

    def store_glossary(
        self,
        book_hash_: str,
        model: str,
        lang: str,
        terms: Mapping[str, str],
        call: CallRecord | None = None,
        prompt_version: str = BASELINE_GLOSSARY_PROMPT_VERSION,
    ) -> None:
        """Persist the per-book glossary term map (and optional call accounting).

        Args:
            book_hash_: The owning book's hash.
            model: Model identifier.
            lang: Target language code.
            terms: The ``source term -> fixed rendering`` map.
            call: Optional token/cost accounting for the glossary API call.
            prompt_version: Identity of the glossary-extraction prompt and its
                sampling that produced ``terms``.
        """
        now = time.time()
        with self._conn:
            self._conn.execute(
                "INSERT OR REPLACE INTO glossaries "
                "(book_hash, model, lang, prompt_version, terms_json, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (
                    book_hash_,
                    model,
                    lang,
                    prompt_version,
                    json.dumps(dict(terms), ensure_ascii=False),
                    now,
                ),
            )
            if call is not None:
                self._conn.execute(
                    "INSERT INTO calls "
                    "(book_hash, model, lang, kind, input_tokens, output_tokens, "
                    "cost_eur, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        book_hash_,
                        model,
                        lang,
                        call.kind,
                        call.input_tokens,
                        call.output_tokens,
                        call.cost_eur,
                        now,
                    ),
                )

    def get_book_context(
        self, book_hash_: str, model: str, lang: str, prompt_version: str
    ) -> str | None:
        """Return the cached per-book style memo, or ``None`` if not built yet.

        Args:
            book_hash_: The owning book's hash.
            model: Model identifier the memo was built with.
            lang: Target language code.
            prompt_version: Translation style version that asked for the memo.

        Returns:
            The memo text, or ``None``.
        """
        row = self._conn.execute(
            "SELECT memo FROM book_contexts "
            "WHERE book_hash = ? AND model = ? AND lang = ? AND prompt_version = ?",
            (book_hash_, model, lang, prompt_version),
        ).fetchone()
        return row["memo"] if row is not None else None

    def store_book_context(
        self,
        book_hash_: str,
        model: str,
        lang: str,
        prompt_version: str,
        memo: str,
        call: CallRecord | None = None,
    ) -> None:
        """Persist the per-book style memo (and optional call accounting).

        Memoizing the memo keeps a resumed run both free and deterministic: it
        neither re-bills the derivation call nor switches the rest of the book
        onto differently-worded guidance.

        Args:
            book_hash_: The owning book's hash.
            model: Model identifier.
            lang: Target language code.
            prompt_version: Translation style version that asked for the memo.
            memo: The one-paragraph style memo.
            call: Optional token/cost accounting for the derivation call.
        """
        now = time.time()
        with self._conn:
            self._conn.execute(
                "INSERT OR REPLACE INTO book_contexts "
                "(book_hash, model, lang, prompt_version, memo, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (book_hash_, model, lang, prompt_version, memo, now),
            )
            if call is not None:
                self._conn.execute(
                    "INSERT INTO calls "
                    "(book_hash, model, lang, kind, input_tokens, output_tokens, "
                    "cost_eur, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        book_hash_,
                        model,
                        lang,
                        call.kind,
                        call.input_tokens,
                        call.output_tokens,
                        call.cost_eur,
                        now,
                    ),
                )

    def close(self) -> None:
        """Close the underlying SQLite connection."""
        self._conn.close()

    def __enter__(self) -> TranslationCache:
        """Enter the runtime context, returning ``self``."""
        return self

    def __exit__(self, *exc: object) -> None:
        """Close the connection on context exit."""
        self.close()


def total_cost(records: Iterable[CallRecord]) -> float:
    """Sum the EUR cost across call records.

    Args:
        records: Call records to total.

    Returns:
        The summed EUR cost.
    """
    return sum(record.cost_eur for record in records)
