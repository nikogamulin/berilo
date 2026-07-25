package app.berilo.reader.store.importer

import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookImporterTest {

    private lateinit var tempDir: File
    private lateinit var dao: FakeBookDao
    private lateinit var extractor: FakeMetadataExtractor
    private lateinit var importer: BookImporter

    @Before
    fun setUp() {
        tempDir = createTempDirectory(prefix = "berilo-import-test").toFile()
        dao = FakeBookDao()
        extractor = FakeMetadataExtractor()
        importer =
            BookImporter(
                dao,
                extractor,
                File(tempDir, "books"),
                File(tempDir, "covers"),
                clock = { 1_000L },
                ioDispatcher = UnconfinedTestDispatcher(),
            )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `importing new bytes creates exactly one book`() =
        runTest {
            val outcome = importer.import(bytes("hello world"), "book.epub")

            assertTrue(outcome is ImportOutcome.Imported)
            assertEquals(1, dao.count())
        }

    @Test
    fun `importing identical bytes twice dedupes on content hash, not filename`() =
        runTest {
            val first = importer.import(bytes("hello world"), "book.epub")
            val second = importer.import(bytes("hello world"), "book-renamed-copy.epub")

            assertTrue(first is ImportOutcome.Imported)
            assertTrue("re-importing identical content must be reported as a duplicate", second is ImportOutcome.Duplicate)
            assertEquals("re-import must not create a second row", 1, dao.count())
            assertEquals(
                "the two imports must resolve to the same content-hash id",
                (first as ImportOutcome.Imported).bookId,
                (second as ImportOutcome.Duplicate).bookId,
            )
        }

    @Test
    fun `importing different bytes creates two distinct books`() =
        runTest {
            importer.import(bytes("hello world"), "a.epub")
            importer.import(bytes("goodbye world"), "b.epub")

            assertEquals(2, dao.count())
        }

    @Test
    fun `duplicate import leaves no extra file on disk`() =
        runTest {
            importer.import(bytes("hello world"), "a.epub")
            importer.import(bytes("hello world"), "a-copy.epub")

            val booksDir = File(tempDir, "books")
            assertEquals(1, booksDir.listFiles()?.size)
        }

    @Test
    fun `extractor failure is reported as Failed and leaves no partial book`() =
        runTest {
            extractor.shouldFail = true

            val outcome = importer.import(bytes("hello world"), "bad.epub")

            assertTrue(outcome is ImportOutcome.Failed)
            assertEquals(0, dao.count())
            val booksDir = File(tempDir, "books")
            assertTrue(booksDir.listFiles()?.isEmpty() != false)
        }

    /** A factory, not a stream: [BookImporter.import] owns opening and closing. */
    private fun bytes(text: String): () -> InputStream = { text.toByteArray().inputStream() }
}
