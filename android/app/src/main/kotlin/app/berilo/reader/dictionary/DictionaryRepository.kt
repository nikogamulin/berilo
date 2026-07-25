package app.berilo.reader.dictionary

import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.store.db.DictionaryDao
import app.berilo.reader.store.db.DictionaryEntryEntity
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A dictionary lookup outcome, cached or freshly fetched. */
data class DictionaryLookup(val definition: DictionaryDefinition, val costEur: Double, val fromCache: Boolean)

/**
 * Cache-first dictionary lookups: a hit on ([word] lowercased, sentence hash, target
 * language, effective model) is served from [DictionaryDao] with zero calls to
 * [DictionaryService]/[app.berilo.reader.llm.LlmClient] — a word is billed once per
 * (sentence, language, model) combination (product spec §5.2).
 *
 * @param ioDispatcher Dispatcher DB/network work runs on, overridable so tests can pass a
 *   `TestDispatcher` and stay on virtual time (`docs/findings.md` pattern).
 * @param clock Time source for [DictionaryEntryEntity.createdAt], injectable for tests.
 */
class DictionaryRepository(
    private val dao: DictionaryDao,
    private val service: DictionaryService,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Looks up [word] as used in [sentence], serving a cache hit if one exists for the
     * current settings, else calling [DictionaryService] and caching the result.
     *
     * @throws app.berilo.reader.llm.LlmError If there is no cache hit and the network call
     *   fails.
     */
    suspend fun lookup(settings: LlmSettings, word: String, sentence: String): DictionaryLookup =
        withContext(ioDispatcher) {
            val normalizedWord = word.trim().lowercase(Locale.ROOT)
            val effectiveModel = settings.dictionaryModel ?: settings.model
            val sentenceHash = SentenceHash.of(sentence)

            val cached = dao.find(normalizedWord, sentenceHash, settings.targetLang, effectiveModel)
            if (cached != null) {
                return@withContext DictionaryLookup(cached.toDefinition(normalizedWord), cached.costEur, fromCache = true)
            }

            val result = service.define(settings, normalizedWord, sentence)
            val now = clock()
            dao.upsert(
                DictionaryEntryEntity(
                    word = normalizedWord,
                    sentenceHash = sentenceHash,
                    lang = settings.targetLang,
                    model = effectiveModel,
                    definition = result.definition.definition,
                    contextMeaning = result.definition.contextMeaning,
                    baseForm = result.definition.baseForm,
                    usageNote = result.definition.usageNote,
                    costEur = result.costEur,
                    createdAt = now,
                    // S3.2 ([OPEN-1]): the raw sentence is stored alongside its hash so the web
                    // vocabulary review can show the word in context. The hash stays the cache
                    // key; this column is carried, never matched on.
                    sentence = sentence,
                    updatedAt = now,
                ),
            )
            DictionaryLookup(result.definition, result.costEur, fromCache = false)
        }

    private fun DictionaryEntryEntity.toDefinition(word: String) =
        DictionaryDefinition(
            word = word,
            definition = definition,
            contextMeaning = contextMeaning,
            baseForm = baseForm,
            usageNote = usageNote,
        )
}
