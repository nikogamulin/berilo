package app.berilo.reader.translate.job

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.FakeMetadataExtractor
import app.berilo.reader.translate.engine.RoomTranslationCache
import app.berilo.reader.translate.prompts.StyleTier
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **A killed job resumes without re-billing.**
 *
 * A book is ~162 calls over hours on a battery-powered e-ink tablet that Android may kill at
 * any moment, so this is not a nicety: it is the property that makes the feature usable at all.
 * The engine commits each batch to the cache in one transaction as it returns, so process death
 * costs at most one batch — and the assertion below is on the **fake client's own call
 * counter**, never a log line, because a log line can be written without a call being billed.
 *
 * Driven against the real Room cache (B4), not the in-memory double: resumability is a claim
 * about what actually survives in SQLite under the real six-column key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookTranslationJobResumeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: AppDatabase
    private lateinit var sourceFile: File

    private val settings = LlmSettings(openaiKey = "fixture-key", model = JOB_TEST_MODEL)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        sourceFile = writeSourceEpub(folder.newFile("source.epub"), paragraphsPerChapter = 12)
    }

    @After
    fun tearDown() = database.close()

    /** A job wired to the shared database, with its own client. */
    private fun jobWith(client: TranslatingLlmClient) =
        BookTranslationJob(
            cache = RoomTranslationCache(database.translationCacheDao()),
            bookImporter =
                BookImporter(
                    bookDao = database.bookDao(),
                    metadataExtractor = FakeMetadataExtractor(),
                    booksDir = folder.newFolder(),
                    coversDir = folder.newFolder(),
                    ioDispatcher = dispatcher,
                ),
            workDir = folder.newFolder(),
            clientFactory = { client },
            ioDispatcher = dispatcher,
        )

    private fun request() =
        TranslationRequest(
            sourceId = "source-hash",
            sourceFilePath = sourceFile.absolutePath,
            displayName = "A Quiet Library",
            targetLang = "sl",
            model = JOB_TEST_MODEL,
            tier = StyleTier.ECONOMY,
        )

    /**
     * Kill the run mid-book, resume it, and the two runs together bill exactly what one
     * uninterrupted run bills.
     *
     * **Mutation-proof:** move `cache.storeBatch(...)` out of the engine's batch loop to after
     * it (the "commit once at the end" shape) and the resumed run re-sends every batch — the
     * combined call count then exceeds the baseline and this fails. That single line is the
     * resumability guarantee, and this is the test that notices when it moves.
     */
    @Test
    fun `a job killed mid-book resumes and never re-bills a committed batch`() =
        runTest(dispatcher) {
            // Baseline: what one uninterrupted run of this book costs, on its own database.
            val uninterrupted = TranslatingLlmClient()
            val baselineOutcome = jobWith(uninterrupted).run(request(), settings)
            assertTrue("baseline completed", baselineOutcome is TranslationJobOutcome.Completed)
            val baselineCalls = uninterrupted.callCount
            assertTrue("the baseline made several batch calls", baselineCalls >= 3)

            // A fresh database, so the interrupted pair starts from the same cold cache.
            database.close()
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                ).build()

            // Run 1 dies after two calls — the glossary extraction and one batch.
            val dying = TranslatingLlmClient(dieAfterCalls = 2)
            val died = jobWith(dying).run(request(), settings)
            assertTrue("the killed run reported failure", died is TranslationJobOutcome.Failed)
            assertTrue(
                "and reported it as resumable, so the worker retries rather than giving up",
                (died as TranslationJobOutcome.Failed).retryable,
            )
            // Calls 1 and 2 succeeded (glossary extraction, then one batch, which committed);
            // call 3 raised, standing in for the process dying mid-flight.
            assertEquals("it entered exactly three calls", 3, dying.callCount)
            val succeededBeforeDeath = dying.callCount - 1

            // Run 2 resumes over the same cache.
            val resuming = TranslatingLlmClient()
            val finished = jobWith(resuming).run(request(), settings)
            assertTrue("the resumed run completed", finished is TranslationJobOutcome.Completed)

            // The load-bearing assertion. Everything committed before the kill — the glossary
            // and the first batch — came back from the cache for free, so the interrupted pair
            // bills exactly what one uninterrupted run bills.
            assertEquals(
                "an interrupted book costs the same as an uninterrupted one: nothing committed " +
                    "before the kill was ever re-sent",
                baselineCalls,
                succeededBeforeDeath + resuming.callCount,
            )
            assertTrue(
                "and the resume really did skip work rather than redo it",
                resuming.callCount < baselineCalls,
            )

            val shelved = database.bookDao().observeAll().first()
            assertEquals("and the book is in the library exactly once", 1, shelved.size)
            assertTrue("with a real file", File(shelved.single().filePath).length() > 0L)
        }

    /**
     * The reported cost is **this run's** spend, and it includes the glossary call.
     *
     * The glossary extraction is billed before `translateBook` is entered, so it sits outside
     * the engine's own `TranslationStats`. Reporting the engine's number alone would understate
     * every run by one call — and CLAUDE.md §4 makes the reported figure a promise.
     *
     * **Mutation-proof:** drop [MeteredLlmClient] and report `stats` straight from the engine;
     * the first assertion fails by exactly the glossary call.
     */
    @Test
    fun `the reported cost is every call this run made, glossary extraction included`() =
        runTest(dispatcher) {
            val client = TranslatingLlmClient()
            var lastSeen: TranslationProgress? = null

            val outcome =
                jobWith(client).run(request(), settings) { stats -> lastSeen = stats.toProgress() }
            val completed = outcome as TranslationJobOutcome.Completed

            assertEquals(
                "every call the client was asked for is in the reported total",
                client.callCount,
                completed.stats.apiCalls,
            )
            assertEquals(
                "and the euro figure is what it billed",
                client.billedEur,
                completed.stats.costEur,
                1e-9,
            )
            assertNotNull("progress reached the caller", lastSeen)
            assertEquals(
                "the running figure agrees with the final one",
                completed.stats.costEur,
                lastSeen!!.costEur,
                1e-9,
            )

            // A second, fully-cached run spends nothing — and says so.
            val cached = TranslatingLlmClient()
            val second = jobWith(cached).run(request(), settings) as TranslationJobOutcome.Completed
            assertEquals("no call at all", 0, cached.callCount)
            assertEquals("reported as free, not as the first run's cost", 0.0, second.stats.costEur, 1e-9)
            assertTrue("and the book was recognised, not duplicated", second.alreadyInLibrary)
        }

    /** A missing source file is a dead end the user must act on, not something to retry. */
    @Test
    fun `a vanished source file fails without retrying and without calling the model`() =
        runTest(dispatcher) {
            val client = TranslatingLlmClient()
            val request = request().copy(sourceFilePath = folder.root.resolve("gone.epub").absolutePath)

            val outcome = jobWith(client).run(request, settings) as TranslationJobOutcome.Failed

            assertEquals("no call was made", 0, client.callCount)
            assertTrue("not retryable — retrying cannot conjure the file back", !outcome.retryable)
        }
}
