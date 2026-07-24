package app.berilo.reader.store.db

import androidx.room.Entity

/**
 * A cached LLM dictionary lookup (S2.4).
 *
 * Keyed on ([word], [sentenceHash], [lang], [model]) so the same word looked up in a
 * different sentence (disambiguation) or under a different target language/model gets its
 * own entry — a cache hit only bypasses the network when all four match, which is what
 * makes the cache safe to reuse silently.
 *
 * @property word The looked-up word, lowercased (case-insensitive cache key).
 * @property sentenceHash Stable hash ([app.berilo.reader.dictionary.SentenceHash]) of the
 *   surrounding sentence supplied as disambiguation context.
 * @property lang Target language code the definition was generated in (e.g. `"sl"`).
 * @property model The model identifier that produced this definition.
 * @property definition Short definition/translation of the word.
 * @property contextMeaning The word's meaning in this specific sentence.
 * @property baseForm The word's dictionary/lemma form.
 * @property usageNote Brief usage note.
 * @property costEur Actual EUR cost of the call that produced this entry (`0.0` if it was
 *   never billed, which cannot happen for a freshly-fetched entry but keeps the column
 *   non-null for cache rows written defensively).
 * @property createdAt Epoch-millis timestamp this entry was written.
 */
@Entity(tableName = "dictionary_entries", primaryKeys = ["word", "sentenceHash", "lang", "model"])
data class DictionaryEntryEntity(
    val word: String,
    val sentenceHash: String,
    val lang: String,
    val model: String,
    val definition: String,
    val contextMeaning: String,
    val baseForm: String,
    val usageNote: String,
    val costEur: Double,
    val createdAt: Long,
)
