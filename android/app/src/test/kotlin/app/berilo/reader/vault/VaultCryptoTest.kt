package app.berilo.reader.vault

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-side encryption for the vault (S3.7, `docs/sync_api.md` §8.2(2)).
 *
 * Plain JUnit — no Robolectric, no Android: `javax.crypto` is the platform's, which is the point
 * of choosing it (S3.7 adds no dependency).
 */
class VaultCryptoTest {

    private val salt = VaultCrypto.newSalt()

    @Test
    fun `a sealed payload round-trips under the same passphrase and salt`() {
        val key = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)

        val sealed = VaultCrypto.seal(key, PLAINTEXT)

        assertEquals(PLAINTEXT, VaultCrypto.openText(key, sealed))
    }

    /** The cross-device case: same secret, same salt, different derivation call, same key. */
    @Test
    fun `a second device derives the same key from the same secret and salt`() {
        val firstDevice = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)
        val secondDevice = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)

        val sealed = VaultCrypto.seal(firstDevice, PLAINTEXT)

        assertEquals(
            "a restore on another device must open what this one sealed",
            PLAINTEXT,
            VaultCrypto.openText(secondDevice, sealed),
        )
    }

    /** §8.2(2): the stored bytes are opaque. Nothing readable survives into the ciphertext. */
    @Test
    fun `the ciphertext contains no plaintext`() {
        val key = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)

        val sealed = VaultCrypto.seal(key, PLAINTEXT)

        assertFalse(
            "the server must hold bytes it cannot read",
            sealed.ciphertext.containsSubsequence(PLAINTEXT.toByteArray()),
        )
        assertFalse(
            "and must never hold the secret that opens them",
            sealed.ciphertext.containsSubsequence(PASSPHRASE.toByteArray()),
        )
    }

    /** AES-GCM authenticates, so a wrong secret fails loudly rather than yielding garbage. */
    @Test
    fun `a wrong passphrase fails to authenticate rather than returning garbage`() {
        val right = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)
        val wrong = VaultCrypto.deriveKey("not the passphrase".toCharArray(), salt)
        val sealed = VaultCrypto.seal(right, PLAINTEXT)

        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(wrong, sealed) }
    }

    /** A different salt is a different key, which is why the salt must be stored and replayed. */
    @Test
    fun `the same passphrase under a different salt cannot open the payload`() {
        val original = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)
        val reSalted = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), VaultCrypto.newSalt())
        val sealed = VaultCrypto.seal(original, PLAINTEXT)

        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(reSalted, sealed) }
    }

    /**
     * A repeated (key, nonce) pair in GCM leaks the XOR of two plaintexts and destroys
     * authentication, so [VaultCrypto.seal] must draw a fresh nonce every time and never expose
     * a way to supply one.
     */
    @Test
    fun `every seal draws a fresh nonce`() {
        val key = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)

        val nonces = (1..NONCE_SAMPLES).map { VaultCrypto.seal(key, PLAINTEXT).nonce.toList() }

        assertEquals(
            "a reused nonce under one key breaks GCM outright",
            NONCE_SAMPLES,
            nonces.toSet().size,
        )
    }

    /** Sealing the same text twice must not produce the same bytes — no ciphertext fingerprint. */
    @Test
    fun `sealing identical text twice produces different ciphertext`() {
        val key = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)

        val first = VaultCrypto.seal(key, PLAINTEXT)
        val second = VaultCrypto.seal(key, PLAINTEXT)

        assertNotEquals(
            "identical ciphertexts would let a server learn two users hold the same passage",
            first.ciphertext.toList(),
            second.ciphertext.toList(),
        )
    }

    /** Tampering is detected: GCM's tag covers the whole ciphertext. */
    @Test
    fun `a single flipped byte fails to authenticate`() {
        val key = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)
        val sealed = VaultCrypto.seal(key, PLAINTEXT)
        val tampered = sealed.copy(ciphertext = sealed.ciphertext.copyOf())
        tampered.ciphertext[0] = (tampered.ciphertext[0] + 1).toByte()

        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(key, tampered) }
    }

    /** The debug rendering must never leak the bytes it wraps. */
    @Test
    fun `toString never renders the ciphertext`() {
        val key = VaultCrypto.deriveKey(PASSPHRASE.toCharArray(), salt)

        val rendered = VaultCrypto.seal(key, PLAINTEXT).toString()

        assertFalse(rendered.contains(PLAINTEXT))
        assertTrue(rendered.contains("bytes"))
    }

    /** An empty owner would collapse every user into one namespace — refuse it at construction. */
    @Test
    fun `a blank UserId is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { UserId("") }
        assertThrows(IllegalArgumentException::class.java) { UserId("   ") }
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        const val PLAINTEXT = "Poglavje ena. Vsaka beseda bralčeve lastne knjige."
        const val NONCE_SAMPLES = 16
    }
}
