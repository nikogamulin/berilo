package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightColor
import app.berilo.reader.store.db.HighlightDao
import app.berilo.reader.store.db.HighlightEntity
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository over [HighlightDao]: CRUD for highlights/notes (S2.6) — the notebook screen's
 * data source and the reader's "Highlight"/"Note" chrome-action creation path.
 *
 * @param ioDispatcher Dispatcher DB work runs on, overridable so tests can pass a
 *   `TestDispatcher` and stay on virtual time (`docs/findings.md` pattern).
 * @param clock Time source for created/updated timestamps, injectable for tests.
 * @param idGenerator Supplies new row ids, injectable so tests can assert on known ids.
 */
class AnnotationsRepository(
    private val dao: HighlightDao,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /** All highlights/notes for [bookId], oldest-created first. */
    fun observeForBook(bookId: String): Flow<List<Highlight>> =
        dao.observeForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    /**
     * Creates a new highlight anchored at [locatorJson], optionally carrying [noteText] (the
     * reader's "Highlight" action passes `null`; "Note" passes the typed text).
     *
     * @return The id of the created row.
     */
    suspend fun create(
        bookId: String,
        color: HighlightColor,
        selectedText: String,
        locatorJson: String,
        chapterTitle: String?,
        noteText: String? = null,
    ): String =
        withContext(ioDispatcher) {
            val id = idGenerator()
            val now = clock()
            dao.insert(
                HighlightEntity(
                    id = id,
                    bookId = bookId,
                    color = color,
                    selectedText = selectedText,
                    note = noteText,
                    locatorJson = locatorJson,
                    chapterTitle = chapterTitle,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            id
        }

    /** Updates [id]'s color, no-op if [id] no longer exists (e.g. deleted concurrently). */
    suspend fun updateColor(id: String, color: HighlightColor) = updateExisting(id) { it.copy(color = color) }

    /** Updates [id]'s note text (`null`/blank clears it, keeping the highlight itself). */
    suspend fun updateNote(id: String, noteText: String?) =
        updateExisting(id) { it.copy(note = noteText?.trim()?.ifBlank { null }) }

    /** Deletes [id]. */
    suspend fun delete(id: String) = withContext(ioDispatcher) { dao.deleteById(id) }

    private suspend fun updateExisting(id: String, transform: (HighlightEntity) -> HighlightEntity) =
        withContext(ioDispatcher) {
            val existing = dao.getById(id) ?: return@withContext
            dao.update(transform(existing).copy(updatedAt = clock()))
        }
}
