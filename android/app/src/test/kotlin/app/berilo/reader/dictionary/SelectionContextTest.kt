package app.berilo.reader.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionContextTest {

    @Test
    fun `blank highlight means nothing was selected`() {
        assertNull(buildSelectionContext(before = "He walked to the ", highlight = "  ", after = " and stopped."))
    }

    @Test
    fun `reconstructs the enclosing sentence from before and after`() {
        val context =
            buildSelectionContext(
                before = "It was raining hard. She sat by the river ",
                highlight = "bank",
                after = " and watched the water rise. Then she left.",
            )

        assertEquals("bank", context?.word)
        assertEquals("She sat by the river bank and watched the water rise.", context?.sentence)
    }

    @Test
    fun `word is trimmed but sentence keeps surrounding punctuation`() {
        val context = buildSelectionContext(before = "The ", highlight = " bank ", after = "was closed.")

        assertEquals("bank", context?.word)
        assertEquals("The bank was closed.", context?.sentence)
    }

    @Test
    fun `falls back to the raw concatenation when no sentence terminator exists`() {
        val context = buildSelectionContext(before = "Chapter One ", highlight = "Prologue", after = " Introduction")

        assertEquals("Prologue", context?.word)
        assertEquals("Chapter One Prologue Introduction", context?.sentence)
    }

    @Test
    fun `a highlight with no before or after context still yields a usable sentence`() {
        val context = buildSelectionContext(before = "", highlight = "Prologue", after = "")

        assertEquals("Prologue", context?.word)
        assertEquals("Prologue", context?.sentence)
    }
}
