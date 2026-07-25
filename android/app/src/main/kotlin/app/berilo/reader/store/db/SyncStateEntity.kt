package app.berilo.reader.store.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-entity sync bookkeeping (S3.2) — one row per synced entity name.
 *
 * Two independent watermarks live here, and conflating them would break sync:
 *
 * - [cursor] is the server's opaque keyset cursor, the "I have pulled everything up to here"
 *   baseline. Per `docs/sync_api.md` §4's drain protocol it is written **only** when a pull
 *   page comes back with `has_more: false`; mid-drain pages are kept in memory, so a crash
 *   halfway through simply re-drains from the last complete baseline.
 * - [lastPushedAt] is a local epoch-millis high-water mark: every row whose `updatedAt`
 *   exceeds it is dirty and owed to the server. This replaces a separate outbox table —
 *   the rows already carry the timestamp that decides last-write-wins, so a second copy of
 *   the same mutation would only add a way for the two to disagree.
 *
 * @property entity The server-side entity name (`highlights`, `vocabulary`, ...).
 * @property cursor Opaque cursor from the last fully-drained pull, or null if never drained.
 * @property lastPushedAt Epoch-millis; rows updated at or before this are known-pushed.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val entity: String,
    val cursor: String? = null,
    val lastPushedAt: Long = 0L,
)
