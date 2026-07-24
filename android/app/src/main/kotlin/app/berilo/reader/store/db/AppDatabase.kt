package app.berilo.reader.store.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App-wide Room database. Version 1 held the `books` table only (S2.1); version 2 (S2.4)
 * adds `dictionary_entries` for the LLM dictionary cache. Version 1 never shipped (no
 * released build), so the app is built with `fallbackToDestructiveMigration()` rather than
 * a real `Migration` — see [app.berilo.reader.AppContainer].
 *
 * Schema export (`exportSchema`) stays off — turning it on pulls in Room's
 * kotlinx-serialization-based schema bundler on the KSP classpath, which clashes on this
 * dependency set (`docs/findings.md`).
 */
@Database(entities = [BookEntity::class, DictionaryEntryEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        const val DATABASE_NAME = "berilo.db"
    }
}
