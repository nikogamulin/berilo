package app.berilo.reader.translate.epub

import java.security.MessageDigest
import java.util.UUID

/**
 * RFC 4122 version-5 (SHA-1, name-based) UUIDs, matching Python's `uuid.uuid5` exactly.
 *
 * `java.util.UUID` ships a v3 factory (`nameUUIDFromBytes`, MD5) and **no v5 factory at all**,
 * so this is hand-rolled. [EpubWriter] derives a book's `dc:identifier` this way, and that
 * identifier is part of `content.opf` — get a single bit wrong and the workstation's EPUB and
 * the tablet's differ, which is precisely the drift the byte-identity gate exists to catch.
 *
 * The two easy ways to get it wrong, both guarded by [Uuid5Test]:
 *
 * - **Namespace bytes.** The digest is taken over the namespace's 16 *binary* octets in RFC
 *   4122 field order (`time_low`, `time_mid`, `time_hi_and_version`, `clock_seq`, `node`),
 *   not over its 36-character string form. `UUID.mostSignificantBits`/`leastSignificantBits`
 *   are already in that order, written big-endian.
 * - **Version and variant.** After hashing, byte 6's high nibble is forced to 5 and byte 8's
 *   top two bits to `10`. Skip either and the value is still a plausible-looking UUID.
 */

/** Length of a binary UUID. */
private const val UUID_BYTES = 16

/** Offset of the byte whose high nibble carries the version. */
private const val VERSION_BYTE = 6

/** Offset of the byte whose two high bits carry the RFC 4122 variant. */
private const val VARIANT_BYTE = 8

private const val SHA1 = "SHA-1"

/**
 * Derive the version-5 UUID of [name] within [namespace].
 *
 * @param namespace The namespace UUID.
 * @param name The name, hashed as UTF-8 — matching Python, which encodes `str` names as UTF-8.
 * @return The name-based UUID, equal to `uuid.uuid5(namespace, name)`.
 */
fun uuid5(namespace: UUID, name: String): UUID {
    val digest = MessageDigest.getInstance(SHA1)
    digest.update(namespace.toBytes())
    digest.update(name.toByteArray(Charsets.UTF_8))
    val hash = digest.digest().copyOf(UUID_BYTES)
    hash[VERSION_BYTE] = ((hash[VERSION_BYTE].toInt() and 0x0F) or 0x50).toByte()
    hash[VARIANT_BYTE] = ((hash[VARIANT_BYTE].toInt() and 0x3F) or 0x80).toByte()
    return hash.toUuid()
}

/** The UUID's 16 octets, big-endian, in RFC 4122 field order. */
private fun UUID.toBytes(): ByteArray {
    val bytes = ByteArray(UUID_BYTES)
    var high = mostSignificantBits
    var low = leastSignificantBits
    for (index in 7 downTo 0) {
        bytes[index] = (high and 0xFF).toByte()
        bytes[index + 8] = (low and 0xFF).toByte()
        high = high ushr 8
        low = low ushr 8
    }
    return bytes
}

/** Read 16 big-endian octets back into a [UUID]. */
private fun ByteArray.toUuid(): UUID {
    var high = 0L
    var low = 0L
    for (index in 0 until 8) {
        high = (high shl 8) or (this[index].toLong() and 0xFF)
        low = (low shl 8) or (this[index + 8].toLong() and 0xFF)
    }
    return UUID(high, low)
}
