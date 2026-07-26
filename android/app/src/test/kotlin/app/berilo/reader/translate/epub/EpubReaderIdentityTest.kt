package app.berilo.reader.translate.epub

import app.berilo.reader.translate.model.IdentityFixtures
import app.berilo.reader.translate.model.bookHash
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **The cross-language gate.** This reader must produce, for each real example book, exactly
 * the `Book` the Python CLI produces — proven segment by segment against B1a's fixtures.
 *
 * [IdentityFixtures.assertMatchesFixture] *recomputes* every id from
 * `(chapterIndex, position, text)` rather than reading it off the book, so ids copied from
 * anywhere else cannot pass. Until this test existed the fixtures could only prove they were
 * internally consistent; with a reader in front of them the same file becomes a full
 * cross-language round trip.
 *
 * Why it matters in money: `book_hash` keys the translation cache. If the tablet assigns one
 * different `chapterIndex` or `position` than the workstation, every later id differs, no
 * cache row is shared, and a book already paid for on the laptop is re-billed in full on the
 * device.
 *
 * Skips cleanly without `data/` — see [ExampleBooks].
 */
class EpubReaderIdentityTest {

    @Test
    fun `every example book reproduces its committed identity fixture`() {
        // Per-test, not @BeforeClass: a class-level assumption makes JUnit ignore the whole
        // class, which reads as "no tests found" under a --tests filter rather than as a skip.
        ExampleBooks.assumeAvailable()
        IdentityFixtures.EXAMPLE_SLUGS.forEach { slug ->
            val book = EpubReader().read(ExampleBooks.bookOf(slug))
            IdentityFixtures.assertMatchesFixture(book, IdentityFixtures.loadIdentity(slug))
        }
    }

    @Test
    fun `every example book reproduces the book hash the plan measured`() {
        ExampleBooks.assumeAvailable()
        // Duplicated from the plan (docs/plans/2026-07-26-ondevice-translation.md §5) on
        // purpose: the fixture files and this table are two independent statements of the same
        // fact, and a regenerated fixture that silently moved would still fail here.
        val expected =
            mapOf(
                "new-rules-of-war" to Measured("2db2bd1ca6782089f32f2af99bc2b69cbbaf259a", 2309, 27, 4),
                "revenge-of-geography" to Measured("f30cd8f30a17f696909f446a121bd8b0eb20b91c", 1294, 47, 17),
                "sandworm" to Measured("3f76c96f58a59016bc86bc11616b040f12038ef2", 1813, 61, 3),
                "ember-spark" to Measured("14425e03f36e50dfbcd2c6631fbebfd90f7d5dbf", 1231, 35, 61),
            )
        assertEquals(IdentityFixtures.EXAMPLE_SLUGS.toSet(), expected.keys)
        expected.forEach { (slug, row) ->
            val book = EpubReader().read(ExampleBooks.bookOf(slug))
            assertEquals(
                "$slug",
                row,
                Measured(bookHash(book), book.segments.size, book.chapterCount, book.images.size),
            )
        }
    }

    /** One row of the §5 table: what the Python CLI measured for a book at €0. */
    private data class Measured(
        val bookHash: String,
        val segments: Int,
        val chapters: Int,
        val images: Int,
    )
}
