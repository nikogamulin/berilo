package app.berilo.reader.annotations

import app.berilo.reader.store.db.TranslationFlagDao
import app.berilo.reader.store.db.TranslationFlagEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [TranslationFlagDao] fake (B9), mirroring [FakeHighlightDao] so ViewModel tests
 * stay off the Robolectric runtime. The DAO-against-Room contract is covered separately by
 * `TranslationFlagDaoTest`. */
class FakeTranslationFlagDao : TranslationFlagDao {
    private val entries = MutableStateFlow<Map<String, TranslationFlagEntity>>(emptyMap())

    /** Live rows only, mirroring the real DAO's `deletedAt IS NULL` filter. */
    override fun observeForBook(bookId: String) =
        entries.map { all ->
            all.values
                .filter { it.bookId == bookId && it.deletedAt == null }
                .sortedBy { it.createdAt }
        }

    override suspend fun getById(id: String): TranslationFlagEntity? =
        entries.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun insert(entity: TranslationFlagEntity) {
        entries.value = entries.value + (entity.id to entity)
    }

    override suspend fun softDelete(id: String, at: Long) {
        val existing = entries.value[id] ?: return
        entries.value = entries.value + (id to existing.copy(deletedAt = at, updatedAt = at))
    }

    override suspend fun getAnyById(id: String): TranslationFlagEntity? = entries.value[id]
}
