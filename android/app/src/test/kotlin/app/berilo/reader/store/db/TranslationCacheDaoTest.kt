package app.berilo.reader.store.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * DAO tests for the on-device translation cache (B4) against a real (in-memory) Room database.
 *
 * Mirrors what `tests/test_cache.py` verifies for `translator/berilo/cache.py`: per-segment
 * lookup, one-transaction batch storage, and — the whole point of the six-column key — that
 * the same segment under two different glossaries is two distinct rows.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationCacheDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TranslationCacheDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.translationCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `getTranslation is a miss before any batch is stored`() =
        runTest {
            val hit = dao.getTranslation("bh", "sh", "gpt-5-mini", "sl", "revise_v1", "gh")

            assertNull(hit)
        }

    @Test
    fun `a stored batch is readable back by exact key`() =
        runTest {
            dao.storeBatch(
                listOf(translation(segmentHash = "sh1", text = "Prevod ena.")),
                call(),
            )

            val text = dao.getTranslation("bh", "sh1", "gpt-5-mini", "sl", "revise_v1", "gh")

            assertEquals("Prevod ena.", text)
        }

    @Test
    fun `cachedHashes returns every segment hash stored under a key`() =
        runTest {
            dao.storeBatch(
                listOf(
                    translation(segmentHash = "sh1", text = "Prvi."),
                    translation(segmentHash = "sh2", text = "Drugi."),
                ),
                call(),
            )

            val hashes = dao.cachedHashes("bh", "gpt-5-mini", "sl", "revise_v1", "gh")

            assertEquals(setOf("sh1", "sh2"), hashes.toSet())
        }

    @Test
    fun `the same segment under two different glossaries stores two distinct rows`() =
        runTest {
            dao.storeBatch(
                listOf(translation(segmentHash = "sh1", glossaryHash = "gh-a", text = "Prevod A.")),
                call(),
            )
            dao.storeBatch(
                listOf(translation(segmentHash = "sh1", glossaryHash = "gh-b", text = "Prevod B.")),
                call(),
            )

            val textA = dao.getTranslation("bh", "sh1", "gpt-5-mini", "sl", "revise_v1", "gh-a")
            val textB = dao.getTranslation("bh", "sh1", "gpt-5-mini", "sl", "revise_v1", "gh-b")

            assertEquals("Prevod A.", textA)
            assertEquals("Prevod B.", textB)
        }

    @Test
    fun `storing a batch also records its call accounting`() =
        runTest {
            dao.storeBatch(
                listOf(translation(segmentHash = "sh1", text = "Prevod.")),
                call(costEur = 0.0123),
            )

            assertEquals(0.0123, dao.totalCost("bh"), 1e-9)
        }

    @Test
    fun `totalCost sums every call recorded for a book`() =
        runTest {
            dao.storeBatch(listOf(translation(segmentHash = "sh1")), call(costEur = 0.01))
            dao.storeBatch(listOf(translation(segmentHash = "sh2")), call(costEur = 0.02))

            assertEquals(0.03, dao.totalCost("bh"), 1e-9)
        }

    @Test
    fun `totalCost is zero for a book with no recorded calls`() =
        runTest {
            assertEquals(0.0, dao.totalCost("unknown-book"), 1e-9)
        }

    @Test
    fun `glossary get is a miss before any store, then round-trips after`() =
        runTest {
            val before = dao.getGlossaryTermsJson("bh", "gpt-5-mini", "sl", "glossary_v1")
            assertNull(before)

            dao.upsertGlossary(
                GlossaryEntity(
                    bookHash = "bh",
                    model = "gpt-5-mini",
                    lang = "sl",
                    promptVersion = "glossary_v1",
                    termsJson = """{"Frodo":"Frodo"}""",
                    createdAt = 1L,
                ),
            )

            val after = dao.getGlossaryTermsJson("bh", "gpt-5-mini", "sl", "glossary_v1")
            assertEquals("""{"Frodo":"Frodo"}""", after)
        }

    /**
     * A batch store must be atomic (CLAUDE.md §2's resumability guarantee): a failure partway
     * through must leave no partial rows, exactly like `cache.py`'s single-transaction
     * `store_batch`. The failure is forced by pre-occupying the call's autoincrement id so the
     * batch's own [dao.insertCall] aborts on a primary-key conflict — proving [TranslationCacheDao.storeBatch]'s
     * `@Transaction` rolls the translations insert back too, not just the failing statement.
     *
     * Mutation-proof: removing `@Transaction` from `storeBatch` turns this red (the translation
     * rows survive the call-insert failure) while every other test here stays green.
     */
    @Test
    fun `a batch store is atomic — a call-insert failure leaves no partial translation rows`() =
        runTest {
            dao.insertCall(call(id = 42L))

            var threw = false
            try {
                dao.storeBatch(
                    listOf(translation(segmentHash = "sh1", text = "Ne bi smelo obstati.")),
                    call(id = 42L),
                )
            } catch (e: SQLiteConstraintException) {
                threw = true
            }

            assertTrue("the conflicting call insert must throw", threw)
            assertNull(
                "a rolled-back batch must leave no translation row behind",
                dao.getTranslation("bh", "sh1", "gpt-5-mini", "sl", "revise_v1", "gh"),
            )
        }

    private fun translation(
        bookHash: String = "bh",
        segmentHash: String = "sh1",
        model: String = "gpt-5-mini",
        lang: String = "sl",
        promptVersion: String = "revise_v1",
        glossaryHash: String = "gh",
        text: String = "Prevedeno besedilo.",
        costEur: Double = 0.0,
        createdAt: Long = 0L,
    ) = TranslationEntity(
        bookHash = bookHash,
        segmentHash = segmentHash,
        model = model,
        lang = lang,
        promptVersion = promptVersion,
        glossaryHash = glossaryHash,
        text = text,
        costEur = costEur,
        createdAt = createdAt,
    )

    private fun call(
        id: Long = 0L,
        bookHash: String = "bh",
        model: String = "gpt-5-mini",
        lang: String = "sl",
        kind: String = "batch",
        costEur: Double = 0.0,
    ) = TranslationCallEntity(
        id = id,
        bookHash = bookHash,
        model = model,
        lang = lang,
        kind = kind,
        inputTokens = 10,
        outputTokens = 20,
        costEur = costEur,
        createdAt = 0L,
    )
}
