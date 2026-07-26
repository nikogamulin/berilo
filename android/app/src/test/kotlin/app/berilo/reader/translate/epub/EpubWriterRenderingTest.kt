package app.berilo.reader.translate.epub

import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.ImageResource
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.SegmentType
import app.berilo.reader.translate.model.makeSegmentId
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Rendering rules asserted directly on the markup, rather than only through a digest.
 *
 * The byte-identity gate ([EpubWriterByteIdentityTest]) already proves agreement with Python on
 * every case the vector covers. These tests name the *rules* — so a failure says "CAPTION lost
 * its class" instead of "sha256 differs", and so rules the vector does not happen to exercise
 * are still stated somewhere.
 */
class EpubWriterRenderingTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `CAPTION and OTHER carry the class the reader recovers their type from`() {
        // Not cosmetic: EpubReader maps p.caption and p.other back to CAPTION and OTHER. Drop
        // the class and every caption in a rebuilt book comes back as a plain paragraph, which
        // changes the type fingerprint eval alignment is keyed on.
        val body = renderBody(
            segment(0, "Podnapis", SegmentType.CAPTION),
            segment(1, "Ostanek", SegmentType.OTHER),
            segment(2, "Odstavek", SegmentType.PARAGRAPH),
        )
        assertEquals(
            "<p class=\"caption\">Podnapis</p><p class=\"other\">Ostanek</p><p>Odstavek</p>",
            body,
        )
    }

    @Test
    fun `an image is wrapped in div figure, never in figure or figcaption`() {
        // <figure>/<figcaption> would look tidier and would be wrong: EpubReader turns a
        // figcaption into a CAPTION segment, so a real <figure> mints a NEW segment on every
        // rebuild, shifting every later position, every later segment id and the book_hash.
        val body = renderBody(
            segments = listOf(segment(0, "Odstavek")),
            images = listOf(image("img0001", anchor = null, alt = "Opis")),
        )
        assertTrue(body, body.startsWith("<div class=\"figure\"><img src=\"images/img_0001.png\""))
        assertTrue("must not emit <figure>", "<figure" !in body)
        assertTrue("must not emit <figcaption>", "figcaption" !in body)
    }

    @Test
    fun `a heading defaults to h1 then h2 and clamps to h1 through h3`() {
        val body = renderBody(
            segment(0, "Prvi", SegmentType.HEADING, headingLevel = null),
            segment(1, "Drugi", SegmentType.HEADING, headingLevel = null),
            segment(2, "Globok", SegmentType.HEADING, headingLevel = 6),
            segment(3, "Plitev", SegmentType.HEADING, headingLevel = 0),
        )
        assertEquals(
            "<h1>Prvi</h1><h2>Drugi</h2><h3>Globok</h3><h1>Plitev</h1>",
            body,
        )
    }

    @Test
    fun `consecutive list items collapse into one ul and a paragraph breaks the run`() {
        val body = renderBody(
            segment(0, "Ena", SegmentType.LIST_ITEM),
            segment(1, "Dve", SegmentType.LIST_ITEM),
            segment(2, "Vmes"),
            segment(3, "Tri", SegmentType.LIST_ITEM),
        )
        assertEquals("<ul><li>Ena</li><li>Dve</li></ul><p>Vmes</p><ul><li>Tri</li></ul>", body)
    }

    @Test
    fun `a well-formed inline subset passes through, lower-cased`() {
        val body = renderBody(
            segment(0, "A <em>b</em> and <STRONG>c</STRONG> and <sub>d</sub><sup>e</sup>"),
            segment(1, "Nested <strong><em>both</em></strong> here"),
            segment(2, "Tags <i>i</i> and <b>b</b>"),
        )
        assertEquals(
            "<p>A <em>b</em> and <strong>c</strong> and <sub>d</sub><sup>e</sup></p>" +
                "<p>Nested <strong><em>both</em></strong> here</p>" +
                "<p>Tags <i>i</i> and <b>b</b></p>",
            body,
        )
    }

    @Test
    fun `an unbalanced tag escapes the whole segment`() {
        // All-or-nothing on purpose: losing the emphasis beats emitting XHTML that will not
        // parse, and a partial escape would leave exactly that.
        val body = renderBody(
            segment(0, "Unclosed <em>tail"),
            segment(1, "Mismatched <em>x</strong> y"),
            segment(2, "Stray close</em> here"),
            segment(3, "Wrong order <em><strong>x</em></strong>"),
            // The discriminating case. Where a segment's ONLY tag is the bad one, escaping just
            // that tag happens to produce the same string as escaping everything; here it does
            // not, so this is the segment that distinguishes the two strategies.
            segment(4, "Valid <em>pair</em> then stray</strong> closer"),
        )
        assertEquals(
            "<p>Unclosed &lt;em>tail</p>" +
                "<p>Mismatched &lt;em>x&lt;/strong> y</p>" +
                "<p>Stray close&lt;/em> here</p>" +
                "<p>Wrong order &lt;em>&lt;strong>x&lt;/em>&lt;/strong></p>" +
                "<p>Valid &lt;em>pair&lt;/em> then stray&lt;/strong> closer</p>",
            body,
        )
    }

    @Test
    fun `chapters follow first appearance in the segment list, not chapter index order`() {
        // normalize_epub numbers chapters sequentially, so sorting would look identical on
        // every book it produces — and be wrong for anything that reorders segments later.
        val book =
            book(
                listOf(
                    segment(0, "Peto", chapterIndex = 5, chapterTitle = "Peto"),
                    segment(1, "Drugo", chapterIndex = 2, chapterTitle = "Drugo"),
                    segment(2, "Peto spet", chapterIndex = 5, chapterTitle = "Peto"),
                    segment(3, "Deveto", chapterIndex = 9, chapterTitle = "Deveto"),
                ),
            )
        assertEquals(
            listOf("<p>Peto</p><p>Peto spet</p>", "<p>Drugo</p>", "<p>Deveto</p>"),
            chapterBodies(book),
        )
        val nav = document(write(book), "OEBPS/nav.xhtml")
        assertEquals(
            listOf("Peto", "Drugo", "Deveto"),
            Regex("<a href=\"chap_\\d+\\.xhtml\">([^<]*)</a>").findAll(nav)
                .map { it.groupValues[1] }.toList(),
        )
    }

    @Test
    fun `body text escapes ampersand and less-than and leaves gt and quotes alone`() {
        val body = renderBody(segment(0, "AT&T, a < b, 5 > 3, \"q\" and 'a'"))
        assertEquals("<p>AT&amp;T, a &lt; b, 5 > 3, \"q\" and 'a'</p>", body)
    }

    @Test
    fun `alt text additionally escapes the double quote`() {
        // It sits inside an attribute, so an unescaped " would end the attribute early; `>`
        // stays raw there too, matching Python exactly.
        val body = renderBody(
            segments = listOf(segment(0, "x")),
            images = listOf(image("img0001", anchor = null, alt = "\"q\" & <t> 5 > 3")),
        )
        assertTrue(body, "alt=\"&quot;q&quot; &amp; &lt;t> 5 > 3\"" in body)
    }

    @Test
    fun `an image anchored outside its own chapter lands at the chapter end, never dropped`() {
        // Review finding 15: a misplaced image is a defect, a vanished one is data loss.
        val first = segment(0, "Prvi", chapterIndex = 0)
        val second = segment(1, "Drugi", chapterIndex = 1)
        val book =
            book(
                segments = listOf(first, second),
                images =
                    listOf(
                        // Declared in chapter 1, anchored to a chapter-0 segment.
                        image("img0001", anchor = first.id, chapterIndex = 1, alt = "Sirota"),
                        image("img0002", anchor = second.id, chapterIndex = 1, alt = "Doma"),
                    ),
            )
        val chapters = chapterBodies(book)
        assertEquals("chapter 1 keeps no orphan", "<p>Prvi</p>", chapters[0])
        assertEquals(
            "the orphan trails its own chapter's content",
            "<p>Drugi</p>" +
                "<div class=\"figure\"><img src=\"images/img_0002.png\" alt=\"Doma\"/></div>" +
                "<div class=\"figure\"><img src=\"images/img_0001.png\" alt=\"Sirota\"/></div>",
            chapters[1],
        )
    }

    @Test
    fun `an image anchored to a list item is emitted after the whole list`() {
        val item = segment(0, "Postavka", SegmentType.LIST_ITEM)
        val body =
            renderBody(
                segments = listOf(item, segment(1, "Druga", SegmentType.LIST_ITEM)),
                images = listOf(image("img0001", anchor = item.id)),
            )
        assertEquals(
            "<ul><li>Postavka</li><li>Druga</li></ul>" +
                "<div class=\"figure\"><img src=\"images/img_0001.png\" alt=\"\"/></div>",
            body,
        )
    }

    @Test
    fun `a chapter with no title falls back to Chapter N in the nav and the title element`() {
        val book = book(segments = listOf(segment(0, "Besedilo", chapterTitle = null)))
        val output = write(book)
        val nav = document(output, "OEBPS/nav.xhtml")
        assertTrue(nav, "<a href=\"chap_0001.xhtml\">Chapter 1</a>" in nav)
        assertTrue("<title>Chapter 1</title>" in document(output, "OEBPS/chap_0001.xhtml"))
    }

    @Test
    fun `bilingual mode refuses a misaligned source book`() {
        // Segment integrity is canon: a bilingual EPUB pairs each translated segment with the
        // exact source it came from, never a positional guess.
        val target = book(segments = listOf(segment(0, "Ena"), segment(1, "Dve")))
        val shortSource = book(segments = listOf(segment(0, "One")))
        val misIdentified = book(segments = listOf(segment(0, "One"), segment(9, "Two")))

        assertThrows(IllegalArgumentException::class.java) {
            write(target, bilingual = true, sourceBook = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            write(target, bilingual = true, sourceBook = shortSource)
        }
        assertThrows(IllegalArgumentException::class.java) {
            write(target, bilingual = true, sourceBook = misIdentified)
        }
    }

    // --- helpers ------------------------------------------------------------------------

    private fun segment(
        position: Int,
        text: String,
        type: SegmentType = SegmentType.PARAGRAPH,
        headingLevel: Int? = null,
        chapterIndex: Int = 0,
        chapterTitle: String? = "Poglavje",
    ) = Segment(
        id = makeSegmentId(chapterIndex, position, text),
        type = type,
        text = text,
        chapterIndex = chapterIndex,
        chapterTitle = chapterTitle,
        position = position,
        headingLevel = headingLevel,
    )

    private fun image(
        id: String,
        anchor: String?,
        chapterIndex: Int = 0,
        alt: String? = null,
    ) = ImageResource(
        id = id,
        mediaType = "image/png",
        data = byteArrayOf(1, 2, 3, 4),
        sourceHref = "$id.png",
        chapterIndex = chapterIndex,
        anchorSegmentId = anchor,
        alt = alt,
    )

    private fun book(segments: List<Segment>, images: List<ImageResource> = emptyList()) =
        Book(
            title = "Knjiga",
            authors = listOf("Ana Novak"),
            language = "sl",
            sourcePath = "knjiga.epub",
            sourceFormat = "epub",
            segments = segments,
            images = images,
        )

    private fun write(
        book: Book,
        bilingual: Boolean = false,
        sourceBook: Book? = null,
    ): File =
        EpubWriter().write(
            book,
            File(temporaryFolder.newFolder(), "out.epub"),
            bilingual,
            sourceBook,
        )

    private fun document(file: File, name: String): String =
        ZipFile(file).use { archive ->
            archive.getInputStream(requireNotNull(archive.getEntry(name))).use { it.readBytes() }
                .toString(Charsets.UTF_8)
        }

    /** The inner `<body>` markup of every chapter, in spine order. */
    private fun chapterBodies(book: Book): List<String> {
        val output = write(book)
        return ZipFile(output).use { archive ->
            archive.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("OEBPS/chap_") }
                .toList()
        }.map { name ->
            document(output, name).substringAfter("<body>\n").substringBefore("\n</body>")
        }
    }

    private fun renderBody(vararg segments: Segment): String =
        chapterBodies(book(segments.toList())).single()

    private fun renderBody(segments: List<Segment>, images: List<ImageResource>): String =
        chapterBodies(book(segments, images)).single()
}
