package app.berilo.reader.store.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
 * DAO tests against a real (in-memory) Room database — same Robolectric pattern as
 * [DictionaryDaoTest]/[InterpretationDaoTest]. Also exercises the S2.6 version-4 schema
 * (`highlights` alongside `books`/`dictionary_entries`/`interpretation_entries`), the offline
 * signal that the `fallbackToDestructiveMigration()` bump didn't break the existing tables.
 */
@RunWith(RobolectricTestRunner::class)
class HighlightDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: HighlightDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.highlightDao()
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
    fun `insert then getById round-trips every field, including a note and a color`() =
        runTest {
            val entity = entity(id = "h1", note = "moja opomba")

            dao.insert(entity)
            val found = dao.getById("h1")

            assertEquals(entity, found)
        }

    @Test
    fun `a highlight with no note persists note as null`() =
        runTest {
            dao.insert(entity(id = "h1", note = null))

            assertNull(dao.getById("h1")?.note)
        }

    @Test
    fun `observeForBook only returns rows for that book, oldest-created first`() =
        runTest {
            dao.insert(entity(id = "h1", bookId = "book-1", createdAt = 2_000L))
            dao.insert(entity(id = "h2", bookId = "book-2", createdAt = 1_000L))
            dao.insert(entity(id = "h3", bookId = "book-1", createdAt = 1_000L))

            val forBook1 = dao.observeForBook("book-1").first()

            assertEquals(listOf("h3", "h1"), forBook1.map { it.id })
        }

    @Test
    fun `update replaces color and note, keeping id, locator, and createdAt`() =
        runTest {
            val original = entity(id = "h1", color = HighlightColor.AMBER, note = null)
            dao.insert(original)

            val updated = original.copy(color = HighlightColor.ROSE, note = "dodana opomba", updatedAt = 9_999L)
            dao.update(updated)

            val found = dao.getById("h1")
            assertEquals(HighlightColor.ROSE, found?.color)
            assertEquals("dodana opomba", found?.note)
            assertEquals(original.createdAt, found?.createdAt)
            assertEquals(9_999L, found?.updatedAt)
        }

    @Test
    fun `deleteById removes exactly that row`() =
        runTest {
            dao.insert(entity(id = "h1"))
            dao.insert(entity(id = "h2", bookId = "book-1", createdAt = 5_000L))

            dao.deleteById("h1")

            assertNull(dao.getById("h1"))
            assertEquals(1, dao.observeForBook("book-1").first().size)
        }

    private fun entity(
        id: String,
        bookId: String = "book-1",
        color: HighlightColor = HighlightColor.SKY,
        note: String? = "moja opomba",
        createdAt: Long = 1_000L,
    ) =
        HighlightEntity(
            id = id,
            bookId = bookId,
            color = color,
            selectedText = "izbrano besedilo — šumniki: č, š, ž",
            note = note,
            locatorJson = "{\"href\":\"chapter1.xhtml\"}",
            chapterTitle = "Poglavje ena",
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}
