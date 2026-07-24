package app.berilo.reader

import android.app.Application
import androidx.room.Room
import app.berilo.reader.dictionary.DictionaryRepository
import app.berilo.reader.dictionary.DictionaryService
import app.berilo.reader.interpretation.InterpretationRepository
import app.berilo.reader.interpretation.InterpretationService
import app.berilo.reader.settings.EncryptedKeyValueStore
import app.berilo.reader.settings.SettingsRepository
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.ReadiumMetadataExtractor
import app.berilo.reader.store.repository.BookRepository
import java.io.File

/**
 * Hand-rolled composition root. The app is small enough (S2.1) that a DI
 * framework would be pure overhead; this is the single place object graphs
 * are wired.
 */
class BeriloApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val database: AppDatabase =
        Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            // Version 1 (books-only) never shipped in a release build, so a destructive
            // fallback is safe — there is no user data to preserve across the S2.4 bump to
            // version 2 (adds dictionary_entries) or the S2.5 bump to version 3 (adds
            // interpretation_entries).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    private val booksDir = File(app.filesDir, "books")
    private val coversDir = File(app.filesDir, "covers")

    val bookRepository = BookRepository(database.bookDao())

    val bookImporter =
        BookImporter(
            bookDao = database.bookDao(),
            metadataExtractor = ReadiumMetadataExtractor(app),
            booksDir = booksDir,
            coversDir = coversDir,
        )

    val settingsRepository = SettingsRepository(EncryptedKeyValueStore(app))

    val dictionaryRepository = DictionaryRepository(database.dictionaryDao(), DictionaryService())

    val interpretationRepository = InterpretationRepository(database.interpretationDao(), InterpretationService())
}
