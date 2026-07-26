package app.berilo.reader.store.db

import androidx.room.Entity

/**
 * One cached segment translation (B4), the on-device mirror of `translator/berilo/cache.py`'s
 * `translations` table.
 *
 * Keyed on the same six columns as the Python cache, in the same order, for the same reason:
 * every column is prompt input, so leaving one out of the key would let a change to it be
 * silently served from a stale row instead of re-translated (CLAUDE.md §9).
 *
 * @property bookHash Owning book's identity hash ([app.berilo.reader.translate.model.bookHash]).
 * @property segmentHash Content hash of the segment's stripped source text
 *   ([app.berilo.reader.translate.model.segmentHash]) — not position, so identical source text
 *   shares one cached translation.
 * @property model Model identifier the translation was produced with.
 * @property lang Target language code (e.g. `"sl"`).
 * @property promptVersion Identity of the translation-style prompt that produced [text].
 * @property glossaryHash Identity of the glossary injected into the prompt that produced
 *   [text] ([app.berilo.reader.translate.model.glossaryIdentity]). The same segment translated
 *   under two different glossaries stores two distinct rows.
 * @property text The cached translated text.
 * @property costEur Share of the batch's cost attributed to this segment.
 * @property createdAt Epoch-millis timestamp this row was written.
 */
@Entity(
    tableName = "translations",
    primaryKeys = ["bookHash", "segmentHash", "model", "lang", "promptVersion", "glossaryHash"],
)
data class TranslationEntity(
    val bookHash: String,
    val segmentHash: String,
    val model: String,
    val lang: String,
    val promptVersion: String,
    val glossaryHash: String,
    val text: String,
    val costEur: Double,
    val createdAt: Long,
)
