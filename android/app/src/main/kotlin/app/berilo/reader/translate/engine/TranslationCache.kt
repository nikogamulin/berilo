package app.berilo.reader.translate.engine

/**
 * The translate stage's view of the on-device cache, ported from
 * `translator/berilo/cache.py`'s `TranslationCache`.
 *
 * The engine is written against this interface rather than against
 * [app.berilo.reader.store.db.TranslationCacheDao] directly for one reason: the resumability
 * guarantee (CLAUDE.md §2) is a property of *when* the engine writes, not of what backs the
 * write, and it has to be provable without a database. [RoomTranslationCache] is the production
 * implementation over B4's DAO; tests drive the same engine through an in-memory implementation
 * and assert on the fake LLM client's call count.
 */
interface TranslationCache {

    /**
     * Return the cached translation for one segment, or `null` on a cache miss.
     *
     * Every parameter is part of the six-column key for the same reason B4 keyed the table that
     * way: all six are prompt input, so omitting one lets a change to it be served from a stale
     * row instead of re-translated (CLAUDE.md §9).
     */
    suspend fun getTranslation(
        bookHash: String,
        segmentHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        glossaryHash: String,
    ): String?

    /**
     * Persist one batch's translations and its call accounting **atomically**.
     *
     * This is the resumability guarantee: it is called immediately after each batch returns and
     * before the engine moves on, so a process killed mid-book loses at most one batch's spend.
     * Implementations must commit [translations] and [call] in a single transaction — a partial
     * write would leave translations that no `calls` row accounts for.
     */
    suspend fun storeBatch(
        bookHash: String,
        model: String,
        lang: String,
        translations: List<SegmentTranslation>,
        call: CallRecord,
        promptVersion: String,
        glossaryHash: String,
    )

    /** Return the cached glossary term map, or `null` if the pass has not run for this key. */
    suspend fun getGlossary(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
    ): Map<String, String>?

    /** Persist the per-book glossary term map and the extraction call's accounting. */
    suspend fun storeGlossary(
        bookHash: String,
        model: String,
        lang: String,
        terms: Map<String, String>,
        call: CallRecord?,
        promptVersion: String,
    )

    /** Return the cached per-book style memo, or `null` if it has not been derived. */
    suspend fun getBookContext(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
    ): String?

    /** Persist the per-book style memo and the derivation call's accounting. */
    suspend fun storeBookContext(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        memo: String,
        call: CallRecord?,
    )
}

/**
 * One translated segment ready to be persisted, mirroring `cache.py`'s `SegmentTranslation`.
 *
 * @property segmentHash Content hash of the **source** text
 *   ([app.berilo.reader.translate.model.segmentHash]).
 * @property text Translated text for the segment.
 * @property costEur Share of the batch's cost attributed to this segment.
 */
data class SegmentTranslation(
    val segmentHash: String,
    val text: String,
    val costEur: Double,
)

/**
 * Accounting for one or more API calls, aggregated per stored batch — `cache.py`'s `CallRecord`.
 *
 * @property kind Call category: [CALL_KIND_BATCH], [CALL_KIND_GLOSSARY] or
 *   [CALL_KIND_BOOK_CONTEXT].
 * @property inputTokens Total input (prompt) tokens billed.
 * @property outputTokens Total output (completion) tokens billed.
 * @property costEur Total EUR cost of the call(s).
 */
data class CallRecord(
    val kind: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costEur: Double,
)

/** [CallRecord.kind] for a segment-translation batch (including its retries and revision pass). */
const val CALL_KIND_BATCH: String = "batch"

/** [CallRecord.kind] for the once-per-book glossary-extraction call. */
const val CALL_KIND_GLOSSARY: String = "glossary"

/** [CallRecord.kind] for the once-per-book style-memo call. */
const val CALL_KIND_BOOK_CONTEXT: String = "book_context"
