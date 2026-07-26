package app.berilo.reader.translate.epub

import app.berilo.reader.translate.model.pythonCollapseWhitespace
import app.berilo.reader.translate.model.pythonStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every place the port has to reproduce a *Python* string or path semantic, not a JVM one.
 *
 * These are the silent divergences: each would produce a plausible-looking `Book` whose segment
 * text differs from the CLI's by an invisible character, and therefore whose ids and
 * `book_hash` differ. The expected values in [PythonReference] were computed by CPython
 * running `normalize/epub.py`'s own functions.
 */
class PythonSemanticsTest {

    @Test
    fun `whitespace collapsing matches Python, including the four separators the JVM misses`() {
        PythonReference.COLLAPSE.forEach { case ->
            assertEquals(case.raw.debug(), case.collapsed, case.raw.pythonCollapseWhitespace())
        }
    }

    @Test
    fun `Kotlin's own whitespace regex would get the collapsing wrong`() {
        // The discriminator, so this file is not just restating itself: U+001C-U+001F are
        // Python whitespace and are matched by no JVM `\s`, plain or UNICODE_CHARACTER_CLASS.
        val separators = PythonReference.COLLAPSE.first { it.raw.contains('\u001c') }
        assertEquals("File separator padding", separators.raw.pythonCollapseWhitespace())
        assertNotEquals(
            separators.raw.pythonCollapseWhitespace(),
            Regex("\\s+").replace(separators.raw, " ").trim(),
        )
    }

    @Test
    fun `pythonStrip removes U+0085 where trim does not`() {
        val padded = "\u0085text\u0085"
        assertEquals("text", padded.pythonStrip())
        assertEquals("the JVM does not treat U+0085 as whitespace", padded, padded.trim())
    }

    @Test
    fun `block text matches Python on the inline subset, entities, CDATA and comments`() {
        PythonReference.BLOCK_TEXT.forEach { case ->
            assertEquals(case.xml.debug(), case.text, blockTextOf(case.xml))
        }
    }

    @Test
    fun `heading-like retyping matches Python, astral characters included`() {
        PythonReference.HEADING_LIKE.forEach { case ->
            val book = readSingleBlock(case.xml)
            assertEquals(case.xml.debug() + " text", case.text, book.text)
            assertEquals(case.xml.debug() + " headingLike", case.headingLike, book.isHeading)
        }
    }

    @Test
    fun `path resolution matches posixpath`() {
        PythonReference.PATHS.forEach { case ->
            assertEquals(
                "join(${case.base.debug()}, ${case.href.debug()})",
                case.resolved,
                PosixPaths.resolve(case.base, case.href),
            )
        }
        PythonReference.DIRECTORY_NAMES.forEach { (path, expected) ->
            assertEquals("dirname(${path.debug()})", expected, PosixPaths.directoryName(path))
        }
        PythonReference.EXTENSIONS.forEach { (path, expected) ->
            assertEquals("splitext(${path.debug()})", expected, PosixPaths.extension(path))
        }
    }

    @Test
    fun `the HTML5 entity table mirrors CPython's`() {
        assertEquals(Html5Entities.PYTHON_ENTITY_COUNT, Html5Entities.size)
        assertEquals(listOf(0x00a0), Html5Entities.codePointsOf("nbsp")?.toList())
        // Two code points, which is why the table stores code points and not a String: a naive
        // port iterating UTF-16 units would emit four numeric references for an astral entity.
        assertEquals(listOf(0x2242, 0x0338), Html5Entities.codePointsOf("NotEqualTilde")?.toList())
        assertNull(Html5Entities.codePointsOf("notanentity"))
    }

    /** The block text of a one-paragraph document, read through the real reader. */
    private fun blockTextOf(fragment: String): String = readSingleBlock(fragment).text

    private data class ReadBlock(val text: String, val isHeading: Boolean)

    /**
     * Read a single XHTML fragment through the whole reader.
     *
     * Deliberately not a call to a private helper: the assertion is about what a `Segment`
     * ends up carrying, so an accidental extra normalization step anywhere in the walk still
     * fails this test.
     */
    private fun readSingleBlock(fragment: String): ReadBlock {
        val file =
            SyntheticEpub()
                .document("ch1.xhtml", "<h1>Chapter</h1>$fragment", title = "Chapter")
                .writeTo(java.io.File.createTempFile("berilo-fragment", ".epub"))
        val segments = EpubReader().read(file).segments
        file.delete()
        // Index 1: the <h1> at index 0 guarantees the document yields a chapter even when the
        // fragment collapses to nothing.
        return if (segments.size > 1) {
            ReadBlock(segments[1].text, segments[1].headingLevel != null)
        } else {
            ReadBlock("", false)
        }
    }

    /** Render a string with its invisible characters visible, for assertion messages. */
    private fun String.debug(): String =
        map { if (it.code in 0x20..0x7e) it.toString() else "\\u%04x".format(it.code) }
            .joinToString("")
            .let { if (it.length <= DEBUG_LIMIT) it else it.take(DEBUG_LIMIT) + "..." }

    private companion object {
        const val DEBUG_LIMIT = 120
    }
}
