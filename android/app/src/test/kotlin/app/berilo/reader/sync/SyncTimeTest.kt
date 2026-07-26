package app.berilo.reader.sync

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DST half of S3.2's Verify line.
 *
 * `sync_api.md` §5 notes that the UTC-only wire format cannot itself break on DST — the risk it
 * names is the *client's* local-to-UTC conversion. These tests pin that conversion at
 * Europe/Ljubljana's two transitions, including the October hour that occurs twice, which is
 * the only case where a local wall-clock time is genuinely ambiguous.
 */
class SyncTimeTest {

    private val ljubljana = ZoneId.of("Europe/Ljubljana")

    /** The server's own validation regex, so a format drift fails here rather than in production. */
    private val wireFormat =
        Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$""")

    @Test
    fun `every rendered timestamp matches the format the server validates`() {
        val samples =
            listOf(
                0L,
                1_774_000_000_000L,
                1_774_000_000_123L, // millisecond precision -> 3 fractional digits
                ZonedDateTime.of(2026, 7, 25, 13, 0, 0, 0, ljubljana).toInstant().toEpochMilli(),
            )
        samples.forEach { millis ->
            val wire = SyncTime.toWire(millis)
            assertTrue("$wire must match the contract's timestamp format", wireFormat.matches(wire))
        }
    }

    @Test
    fun `round-trips through the wire without drift`() {
        val millis = 1_774_000_000_123L
        assertEquals(millis, SyncTime.fromWire(SyncTime.toWire(millis)))
    }

    /**
     * Spring forward: 02:00 local does not exist on this date. Two writes a minute either side
     * of the gap must stay one minute apart and strictly ordered — if they did not, last-write-
     * wins would pick the wrong edit for every book touched during the transition.
     */
    @Test
    fun `writes spanning the March DST gap stay ordered`() {
        val before = ZonedDateTime.of(2026, 3, 29, 1, 59, 0, 0, ljubljana).toInstant().toEpochMilli()
        val after = ZonedDateTime.of(2026, 3, 29, 3, 1, 0, 0, ljubljana).toInstant().toEpochMilli()

        assertTrue("the later local write must be the later instant", after > before)
        assertTrue(SyncTime.toWire(after) > SyncTime.toWire(before))
        // Local clocks jumped an hour; the true elapsed time is two minutes.
        assertEquals(2 * 60 * 1000L, after - before)
    }

    /**
     * Fall back: 02:30 local happens twice on this date. The two instants are an hour apart and
     * must render as two different UTC timestamps — collapsing them would make one edit
     * silently overwrite the other despite being an hour older.
     */
    @Test
    fun `the repeated October hour maps to two distinct instants`() {
        val ambiguous = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, ljubljana)
        val firstPass = ambiguous.withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val secondPass = ambiguous.withLaterOffsetAtOverlap().toInstant().toEpochMilli()

        assertEquals(60 * 60 * 1000L, secondPass - firstPass)
        assertNotEquals(SyncTime.toWire(firstPass), SyncTime.toWire(secondPass))
        assertTrue(SyncTime.toWire(secondPass) > SyncTime.toWire(firstPass))
    }

    @Test
    fun `a malformed timestamp is skipped rather than throwing`() {
        // One bad row must not abort a whole sync round.
        assertNull(SyncTime.fromWire("not-a-date"))
        assertNull(SyncTime.fromWire(""))
        assertNull(SyncTime.fromWire(null))
    }
}
