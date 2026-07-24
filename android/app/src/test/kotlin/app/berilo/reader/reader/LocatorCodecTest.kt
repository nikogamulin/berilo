package app.berilo.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locator persistence round-trip. Runs under Robolectric because Readium parses
 * a Locator's href through `android.net.Uri`, which the bare JVM lacks. This is
 * the guarantee behind "reopening restores the exact position": the locations
 * (progression / position / totalProgression) must survive encode → decode
 * unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class LocatorCodecTest {

    @Test
    fun `decode then encode then decode preserves the reading position`() {
        val restored = LocatorCodec.decode(SAMPLE)
        assertNotNull("a valid Readium locator must decode", restored)
        restored!!

        assertEquals(0.42, restored.locations.progression!!, DELTA)
        assertEquals(57, restored.locations.position)
        assertEquals(0.31, restored.locations.totalProgression!!, DELTA)
        assertTrue(restored.href.toString().contains("ch3.html"))
        assertEquals("Chapter 3", restored.title)

        val roundTripped = LocatorCodec.decode(LocatorCodec.encode(restored))
        assertNotNull(roundTripped)
        assertEquals(restored.locations.progression!!, roundTripped!!.locations.progression!!, DELTA)
        assertEquals(restored.locations.position, roundTripped.locations.position)
        assertEquals(restored.locations.totalProgression!!, roundTripped.locations.totalProgression!!, DELTA)
        assertEquals(restored.href.toString(), roundTripped.href.toString())
    }

    @Test
    fun `decode returns null for blank, null, or malformed input`() {
        assertNull(LocatorCodec.decode(null))
        assertNull(LocatorCodec.decode(""))
        assertNull(LocatorCodec.decode("   "))
        assertNull(LocatorCodec.decode("not json at all"))
    }

    private companion object {
        const val DELTA = 1e-9
        const val SAMPLE = """
            {
              "href": "/OEBPS/ch3.html",
              "type": "application/xhtml+xml",
              "title": "Chapter 3",
              "locations": { "progression": 0.42, "position": 57, "totalProgression": 0.31 },
              "text": { "highlight": "the quick brown fox" }
            }
        """
    }
}
