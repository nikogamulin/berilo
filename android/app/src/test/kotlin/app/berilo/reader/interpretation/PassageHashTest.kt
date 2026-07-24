package app.berilo.reader.interpretation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PassageHashTest {

    @Test
    fun `same passage hashes to the same value`() {
        val passage = "The city slept beneath a sky the color of ash."

        assertEquals(PassageHash.of(passage), PassageHash.of(passage))
    }

    @Test
    fun `hash is stable across repeated calls (no incidental state)`() {
        val passage = "A long paragraph describing the fall of the old regime and its aftermath."

        val first = PassageHash.of(passage)
        val second = PassageHash.of(passage)
        val third = PassageHash.of(passage)

        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `leading and trailing whitespace does not change the hash`() {
        val passage = "Exact same passage."

        assertEquals(PassageHash.of(passage), PassageHash.of("  $passage  \n"))
    }

    @Test
    fun `different passages hash differently`() {
        assertNotEquals(PassageHash.of("Passage one."), PassageHash.of("Passage two."))
    }
}
