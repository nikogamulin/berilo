package app.berilo.reader.store.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * App-wide Room database. Version 1 held the `books` table only (S2.1); version 2 (S2.4)
 * added `dictionary_entries` for the LLM dictionary cache; version 3 (S2.5) adds
 * `interpretation_entries` for the paragraph interpretation cache; version 4 (S2.6) adds
 * `highlights` for highlights/notes; version 5 (S3.2) makes the synced entities sync-shaped
 * (sync timestamps, tombstones, raw dictionary sentence) and adds `sync_state`.
 *
 * Versions 1–4 predate any released build, so the app still falls back to a destructive
 * migration for those; 4 -> 5 is a real [MIGRATION_4_5] because S2.11 put builds on a device
 * and highlights are now user data worth keeping — see [app.berilo.reader.AppContainer].
 *
 * Schema export (`exportSchema`) stays off — turning it on pulls in Room's
 * kotlinx-serialization-based schema bundler on the KSP classpath, which clashes on this
 * dependency set (`docs/findings.md`).
 */
@Database(
    entities = [
        BookEntity::class,
        DictionaryEntryEntity::class,
        InterpretationEntryEntity::class,
        HighlightEntity::class,
        SyncStateEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(HighlightColorConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun dictionaryDao(): DictionaryDao

    abstract fun interpretationDao(): InterpretationDao

    abstract fun highlightDao(): HighlightDao

    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DATABASE_NAME = "berilo.db"
    }
}
