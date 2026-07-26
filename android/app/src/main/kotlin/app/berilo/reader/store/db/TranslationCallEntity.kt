package app.berilo.reader.store.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Accounting for one on-device translation API call (B4), the on-device mirror of
 * `translator/berilo/cache.py`'s `calls` table.
 *
 * One row is written per stored batch (or per glossary-extraction call), alongside the
 * translations it produced, so the reader's cost total is a plain sum over this table rather
 * than a second, independently-maintained running counter that could drift from it.
 *
 * @property id Autoincrement row id.
 * @property bookHash Owning book's identity hash.
 * @property model Model identifier the call was made with.
 * @property lang Target language code.
 * @property kind Call category (`"batch"`, `"glossary"`, ...).
 * @property inputTokens Input (prompt) tokens billed.
 * @property outputTokens Output (completion) tokens billed.
 * @property costEur EUR cost of the call.
 * @property createdAt Epoch-millis timestamp the call was recorded.
 */
@Entity(tableName = "calls")
data class TranslationCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookHash: String,
    val model: String,
    val lang: String,
    val kind: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costEur: Double,
    val createdAt: Long,
)
