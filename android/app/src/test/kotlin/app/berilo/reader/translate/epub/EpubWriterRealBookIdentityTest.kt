package app.berilo.reader.translate.epub

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real-book half of the byte-identity gate: `EpubReader` → `EpubWriter` over a genuine
 * 2000-segment book must equal what the Python CLI produces from the same source, to the byte.
 *
 * [AssembleVectors] already covers every rendering rule on synthetic input. What it cannot
 * cover is scale and the long tail — thousands of segments, dozens of real images, chapter
 * titles with punctuation the synthetic corpus never contains. A corpus-derived gate proves
 * agreement on what the corpus contains and nothing more, so the two halves are complementary
 * and neither replaces the other.
 *
 * Skips cleanly without `data/` **or** without the reference archives — see [ExampleBooks] and
 * [ReferenceEpubs].
 */
class EpubWriterRealBookIdentityTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `every example book rebuilds byte-identically to the CLI`() {
        // Per-test, not @BeforeClass: a class-level assumption makes JUnit ignore the whole
        // class, which reads as "no tests found" under a --tests filter rather than as a skip.
        ExampleBooks.assumeAvailable()
        ReferenceEpubs.assumeAvailable()
        ExampleBooks.SOURCE_PREFIXES.keys.forEach { slug ->
            val book = EpubReader().read(ExampleBooks.bookOf(slug))
            val output = File(temporaryFolder.newFolder(), "$slug.epub")
            EpubWriter().write(book, output)
            assertEquals(
                "$slug: sha256 of the rebuilt archive",
                sha256(ReferenceEpubs.referenceOf(slug)),
                sha256(output),
            )
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
