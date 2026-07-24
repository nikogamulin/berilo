package app.berilo.reader.store.repository

import app.berilo.reader.store.db.BookDao
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository over [BookDao]; owns cleanup of on-disk book/cover files on delete.
 *
 * @param ioDispatcher Dispatcher file/DB work runs on, overridable so tests
 *   can pass a `TestDispatcher` and stay on virtual time.
 */
class BookRepository(
    private val bookDao: BookDao,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {

    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getBook(id: String): Book? = bookDao.getById(id)?.toDomain()

    suspend fun markOpened(id: String, openedAt: Long) {
        val entity = bookDao.getById(id) ?: return
        bookDao.update(entity.copy(lastOpenedAt = openedAt))
    }

    /** Reads the last persisted reading position (Readium Locator JSON), or null. */
    suspend fun getProgression(id: String): String? = bookDao.getProgression(id)

    /**
     * Persists the reading position and refreshes `lastOpenedAt` in a single
     * write. Called on the debounced position-save path from the reader.
     */
    suspend fun saveProgression(id: String, progressionJson: String, openedAt: Long) =
        bookDao.updateProgression(id, progressionJson, openedAt)

    /** Deletes the book row and its on-disk copy (EPUB + cover, if present). */
    suspend fun deleteBook(book: Book) =
        withContext(ioDispatcher) {
            bookDao.deleteById(book.id)
            File(book.filePath).delete()
            book.coverPath?.let { File(it).delete() }
        }
}
