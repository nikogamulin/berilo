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

    override fun observeForBook(bookId: String) =
        entries.map { all ->
            all.values.filter { it.bookId == bookId }.sortedBy { it.createdAt }
        }

    override suspend fun getById(id: String): HighlightEntity? = entries.value[id]

    override suspend fun insert(entity: HighlightEntity) {
        entries.value = entries.value + (entity.id to entity)
    }

    override suspend fun update(entity: HighlightEntity) {
        entries.value = entries.value + (entity.id to entity)
    }

    override suspend fun deleteById(id: String) {
        entries.value = entries.value - id
    }
}
