package app.berilo.reader

import android.app.Application
import androidx.room.Room
import app.berilo.reader.annotations.AnnotationsRepository
import app.berilo.reader.dictionary.DictionaryRepository
import app.berilo.reader.dictionary.DictionaryService
import app.berilo.reader.interpretation.InterpretationRepository
import app.berilo.reader.interpretation.InterpretationService
import app.berilo.reader.settings.EncryptedKeyValueStore
import app.berilo.reader.settings.SettingsRepository
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.db.MIGRATION_4_5
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.ReadiumMetadataExtractor
import app.berilo.reader.store.repository.BookRepository
import app.berilo.reader.sync.SyncApi
import app.berilo.reader.sync.SyncEngine
import app.berilo.reader.sync.SyncManager
import app.berilo.reader.sync.auth.AuthGateway
import app.berilo.reader.sync.auth.ClerkAuthGateway
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
            // Versions 1-4 never shipped in a release build, so a destructive fallback is safe
            // across the S2.4 bump to version 2 (adds dictionary_entries), the S2.5 bump to
            // version 3 (adds interpretation_entries), and the S2.6 bump to version 4 (adds
            // highlights). Version 5 is different: S2.11 put builds on a device, so 4 -> 5
            // carries a real migration and highlights/notes survive it.
            .addMigrations(MIGRATION_4_5)
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

    val annotationsRepository = AnnotationsRepository(database.highlightDao())

    // --- S3.2: cloud sync -------------------------------------------------------------
    // Built from BuildConfig values injected at build time (app/build.gradle.kts). With no
    // publishable key the gateway reports Unconfigured and every path below is inert, so a
    // credential-free clone still runs the whole app minus the account screen.

    val authGateway: AuthGateway =
        ClerkAuthGateway(app, BuildConfig.CLERK_PUBLISHABLE_KEY)

    private val syncApi =
        SyncApi(
            baseUrl = BuildConfig.SYNC_API_BASE_URL,
            tokenProvider = authGateway::token,
        )

    private val syncEngine =
        SyncEngine(
            api = syncApi,
            bookDao = database.bookDao(),
            highlightDao = database.highlightDao(),
            dictionaryDao = database.dictionaryDao(),
            syncStateDao = database.syncStateDao(),
        )

    val syncManager = SyncManager(authGateway, syncEngine)
}
