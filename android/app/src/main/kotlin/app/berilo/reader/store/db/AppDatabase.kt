package app.berilo.reader.store.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App-wide Room database. Version 1 holds the `books` table only (S2.1).
 *
 * Schema export (`exportSchema`) stays off until the first migration lands —
 * there is nothing to diff against yet, and turning it on pulls in Room's
 * kotlinx-serialization-based schema bundler on the KSP classpath.
 */
@Database(entities = [BookEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        const val DATABASE_NAME = "berilo.db"
    }
}
