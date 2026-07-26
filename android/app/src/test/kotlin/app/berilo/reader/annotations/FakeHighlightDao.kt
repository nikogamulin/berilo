package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightDao
import app.berilo.reader.store.db.HighlightEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [HighlightDao] fake: keeps repository/ViewModel tests off the Robolectric
 * runtime, mirroring `FakeInterpretationDao`/`FakeDictionaryDao`. */
class FakeHighlightDao : HighlightDao {
    private val entries = MutableStateFlow<Map<String, HighlightEntity>>(emptyMap())

    fun count(): Int = entries.value.size

    /** Live rows only, mirroring the real DAO's `deletedAt IS NULL` filter (S3.2). */
    override fun observeForBook(bookId: String) =
        entries.map { all ->
            all.values
                .filter { it.bookId == bookId && it.deletedAt == null }
                .sortedBy { it.createdAt }
        }

    override suspend fun getById(id: String): HighlightEntity? =
        entries.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun insert(entity: HighlightEntity) {
        entries.value = entries.value + (entity.id to entity)
    }

    override suspend fun update(entity: HighlightEntity) {
        entries.value = entries.value + (entity.id to entity)
    }

    override suspend fun deleteById(id: String) {
        entries.value = entries.value - id
    }

    override suspend fun softDelete(id: String, at: Long) {
        val existing = entries.value[id] ?: return
        entries.value = entries.value + (id to existing.copy(deletedAt = at, updatedAt = at))
    }

    override suspend fun dirtySince(since: Long, limit: Int): List<HighlightEntity> =
        entries.value.values
            .filter { it.updatedAt > since }
            .sortedWith(compareBy({ it.updatedAt }, { it.id }))
            .take(limit)

    override suspend fun getAnyById(id: String): HighlightEntity? = entries.value[id]

    override suspend fun upsert(entity: HighlightEntity) {
        entries.value = entries.value + (entity.id to entity)
    }
}
