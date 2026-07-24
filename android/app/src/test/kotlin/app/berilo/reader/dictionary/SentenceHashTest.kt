package app.berilo.reader.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SentenceHashTest {

    @Test
    fun `same sentence always hashes to the same value`() {
        val sentence = "The bank was closed on Sunday."

        assertEquals(SentenceHash.of(sentence), SentenceHash.of(sentence))
    }

    @Test
    fun `matches the known SHA-256 test vector for the word 'test' (locks in the algorithm)`() {
        // Public SHA-256 reference digest for "test" — a regression guard against silently
        // swapping in a faster, non-cryptographic hash (e.g. String.hashCode()), which would
        // still pass the other tests here but drop the collision-resistance guarantee the
        // cache key relies on.
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            SentenceHash.of("test"),
        )
    }

    @Test
    fun `different sentences hash differently`() {
        assertNotEquals(
            SentenceHash.of("The bank was closed."),
            SentenceHash.of("She sat by the river bank."),
        )
    }

    @Test
    fun `leading and trailing whitespace does not change the hash`() {
        assertEquals(
            SentenceHash.of("The bank was closed."),
            SentenceHash.of("  The bank was closed.  "),
        )
    }

    @Test
    fun `hash is a lowercase hex string`() {
        val hash = SentenceHash.of("Some sentence.")

        assertEquals(64, hash.length)
        assertEquals(hash, hash.lowercase())
        assertEquals(true, hash.all { it in "0123456789abcdef" })
    }
}
