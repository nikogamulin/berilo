package app.berilo.reader

import android.app.Application
import androidx.room.Room
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
        Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.DATABASE_NAME).build()

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
}
