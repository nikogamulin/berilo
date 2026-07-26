package app.berilo.reader.store.db

import androidx.room.Entity

/**
 * The cached per-book glossary term map (B4), the on-device mirror of `translator/berilo/cache.py`'s
 * `glossaries` table.
 *
 * Keyed on `(bookHash, model, lang, promptVersion)` — the glossary-extraction pass runs at most
 * once per book/model/lang **and glossary-prompt identity**; changing the extraction prompt or
 * its sampling changes [promptVersion], so an improved extraction re-derives instead of returning
 * stale terms.
 *
 * @property bookHash Owning book's identity hash.
 * @property model Model identifier the glossary was built with.
 * @property lang Target language code.
 * @property promptVersion Identity of the glossary-extraction prompt and its sampling
 *   parameters that produced [termsJson].
 * @property termsJson The `source term -> fixed rendering` map, JSON-encoded.
 * @property createdAt Epoch-millis timestamp this row was written.
 */
@Entity(tableName = "glossaries", primaryKeys = ["bookHash", "model", "lang", "promptVersion"])
data class GlossaryEntity(
    val bookHash: String,
    val model: String,
    val lang: String,
    val promptVersion: String,
    val termsJson: String,
    val createdAt: Long,
)
