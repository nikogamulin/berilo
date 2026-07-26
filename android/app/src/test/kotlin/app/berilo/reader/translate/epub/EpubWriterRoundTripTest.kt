package app.berilo.reader.translate.epub

import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.bookHash
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reader → writer → reader must be **type-stable and identity-stable**.
 *
 * `book_hash` keys the translation cache, and it is a sha1 over segment ids derived from
 * `(chapterIndex, position, text)`. So if re-reading this writer's own output yields one extra
 * segment, one shifted position or one changed type, a rebuilt book stops resolving against the
 * translations already paid for. The two rendering decisions that look like defects — the
 * `class="caption"`/`class="other"` tags and `<div class="figure">` instead of
 * `<figure>`/`<figcaption>` — exist entirely to make this test pass, and each has a mutation
 * below that shows what breaks without it.
 */
class EpubWriterRoundTripTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `every vector book survives writer then reader unchanged`() {
        AssembleVectors.cases.forEach { case ->
            // The vector books are hand-built, so their own ids do not come from a reader.
            // One pass through the pair normalizes that; the invariant is that the SECOND
            // pass changes nothing.
            val once = roundTrip(case.book(), "${case.name}-1")
            val twice = roundTrip(once, "${case.name}-2")
            assertBooksAgree(case.name, once, twice)
        }
    }

    @Test
    fun `every example book survives writer then reader unchanged`() {
        ExampleBooks.assumeAvailable()
        ExampleBooks.SOURCE_PREFIXES.keys.forEach { slug ->
            val source = EpubReader().read(ExampleBooks.bookOf(slug))
            val rebuilt = roundTrip(source, slug)
            assertBooksAgree(slug, source, rebuilt)
            assertEquals("$slug: book_hash", bookHash(source), bookHash(rebuilt))
        }
    }

    @Test
    fun `caption and other survive the round trip only because of their class attribute`() {
        val case = AssembleVectors.case("inline")
        val rebuilt = roundTrip(roundTrip(case.book(), "types-1"), "types-2")
        val types = rebuilt.segments.map { it.type.value }
        assertEquals(
            "the class-tagged types are the ones a plain <p> would lose",
            listOf("caption", "other"),
            types.filter { it == "caption" || it == "other" },
        )
    }

    private fun roundTrip(book: Book, label: String): Book {
        val output = File(temporaryFolder.newFolder(), "$label.epub")
        EpubWriter().write(book, output)
        return EpubReader().read(output)
    }

    private fun assertBooksAgree(label: String, expected: Book, actual: Book) {
        assertEquals("$label: segment count", expected.segments.size, actual.segments.size)
        assertEquals(
            "$label: segment types",
            expected.segments.map { it.type },
            actual.segments.map { it.type },
        )
        assertEquals(
            "$label: (chapterIndex, position, text)",
            expected.segments.map(::identityOf),
            actual.segments.map(::identityOf),
        )
        assertEquals(
            "$label: segment ids",
            expected.segments.map(Segment::id),
            actual.segments.map(Segment::id),
        )
        assertEquals("$label: book_hash", bookHash(expected), bookHash(actual))
        assertEquals("$label: image count", expected.images.size, actual.images.size)
        assertEquals(
            "$label: image bytes",
            expected.images.map { it.data.toList() },
            actual.images.map { it.data.toList() },
        )
    }

    /** Everything a segment id is derived from, as one comparable value. */
    private fun identityOf(segment: Segment) =
        Triple(segment.chapterIndex, segment.position, segment.text)
}
