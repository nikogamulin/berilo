package app.berilo.reader.annotations

import app.berilo.reader.store.db.TranslationFlagDao
import app.berilo.reader.store.db.TranslationFlagEntity
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository over [TranslationFlagDao]: the reader's "Bad translation" action writes here and
 * the notebook reads from here (B9).
 *
 * Shaped after [AnnotationsRepository], with one addition: [create] asks
 * [TranslationProvenanceResolver] which cached translation the flagged text came from and
 * stores whatever it finds. A resolver failure is not a create failure — the flag is the
 * signal, the provenance is a bonus.
 *
 * @param dao Flag DAO.
 * @param provenanceResolver Resolves the flagged text back to a translation-cache key.
 * @param ioDispatcher Dispatcher DB work runs on, overridable so tests stay on virtual time.
 * @param clock Time source for created/updated timestamps.
 * @param idGenerator Supplies new row ids, injectable so tests can assert on known ids.
 */
class TranslationFlagRepository(
    private val dao: TranslationFlagDao,
    private val provenanceResolver: ProvenanceResolver,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /** All live flags for [bookId], oldest-created first. */
    fun observeForBook(bookId: String): Flow<List<TranslationFlag>> =
        dao.observeForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    /**
     * Records a flagged passage.
     *
     * @param bookId Book the passage belongs to.
     * @param selectedText The translated text the user selected.
     * @param locatorJson Readium Locator JSON of the passage.
     * @param chapterTitle Enclosing chapter title, or null.
     * @param comment The user's suggestion or complaint; blank and null both store as null, so
     *   "flagged with an empty comment" and "flagged with no comment" are the same row rather
     *   than two states nothing downstream distinguishes.
     * @return The id of the created row.
     */
    suspend fun create(
        bookId: String,
        selectedText: String,
        locatorJson: String,
        chapterTitle: String?,
        comment: String? = null,
    ): String =
        withContext(ioDispatcher) {
            val provenance = provenanceResolver.resolve(selectedText)
            val id = idGenerator()
            val now = clock()
            dao.insert(
                TranslationFlagEntity(
                    id = id,
                    bookId = bookId,
                    selectedText = selectedText,
                    comment = comment?.trim()?.ifBlank { null },
                    locatorJson = locatorJson,
                    chapterTitle = chapterTitle,
                    cacheBookHash = provenance?.bookHash,
                    cacheSegmentHash = provenance?.segmentHash,
                    cacheModel = provenance?.model,
                    cacheLang = provenance?.lang,
                    cachePromptVersion = provenance?.promptVersion,
                    cacheGlossaryHash = provenance?.glossaryHash,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            id
        }

    /** Tombstones [id], for the reason given on [AnnotationsRepository.delete]. */
    suspend fun delete(id: String) = withContext(ioDispatcher) { dao.softDelete(id, clock()) }
}
