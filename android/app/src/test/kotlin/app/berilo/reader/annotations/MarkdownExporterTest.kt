package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden test for [MarkdownExporter]: fixed input, exact expected Markdown string —
 * `docs/project_plan.md` S2.6 asks for byte-exact assertions, šumniki preserved, since the
 * output is what Obsidian actually renders. */
class MarkdownExporterTest {

    private fun highlight(text: String, note: String? = null) =
        Highlight(
            id = "id-$text",
            bookId = "book-1",
            color = HighlightColor.AMBER,
            selectedText = text,
            note = note,
            locatorJson = "{}",
            chapterTitle = null,
            createdAt = 0L,
            updatedAt = 0L,
        )

    @Test
    fun `renders title, chapter headings, quoted excerpts, and note paragraphs`() {
        val chapters =
            listOf(
                ChapterHighlights(
                    chapterTitle = "Poglavje ena",
                    highlights =
                        listOf(
                            highlight("Prva izbrana vrstica."),
                            highlight("Druga vrstica z opombo.", note = "Moja misel o tem odlomku — šumniki: č, š, ž."),
                        ),
                ),
                ChapterHighlights(
                    chapterTitle = "Poglavje dve",
                    highlights = listOf(highlight("Tretja vrstica.")),
                ),
            )

        val markdown = MarkdownExporter.export("Testna knjiga", chapters)

        val expected =
            "# Testna knjiga\n" +
                "\n" +
                "## Poglavje ena\n" +
                "\n" +
                "> Prva izbrana vrstica.\n" +
                "\n" +
                "> Druga vrstica z opombo.\n" +
                "\n" +
                "Moja misel o tem odlomku — šumniki: č, š, ž.\n" +
                "\n" +
                "## Poglavje dve\n" +
                "\n" +
                "> Tretja vrstica.\n"

        assertEquals(expected, markdown)
    }

    @Test
    fun `a multi-line excerpt quotes every line`() {
        val chapters =
            listOf(ChapterHighlights("Poglavje ena", listOf(highlight("prva vrstica\ndruga vrstica"))))

        val markdown = MarkdownExporter.export("Knjiga", chapters)

        assertEquals(
            "# Knjiga\n\n## Poglavje ena\n\n> prva vrstica\n> druga vrstica\n",
            markdown,
        )
    }

    @Test
    fun `no chapters renders just the title heading`() {
        assertEquals("# Knjiga\n", MarkdownExporter.export("Knjiga", emptyList()))
    }

    @Test
    fun `a blank note is omitted, not rendered as an empty paragraph`() {
        val chapters = listOf(ChapterHighlights("Poglavje ena", listOf(highlight("vrstica", note = "   "))))

        val markdown = MarkdownExporter.export("Knjiga", chapters)

        assertEquals("# Knjiga\n\n## Poglavje ena\n\n> vrstica\n", markdown)
    }
}
