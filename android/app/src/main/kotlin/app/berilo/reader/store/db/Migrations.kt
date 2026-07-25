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
