package app.berilo.reader.interpretation

import app.berilo.reader.store.db.InterpretationDao
import app.berilo.reader.store.db.InterpretationEntryEntity

/** In-memory [InterpretationDao] fake: keeps cache-repository tests off the Android/Robolectric runtime. */
class FakeInterpretationDao : InterpretationDao {
    private val entries = mutableMapOf<Key, InterpretationEntryEntity>()

    private data class Key(val passageHash: String, val lang: String, val model: String)

    fun count(): Int = entries.size

    override suspend fun find(passageHash: String, lang: String, model: String): InterpretationEntryEntity? =
        entries[Key(passageHash, lang, model)]

    override suspend fun upsert(entry: InterpretationEntryEntity) {
        entries[Key(entry.passageHash, entry.lang, entry.model)] = entry
    }
}
