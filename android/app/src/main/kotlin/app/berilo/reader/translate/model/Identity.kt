package app.berilo.reader.translate.model

import java.security.MessageDigest

/**
 * The three identity hashes, ported byte-exactly from the Python translator.
 *
 * `book_hash` is a sha1 over the ordered segment ids; [makeSegmentId] is a sha1 over
 * `"{chapter_index}:{position}:{text.strip()}"` (`translator/berilo/models.py`), and
 * [segmentHash] is a sha1 over the stripped source text (`translator/berilo/cache.py`).
 *
 * If this file disagrees with Python by a single byte, the tablet shares no cache row with the
 * workstation and a book already paid for on the laptop is re-billed in full on the device.
 * `IdentityFixtureTest` holds that line against fixtures generated from the real books.
 */

/** Length of a hex-encoded sha1 digest. */
const val SHA1_HEX_LENGTH: Int = 40

private const val SHA1 = "SHA-1"

/** Separator between the three components of a segment id payload. */
private const val SEGMENT_ID_SEPARATOR = ":"

/** Separator between segment ids in the book-hash payload. */
private const val BOOK_HASH_SEPARATOR = "\n"

/**
 * Every code point for which Python's `str.isspace()` is true, and therefore exactly what
 * `str.strip()` removes.
 *
 * Kotlin's [Char.isWhitespace] delegates to `Character.isWhitespace() || Character.isSpaceChar()`,
 * which covers all of these **except U+0085 NEXT LINE** (category `Cc`, not a space char, and
 * excluded from `Character.isWhitespace`). `String.trim()` would therefore leave a leading or
 * trailing U+0085 in place where Python strips it, producing a different digest for the same
 * segment. Hard-coding the set also immunizes the hash against a JDK Unicode-version bump.
 */
private val PYTHON_WHITESPACE: Set<Char> =
    buildSet {
        addAll('\u0009'..'\u000D')
        addAll('\u001C'..'\u001F')
        add('\u0020')
        add('\u0085')
        add('\u00A0')
        add('\u1680')
        addAll('\u2000'..'\u200A')
        add('\u2028')
        add('\u2029')
        add('\u202F')
        add('\u205F')
        add('\u3000')
    }

/**
 * Strip leading and trailing whitespace exactly as Python's `str.strip()` does.
 *
 * Use this, never [String.trim], anywhere a value feeds a hash that must match the CLI.
 *
 * @return The string without leading or trailing Python-whitespace characters.
 */
fun String.pythonStrip(): String {
    var start = 0
    var end = length
    while (start < end && this[start] in PYTHON_WHITESPACE) start++
    while (end > start && this[end - 1] in PYTHON_WHITESPACE) end--
    return substring(start, end)
}

/**
 * Collapse every run of Python-whitespace to a single space, then [pythonStrip] the result.
 *
 * This is `re.sub(r"\s+", " ", text).strip()`, which is how `normalize_epub` turns a block
 * element's serialized content into `Segment.text` — and therefore part of the segment id.
 * Kotlin's `Regex("\\s+")` will not do: the JVM's `\s` is the six ASCII whitespace characters,
 * so a non-breaking space or a U+2028 inside a paragraph would survive here and be collapsed
 * in Python, giving the same paragraph two different digests.
 *
 * The trailing [pythonStrip] is belt-and-braces rather than load-bearing: after the collapse
 * the only whitespace left is U+0020, so Kotlin's `trim()` would do the same thing here (it is
 * Unicode-aware, unlike Java's `String.trim()`, which cuts everything below U+0021). It stays
 * because the pairing is the invariant — change the collapse and the strip still holds.
 *
 * @return The text with internal whitespace runs collapsed and the ends stripped.
 */
fun String.pythonCollapseWhitespace(): String {
    val collapsed = StringBuilder(length)
    var inWhitespace = false
    for (character in this) {
        if (character in PYTHON_WHITESPACE) {
            inWhitespace = true
        } else {
            if (inWhitespace) collapsed.append(' ')
            inWhitespace = false
            collapsed.append(character)
        }
    }
    if (inWhitespace) collapsed.append(' ')
    return collapsed.toString().pythonStrip()
}

/**
 * Derive a stable content-hash id for a segment.
 *
 * Mirrors `berilo.models.make_segment_id`: sha1 of the UTF-8 encoding of
 * `"{chapterIndex}:{position}:{text.pythonStrip()}"`.
 *
 * @param chapterIndex Zero-based index of the chapter containing the segment.
 * @param position Zero-based, **book-global** document-order position of the segment.
 * @param text The segment's text; stripped before hashing.
 * @return A 40-character lowercase hexadecimal sha1 digest.
 */
fun makeSegmentId(chapterIndex: Int, position: Int, text: String): String {
    val payload =
        chapterIndex.toString() +
            SEGMENT_ID_SEPARATOR +
            position.toString() +
            SEGMENT_ID_SEPARATOR +
            text.pythonStrip()
    return sha1Hex(payload)
}

/**
 * Derive a stable per-book hash from ordered segment ids.
 *
 * Mirrors `berilo.cache.book_hash`: sha1 over the newline-joined ids.
 *
 * @param segmentIds Segment ids in document order.
 * @return A 40-character lowercase hexadecimal sha1 digest.
 */
fun bookHash(segmentIds: List<String>): String =
    sha1Hex(segmentIds.joinToString(BOOK_HASH_SEPARATOR))

/**
 * Derive a stable per-book hash from a book's segments, in document order.
 *
 * @param book The book whose identity to hash.
 * @return A 40-character lowercase hexadecimal sha1 digest.
 */
fun bookHash(book: Book): String = bookHash(book.segments.map(Segment::id))

/**
 * Derive the translation-cache key component for a segment's source text.
 *
 * Mirrors `berilo.cache.segment_hash`: sha1 over the stripped text, with no position, so
 * identical paragraphs share one cached translation.
 *
 * @param text The segment's source text.
 * @return A 40-character lowercase hexadecimal sha1 digest.
 */
fun segmentHash(text: String): String = sha1Hex(text.pythonStrip())

/**
 * Hex-encode the sha1 digest of a string's UTF-8 bytes.
 *
 * Internal (not private) so [glossaryIdentity] (`Glossary.kt`) reuses the same digest
 * mechanism rather than reimplementing it — a second sha1-hex helper drifting from this one
 * is exactly the kind of silent divergence B1a exists to prevent.
 *
 * @param payload The string to hash.
 * @return A 40-character lowercase hexadecimal digest.
 */
internal fun sha1Hex(payload: String): String {
    val digest = MessageDigest.getInstance(SHA1).digest(payload.toByteArray(Charsets.UTF_8))
    return buildString(SHA1_HEX_LENGTH) {
        digest.forEach { byte -> append("%02x".format(byte)) }
    }
}
