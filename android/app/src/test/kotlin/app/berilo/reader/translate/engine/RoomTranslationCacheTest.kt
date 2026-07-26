package app.berilo.reader.translate.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.db.TranslationCacheDao
import app.berilo.reader.translate.model.bookHash
import app.berilo.reader.translate.model.glossaryIdentity
import app.berilo.reader.translate.prompts.BASELINE
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine driven against the **real** Room cache (B4), not the in-memory double.
 *
 * `TranslateEngineTest` proves the engine's logic; this proves the production adapter honours
 * the same contract — in particular that a resumed run really does read back what a previous
 * run committed, through the actual six-column primary key. Robolectric supplies the Android
 * SQLite runtime the JVM lacks, so this stays in `./gradlew test`.
 */
@RunWith(RobolectricTestRunner::class)
class RoomTranslationCacheTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TranslationCacheDao
    private lateinit var cache: RoomTranslationCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.translationCacheDao()
        cache = RoomTranslationCache(dao) { FIXED_CLOCK }
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * End-to-end resumability against real SQLite: translate, then translate again over the
     * same database and make **zero** calls.
     */
    @Test
    fun `a full run followed by a second run over real SQLite makes zero calls`() =
        runTest {
            val book = chapterOf(14)

            val first = EchoBatchLlmClient()
            translateBook(book, first, "sl", cache, TEST_MODEL, style = BASELINE)
            assertEquals("batches of 10 and 4", 2, first.callCount)

            val second = EchoBatchLlmClient()
            val translated = translateBook(book, second, "sl", cache, TEST_MODEL, style = BASELINE)

            assertEquals(0, second.callCount)
            assertEquals(14, translated.segments.size)
            assertTrue(translated.segments.all { it.text.startsWith("SL:") })
        }

    @Test
    fun `a committed batch is readable back under the exact six-column key`() =
        runTest {
            val book = chapterOf(3)

            translateBook(book, EchoBatchLlmClient(), "sl", cache, TEST_MODEL, style = BASELINE)

            val hit =
                dao.getTranslation(
                    bookHash = bookHash(book),
                    segmentHash = app.berilo.reader.translate.model.segmentHash("Sentence 1."),
                    model = TEST_MODEL,
                    lang = "sl",
                    promptVersion = BASELINE.version,
                    glossaryHash = glossaryIdentity(null),
                )

            assertEquals("SL:Sentence 1.", hit)
        }

    @Test
    fun `a batch's call accounting reaches the calls table and totals correctly`() =
        runTest {
            val book = chapterOf(14) // two batches, EUR 0.001 each

            translateBook(book, EchoBatchLlmClient(), "sl", cache, TEST_MODEL, style = BASELINE)

            assertEquals(0.002, dao.totalCost(bookHash(book)), 1e-12)
        }

    @Test
    fun `the glossary round-trips through the real table and is not rebuilt`() =
        runTest {
            val book = chapterOf(3)
            val client =
                ScriptedLlmClient(listOf(ScriptedReply.Text("""{"Kaplan": "Kaplan"}""")))

            val built = buildGlossary(book, client, "sl", TEST_MODEL, cache)
            assertEquals(mapOf("Kaplan" to "Kaplan"), built.terms)

            val second = ScriptedLlmClient(emptyList())
            val reloaded = buildGlossary(book, second, "sl", TEST_MODEL, cache)

            assertEquals(0, second.callCount)
            assertEquals(built.terms, reloaded.terms)
        }

    /**
     * The book-context memo has no table on device — reported, not fixed (see
     * [RoomTranslationCache.getBookContext]). Asserted explicitly rather than left to be
     * discovered: an unset optional that silently does nothing is precisely the class of defect
     * CLAUDE.md §9 records from S2.6.
     */
    @Test
    fun `the book-context memo is documented as not persisted, and no style on device needs it`() =
        runTest {
            cache.storeBookContext("bh", TEST_MODEL, "sl", "book_context_v1", "memo", null)

            assertNull(cache.getBookContext("bh", TEST_MODEL, "sl", "book_context_v1"))
            assertNull(
                "no style the device resolves declares a book-context pass",
                app.berilo.reader.translate.prompts.resolveStyle(
                    "sl",
                    context = app.berilo.reader.translate.prompts.ExecutionContext.DEVICE,
                ).bookContextSystem,
            )
            assertNull(
                app.berilo.reader.translate.prompts.resolveStyle(
                    "sl",
                    context = app.berilo.reader.translate.prompts.ExecutionContext.DEVICE,
                    tier = app.berilo.reader.translate.prompts.StyleTier.QUALITY,
                ).bookContextSystem,
            )
        }

    private companion object {
        const val FIXED_CLOCK = 1_700_000_000_000L
    }
}
