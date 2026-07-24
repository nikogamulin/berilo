package app.berilo.reader.dictionary

import java.security.MessageDigest

private const val SHA_256 = "SHA-256"

/**
 * Stable hash of a sentence-context string, used as part of the dictionary cache key
 * ([app.berilo.reader.store.db.DictionaryEntryEntity]) so the same word looked up in a
 * different sentence gets its own cache entry (disambiguation).
 *
 * SHA-256 rather than `String.hashCode()`: it is collision-resistant (a hash collision
 * would silently serve the wrong cached definition) and, unlike a rolling hash tuned for
 * speed, its stability across JVM/Android versions is a documented algorithm guarantee
 * rather than an implementation detail.
 */
object SentenceHash {

    /**
     * Computes the cache-key hash for [sentence].
     *
     * The sentence is trimmed before hashing so whitespace differences at the edges (e.g.
     * from how the navigator reconstructs Locator text) never split one cache entry into
     * two.
     *
     * @param sentence The sentence-context string to hash.
     * @return A lowercase hex-encoded SHA-256 digest.
     */
    fun of(sentence: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(sentence.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
