package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterHighlightsTest {

    private fun highlight(chapterTitle: String?, text: String, createdAt: Long) =
        Highlight(
            id = "id-$text",
            bookId = "book-1",
            color = HighlightColor.AMBER,
            selectedText = text,
            note = null,
            locatorJson = "{}",
            chapterTitle = chapterTitle,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    @Test
    fun `groups by chapter title, preserving first-appearance order`() {
        val highlights =
            listOf(
                highlight("Chapter One", "a", 1L),
                highlight("Chapter Two", "b", 2L),
                highlight("Chapter One", "c", 3L),
            )

        val grouped = groupByChapter(highlights)

        assertEquals(listOf("Chapter One", "Chapter Two"), grouped.map { it.chapterTitle })
        assertEquals(listOf("a", "c"), grouped[0].highlights.map { it.selectedText })
        assertEquals(listOf("b"), grouped[1].highlights.map { it.selectedText })
    }

    @Test
    fun `null or blank chapter title falls back to the Untitled bucket`() {
        val highlights = listOf(highlight(null, "a", 1L), highlight("   ", "b", 2L))

        val grouped = groupByChapter(highlights)

        assertEquals(1, grouped.size)
        assertEquals(UNTITLED_CHAPTER, grouped[0].chapterTitle)
        assertEquals(listOf("a", "b"), grouped[0].highlights.map { it.selectedText })
    }

    @Test
    fun `empty input groups to an empty list`() {
        assertEquals(emptyList<ChapterHighlights>(), groupByChapter(emptyList()))
    }
}
