package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightColor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationsRepositoryTest {

    private fun repository(dao: FakeHighlightDao = FakeHighlightDao(), clock: () -> Long = { 1_000L }, ids: Iterator<String> = idSequence()) =
        AnnotationsRepository(
            dao = dao,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = clock,
            idGenerator = { ids.next() },
        )

    private fun idSequence(): Iterator<String> = generateSequence(0) { it + 1 }.map { "id-$it" }.iterator()

    @Test
    fun `create persists a plain highlight with no note`() =
        runTest {
            val dao = FakeHighlightDao()
            val repo = repository(dao)

            val id = repo.create("book-1", HighlightColor.AMBER, "selected text", "{}", "Chapter One")

            val stored = dao.getById(id)
            assertEquals("book-1", stored?.bookId)
            assertEquals(HighlightColor.AMBER, stored?.color)
            assertEquals("selected text", stored?.selectedText)
            assertNull(stored?.note)
            assertEquals("Chapter One", stored?.chapterTitle)
            assertEquals(1_000L, stored?.createdAt)
            assertEquals(1_000L, stored?.updatedAt)
        }

    @Test
    fun `create with note text persists both together`() =
        runTest {
            val dao = FakeHighlightDao()
            val repo = repository(dao)

            val id = repo.create("book-1", HighlightColor.SKY, "quoted line", "{}", null, noteText = "a thought")

            assertEquals("a thought", dao.getById(id)?.note)
            assertNull(dao.getById(id)?.chapterTitle)
        }

    @Test
    fun `observeForBook only returns rows for that book, oldest first`() =
        runTest {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            repo.create("book-1", HighlightColor.AMBER, "a", "{}", null)
            repo.create("book-2", HighlightColor.AMBER, "b", "{}", null)
            repo.create("book-1", HighlightColor.SAGE, "c", "{}", null)

            val result = repo.observeForBook("book-1").first()

            assertEquals(2, result.size)
            assertTrue(result.all { it.bookId == "book-1" })
            assertEquals("a", result[0].selectedText)
            assertEquals("c", result[1].selectedText)
        }

    @Test
    fun `updateColor changes only the color and bumps updatedAt`() =
        runTest {
            val dao = FakeHighlightDao()
            var now = 1_000L
            val repo = repository(dao, clock = { now })
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)

            now = 2_000L
            repo.updateColor(id, HighlightColor.ROSE)

            val stored = dao.getById(id)
            assertEquals(HighlightColor.ROSE, stored?.color)
            assertEquals("text", stored?.selectedText)
            assertEquals(2_000L, stored?.updatedAt)
            assertEquals(1_000L, stored?.createdAt)
        }

    @Test
    fun `updateNote blank clears the note rather than storing whitespace`() =
        runTest {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null, noteText = "original")

            repo.updateNote(id, "   ")

            assertNull(dao.getById(id)?.note)
        }

    @Test
    fun `delete hides the highlight but keeps a tombstone so the delete syncs`() =
        runTest {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)

            repo.delete(id)

            assertNull("a deleted highlight must not be readable", dao.getById(id))
            assertEquals(
                "and must not appear in the notebook",
                0,
                dao.observeForBook("book-1").first().size,
            )
            // S3.2: the row itself survives with deletedAt set. A hard delete would be
            // invisible to the server, and the next pull would faithfully restore the
            // highlight the user just removed.
            val tombstone = dao.getAnyById(id)
            assertNotNull("the row must survive as a tombstone", tombstone)
            assertNotNull("deletedAt must be set", tombstone!!.deletedAt)
        }

    @Test
    fun `updateColor on a since-deleted id is a silent no-op`() =
        runTest {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)
            repo.delete(id)

            repo.updateColor(id, HighlightColor.SKY) // must not throw

            assertNull(dao.getById(id))
        }
}
