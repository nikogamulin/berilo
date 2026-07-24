package app.berilo.reader.interpretation

import java.security.MessageDigest

private const val SHA_256 = "SHA-256"

/**
 * Stable hash of a passage's text, used as the cache key
 * ([app.berilo.reader.store.db.InterpretationEntryEntity]) so the same passage
 * re-interpreted in a different language/model gets its own cache entry.
 *
 * SHA-256 rather than `String.hashCode()`: it is collision-resistant (a hash collision
 * would silently serve the wrong cached interpretation) and, unlike a rolling hash tuned
 * for speed, its stability across JVM/Android versions is a documented algorithm
 * guarantee rather than an implementation detail (mirrors
 * [app.berilo.reader.dictionary.SentenceHash]'s reasoning for S2.4).
 */
object PassageHash {

    /**
     * Computes the cache-key hash for [passage].
     *
     * The passage is trimmed before hashing so whitespace differences at the edges (e.g.
     * from how the navigator reconstructs selected text) never split one cache entry into
     * two.
     *
     * @param passage The passage text to hash.
     * @return A lowercase hex-encoded SHA-256 digest.
     */
    fun of(passage: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(passage.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
