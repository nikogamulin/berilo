package app.berilo.reader.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side encryption for the personal book vault (S3.7, `docs/sync_api.md` §8.2(2)).
 *
 * **The key never leaves the device.** It is derived here, from a secret only the user holds,
 * and only [VaultSealed.nonce] and [VaultSealed.ciphertext] are ever handed to a
 * [VaultGateway] — never the passphrase, never the derived key, never the plaintext. What the
 * server stores is bytes it cannot read, which is what keeps the provider a conduit rather than
 * a party with editorial control (DSA Art. 6(2); `docs/research/2026-07-27-personal-copy-cloud-sync.md`
 * §3.1(3)).
 *
 * Everything here is `javax.crypto` from the platform — S3.7 adds no dependency. AES-GCM is an
 * AEAD, so a tampered or truncated object fails to decrypt loudly rather than yielding garbage
 * plaintext.
 *
 * The salt is **not** secret and travels with the ciphertext metadata: a second device needs it
 * to derive the same key from the same passphrase, which is the entire point of the vault.
 */
object VaultCrypto {

    /** Algorithm string recorded alongside every stored object, so a future scheme can migrate. */
    const val ALGORITHM: String = "AES-256-GCM/PBKDF2-HMAC-SHA256"

    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 256-bit content key. */
    private const val KEY_LENGTH_BITS = 256

    /**
     * PBKDF2 iteration count. At OWASP's 2023 floor for PBKDF2-HMAC-SHA256; raising it later is a
     * migration (the count would have to be recorded per object), which is why it is named here
     * rather than inlined at the call site.
     */
    private const val ITERATIONS = 210_000

    /** 128-bit salt — per user, generated once, stored with the ciphertext metadata. */
    private const val SALT_LENGTH_BYTES = 16

    /**
     * 96-bit nonce, the size GCM is specified for. **Never reused under one key**: a repeated
     * (key, nonce) pair in GCM leaks the XOR of two plaintexts and destroys authentication, so
     * [seal] draws a fresh one from [SecureRandom] on every call and never accepts one.
     */
    private const val NONCE_LENGTH_BYTES = 12

    /** 128-bit authentication tag, GCM's maximum. */
    private const val TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    /** A fresh random salt for a new vault. Not secret; store it with the ciphertext metadata. */
    fun newSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)

    /**
     * Derive the content key from the user's own secret.
     *
     * @param passphrase The user's secret. Cleared by the caller when done — this function does
     *   not retain it.
     * @param salt Per-user salt from [newSalt], replayed on every later device.
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        require(passphrase.isNotEmpty()) { "vault passphrase must not be empty" }
        require(salt.size == SALT_LENGTH_BYTES) {
            "salt must be $SALT_LENGTH_BYTES bytes, was ${salt.size}"
        }
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM).generateSecret(spec)
            SecretKeySpec(bytes.encoded, KEY_ALGORITHM)
        } finally {
            spec.clearPassword()
        }
    }

    /** Encrypt [plaintext] under [key] with a fresh nonce. */
    fun seal(key: SecretKey, plaintext: ByteArray): VaultSealed {
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        return VaultSealed(nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    /** Encrypt [plaintext]'s UTF-8 bytes under [key]. */
    fun seal(key: SecretKey, plaintext: String): VaultSealed =
        seal(key, plaintext.toByteArray(Charsets.UTF_8))

    /**
     * Decrypt [sealed] under [key].
     *
     * @throws javax.crypto.AEADBadTagException when the key is wrong or the bytes were altered —
     *   GCM authenticates, so a wrong key fails loudly instead of returning garbage.
     */
    fun open(key: SecretKey, sealed: VaultSealed): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, sealed.nonce))
        return cipher.doFinal(sealed.ciphertext)
    }

    /** Decrypt [sealed] under [key] and decode it as UTF-8 text. */
    fun openText(key: SecretKey, sealed: VaultSealed): String =
        String(open(key, sealed), Charsets.UTF_8)
}

/**
 * One encrypted payload: the nonce it was sealed with, and the ciphertext.
 *
 * Deliberately carries no plaintext, no key and no passphrase — this is the whole of what any
 * [VaultGateway] ever receives, so the type itself documents what leaves the device.
 *
 * @property nonce The 96-bit GCM nonce. Public; unique per (key, message).
 * @property ciphertext AES-GCM ciphertext with its 128-bit tag appended.
 */
data class VaultSealed(val nonce: ByteArray, val ciphertext: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultSealed) return false
        return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()

    /** Never renders the bytes: a ciphertext in a log line is still a copy of the user's book. */
    override fun toString(): String = "VaultSealed(${ciphertext.size} bytes)"
}
