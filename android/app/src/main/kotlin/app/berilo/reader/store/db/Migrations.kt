package app.berilo.reader.store.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema 4 -> 5 (S3.2): makes the synced entities sync-shaped, resolving `docs/sync_api.md`
 * [OPEN-1], [OPEN-2] and [OPEN-4].
 *
 * This is the first *real* migration in the app. Versions 1–4 relied on
 * `fallbackToDestructiveMigration` because nothing had shipped, but S2.11 put builds on a Boox
 * device, so highlights and notes are now real user data: dropping the tables here would
 * destroy exactly the content this story exists to protect.
 *
 * Backfill choices, each of which is a deliberate answer to "what is true of rows that predate
 * the column?":
 * - `updatedAt` on `books` is seeded from `addedAt`, and on `dictionary_entries` from
 *   `createdAt`. Both are the row's real last-write time, so the first sync pushes them with
 *   an honest timestamp instead of pretending they changed just now — which would let a stale
 *   local row win last-write-wins against a newer server row.
 * - `deletedAt` starts null everywhere: nothing existing is a tombstone.
 * - `sentence` starts empty because a hash cannot be inverted. The sync client skips pushing
 *   vocabulary rows with an empty sentence rather than sending a value the server rejects.
 */
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN sourceLang TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN targetLang TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN deletedAt INTEGER")
            db.execSQL("UPDATE books SET updatedAt = addedAt")

            db.execSQL("ALTER TABLE highlights ADD COLUMN deletedAt INTEGER")

            db.execSQL(
                "ALTER TABLE dictionary_entries ADD COLUMN sentence TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE dictionary_entries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN deletedAt INTEGER")
            db.execSQL("UPDATE dictionary_entries SET updatedAt = createdAt")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_state (
                    entity TEXT NOT NULL,
                    cursor TEXT,
                    lastPushedAt INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(entity)
                )
                """
                    .trimIndent(),
            )
        }
    }

/**
 * Schema 5 -> 6 (B4): adds the on-device translation cache — the Kotlin mirror of
 * `translator/berilo/cache.py`'s `translations`, `glossaries` and `calls` tables.
 *
 * Purely additive, unlike [MIGRATION_4_5]: nothing on the device has ever written a translation,
 * so there is no existing data to backfill or transform — three `CREATE TABLE` statements are
 * the whole migration. The `translations` primary key matches `cache.py`'s six-column key
 * exactly (`book_hash, segment_hash, model, lang, prompt_version, glossary_hash`), so the same
 * segment translated under two different glossaries occupies two distinct rows.
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS translations (
                    bookHash TEXT NOT NULL, segmentHash TEXT NOT NULL, model TEXT NOT NULL,
                    lang TEXT NOT NULL, promptVersion TEXT NOT NULL, glossaryHash TEXT NOT NULL,
                    text TEXT NOT NULL, costEur REAL NOT NULL, createdAt INTEGER NOT NULL,
                    PRIMARY KEY(bookHash, segmentHash, model, lang, promptVersion, glossaryHash)
                )
                """
                    .trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS glossaries (
                    bookHash TEXT NOT NULL, model TEXT NOT NULL, lang TEXT NOT NULL,
                    promptVersion TEXT NOT NULL, termsJson TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    PRIMARY KEY(bookHash, model, lang, promptVersion)
                )
                """
                    .trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS calls (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, bookHash TEXT NOT NULL, model TEXT NOT NULL,
                    lang TEXT NOT NULL, kind TEXT NOT NULL, inputTokens INTEGER NOT NULL,
                    outputTokens INTEGER NOT NULL, costEur REAL NOT NULL, createdAt INTEGER NOT NULL
                )
                """
                    .trimIndent(),
            )
        }
    }

/**
 * Schema 6 -> 7 (B9): adds `translation_flags` — passages the reader marked as badly translated.
 *
 * Additive like [MIGRATION_5_6], and for a stricter reason: version 6 is where the on-device
 * translation cache lives, so extending the destructive fallback to cover this bump would throw
 * away paid translation work to add an empty table. Nothing pre-existing is read or rewritten
 * here; the load-bearing property is that every row in `books`, `highlights`,
 * `dictionary_entries`, `interpretation_entries`, `sync_state`, `translations`, `glossaries`
 * and `calls` is still there afterwards.
 *
 * The index name is not cosmetic: Room derives `index_<table>_<columns>` from
 * `@Entity(indices = [Index("bookId")])` and validates it when the database is opened, so a
 * differently-named index here would surface as an `IllegalStateException` on the first open
 * after upgrade rather than as a failing migration.
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS translation_flags (
                    id TEXT NOT NULL, bookId TEXT NOT NULL, selectedText TEXT NOT NULL,
                    comment TEXT, locatorJson TEXT NOT NULL, chapterTitle TEXT,
                    cacheBookHash TEXT, cacheSegmentHash TEXT, cacheModel TEXT, cacheLang TEXT,
                    cachePromptVersion TEXT, cacheGlossaryHash TEXT,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER,
                    PRIMARY KEY(id)
                )
                """
                    .trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_translation_flags_bookId " +
                    "ON translation_flags (bookId)",
            )
        }
    }
