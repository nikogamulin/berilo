package app.berilo.reader.translate.engine

import android.util.Log
import app.berilo.reader.store.db.GlossaryEntity
import app.berilo.reader.store.db.TranslationCacheDao
import app.berilo.reader.store.db.TranslationCallEntity
import app.berilo.reader.store.db.TranslationEntity
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private const val TAG = "RoomTranslationCache"

/** JSON codec for [GlossaryEntity.termsJson]; mirrors `cache.py`'s `json.dumps`/`json.loads`. */
private val glossaryJson = Json

/** `terms_json` is a flat `source term -> fixed rendering` object, exactly as in `cache.py`. */
private val GLOSSARY_TERMS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

/**
 * [TranslationCache] backed by B4's Room DAO.
 *
 * A thin adapter, deliberately: [TranslationCacheDao.storeBatch] already commits the translations
 * and the `calls` row in one `@Transaction`, which is the whole resumability contract, so this
 * class only maps engine records onto entities and stamps [now].
 *
 * @param dao B4's translation-cache DAO.
 * @param now Wall-clock source for `createdAt` columns; injectable so a test can pin timestamps.
 */
class RoomTranslationCache(
    private val dao: TranslationCacheDao,
    private val now: () -> Long = System::currentTimeMillis,
) : TranslationCache {

    override suspend fun getTranslation(
        bookHash: String,
        segmentHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        glossaryHash: String,
    ): String? = dao.getTranslation(bookHash, segmentHash, model, lang, promptVersion, glossaryHash)

    override suspend fun storeBatch(
        bookHash: String,
        model: String,
        lang: String,
        translations: List<SegmentTranslation>,
        call: CallRecord,
        promptVersion: String,
        glossaryHash: String,
    ) {
        val stamp = now()
        dao.storeBatch(
            translations.map { translation ->
                TranslationEntity(
                    bookHash = bookHash,
                    segmentHash = translation.segmentHash,
                    model = model,
                    lang = lang,
                    promptVersion = promptVersion,
                    glossaryHash = glossaryHash,
                    text = translation.text,
                    costEur = translation.costEur,
                    createdAt = stamp,
                )
            },
            call.toEntity(bookHash, model, lang, stamp),
        )
    }

    override suspend fun getGlossary(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
    ): Map<String, String>? {
        val raw = dao.getGlossaryTermsJson(bookHash, model, lang, promptVersion) ?: return null
        return glossaryJson.decodeFromString(GLOSSARY_TERMS_SERIALIZER, raw)
    }

    override suspend fun storeGlossary(
        bookHash: String,
        model: String,
        lang: String,
        terms: Map<String, String>,
        call: CallRecord?,
        promptVersion: String,
    ) {
        val stamp = now()
        dao.upsertGlossary(
            GlossaryEntity(
                bookHash = bookHash,
                model = model,
                lang = lang,
                promptVersion = promptVersion,
                termsJson = glossaryJson.encodeToString(GLOSSARY_TERMS_SERIALIZER, terms),
                createdAt = stamp,
            ),
        )
        if (call != null) dao.insertCall(call.toEntity(bookHash, model, lang, stamp))
    }

    /**
     * Always `null`: B4 shipped no `book_contexts` table.
     *
     * Not an oversight being papered over — it is inert on this platform. The style table
     * ([app.berilo.reader.translate.prompts.resolveStyle]) resolves `DEVICE` to `baseline_v1`
     * (ECONOMY) or `revise_v1`/`revise_generic_v1` (QUALITY), and **none of those declares a
     * `bookContextSystem`**, so [buildBookContext] returns before it ever reaches this method.
     * It can only be hit by explicitly requesting `book_context_v1`, which no app path does.
     * Adding the table is a B4-shaped change (entity + migration) and is reported, not fixed.
     */
    override suspend fun getBookContext(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
    ): String? = null

    /** No-ops with a warning, for the reason given on [getBookContext]. */
    override suspend fun storeBookContext(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        memo: String,
        call: CallRecord?,
    ) {
        Log.w(
            TAG,
            "Book-context memo not persisted: no book_contexts table on device (style " +
                "$promptVersion). A resumed run would re-derive it.",
        )
    }

    private fun CallRecord.toEntity(
        bookHash: String,
        model: String,
        lang: String,
        stamp: Long,
    ) = TranslationCallEntity(
        bookHash = bookHash,
        model = model,
        lang = lang,
        kind = kind,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        costEur = costEur,
        createdAt = stamp,
    )
}
