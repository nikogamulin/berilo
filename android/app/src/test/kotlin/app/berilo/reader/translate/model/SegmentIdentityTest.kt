package app.berilo.reader.translate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three hashes against digests Python produced.
 *
 * Half the cases are literal constants computed by hand from `hashlib.sha1` so the suite is
 * anchored independently of the fixture generator; the rest sweep the committed probe vectors,
 * which cover every code point Python's `str.strip()` removes.
 */
class SegmentIdentityTest {

    /** U+0085 NEXT LINE — Python strips it, `String.trim()` does not. */
    private val nel = "\u0085"

    @Test
    fun `makeSegmentId reproduces hand-computed Python digests`() {
        assertEquals("01b2b9124e42f94d92b86c4b597f0485d0a95eec", makeSegmentId(0, 0, ""))
        assertEquals(
            "6c313d93b9457360a257d2c9986c0a805def82c2",
            makeSegmentId(0, 0, "Hello, world."),
        )
        assertEquals(
            "775b98a0e5cb2ed8bc94abb32872a2be996ab549",
            makeSegmentId(26, 2308, "Zadnji segment knjige."),
        )
    }

    @Test
    fun `makeSegmentId hashes sumniki as UTF-8`() {
        assertEquals(
            "990719cd7ac7e24864c3f5e7b0c1fb8b176bafd2",
            makeSegmentId(3, 17, "Čebela žveji šipek."),
        )
    }

    @Test
    fun `makeSegmentId hashes astral characters as UTF-8`() {
        assertEquals(
            "f99cfb106947dcfc4002a619f7f9ed5c5ca01f51",
            makeSegmentId(0, 0, "Zmaj 🐉"),
        )
    }

    @Test
    fun `makeSegmentId ignores surrounding whitespace`() {
        assertEquals(makeSegmentId(0, 0, "Hello, world."), makeSegmentId(0, 0, "  Hello, world.  "))
    }

    @Test
    fun `makeSegmentId strips U+0085 the way Python does and trim does not`() {
        // The whole point of pythonStrip: on the JVM, Character.isWhitespace('\u0085') and
        // Character.isSpaceChar('\u0085') are both false, so a port that reached for trim()
        // would hash a different payload here and share no cache row with the CLI.
        val padded = nel + "x" + nel
        assertEquals(makeSegmentId(0, 0, "x"), makeSegmentId(0, 0, padded))
        assertEquals("715f519d9b909b004b160bbfbef10ba2c894cf5a", makeSegmentId(0, 0, padded))
        assertNotEquals("trim() must not be equivalent here", "x", padded.trim())
        assertEquals("x", padded.pythonStrip())
    }

    @Test
    fun `makeSegmentId separates its three components`() {
        // "1:23:t" and "12:3:t" must not collide, and neither may fold into the text.
        assertNotEquals(makeSegmentId(1, 23, "t"), makeSegmentId(12, 3, "t"))
        assertNotEquals(makeSegmentId(0, 0, "1:2"), makeSegmentId(0, 1, "2"))
    }

    @Test
    fun `pythonStrip leaves zero-width characters in place`() {
        val invisible = "\u200BVodilna nevidnost\u2060"
        assertEquals(invisible, invisible.pythonStrip())
    }

    @Test
    fun `pythonStrip empties a whitespace-only string`() {
        val allWhitespace =
            "\u0009\u000A\u000B\u000C\u000D\u001C\u001D\u001E\u001F\u0020" +
                "\u0085\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006" +
                "\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000"
        assertEquals("", allWhitespace.pythonStrip())
        assertEquals(29, allWhitespace.length)
    }

    @Test
    fun `segmentHash reproduces hand-computed Python digests`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", segmentHash(""))
        assertEquals("842a368db6edbb1ba1c39d0bf4524ef1cfd454d1", segmentHash("Čebela žveji šipek."))
        assertEquals(segmentHash("Čebela žveji šipek."), segmentHash(" Čebela žveji šipek. "))
    }

    @Test
    fun `bookHash joins ids with a newline`() {
        assertEquals("fcd127ffa1016069006ad91f3f361248f9bdf272", bookHash(listOf("a", "b")))
        // The separator is load-bearing: dropping it collides ["a","b"] with ["ab"].
        assertNotEquals(bookHash(listOf("a", "b")), bookHash(listOf("ab")))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", bookHash(emptyList()))
    }

    @Test
    fun `every committed probe vector reproduces its Python digest`() {
        val fixture = IdentityFixtures.loadVectors()
        assertEquals(IdentityFixtures.EXPECTED_FIXTURE_VERSION, fixture.fixtureVersion)
        assertTrue("vectors present", fixture.vectors.size >= 15)
        fixture.vectors.forEachIndexed { index, vector ->
            assertEquals(
                "vector[$index] id",
                vector.id,
                makeSegmentId(vector.chapterIndex, vector.position, vector.text),
            )
            assertEquals(
                "vector[$index] segment_hash",
                vector.segmentHash,
                segmentHash(vector.text),
            )
            assertEquals("vector[$index] id length", SHA1_HEX_LENGTH, vector.id.length)
        }
    }
}
