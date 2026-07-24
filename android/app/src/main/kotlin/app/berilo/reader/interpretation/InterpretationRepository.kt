package app.berilo.reader.interpretation

import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.store.db.InterpretationDao
import app.berilo.reader.store.db.InterpretationEntryEntity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An interpretation outcome, cached or freshly fetched. */
data class InterpretationLookup(val text: String, val costEur: Double, val fromCache: Boolean)

/**
 * Cache-first paragraph interpretation: a hit on ([passage] hash, target language,
 * effective model) is served from [InterpretationDao] with zero calls to
 * [InterpretationService]/[app.berilo.reader.llm.LlmClient] — a passage is billed once per
 * (passage, language, model) combination, mirroring [app.berilo.reader.dictionary.DictionaryRepository]'s
 * cache-first design (S2.4).
 *
 * @param ioDispatcher Dispatcher DB/network work runs on, overridable so tests can pass a
 *   `TestDispatcher` and stay on virtual time (`docs/findings.md` pattern).
 * @param clock Time source for [InterpretationEntryEntity.createdAt], injectable for tests.
 */
class InterpretationRepository(
    private val dao: InterpretationDao,
    private val service: InterpretationService,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Interprets [passage] from [bookTitle], serving a cache hit if one exists for the
     * current settings, else calling [InterpretationService] and caching the result.
     *
     * @throws app.berilo.reader.llm.LlmError If there is no cache hit and the network call
     *   fails.
     */
    suspend fun interpret(settings: LlmSettings, passage: String, bookTitle: String): InterpretationLookup =
        withContext(ioDispatcher) {
            val effectiveModel = settings.interpretationModel ?: settings.model
            val passageHash = PassageHash.of(passage)

            val cached = dao.find(passageHash, settings.targetLang, effectiveModel)
            if (cached != null) {
                return@withContext InterpretationLookup(cached.text, cached.costEur, fromCache = true)
            }

            val result = service.interpret(settings, passage, bookTitle)
            dao.upsert(
                InterpretationEntryEntity(
                    passageHash = passageHash,
                    lang = settings.targetLang,
                    model = effectiveModel,
                    text = result.text,
                    costEur = result.costEur,
                    createdAt = clock(),
                ),
            )
            InterpretationLookup(result.text, result.costEur, fromCache = false)
        }
}
