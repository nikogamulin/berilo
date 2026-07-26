package app.berilo.reader.translate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The 1:1 marker protocol (B5), asserted to behave exactly as A3's Python does.
 *
 * Every case here maps onto a `tests/test_translate.py` case: a duplicate marker, a missing
 * index, an empty translation and a stray `[[2]]` inside prose. The stray-marker case is the
 * one with money attached — anchoring exists so it does **not** force a retry.
 */
class MarkerParserTest {

    @Test
    fun `a well-formed reply maps 1 to 1 in order`() {
        val parsed = parseNumberedResponse("[[1]] Ena.\n[[2]] Dve.\n[[3]] Tri.", 3)

        assertEquals(listOf("Ena.", "Dve.", "Tri."), parsed)
    }

    @Test
    fun `leading whitespace before a marker still anchors`() {
        val parsed = parseNumberedResponse("  [[1]] Ena.\n\t[[2]] Dve.", 2)

        assertEquals(listOf("Ena.", "Dve."), parsed)
    }

    @Test
    fun `a translation spanning several lines is kept whole`() {
        val parsed = parseNumberedResponse("[[1]] Prva vrstica\nse nadaljuje.\n[[2]] Dve.", 2)

        assertEquals(listOf("Prva vrstica\nse nadaljuje.", "Dve."), parsed)
    }

    /**
     * Review finding 14, and the reason [parseNumberedResponse] anchors to line starts: a
     * `[[2]]` inside a translation is prose. Before anchoring this reply had four "markers" for
     * a three-segment batch and cost a strict retry plus possibly a per-segment fallback.
     *
     * **Mutation-proof:** dropping `RegexOption.MULTILINE`+`^` from `ANCHORED_MARKER_RE` (i.e.
     * reverting to the loose scan first) turns this red.
     */
    @Test
    fun `a stray marker inside prose is prose, not a marker, and costs no retry`() {
        val reply = "[[1]] Ena.\n[[2]] Element [[2]] polja je drugi.\n[[3]] Tri."

        val parsed = parseNumberedResponse(reply, 3)

        assertEquals(listOf("Ena.", "Element [[2]] polja je drugi.", "Tri."), parsed)
    }

    /**
     * The historical unanchored scan must survive as a second attempt: a model that packs
     * several markers onto one line parses exactly as it always did.
     *
     * **Mutation-proof:** deleting the `LOOSE_MARKER_RE` fallback from
     * [parseNumberedResponse] turns this red — anchoring would then *add* a retry, which it is
     * forbidden to do.
     */
    @Test
    fun `several markers on one line still parse via the loose second pass`() {
        val parsed = parseNumberedResponse("[[1]] Ena. [[2]] Dve. [[3]] Tri.", 3)

        assertEquals(listOf("Ena.", "Dve.", "Tri."), parsed)
    }

    @Test
    fun `a duplicate marker is a mapping failure`() {
        val error =
            assertThrows(MarkerMappingException::class.java) {
                parseNumberedResponse("[[1]] Ena.\n[[1]] Spet ena.\n[[3]] Tri.", 3)
            }

        assertEquals(
            "expected 3 numbered segments, found 3 markers (2 distinct)",
            error.message,
        )
    }

    @Test
    fun `a missing index is a mapping failure naming the segment`() {
        val error =
            assertThrows(MarkerMappingException::class.java) {
                parseNumberedResponse("[[1]] Ena.\n[[2]] Dve.", 3)
            }

        // Both scans see 2 markers for a 3-segment batch, so the count check fires first.
        assertEquals(
            "expected 3 numbered segments, found 2 markers (2 distinct)",
            error.message,
        )
    }

    @Test
    fun `an index outside 1 to n is a mapping failure`() {
        val error =
            assertThrows(MarkerMappingException::class.java) {
                parseNumberedResponse("[[1]] Ena.\n[[2]] Dve.\n[[7]] Sedem.", 3)
            }

        assertEquals("segment [[3]] missing or empty in reply", error.message)
    }

    @Test
    fun `an empty translation is a mapping failure, never a silently dropped segment`() {
        val error =
            assertThrows(MarkerMappingException::class.java) {
                parseNumberedResponse("[[1]] Ena.\n[[2]]\n[[3]] Tri.", 3)
            }

        assertEquals("segment [[2]] missing or empty in reply", error.message)
    }

    @Test
    fun `a reply with extra markers is a mapping failure`() {
        val error =
            assertThrows(MarkerMappingException::class.java) {
                parseNumberedResponse("[[1]] Ena.\n[[2]] Dve.\n[[3]] Tri.\n[[4]] Stiri.", 3)
            }

        assertEquals(
            "expected 3 numbered segments, found 4 markers (4 distinct)",
            error.message,
        )
    }

    @Test
    fun `a reply with no markers at all is a mapping failure`() {
        assertThrows(MarkerMappingException::class.java) {
            parseNumberedResponse("Tukaj ni oznak.", 2)
        }
    }

    @Test
    fun `a zero-padded marker resolves to its integer index, as in Python`() {
        val parsed = parseNumberedResponse("[[01]] Ena.\n[[02]] Dve.", 2)

        assertEquals(listOf("Ena.", "Dve."), parsed)
    }

    @Test
    fun `an absurdly wide marker fails the mapping instead of overflowing`() {
        assertThrows(MarkerMappingException::class.java) {
            parseNumberedResponse("[[99999999999999999999]] Ena.", 1)
        }
    }

    /**
     * `^` under Java's `MULTILINE` would also match after a bare carriage return; Python's would
     * not. [RegexOption.UNIX_LINES] closes that gap, so tablet and workstation agree.
     *
     * **Mutation-proof:** removing `RegexOption.UNIX_LINES` turns this red — the anchored scan
     * would then find two markers and parse a reply Python treats as one.
     */
    @Test
    fun `a marker after a bare carriage return does not anchor, matching Python`() {
        val reply = "[[1]] Ena je tu.\r[[2]] To je se vedno prvi segment."

        val parsed = parseNumberedResponse(reply, 1)

        assertEquals(listOf("Ena je tu.\r[[2]] To je se vedno prvi segment."), parsed)
    }
}
