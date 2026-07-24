package app.berilo.reader.store.importer

import app.berilo.reader.store.db.BookDao
import app.berilo.reader.store.db.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [BookDao] fake: keeps import-bookkeeping tests off the Android/Robolectric runtime. */
class FakeBookDao : BookDao {
    private val books = LinkedHashMap<String, BookEntity>()
    private val state = MutableStateFlow<List<BookEntity>>(emptyList())

    fun count(): Int = books.size

    override fun observeAll(): Flow<List<BookEntity>> = state

    override suspend fun getById(id: String): BookEntity? = books[id]

    override suspend fun exists(id: String): Boolean = books.containsKey(id)

    override suspend fun getProgression(id: String): String? = books[id]?.progressionJson

    override suspend fun updateProgression(id: String, progressionJson: String?, openedAt: Long) {
        val existing = books[id] ?: return
        books[id] = existing.copy(progressionJson = progressionJson, lastOpenedAt = openedAt)
        publish()
    }

    override suspend fun insert(book: BookEntity) {
        check(!books.containsKey(book.id)) { "duplicate id ${book.id}" }
        books[book.id] = book
        publish()
    }

    override suspend fun update(book: BookEntity) {
        books[book.id] = book
        publish()
    }

    override suspend fun delete(book: BookEntity) {
        books.remove(book.id)
        publish()
    }

    override suspend fun deleteById(id: String) {
        books.remove(id)
        publish()
    }

    private fun publish() {
        state.value = books.values.sortedByDescending { it.addedAt }
    }
}
