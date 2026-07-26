package app.berilo.reader.sync

import app.berilo.reader.store.db.BookEntity
import app.berilo.reader.store.db.DictionaryEntryEntity
import app.berilo.reader.store.db.HighlightColor
import app.berilo.reader.store.db.HighlightEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapping between Room entities and the contract's row shapes (`docs/sync_api.md` §2). */
class SyncMapperTest {

    private fun book(
        id: String = "hash-1",
        deletedAt: Long? = null,
        progression: String? = null,
        lastOpenedAt: Long? = null,
    ) =
        BookEntity(
            id = id,
            title = "Vojna in mir",
            authors = "Lev Tolstoj",
            filePath = "/data/user/0/app.berilo.reader/files/books/hash-1.epub",
            coverPath = "/data/user/0/app.berilo.reader/files/covers/hash-1.jpg",
            addedAt = 1_000L,
            lastOpenedAt = lastOpenedAt,
            progressionJson = progression,
            sourceLang = "en",
            targetLang = "sl",
            updatedAt = 2_000L,
            deletedAt = deletedAt,
        )

    /**
     * The anti-piracy invariant (CLAUDE.md §2), asserted on the actual bytes rather than
     * trusted to review. If a future edit adds the file path to the payload, this fails.
     */
    @Test
    fun `a book payload carries metadata only, never a path to the file`() {
        val payload = SyncMapper.bookMetadataItem(book()).toString()

        assertFalse("the EPUB path must never be sent", payload.contains("books/hash-1.epub"))
        assertFalse("the cover path must never be sent", payload.contains("covers"))
        assertFalse(payload.contains("filePath"))
        assertFalse(payload.contains("coverPath"))
        assertTrue(payload.contains("Vojna in mir"))
    }

    @Test
    fun `a book payload uses exactly the fields the contract defines`() {
        val item = SyncMapper.bookMetadataItem(book())

        assertEquals(
            setOf(
                "op",
                "id",
                "content_hash",
                "updated_at",
                "title",
                "authors",
                "source_lang",
                "target_lang",
                "created_at",
            ),
            item.keys,
        )
        assertEquals("upsert", item["op"]?.jsonPrimitive?.content)
        assertEquals("hash-1", item["content_hash"]?.jsonPrimitive?.content)
        assertEquals("sl", item["target_lang"]?.jsonPrimitive?.content)
    }

    /** A tombstone sends the key and the timestamp — no point re-sending a deleted row's body. */
    @Test
    fun `a deleted book becomes a delete op carrying no content`() {
        val item = SyncMapper.bookMetadataItem(book(deletedAt = 3_000L))

        assertEquals("delete", item["op"]?.jsonPrimitive?.content)
        assertNull("a delete must not resend the title", item["title"])
        assertEquals(SyncTime.toWire(2_000L), item["updated_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun `progress converts Readium totalProgression to a percentage`() {
        val locator = """{"href":"/c1.xhtml","locations":{"totalProgression":0.4213}}"""
        val item = SyncMapper.progressItem(book(progression = locator, lastOpenedAt = 5_000L))

        assertNotNull(item)
        assertEquals(42.13, item!!["percent"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("upsert", item["op"]?.jsonPrimitive?.content)
        // locator_json must be a JSON object, not a string — the server validates it as one.
        assertTrue(item["locator_json"] is JsonObject)
    }

    /**
     * A position that cannot be represented is skipped rather than approximated: inventing a
     * percent would overwrite the real reading position on the user's other device.
     */
    @Test
    fun `progress is skipped when there is no usable locator`() {
        assertNull(SyncMapper.progressItem(book(progression = null, lastOpenedAt = 5_000L)))
        assertNull(SyncMapper.progressItem(book(progression = "not json", lastOpenedAt = 5_000L)))
        assertNull(SyncMapper.progressItem(book(progression = "{}", lastOpenedAt = null)))
    }

    @Test
    fun `a locator without progression reports zero rather than failing`() {
        val locator = Json.parseToJsonElement("""{"href":"/c1.xhtml"}""") as JsonObject
        assertEquals(0.0, SyncMapper.percentOf(locator), 0.0)
    }

    @Test
    fun `percent is clamped into the range the server accepts`() {
        val over = Json.parseToJsonElement("""{"locations":{"totalProgression":1.5}}""") as JsonObject
        val under = Json.parseToJsonElement("""{"locations":{"totalProgression":-0.2}}""") as JsonObject

        assertEquals(100.0, SyncMapper.percentOf(over), 0.0)
        assertEquals(0.0, SyncMapper.percentOf(under), 0.0)
    }

    @Test
    fun `highlight colors serialize to the four values the server allows`() {
        HighlightColor.entries.forEach { color ->
            val item =
                SyncMapper.highlightItem(
                    HighlightEntity(
                        id = "11111111-1111-4111-8111-111111111111",
                        bookId = "hash-1",
                        color = color,
                        selectedText = "šumniki: č š ž",
                        note = null,
                        locatorJson = """{"href":"/c1.xhtml"}""",
                        chapterTitle = "I",
                        createdAt = 1L,
                        updatedAt = 2L,
                    ),
                )
            assertNotNull(item)
            assertTrue(
                item!!["color"]!!.jsonPrimitive.content in
                    setOf("AMBER", "SAGE", "SKY", "ROSE"),
            )
        }
    }

    @Test
    fun `highlight round-trips through the wire shape, šumniki intact`() {
        val original =
            HighlightEntity(
                id = "11111111-1111-4111-8111-111111111111",
                bookId = "hash-1",
                color = HighlightColor.SAGE,
                selectedText = "Nič ni bilo slišati",
                note = "zapisek s šumniki: čžš",
                locatorJson = """{"href":"/c1.xhtml","locations":{"progression":0.5}}""",
                chapterTitle = "Prvo poglavje",
                createdAt = 1_000L,
                updatedAt = 2_000L,
            )
        val wire = SyncMapper.highlightItem(original)!!
        // Re-read it as though the server had echoed it back.
        val decoded = SyncMapper.highlightFrom(wire)

        assertNotNull(decoded)
        assertEquals(original.selectedText, decoded!!.selectedText)
        assertEquals(original.note, decoded.note)
        assertEquals(original.chapterTitle, decoded.chapterTitle)
        assertEquals(original.color, decoded.color)
        assertEquals(original.updatedAt, decoded.updatedAt)
    }

    @Test
    fun `vocabulary carries the raw sentence the web review needs`() {
        val item =
            SyncMapper.vocabularyItem(
                DictionaryEntryEntity(
                    word = "brook",
                    sentenceHash = "abc123",
                    lang = "sl",
                    model = "gpt-5-mini",
                    definition = "potok",
                    contextMeaning = "majhen vodotok",
                    baseForm = "brook",
                    usageNote = "samostalnik",
                    costEur = 0.0001,
                    createdAt = 1_000L,
                    sentence = "The brook ran fast.",
                    updatedAt = 1_000L,
                ),
            )

        assertEquals("The brook ran fast.", item["sentence"]?.jsonPrimitive?.content)
        assertEquals("abc123", item["sentence_hash"]?.jsonPrimitive?.content)
        assertEquals("brook|abc123|sl|gpt-5-mini", item["id"]?.jsonPrimitive?.content)
    }

    /**
     * Pulled book rows must not clobber device-local columns: the file lives on this device and
     * the server has no idea where.
     */
    @Test
    fun `a pulled book row leaves the local file path alone`() {
        val local = book()
        val row =
            Json.parseToJsonElement(
                """{"content_hash":"hash-1","title":"Nov naslov","authors":"Lev Tolstoj",
                   "updated_at":"2026-07-25T13:00:00Z"}""",
            ) as JsonObject

        val merged = SyncMapper.bookMetadataFrom(row, local)

        assertNotNull(merged)
        assertEquals("Nov naslov", merged!!.title)
        assertEquals("the local EPUB path must survive a pull", local.filePath, merged.filePath)
        assertEquals(local.coverPath, merged.coverPath)
    }

    @Test
    fun `a book known only to the server lands without a file path`() {
        val row =
            Json.parseToJsonElement(
                """{"content_hash":"hash-9","title":"Druga knjiga","authors":"Nekdo",
                   "updated_at":"2026-07-25T13:00:00Z"}""",
            ) as JsonObject

        val created = SyncMapper.bookMetadataFrom(row, null)

        assertNotNull(created)
        assertEquals("", created!!.filePath)
        assertNull(created.coverPath)
    }
}
