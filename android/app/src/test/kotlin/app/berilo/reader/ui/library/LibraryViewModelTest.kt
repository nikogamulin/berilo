package app.berilo.reader.ui.library

import app.berilo.reader.store.db.BookEntity
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.FakeBookDao
import app.berilo.reader.store.importer.FakeMetadataExtractor
import app.berilo.reader.store.repository.BookRepository
import app.berilo.reader.store.repository.toDomain
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Import bookkeeping and dedupe are exercised end-to-end through the
 * ViewModel here (unit-level detail lives in BookImporterTest); this proves
 * the ViewModel wires importer outcomes to [LibraryEvent]s and that deletes
 * reach the repository — all on the host JVM (viewModelScope only needs
 * `Dispatchers.Main` set via kotlinx-coroutines-test, no Robolectric).
 *
 * [BookImporter] and [BookRepository] both take the same [testDispatcher] as
 * their IO dispatcher: without that, their internal `withContext(Dispatchers.IO)`
 * hops onto a real background thread, escaping `runTest`'s virtual time and
 * making event assertions flaky.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = createTempDirectory(prefix = "berilo-viewmodel-test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `importBook reports Imported for new content and adds it to the library`() =
        runTest(testDispatcher) {
            val dao = FakeBookDao()
            val viewModel = viewModel(dao)
            val events = mutableListOf<LibraryEvent>()
            backgroundScope.launch { viewModel.events.collect { events.add(it) } }

            viewModel.importBook({ "hello".toByteArray().inputStream() },"a.epub")
            advanceUntilIdle()

            assertEquals(listOf(LibraryEvent.Imported), events)
            assertEquals(1, dao.count())
        }

    /**
     * Regression: imports used to take an already-open [java.io.InputStream], so
     * MainActivity's `openInputStream(uri).use { importBook(it, name) }` closed it
     * the instant importBook launched — every real import died with
     * `ImportFailed(reason=Stream Closed)`. Only a *lazily opened* real file stream
     * catches this; ByteArrayInputStream.close() is a no-op and hides it.
     */
    @Test
    fun `import opens its own stream, after the picker callback has returned`() =
        runTest(testDispatcher) {
            val dao = FakeBookDao()
            val viewModel = viewModel(dao)
            val source = File(tempDir, "picked.epub").apply { writeBytes("hello".toByteArray()) }
            var opened = false
            val events = mutableListOf<LibraryEvent>()
            backgroundScope.launch { viewModel.events.collect { events.add(it) } }

            viewModel.importBook({ opened = true; source.inputStream() }, "picked.epub")
            assertFalse("the stream must not be opened in the picker callback", opened)

            advanceUntilIdle()

            assertTrue("the import coroutine must open the stream itself", opened)
            assertEquals(listOf(LibraryEvent.Imported), events)
        }

    @Test
    fun `import reports a failure when the picked document cannot be opened`() =
        runTest(testDispatcher) {
            val dao = FakeBookDao()
            val viewModel = viewModel(dao)
            val events = mutableListOf<LibraryEvent>()
            backgroundScope.launch { viewModel.events.collect { events.add(it) } }

            viewModel.importBook({ null }, "gone.epub")
            advanceUntilIdle()

            assertTrue(events.single() is LibraryEvent.ImportFailed)
            assertEquals("a failed open must not create a row", 0, dao.count())
        }

    @Test
    fun `importBook reports AlreadyInLibrary for content already imported, by hash not filename`() =
        runTest(testDispatcher) {
            val dao = FakeBookDao()
            // Seed the library directly through BookImporter so the content-hash id
            // matches exactly what the ViewModel's own import call will compute.
            importer(dao).import({ "hello".toByteArray().inputStream() },"seed.epub")
            val viewModel = viewModel(dao)
            val events = mutableListOf<LibraryEvent>()
            backgroundScope.launch { viewModel.events.collect { events.add(it) } }

            viewModel.importBook({ "hello".toByteArray().inputStream() },"a-different-filename.epub")
            advanceUntilIdle()

            assertEquals(listOf(LibraryEvent.AlreadyInLibrary), events)
            assertEquals("dedupe must not create a second row", 1, dao.count())
        }

    @Test
    fun `deleteBook removes the book from the library and deletes its file`() =
        runTest(testDispatcher) {
            val dao = FakeBookDao()
            val epub = File(tempDir, "x.epub").apply { writeText("stub") }
            dao.insert(
                BookEntity(
                    id = "x",
                    title = "Title",
                    authors = "Author",
                    filePath = epub.absolutePath,
                    coverPath = null,
                    addedAt = 1L,
                    lastOpenedAt = null,
                    progressionJson = null,
                ),
            )
            val viewModel = viewModel(dao)

            viewModel.deleteBook(dao.getById("x")!!.toDomain())
            advanceUntilIdle()

            assertNull("a deleted book must not be readable", dao.getById("x"))
            assertEquals("the library must not list it", 0, dao.observeAll().first().size)
            assertFalse("the EPUB itself must leave the disk", epub.exists())
        }

    /**
     * S3.2: deletion is a tombstone, not a row removal. Without the surviving row the delete
     * would never reach the user's other devices, and the next pull would restore the book the
     * user just deleted. The metadata that survives is title/authors only — the file is gone.
     */
    @Test
    fun `deleteBook leaves a tombstone so the delete can propagate`() =
        runTest(testDispatcher) {
            val dao = FakeBookDao()
            dao.insert(
                BookEntity(
                    id = "x",
                    title = "Title",
                    authors = "Author",
                    filePath = File(tempDir, "x.epub").apply { writeText("stub") }.absolutePath,
                    coverPath = null,
                    addedAt = 1L,
                    lastOpenedAt = null,
                    progressionJson = null,
                ),
            )
            val viewModel = viewModel(dao)

            viewModel.deleteBook(dao.getById("x")!!.toDomain())
            advanceUntilIdle()

            val tombstone = dao.getAnyById("x")
            assertNotNull("the row must survive as a tombstone", tombstone)
            assertNotNull("deletedAt must be set", tombstone!!.deletedAt)
            assertTrue(
                "the tombstone must be dirty so it is pushed",
                dao.metadataDirtySince(0L, 10).any { it.id == "x" },
            )
        }

    private fun importer(dao: FakeBookDao): BookImporter =
        BookImporter(
            dao,
            FakeMetadataExtractor(),
            File(tempDir, "books"),
            File(tempDir, "covers"),
            clock = { 1L },
            ioDispatcher = testDispatcher,
        )

    private fun viewModel(dao: FakeBookDao): LibraryViewModel =
        LibraryViewModel(BookRepository(dao, ioDispatcher = testDispatcher), importer(dao))
}
