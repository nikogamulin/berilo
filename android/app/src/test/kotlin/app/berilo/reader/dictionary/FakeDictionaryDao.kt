package app.berilo.reader.dictionary

import app.berilo.reader.store.db.DictionaryDao
import app.berilo.reader.store.db.DictionaryEntryEntity

/** In-memory [DictionaryDao] fake: keeps cache-repository tests off the Android/Robolectric runtime. */
class FakeDictionaryDao : DictionaryDao {
    private val entries = mutableMapOf<Key, DictionaryEntryEntity>()

    private data class Key(val word: String, val sentenceHash: String, val lang: String, val model: String)

    fun count(): Int = entries.size

    override suspend fun find(word: String, sentenceHash: String, lang: String, model: String): DictionaryEntryEntity? =
        entries[Key(word, sentenceHash, lang, model)]

    override suspend fun upsert(entry: DictionaryEntryEntity) {
        entries[Key(entry.word, entry.sentenceHash, entry.lang, entry.model)] = entry
    }
}
