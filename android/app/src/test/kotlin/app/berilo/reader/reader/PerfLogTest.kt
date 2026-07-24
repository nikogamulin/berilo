package app.berilo.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The R2 page-turn timing math. [PerfLog.renderMillis] converts a nanosecond
 * span to whole milliseconds and floors negatives at zero (monotonic clocks can
 * read equal or, across cores, momentarily backwards).
 */
class PerfLogTest {

    @Test
    fun `render duration converts nanos to floored whole milliseconds`() {
        assertEquals(150L, PerfLog.renderMillis(0L, 150_000_000L))
        assertEquals(0L, PerfLog.renderMillis(0L, 999_999L))
        assertEquals(1L, PerfLog.renderMillis(1_000_000L, 2_500_000L))
    }

    @Test
    fun `a non-positive span is floored at zero, never negative`() {
        assertEquals(0L, PerfLog.renderMillis(100L, 100L))
        assertEquals(0L, PerfLog.renderMillis(500_000_000L, 400_000_000L))
    }
}
