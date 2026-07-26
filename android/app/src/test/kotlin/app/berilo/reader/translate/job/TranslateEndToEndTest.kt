package app.berilo.reader.translate.job

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.settings.FakeKeyValueStore
import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.settings.SettingsRepository
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.FakeMetadataExtractor
import app.berilo.reader.translate.engine.RoomTranslationCache
import app.berilo.reader.translate.epub.EpubReader
import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.StyleTier
import app.berilo.reader.ui.translate.TranslateUiState
import app.berilo.reader.ui.translate.TranslateViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * **The story's point: one walked path from user gesture to stored result.**
 *
 * `docs/findings.md` (2026-07-26) records that S2.6 shipped highlights, notes, the notebook and
 * the decoration renderer with no reachable path to any of them, and that two months of green
 * tests said nothing because nobody walked one end to end. B5's engine was in the same position
 * — complete, tested, unreachable. This test is the walk.
 *
 * It goes through the production types at every step: [TranslateViewModel] (the screen's brain),
 * [SourceBookImporter], [TranslationPlanner], [BookTranslationJob], the real
 * [app.berilo.reader.translate.engine.translateBook], the real [EpubReader]/`EpubWriter`, the
 * real [BookImporter], and a **real Room database** for both the library and the translation
 * cache. Two things are substituted, and only two:
 *
 * 1. the LLM client, which is [TranslatingLlmClient] — **no test may spend API budget**; and
 * 2. WorkManager's *scheduler*, replaced by [InlineTranslationRunner], which performs the exact
 *    composition [TranslateWorker.doWork] performs (run the job, map the outcome through
 *    [verdictFor]). The enqueue call and its network constraint are what remain unwalked here;
 *    `TranslateWorkerContractTest` covers the three pure functions the worker composes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TranslateEndToEndTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var client: TranslatingLlmClient
    private lateinit var runner: InlineTranslationRunner
    private lateinit var viewModel: TranslateViewModel
    private lateinit var booksDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        booksDir = folder.newFolder("books")
        client = TranslatingLlmClient()

        val settingsRepository = SettingsRepository(FakeKeyValueStore())
        settingsRepository.save(
            LlmSettings(openaiKey = "fixture-key", model = JOB_TEST_MODEL, targetLang = "sl"),
        )

        val job =
            BookTranslationJob(
                cache = RoomTranslationCache(database.translationCacheDao()),
                bookImporter =
                    BookImporter(
                        bookDao = database.bookDao(),
                        metadataExtractor = FakeMetadataExtractor(),
                        booksDir = booksDir,
                        coversDir = folder.newFolder("covers"),
                        ioDispatcher = dispatcher,
                    ),
                workDir = folder.newFolder("translate-work"),
                clientFactory = { client },
                ioDispatcher = dispatcher,
            )
        runner = InlineTranslationRunner(job)

        viewModel =
            TranslateViewModel(
                sourceImporter = SourceBookImporter(folder.newFolder("sources"), dispatcher),
                planner = TranslationPlanner(EpubReader(), dispatcher),
                settingsRepository = settingsRepository,
                runner = runner,
            )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `import a source EPUB, estimate, confirm, and the translated book is in the library`() =
        runTest(dispatcher) {
            // --- 0. A source-language EPUB, where a SAF pick would deliver it from. ----------
            val picked = writeSourceEpub(folder.newFile("picked.epub"))

            // --- 1. IMPORT: the user picks it. -----------------------------------------------
            viewModel.onSourcePicked({ picked.inputStream() }, "A Quiet Library.epub")
            advanceUntilIdle()

            // --- 2. ESTIMATE: priced, both tiers, nothing spent. -----------------------------
            val estimate = viewModel.uiState.value
            assertTrue("reached the estimate screen, got $estimate", estimate is TranslateUiState.Estimate)
            val plan = (estimate as TranslateUiState.Estimate).plan

            assertEquals("ECONOMY is the device default", StyleTier.ECONOMY, estimate.tier)
            assertEquals("single-pass style", BASELINE.name, plan.economy.styleName)
            assertEquals("chapters", 2, plan.chapterCount)
            assertTrue("segments to translate", plan.economy.estimate.translatableSegments > 0)
            assertEquals("priced for the configured model", JOB_TEST_MODEL, plan.model)
            assertTrue("quality costs more than economy", plan.quality.costEur > plan.economy.costEur)
            assertTrue("a real price is quoted", plan.economy.costEur > 0.0)
            assertEquals(
                "the dry run is free: not one API call to reach a price",
                0,
                client.callCount,
            )
            assertTrue("nothing started", runner.started.isEmpty())

            // --- 3. CONFIRM: the one gesture that authorizes spending. -----------------------
            viewModel.confirmAndTranslate()
            advanceUntilIdle()
            assertEquals("exactly one run requested", 1, runner.started.size)
            assertEquals(
                "the confirmed tier is what was requested",
                StyleTier.ECONOMY,
                runner.started.single().tier,
            )

            // --- 4. RUN: the engine translates against the fake client. ----------------------
            runner.drain()
            advanceUntilIdle()
            assertTrue("the engine actually called the model", client.callCount > 0)
            assertEquals("the worker would report success", WorkerVerdict.SUCCESS, runner.verdicts.single())

            // --- 5. STORED RESULT: the translated EPUB is on disk and in the library. --------
            val shelved = database.bookDao().observeAll().first()
            assertEquals("exactly one book in the library", 1, shelved.size)
            val entity = shelved.single()

            val outputFile = File(entity.filePath)
            assertTrue("the EPUB is on disk at ${outputFile.path}", outputFile.isFile)
            assertTrue("and is not empty", outputFile.length() > 0L)
            assertEquals("it lives in the library directory", booksDir, outputFile.parentFile)

            // --- 6. And it really is TRANSLATED, not merely written. -------------------------
            val source = EpubReader().read(picked)
            val translated = EpubReader().read(outputFile)
            assertEquals("the source declared its own language", "en-US", source.language)
            assertEquals(
                "the shelved EPUB declares the TARGET language, so the reader and any " +
                    "re-import see 'sl' rather than the source tag",
                "sl",
                translated.language,
            )
            assertEquals(
                "segment integrity: 1:1 source to target (CLAUDE.md §2)",
                source.segments.size,
                translated.segments.size,
            )
            // Ids are sha1 over (chapterIndex, position, text), so they *must* move once the text
            // is Slovenian — `translateBook` guarantees id stability on the in-memory book it
            // returns (B5 asserts that), not across a re-read of the written file. What must
            // survive the round trip is the structure each segment sits in: same chapter, same
            // position, same type, in the same order.
            assertEquals(
                "every segment keeps its chapter, position and type",
                source.segments.map { Triple(it.chapterIndex, it.position, it.type) },
                translated.segments.map { Triple(it.chapterIndex, it.position, it.type) },
            )
            assertTrue(
                "every segment carries translated text",
                translated.segments.all { it.text.startsWith("SL:") },
            )
            assertEquals(
                "and the translation is of the matching source segment",
                source.segments.map { "SL:${it.text}" },
                translated.segments.map { it.text },
            )

            // --- 7. The run's own accounting reached the UI. ---------------------------------
            val done = viewModel.uiState.value
            assertTrue("the screen shows completion, got $done", done is TranslateUiState.Done)
            val progress = (done as TranslateUiState.Done).progress
            assertNotNull("a final progress snapshot reached the UI", progress)
            assertEquals("the UI's call count is the client's", client.callCount, progress!!.apiCalls)
            assertEquals("the UI's cost is what was billed", client.billedEur, progress.costEur, 1e-9)
        }

    /**
     * Re-picking the same file after a completed translation costs nothing.
     *
     * The second run re-reads the book, hits the cache for every segment and shelves the same
     * bytes, so [BookImporter] reports a duplicate rather than a second copy of the same book.
     * This is the property that makes the flow safe to re-enter — a user who taps translate
     * twice must not be billed twice.
     */
    @Test
    fun `translating the same book twice bills nothing the second time`() =
        runTest(dispatcher) {
            val picked = writeSourceEpub(folder.newFile("picked.epub"))

            viewModel.onSourcePicked({ picked.inputStream() }, "A Quiet Library.epub")
            advanceUntilIdle()
            viewModel.confirmAndTranslate()
            advanceUntilIdle()
            runner.drain()
            advanceUntilIdle()
            val firstRunCalls = client.callCount
            assertTrue("the first run billed", firstRunCalls > 0)

            viewModel.reset()
            viewModel.onSourcePicked({ picked.inputStream() }, "A Quiet Library.epub")
            advanceUntilIdle()
            viewModel.confirmAndTranslate()
            advanceUntilIdle()
            runner.drain()
            advanceUntilIdle()

            assertEquals("the second run made no call at all", firstRunCalls, client.callCount)
            assertEquals("still one book, not a duplicate", 1, database.bookDao().observeAll().first().size)
            assertEquals(
                "and both runs succeeded",
                listOf(WorkerVerdict.SUCCESS, WorkerVerdict.SUCCESS),
                runner.verdicts,
            )
        }
}
