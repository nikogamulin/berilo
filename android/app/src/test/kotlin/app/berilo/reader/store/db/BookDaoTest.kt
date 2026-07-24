package app.berilo.reader.store.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO tests against a real (in-memory) Room database. Robolectric provides
 * the Android SQLite runtime the JVM lacks on its own — no emulator/device
 * needed, so this stays in `./gradlew test`.
 */
@RunWith(RobolectricTestRunner::class)
class BookDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.bookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `observeAll orders newest import first`() =
        runTest {
            dao.insert(book(id = "a", addedAt = 1L))
            dao.insert(book(id = "b", addedAt = 2L))

            val books = dao.observeAll().first()

            assertEquals(listOf("b", "a"), books.map { it.id })
        }

    @Test
    fun `exists is the content-hash dedupe check`() =
        runTest {
            assertFalse(dao.exists("hash-1"))

            dao.insert(book(id = "hash-1"))

            assertTrue(dao.exists("hash-1"))
        }

    @Test
    fun `insert rejects a second row with the same id`() =
        runTest {
            dao.insert(book(id = "hash-1"))

            var threw = false
            try {
                dao.insert(book(id = "hash-1", title = "Different title, same hash"))
            } catch (e: Exception) {
                threw = true
            }

            assertTrue("inserting a duplicate id must fail, not silently overwrite", threw)
        }

    @Test
    fun `deleteById removes exactly that book`() =
        runTest {
            dao.insert(book(id = "a"))
            dao.insert(book(id = "b"))

            dao.deleteById("a")

            val remaining = dao.observeAll().first()
            assertEquals(listOf("b"), remaining.map { it.id })
            assertNull(dao.getById("a"))
        }

    @Test
    fun `update persists lastOpenedAt`() =
        runTest {
            dao.insert(book(id = "a"))

            dao.update(dao.getById("a")!!.copy(lastOpenedAt = 42L))

            assertEquals(42L, dao.getById("a")?.lastOpenedAt)
        }

    private fun book(id: String, addedAt: Long = 0L, title: String = "Title $id") =
        BookEntity(
            id = id,
            title = title,
            authors = "Author",
            filePath = "/tmp/$id.epub",
            coverPath = null,
            addedAt = addedAt,
            lastOpenedAt = null,
            progressionJson = null,
        )
}
