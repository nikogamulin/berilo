package app.berilo.reader.sync

import app.berilo.reader.store.db.BookEntity
import app.berilo.reader.store.db.DictionaryEntryEntity
import app.berilo.reader.store.db.HighlightColor
import app.berilo.reader.store.db.HighlightEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Translation between Room entities and the contract's row shapes (`docs/sync_api.md` §2).
 *
 * Two rules run through everything here:
 *
 * 1. **Only user-created data and book metadata cross the boundary.** `filePath`, `coverPath`
 *    and the EPUB itself have no wire representation at all — not omitted at the last moment,
 *    but absent from every builder below. That is the anti-piracy invariant (CLAUDE.md §2) made
 *    structural rather than a rule someone has to remember.
 * 2. **A row that cannot be represented faithfully is skipped, never approximated.** The
 *    builders return null instead of inventing a locator or a percent, because a fabricated
 *    reading position silently corrupts the position on the user's other device.
 */
object SyncMapper {

    private val json = Json { ignoreUnknownKeys = true }

    // --- push: local -> wire ---------------------------------------------------------

    /**
     * `books_metadata`: title, authors and language only. Note what is *not* here — this is the
     * entire reason a book's title can appear on the web while the book cannot.
     */
    fun bookMetadataItem(book: BookEntity): JsonObject = buildJsonObject {
        put("op", if (book.deletedAt != null) OP_DELETE else OP_UPSERT)
        put("id", book.id)
        put("content_hash", book.id)
        put("updated_at", SyncTime.toWire(book.updatedAt))
        if (book.deletedAt == null) {
            put("title", book.title)
            put("authors", book.authors)
            book.sourceLang?.let { put("source_lang", it) }
            book.targetLang?.let { put("target_lang", it) }
            put("created_at", SyncTime.toWire(book.addedAt))
        }
    }

    /**
     * `progress`: upsert-only, so there is no delete branch — the contract rejects
     * `op: delete` on this entity (§1.3), and there is no such thing as un-reading a book.
     *
     * Returns null when the stored locator is not a JSON object or the book was never opened:
     * `locator_json` is validated as an object server-side, and a `percent` conjured from a
     * missing progression would be a lie about where the user is in the book.
     */
    fun progressItem(book: BookEntity): JsonObject? {
        val locator = parseObject(book.progressionJson) ?: return null
        val openedAt = book.lastOpenedAt ?: return null
        return buildJsonObject {
            put("op", OP_UPSERT)
            put("id", book.id)
            put("book_hash", book.id)
            put("locator_json", locator)
            put("percent", JsonPrimitive(percentOf(locator)))
            put("updated_at", SyncTime.toWire(openedAt))
        }
    }

    /** `highlights`: one row serves both a highlight and a note, matching the local model. */
    fun highlightItem(highlight: HighlightEntity): JsonObject? {
        val locator = parseObject(highlight.locatorJson) ?: return null
        return buildJsonObject {
            put("op", if (highlight.deletedAt != null) OP_DELETE else OP_UPSERT)
            put("id", highlight.id)
            put("updated_at", SyncTime.toWire(highlight.updatedAt))
            if (highlight.deletedAt == null) {
                put("book_hash", highlight.bookId)
                put("color", highlight.color.name)
                put("selected_text", highlight.selectedText)
                highlight.note?.let { put("note", it) }
                put("locator_json", locator)
                highlight.chapterTitle?.let { put("chapter_title", it) }
                put("created_at", SyncTime.toWire(highlight.createdAt))
            }
        }
    }

    /**
     * `vocabulary`: keyed by the same (word, sentence hash, language, model) tuple the local
     * cache uses, so a lookup billed once stays billed once across devices.
     */
    fun vocabularyItem(entry: DictionaryEntryEntity): JsonObject = buildJsonObject {
        put("op", if (entry.deletedAt != null) OP_DELETE else OP_UPSERT)
        put("id", vocabularyId(entry))
        put("word", entry.word)
        put("sentence_hash", entry.sentenceHash)
        put("lang", entry.lang)
        put("model", entry.model)
        put("updated_at", SyncTime.toWire(entry.updatedAt))
        if (entry.deletedAt == null) {
            put("sentence", entry.sentence)
            put("definition", entry.definition)
            put("context_meaning", entry.contextMeaning)
            put("base_form", entry.baseForm)
            put("usage_note", entry.usageNote)
            put("cost_eur", JsonPrimitive(entry.costEur))
            put("created_at", SyncTime.toWire(entry.createdAt))
        }
    }

    /**
     * A stable identifier for a composite-key vocabulary row. The server derives the real key
     * from the four natural-key fields and ignores this, but the contract's push schema lists
     * `id` as required, so it is sent for conformance.
     */
    fun vocabularyId(entry: DictionaryEntryEntity): String =
        "${entry.word}|${entry.sentenceHash}|${entry.lang}|${entry.model}"

    // --- pull: wire -> local ---------------------------------------------------------

    /** Maps a pulled `books_metadata` row, or null if it is missing fields this app needs. */
    fun bookMetadataFrom(row: JsonObject, existing: BookEntity?): BookEntity? {
        val hash = row.string("content_hash") ?: return null
        val updatedAt = SyncTime.fromWire(row.string("updated_at")) ?: return null
        val deletedAt = SyncTime.fromWire(row.string("deleted_at"))
        val base =
            existing
                ?: BookEntity(
                    id = hash,
                    title = row.string("title") ?: return null,
                    authors = row.string("authors").orEmpty(),
                    // A book known only to the server has no file here and cannot get one —
                    // the EPUB never syncs. It lands as a metadata-only row so the title is
                    // visible; opening it requires importing the file on this device.
                    filePath = "",
                    coverPath = null,
                    addedAt = SyncTime.fromWire(row.string("created_at")) ?: updatedAt,
                    lastOpenedAt = null,
                    progressionJson = null,
                )
        return base.copy(
            title = row.string("title") ?: base.title,
            authors = row.string("authors") ?: base.authors,
            sourceLang = row.string("source_lang") ?: base.sourceLang,
            targetLang = row.string("target_lang") ?: base.targetLang,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
        )
    }

    /** Maps a pulled `highlights` row, or null if it is unusable. */
    fun highlightFrom(row: JsonObject): HighlightEntity? {
        val id = row.string("id") ?: return null
        val updatedAt = SyncTime.fromWire(row.string("updated_at")) ?: return null
        val bookHash = row.string("book_hash") ?: return null
        val locator = row["locator_json"] as? JsonObject ?: return null
        val color =
            runCatching { HighlightColor.valueOf(row.string("color").orEmpty()) }.getOrNull()
                ?: return null
        return HighlightEntity(
            id = id,
            bookId = bookHash,
            color = color,
            selectedText = row.string("selected_text").orEmpty(),
            note = row.string("note"),
            locatorJson = locator.toString(),
            chapterTitle = row.string("chapter_title"),
            createdAt = SyncTime.fromWire(row.string("created_at")) ?: updatedAt,
            updatedAt = updatedAt,
            deletedAt = SyncTime.fromWire(row.string("deleted_at")),
        )
    }

    /** Maps a pulled `vocabulary` row, or null if it is unusable. */
    fun vocabularyFrom(row: JsonObject): DictionaryEntryEntity? {
        val updatedAt = SyncTime.fromWire(row.string("updated_at")) ?: return null
        return DictionaryEntryEntity(
            word = row.string("word") ?: return null,
            sentenceHash = row.string("sentence_hash") ?: return null,
            lang = row.string("lang") ?: return null,
            model = row.string("model") ?: return null,
            definition = row.string("definition").orEmpty(),
            contextMeaning = row.string("context_meaning").orEmpty(),
            baseForm = row.string("base_form").orEmpty(),
            usageNote = row.string("usage_note").orEmpty(),
            costEur = row["cost_eur"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            createdAt = SyncTime.fromWire(row.string("created_at")) ?: updatedAt,
            sentence = row.string("sentence").orEmpty(),
            updatedAt = updatedAt,
            deletedAt = SyncTime.fromWire(row.string("deleted_at")),
        )
    }

    /** The `updated_at` of any pulled row, for last-write-wins comparison. */
    fun updatedAtOf(row: JsonObject): Long? = SyncTime.fromWire(row.string("updated_at"))

    // --- helpers ---------------------------------------------------------------------

    /**
     * Reads Readium's `locations.totalProgression` (0..1) as a whole-book percentage, clamped
     * to the server's `0 <= percent <= 100` check. Defaults to 0 when the locator carries no
     * progression, which is true of a position anchored only by a fragment.
     */
    fun percentOf(locator: JsonObject): Double {
        val fraction =
            locator["locations"]
                ?.jsonObject
                ?.get("totalProgression")
                ?.jsonPrimitive
                ?.doubleOrNull ?: return 0.0
        return (fraction * PERCENT_SCALE).coerceIn(0.0, PERCENT_SCALE)
    }

    private fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    /** Reads a string field, treating JSON null and non-strings as absent. */
    private fun JsonObject.string(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.booleanOrNull != null && !primitive.isString) return null
        return primitive.takeIf { it.isString || it.content != "null" }?.content?.takeIf {
            it.isNotEmpty()
        }
    }

    private const val OP_UPSERT = "upsert"
    private const val OP_DELETE = "delete"
    private const val PERCENT_SCALE = 100.0
}
