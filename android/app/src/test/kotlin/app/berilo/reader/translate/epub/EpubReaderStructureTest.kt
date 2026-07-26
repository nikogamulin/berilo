package app.berilo.reader.translate.epub

import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.SegmentType
import app.berilo.reader.translate.model.makeSegmentId
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The structural rules that decide `chapterIndex` and `position` — and therefore every id.
 *
 * The real-book gate ([EpubReaderIdentityTest]) proves the whole algorithm agrees with the CLI,
 * but it needs `data/` and it says nothing about *why* a book matches. These do, on synthetic
 * archives that run everywhere, so a rule that breaks names itself.
 */
class EpubReaderStructureTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a segment-less document consumes no chapter index`() {
        // The cover page is the reason this rule exists: it is a real spine document that
        // yields no text. Counting it would shift every later chapterIndex by one and change
        // every id in the book.
        val book =
            read(
                SyntheticEpub()
                    .document("cover.xhtml", "<div><img src=\"cover.png\"/></div>")
                    .document("ch1.xhtml", "<h1>One</h1><p>First.</p>")
                    .document("blank.xhtml", "<div>   </div>")
                    .document("ch2.xhtml", "<h1>Two</h1><p>Second.</p>")
                    .resource("cover.png", ONE_PIXEL_PNG),
            )

        assertEquals(listOf(0, 0, 1, 1), book.segments.map(Segment::chapterIndex))
        assertEquals(listOf(0, 1, 2, 3), book.segments.map(Segment::position))
        assertEquals(2, book.chapterCount)
    }

    @Test
    fun `position is book-global and keeps running across chapters`() {
        val book =
            read(
                SyntheticEpub()
                    .document("ch1.xhtml", "<h1>One</h1><p>a</p><p>b</p>")
                    .document("ch2.xhtml", "<h1>Two</h1><p>c</p>"),
            )
        assertEquals(List(book.segments.size) { it }, book.segments.map(Segment::position))
        assertEquals(listOf(0, 0, 0, 1, 1), book.segments.map(Segment::chapterIndex))
    }

    @Test
    fun `every id is the hash of chapter index, position and text`() {
        val book =
            read(
                SyntheticEpub()
                    .document("ch1.xhtml", "<h1>Šumniki</h1><p>čebela žveji.</p>"),
            )
        book.segments.forEach { segment ->
            assertEquals(
                makeSegmentId(segment.chapterIndex, segment.position, segment.text),
                segment.id,
            )
        }
    }

    @Test
    fun `an entry-less spine document continues the previous chapter's title`() {
        // A9's sibling, review finding 17 (A8): the continuation keeps the title but still
        // takes its own chapterIndex. Reproduced, not fixed — fixing it moves book_hash.
        val book =
            read(
                SyntheticEpub()
                    .document("ch1.xhtml", "<h1>One</h1><p>First half.</p>")
                    .document("ch1b.xhtml", "<p>Second half.</p>")
                    .ncx("ch1.xhtml" to "Chapter One"),
            )

        assertEquals(
            List(3) { "Chapter One" },
            book.segments.map(Segment::chapterTitle),
        )
        assertEquals(
            "A8: a continuation document still takes its own chapter index, so the chapter " +
                "count over-counts a split chapter",
            listOf(0, 0, 1),
            book.segments.map(Segment::chapterIndex),
        )
        assertEquals(2, book.chapterCount)
    }

    @Test
    fun `without a TOC the document heading outranks a title equal to the book title`() {
        val book =
            read(
                SyntheticEpub()
                    .document("ch1.xhtml", "<h1>Real chapter</h1><p>Body.</p>", title = "Synthetic")
                    .document("ch2.xhtml", "<p>No heading here.</p>", title = "Own title"),
            )
        assertEquals(
            listOf("Real chapter", "Real chapter", "Own title"),
            book.segments.map(Segment::chapterTitle),
        )
    }

    @Test
    fun `block tags map to their segment types and headings keep their level`() {
        val book =
            read(
                SyntheticEpub()
                    .document(
                        "ch1.xhtml",
                        "<h2>Head</h2><p>Para</p><ul><li>Item</li></ul>" +
                            "<blockquote>Quote</blockquote><figure><figcaption>Cap</figcaption>" +
                            "</figure><p class=\"caption\">Classed caption</p>" +
                            "<p class=\"other\">Classed other</p>",
                    ),
            )
        assertEquals(
            listOf(
                SegmentType.HEADING,
                SegmentType.PARAGRAPH,
                SegmentType.LIST_ITEM,
                SegmentType.BLOCKQUOTE,
                SegmentType.CAPTION,
                SegmentType.CAPTION,
                SegmentType.OTHER,
            ),
            book.segments.map(Segment::type),
        )
        assertEquals(
            listOf(2, null, null, null, null, null, null),
            book.segments.map(Segment::headingLevel),
        )
    }

    @Test
    fun `a navigation document contributes no segments`() {
        val book =
            read(
                SyntheticEpub()
                    .document("ch1.xhtml", "<h1>One</h1><p>Body.</p>")
                    .document(
                        "nav.xhtml",
                        "<nav epub:type=\"toc\" xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
                            "<ol><li><a href=\"ch1.xhtml\">One</a></li></ol></nav>",
                    ),
            )
        assertEquals(listOf("One", "Body."), book.segments.map(Segment::text))
    }

    @Test
    fun `images are resources anchored to the segment they follow, never segments`() {
        val book =
            read(
                SyntheticEpub()
                    .document(
                        "ch1.xhtml",
                        "<h1>One</h1><p>Before.</p><img src=\"fig.png\" alt=\"A figure\"/>" +
                            "<p>After.</p>",
                    )
                    .resource("fig.png", ONE_PIXEL_PNG),
            )

        assertEquals(3, book.segments.size)
        assertEquals(1, book.images.size)
        val image = book.images.single()
        assertEquals("img0001", image.id)
        assertEquals("image/png", image.mediaType)
        assertEquals("A figure", image.alt)
        assertEquals(book.segments[1].id, image.anchorSegmentId)
        assertEquals(0, image.chapterIndex)
    }

    @Test
    fun `an image leading a chapter has no anchor`() {
        val book =
            read(
                SyntheticEpub()
                    .document("cover.xhtml", "<div><img src=\"cover.png\"/></div>")
                    .document("ch1.xhtml", "<h1>One</h1><p>Body.</p>")
                    .resource("cover.png", ONE_PIXEL_PNG),
            )
        assertNull(book.images.single().anchorSegmentId)
        assertEquals(0, book.images.single().chapterIndex)
    }

    @Test
    fun `a figure referenced twice keeps both placements, furniture referenced thrice does not`() {
        val book =
            read(
                SyntheticEpub()
                    .document("ch1.xhtml", "<h1>One</h1><p>a</p><img src=\"fig.png\"/><img src=\"logo.png\"/>")
                    .document("ch2.xhtml", "<h1>Two</h1><p>b</p><img src=\"fig.png\"/><img src=\"logo.png\"/>")
                    .document("ch3.xhtml", "<h1>Three</h1><p>c</p><img src=\"logo.png\"/>")
                    .resource("fig.png", ONE_PIXEL_PNG)
                    .resource("logo.png", ONE_PIXEL_GIF, mediaType = "image/gif"),
            )

        val bySource = book.images.groupingBy { it.sourceHref }.eachCount()
        assertEquals("a figure reused twice keeps both placements", 2, bySource["OEBPS/fig.png"])
        assertEquals("furniture is carried once", 1, bySource["OEBPS/logo.png"])
        assertEquals(listOf("img0001", "img0002", "img0003"), book.images.map { it.id })
    }

    @Test
    fun `an undeclared image falls back to its extension for a media type`() {
        val book =
            read(
                SyntheticEpub()
                    .document("ch1.xhtml", "<h1>One</h1><img src=\"undeclared.GIF\"/>")
                    .resource("undeclared.GIF", ONE_PIXEL_GIF, mediaType = null),
            )
        assertEquals("image/gif", book.images.single().mediaType)
    }

    @Test
    fun `an SVG cover wrapper is read through xlink href`() {
        val book =
            read(
                SyntheticEpub()
                    .document(
                        "cover.xhtml",
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
                            "xmlns:xlink=\"http://www.w3.org/1999/xlink\">" +
                            "<image xlink:href=\"cover.png\"/></svg>",
                    )
                    .document("ch1.xhtml", "<h1>One</h1><p>Body.</p>")
                    .resource("cover.png", ONE_PIXEL_PNG),
            )
        assertEquals("OEBPS/cover.png", book.images.single().sourceHref)
    }

    @Test
    fun `metadata comes from the OPF package document`() {
        val book =
            read(
                SyntheticEpub().document("ch1.xhtml", "<h1>One</h1>"),
                title = "Naslov",
                language = "sl",
            )
        assertEquals("Naslov", book.title)
        assertEquals("sl", book.language)
        assertEquals("epub", book.sourceFormat)
    }

    private fun read(builder: SyntheticEpub, title: String = "Synthetic", language: String = "en"): Book =
        EpubReader().read(builder.writeTo(folder.newFile(), title, language))

    private companion object {
        /** A 1x1 PNG and a 1x1 GIF, so synthetic books carry real, distinguishable bytes. */
        val ONE_PIXEL_PNG: ByteArray =
            Base64.getDecoder()
                .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" +
                        "AAAABJRU5ErkJggg==",
                )

        val ONE_PIXEL_GIF: ByteArray =
            Base64.getDecoder()
                .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    }
}
