package app.berilo.reader.translate.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Book] serialization against payloads Python actually wrote.
 *
 * `synthetic.book.json` is verbatim `Book.to_json()` output, so decoding it proves the port reads
 * the CLI's shape and re-encoding it proves the port writes the same one. Structural equality
 * (rather than string equality) is the assertion because whitespace between two JSON writers is
 * not part of the contract — key names, nesting, nulls and base64 payloads are.
 */
class BookJsonTest {

    private val pythonJson = IdentityFixtures.readText("synthetic.book.json")

    @Test
    fun `decodes a book Python serialized`() {
        val book = bookFromJson(pythonJson)
        assertEquals("Sintetična knjiga", book.title)
        assertEquals(listOf("Ana Novak", "Boris Kovač"), book.authors)
        assertEquals("sl", book.language)
        assertEquals("synthetic.epub", book.sourcePath)
        assertEquals("epub", book.sourceFormat)
        assertEquals(3, book.chapterCount)
        assertEquals(SegmentType.HEADING, book.segments.first().type)
        assertEquals(1, book.segments.first().headingLevel)
        assertNull(book.segments[1].headingLevel)
        assertTrue(book.segments.any { it.type == SegmentType.LIST_ITEM })
        assertTrue(book.segments.any { it.type == SegmentType.BLOCKQUOTE })
        assertTrue(book.segments.any { it.type == SegmentType.CAPTION })
        assertTrue(book.segments.any { it.type == SegmentType.OTHER })
    }

    @Test
    fun `decodes image resources from base64`() {
        val book = bookFromJson(pythonJson)
        assertEquals(2, book.images.size)
        val cover = book.images.first()
        assertEquals("img0001", cover.id)
        assertEquals("image/png", cover.mediaType)
        assertEquals("images/pika.png", cover.sourceHref)
        assertNull("a leading image has no anchor", cover.anchorSegmentId)
        assertEquals("Ena sama pika", cover.alt)
        // PNG magic: proves the bytes survived base64 rather than being carried as text.
        assertEquals(listOf(137, 80, 78, 71), cover.data.take(4).map { it.toInt() and 0xFF })
        assertNull("alt is optional", book.images[1].alt)
        assertEquals(book.segments[8].id, book.images[1].anchorSegmentId)
    }

    @Test
    fun `re-encodes to the same shape Python wrote`() {
        val reEncoded = bookFromJson(pythonJson).toJson()
        assertEquals(
            Json.parseToJsonElement(pythonJson),
            Json.parseToJsonElement(reEncoded),
        )
    }

    @Test
    fun `survives a toJson-fromJson round trip with book hash unchanged`() {
        val book = bookFromJson(pythonJson)
        val roundTripped = bookFromJson(book.toJson())
        assertEquals(book, roundTripped)
        assertEquals(book.images, roundTripped.images)
        assertEquals(bookHash(book), bookHash(roundTripped))
        assertEquals(
            IdentityFixtures.loadIdentity("synthetic").bookHash,
            bookHash(roundTripped),
        )
    }

    @Test
    fun `accepts a pre-S1_14 payload with no images and no heading levels`() {
        val legacy = bookFromJson(IdentityFixtures.readText("synthetic.book.legacy.json"))
        assertTrue("images default to empty", legacy.images.isEmpty())
        assertTrue("heading levels default to null", legacy.segments.all { it.headingLevel == null })
        // Identity is unaffected: heading_level and images never enter a hash.
        assertEquals(
            IdentityFixtures.loadIdentity("synthetic").bookHash,
            bookHash(legacy),
        )
    }

    @Test
    fun `rejects an unknown segment type instead of defaulting`() {
        val corrupted = pythonJson.replaceFirst("\"type\": \"heading\"", "\"type\": \"sidebar\"")
        assertThrows(SerializationException::class.java) { bookFromJson(corrupted) }
    }

    @Test
    fun `SegmentType round-trips through its Python wire value`() {
        SegmentType.entries.forEach { type ->
            assertEquals(type, SegmentType.ofValue(type.value))
        }
        assertEquals("list_item", SegmentType.LIST_ITEM.value)
        assertThrows(IllegalArgumentException::class.java) { SegmentType.ofValue("sidebar") }
    }
}
