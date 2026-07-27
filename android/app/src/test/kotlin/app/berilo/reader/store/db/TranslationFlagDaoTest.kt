package app.berilo.reader.store.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO tests for B9's `translation_flags`, against a real (in-memory) Room database — the same
 * Robolectric pattern as [HighlightDaoTest].
 */
@RunWith(RobolectricTestRunner::class)
class TranslationFlagDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TranslationFlagDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.translationFlagDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `observeForBook on an empty table emits an empty list`() =
        runTest {
            assertTrue(dao.observeForBook("book-1").first().isEmpty())
        }

    @Test
    fun `insert then getById round-trips every field, provenance included`() =
        runTest {
            val entity = entity(id = "f1")

            dao.insert(entity)

            assertEquals(entity, dao.getById("f1"))
        }

    @Test
    fun `a flag with no comment and no provenance persists them all as null`() =
        runTest {
            dao.insert(entity(id = "f1", comment = null, withProvenance = false))

            val found = dao.getById("f1")
            assertNotNull(found)
            assertNull(found?.comment)
            assertNull(found?.cacheBookHash)
            assertNull(found?.cacheSegmentHash)
            assertNull(found?.cacheModel)
            assertNull(found?.cacheLang)
            assertNull(found?.cachePromptVersion)
            assertNull(found?.cacheGlossaryHash)
        }

    @Test
    fun `observeForBook only returns rows for that book, oldest-created first`() =
        runTest {
            dao.insert(entity(id = "f1", bookId = "book-1", createdAt = 2_000L))
            dao.insert(entity(id = "f2", bookId = "book-2", createdAt = 1_000L))
            dao.insert(entity(id = "f3", bookId = "book-1", createdAt = 1_000L))

            assertEquals(listOf("f3", "f1"), dao.observeForBook("book-1").first().map { it.id })
        }

    @Test
    fun `softDelete tombstones the row instead of removing it`() =
        runTest {
            dao.insert(entity(id = "f1"))

            dao.softDelete("f1", at = 9_999L)

            assertNull("a tombstoned flag must not reach the notebook", dao.getById("f1"))
            assertTrue(dao.observeForBook("book-1").first().isEmpty())
            // The row is still there, which is the point: a future sync client has to be able
            // to tell "deleted" from "never existed" or the next pull restores it.
            assertEquals(9_999L, dao.getAnyById("f1")?.deletedAt)
            assertEquals(9_999L, dao.getAnyById("f1")?.updatedAt)
        }

    private fun entity(
        id: String,
        bookId: String = "book-1",
        comment: String? = "Moj predlog: raje »obala« kot »breg«.",
        createdAt: Long = 1_000L,
        withProvenance: Boolean = true,
    ) =
        TranslationFlagEntity(
            id = id,
            bookId = bookId,
            selectedText = "slab prevod — šumniki: č, š, ž",
            comment = comment,
            locatorJson = "{\"href\":\"chapter1.xhtml\"}",
            chapterTitle = "Poglavje ena",
            cacheBookHash = if (withProvenance) "book-hash-1" else null,
            cacheSegmentHash = if (withProvenance) "segment-hash-1" else null,
            cacheModel = if (withProvenance) "gpt-5-mini" else null,
            cacheLang = if (withProvenance) "sl" else null,
            cachePromptVersion = if (withProvenance) "revise_v1" else null,
            cacheGlossaryHash = if (withProvenance) "glossary-hash-1" else null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}
