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

    override suspend fun getById(id: String): BookEntity? =
        books[id]?.takeIf { it.deletedAt == null }

    override suspend fun exists(id: String): Boolean = getById(id) != null

    override suspend fun getAnyById(id: String): BookEntity? = books[id]

    override suspend fun getProgression(id: String): String? = getById(id)?.progressionJson

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

    override suspend fun softDelete(id: String, at: Long) {
        val existing = books[id] ?: return
        books[id] = existing.copy(deletedAt = at, updatedAt = at)
        publish()
    }

    override suspend fun metadataDirtySince(since: Long, limit: Int): List<BookEntity> =
        books.values
            .filter { it.updatedAt > since }
            .sortedWith(compareBy({ it.updatedAt }, { it.id }))
            .take(limit)

    override suspend fun progressDirtySince(since: Long, limit: Int): List<BookEntity> =
        books.values
            .filter {
                it.deletedAt == null && it.progressionJson != null && (it.lastOpenedAt ?: 0L) > since
            }
            .sortedWith(compareBy({ it.lastOpenedAt ?: 0L }, { it.id }))
            .take(limit)

    override suspend fun applyServerMetadata(
        id: String,
        title: String,
        authors: String,
        sourceLang: String?,
        targetLang: String?,
        updatedAt: Long,
        deletedAt: Long?,
    ) {
        val existing = books[id] ?: return
        // Mirrors the real query: server-owned columns only, device-local paths untouched.
        books[id] =
            existing.copy(
                title = title,
                authors = authors,
                sourceLang = sourceLang,
                targetLang = targetLang,
                updatedAt = updatedAt,
                deletedAt = deletedAt,
            )
        publish()
    }

    override suspend fun applyServerProgress(id: String, progressionJson: String, updatedAt: Long) {
        val existing = books[id]?.takeIf { it.deletedAt == null } ?: return
        books[id] = existing.copy(progressionJson = progressionJson, lastOpenedAt = updatedAt)
        publish()
    }

    /** Mirrors the real query: live rows whose EPUB is present on this device. */
    private fun publish() {
        state.value =
            books.values
                .filter { it.deletedAt == null && it.filePath.isNotEmpty() }
                .sortedByDescending { it.addedAt }
    }
}
