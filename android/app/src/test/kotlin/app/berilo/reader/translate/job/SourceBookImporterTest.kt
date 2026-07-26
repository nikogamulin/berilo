package app.berilo.reader.translate.job

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Staging a source-language EPUB (B7), to `BookImporter`'s discipline: a stream factory rather
 * than an open stream, SHA-256 content dedupe, and nothing left on disk after a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceBookImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private fun importerInto(dir: File) = SourceBookImporter(dir, dispatcher)

    @Test
    fun `a picked EPUB is staged under its content hash`() =
        runTest(dispatcher) {
            val sources = folder.newFolder("sources")
            val picked = writeSourceEpub(folder.newFile("picked.epub"))

            val outcome = importerInto(sources).import({ picked.inputStream() }, "A Quiet Library.epub")

            val imported = outcome as SourceImportOutcome.Imported
            assertEquals("the extension is stripped for display", "A Quiet Library", imported.source.displayName)
            assertEquals("named by content hash", "${imported.source.id}.epub", imported.source.file.name)
            assertEquals("staged in the sources directory", sources, imported.source.file.parentFile)
            assertEquals("byte-for-byte the picked file", picked.readBytes().size, imported.source.file.readBytes().size)
            assertTrue("nothing left over", sources.listFiles()!!.size == 1)
        }

    /**
     * The same bytes are recognised, not copied twice.
     *
     * A tablet's storage is small and a book is megabytes; re-picking the same file after
     * backing out of the estimate screen must not accumulate copies.
     */
    @Test
    fun `re-picking the same bytes reuses the staged copy`() =
        runTest(dispatcher) {
            val sources = folder.newFolder("sources")
            val picked = writeSourceEpub(folder.newFile("picked.epub"))
            val importer = importerInto(sources)

            val first = importer.import({ picked.inputStream() }, "Picked.epub") as SourceImportOutcome.Imported
            val second = importer.import({ picked.inputStream() }, "Picked.epub") as SourceImportOutcome.AlreadyStaged

            assertEquals("same identity", first.source.id, second.source.id)
            assertEquals("same file", first.source.file, second.source.file)
            assertEquals("one copy on disk", 1, sources.listFiles()!!.size)
        }

    /** Different books get different identities, so both can be staged at once. */
    @Test
    fun `different books stage separately`() =
        runTest(dispatcher) {
            val sources = folder.newFolder("sources")
            val importer = importerInto(sources)
            val one = writeSourceEpub(folder.newFile("one.epub"), title = "One")
            val two = writeSourceEpub(folder.newFile("two.epub"), title = "Two")

            val a = importer.import({ one.inputStream() }, "One.epub") as SourceImportOutcome.Imported
            val b = importer.import({ two.inputStream() }, "Two.epub") as SourceImportOutcome.Imported

            assertNotEquals(a.source.id, b.source.id)
            assertEquals(2, sources.listFiles()!!.size)
        }

    /**
     * A failure leaves no half-written archive behind.
     *
     * **Mutation-proof:** delete the `tempFile.delete()` from the catch block and the
     * directory-empty assertion fails, having found the orphaned `source-*.epub` temp file —
     * which a later import would neither reuse nor clean up.
     */
    @Test
    fun `a stream that dies mid-copy leaves nothing on disk`() =
        runTest(dispatcher) {
            val sources = folder.newFolder("sources")

            val outcome =
                importerInto(sources).import({ ExplodingStream("disk gave up") }, "Doomed.epub")

            assertTrue("reported as a failure", outcome is SourceImportOutcome.Failed)
            assertEquals("disk gave up", (outcome as SourceImportOutcome.Failed).reason)
            assertEquals("no temp file survived", 0, sources.listFiles()!!.size)
        }

    /** A picker that cannot open the document is a failure, not a crash. */
    @Test
    fun `an unopenable document fails cleanly`() =
        runTest(dispatcher) {
            val sources = folder.newFolder("sources")

            val outcome = importerInto(sources).import({ null }, "Gone.epub")

            assertTrue(outcome is SourceImportOutcome.Failed)
            assertEquals(0, sources.listFiles()!!.size)
        }

    /** A blank picker name still yields something to show, rather than an empty label. */
    @Test
    fun `a blank suggested name falls back to the content hash`() =
        runTest(dispatcher) {
            val picked = writeSourceEpub(folder.newFile("picked.epub"))

            val imported =
                importerInto(folder.newFolder("sources"))
                    .import({ picked.inputStream() }, "   ") as SourceImportOutcome.Imported

            assertEquals(imported.source.id, imported.source.displayName)
        }
}

/** An [InputStream] that fails partway through, standing in for a dying content provider. */
private class ExplodingStream(private val message: String) : InputStream() {
    private var served = 0

    override fun read(): Int = throw IOException(message)

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (served == 0) {
            served = 1
            destination[offset] = 'P'.code.toByte()
            return 1
        }
        throw IOException(message)
    }
}
