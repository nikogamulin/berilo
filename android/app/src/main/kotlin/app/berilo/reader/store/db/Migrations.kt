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
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookHash TEXT NOT NULL, model TEXT NOT NULL,
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

/**
 * Schema 7 -> 8 (S3.7): adds the personal book vault's device-side bookkeeping —
 * `vault_books`, `vault_translations` and `vault_glossaries`.
 *
 * Additive, like [MIGRATION_5_6] and [MIGRATION_6_7], and for the same reason those two had to
 * be: version 7 holds the paid translation cache, so extending the destructive fallback over this
 * bump would throw away real money to add three empty tables. Nothing pre-existing is read or
 * rewritten; the load-bearing property is that every row in every v7 table is still there
 * afterwards, byte for byte.
 *
 * **`userId` leads every primary key here, and that is the whole point of the migration**
 * (`docs/sync_api.md` §8.2(1)). `vault_translations` in particular is the on-device cache's
 * six-column key with an owner prepended: the six columns alone are content-addressed, so hosted
 * without an owner two users importing the same ISBN would collide on `bookHash` and the second
 * would be served the first's translated text (§8.3(1)). Putting the owner in the *key* — rather
 * than in a `WHERE` clause or an RLS policy alone — is what makes that collision impossible to
 * reintroduce by dropping a predicate.
 *
 * The DDL below must match what Room generates from [VaultBookEntity], [VaultTranslationEntity]
 * and [VaultGlossaryEntity] **exactly**, down to `NOT NULL`: SQLite's `table_info` reports
 * `notnull=0` without the keyword and Room's `TableInfo` comparison is exact, so a mismatch
 * throws `IllegalStateException: Migration didn't properly handle` on the first upgrade open
 * while fresh installs stay green. That is precisely how B4's `calls.id` defect shipped
 * (`docs/findings.md`, 2026-07-27), so `Migration7To8Test` asserts every column with
 * `PRAGMA table_info` and then reopens the file through Room to run its own `validateMigration`.
 *
 * Nullability, chosen to say something true about a row that predates its first upload:
 * `kdfSalt`, `algorithm` and `uploadedAt` are nullable because a book can be opted in and not yet
 * pushed. `enabled` is `INTEGER NOT NULL` with **no SQL `DEFAULT`** even though §8.2(4)'s default
 * is off — the off-by-default rule is enforced in [VaultDao] and
 * [app.berilo.reader.vault.VaultRepository] (a missing row reads as off), and adding a `DEFAULT 0`
 * here would make `PRAGMA table_info` report a `dflt_value` that Room's own `TableInfo` does not
 * expect from an entity with no `@ColumnInfo(defaultValue = …)`. These three statements are
 * copied from Room's generated `AppDatabase_Impl` for exactly that reason.
 */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vault_books (
                    userId TEXT NOT NULL, bookHash TEXT NOT NULL,
                    enabled INTEGER NOT NULL, objectPath TEXT NOT NULL,
                    kdfSalt BLOB, algorithm TEXT, sizeBytes INTEGER NOT NULL,
                    uploadedAt INTEGER, updatedAt INTEGER NOT NULL, deletedAt INTEGER,
                    PRIMARY KEY(userId, bookHash)
                )
                """
                    .trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vault_translations (
                    userId TEXT NOT NULL, bookHash TEXT NOT NULL, segmentHash TEXT NOT NULL,
                    model TEXT NOT NULL, lang TEXT NOT NULL, promptVersion TEXT NOT NULL,
                    glossaryHash TEXT NOT NULL, uploadedAt INTEGER NOT NULL,
                    PRIMARY KEY(userId, bookHash, segmentHash, model, lang, promptVersion,
                        glossaryHash)
                )
                """
                    .trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vault_glossaries (
                    userId TEXT NOT NULL, bookHash TEXT NOT NULL, model TEXT NOT NULL,
                    lang TEXT NOT NULL, promptVersion TEXT NOT NULL,
                    uploadedAt INTEGER NOT NULL,
                    PRIMARY KEY(userId, bookHash, model, lang, promptVersion)
                )
                """
                    .trimIndent(),
            )
        }
    }
