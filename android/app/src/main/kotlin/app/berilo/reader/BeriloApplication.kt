package app.berilo.reader

import android.app.Application
import androidx.room.Room
import app.berilo.reader.annotations.AnnotationsRepository
import app.berilo.reader.annotations.TranslationFlagRepository
import app.berilo.reader.annotations.TranslationProvenanceResolver
import app.berilo.reader.dictionary.DictionaryRepository
import app.berilo.reader.dictionary.DictionaryService
import app.berilo.reader.interpretation.InterpretationRepository
import app.berilo.reader.interpretation.InterpretationService
import app.berilo.reader.settings.EncryptedKeyValueStore
import app.berilo.reader.settings.SettingsRepository
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.db.MIGRATION_4_5
import app.berilo.reader.store.db.MIGRATION_5_6
import app.berilo.reader.store.db.MIGRATION_6_7
import app.berilo.reader.store.db.MIGRATION_7_8
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.ReadiumMetadataExtractor
import app.berilo.reader.store.repository.BookRepository
import app.berilo.reader.sync.SyncApi
import app.berilo.reader.sync.SyncEngine
import app.berilo.reader.sync.SyncManager
import app.berilo.reader.sync.auth.AuthGateway
import app.berilo.reader.sync.auth.ClerkAuthGateway
import app.berilo.reader.translate.engine.RoomTranslationCache
import app.berilo.reader.translate.job.BookTranslationJob
import app.berilo.reader.translate.job.SourceBookImporter
import app.berilo.reader.translate.job.TranslationPlanner
import app.berilo.reader.translate.job.TranslationRunner
import app.berilo.reader.translate.job.WorkManagerTranslationRunner
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
            // carries a real migration and highlights/notes survive it. 5 -> 6 (B4) is likewise
            // real: it adds the on-device translation cache, and paid translation work must
            // never be destructively migrated away. 6 -> 7 (B9) adds translation_flags on top of
            // that same cache, so it is real for the same reason plus its own. 7 -> 8 (S3.7) adds
            // the vault's bookkeeping tables over that same cache — additive, and never
            // destructive for the same reason.
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
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

    // B9: flagged bad translations. The provenance resolver reads B4's translation cache, which
    // is the only table on the device that knows which model/prompt/glossary produced a passage.
    val translationFlagRepository =
        TranslationFlagRepository(
            dao = database.translationFlagDao(),
            provenanceResolver = TranslationProvenanceResolver(database.translationCacheDao()),
        )

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

    // --- B7: on-device book translation -----------------------------------------------
    // Source EPUBs live apart from the library: `sources/` holds untranslated inputs with no
    // BookEntity, `translate-work/` is scratch for the assembled output. Only the *translated*
    // EPUB reaches `books/`, through the same BookImporter a CLI-produced file goes through.

    private val sourcesDir = File(app.filesDir, "sources")
    private val translateWorkDir = File(app.filesDir, "translate-work")

    val sourceBookImporter = SourceBookImporter(sourcesDir)

    val translationPlanner = TranslationPlanner()

    val bookTranslationJob =
        BookTranslationJob(
            cache = RoomTranslationCache(database.translationCacheDao()),
            bookImporter = bookImporter,
            workDir = translateWorkDir,
        )

    // Lazy: this container is constructed by every Robolectric-hosted test, where WorkManager
    // has no initialized instance — the same trap S3.2 hit when scheduling from
    // Application.onCreate (see WorkManagerTranslationRunner).
    val translationRunner: TranslationRunner by lazy { WorkManagerTranslationRunner(app) }
}
