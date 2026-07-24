package app.berilo.reader.store.db

import androidx.room.Entity

/**
 * A cached LLM paragraph interpretation (S2.5).
 *
 * Keyed on ([passageHash], [lang], [model]) — the same passage re-interpreted under a
 * different target language or model gets its own entry — so a cache hit only bypasses
 * the network when all three match.
 *
 * @property passageHash Stable hash ([app.berilo.reader.interpretation.PassageHash]) of the
 *   full selected passage text.
 * @property lang Target language code the interpretation was generated in (e.g. `"sl"`).
 * @property model The model identifier that produced this interpretation.
 * @property text The interpretation text, in [lang].
 * @property costEur Actual EUR cost of the call that produced this entry (`0.0` if it was
 *   never billed, which cannot happen for a freshly-fetched entry but keeps the column
 *   non-null for cache rows written defensively).
 * @property createdAt Epoch-millis timestamp this entry was written.
 */
@Entity(tableName = "interpretation_entries", primaryKeys = ["passageHash", "lang", "model"])
data class InterpretationEntryEntity(
    val passageHash: String,
    val lang: String,
    val model: String,
    val text: String,
    val costEur: Double,
    val createdAt: Long,
)
