package app.berilo.reader.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the `/api/v1/sync` endpoints (`docs/sync_api.md` §3).
 *
 * Rows are carried as raw [JsonObject] rather than per-entity typed classes. The contract is
 * additive-only within v1 (§6) and explicitly requires clients to ignore unknown fields, so
 * decoding into a fixed shape would mean editing this file every time the server grows a
 * column. Mapping to Room entities happens in [SyncMapper], which reads the fields it knows
 * and leaves the rest alone.
 */
@Serializable
data class PullRequest(
    val entities: List<String>,
    val cursors: Map<String, String?> = emptyMap(),
    val limit: Int,
)

@Serializable
data class PullResponse(val entities: Map<String, PullEntityPage> = emptyMap())

@Serializable
data class PullEntityPage(
    val rows: List<JsonObject> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class PushRequest(val entities: Map<String, List<JsonObject>>)

@Serializable
data class PushResponse(val results: Map<String, List<PushItemResult>> = emptyMap())

/**
 * One item's outcome, echoed in request order.
 *
 * @property status `applied`, `conflict`, or `error`.
 * @property serverRow Present on `conflict`: the server's current row, which the client adopts
 *   wholesale — the server's copy is newer, so the local edit lost last-write-wins.
 */
@Serializable
data class PushItemResult(
    val key: JsonObject? = null,
    val status: String,
    @SerialName("server_row") val serverRow: JsonObject? = null,
    val error: String? = null,
) {
    val isApplied: Boolean
        get() = status == STATUS_APPLIED

    val isConflict: Boolean
        get() = status == STATUS_CONFLICT

    companion object {
        const val STATUS_APPLIED = "applied"
        const val STATUS_CONFLICT = "conflict"
    }
}

/** Every non-2xx body: `{"error": {"code", "message", "request_id"}}` (§3). */
@Serializable data class ErrorEnvelope(val error: ApiError)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String? = null,
)

/** The synced entity names this client handles, in the contract's own vocabulary. */
object SyncEntities {
    const val BOOKS_METADATA = "books_metadata"
    const val HIGHLIGHTS = "highlights"
    const val VOCABULARY = "vocabulary"
    const val PROGRESS = "progress"

    /**
     * What the Android client syncs. The web-only social tables (`shelves`, `shelf_items`,
     * `ratings`, `shared_passages`) exist in the contract but have no Android entity yet
     * (`sync_api.md` §1.2) — pulling them would mean storing rows nothing on this device can
     * render or edit.
     */
    val ALL = listOf(BOOKS_METADATA, HIGHLIGHTS, VOCABULARY, PROGRESS)

    /**
     * Push order. Fixed by the contract (§1.3): `books_metadata` first, because every other
     * entity carries a foreign key to it and a child pushed before its parent comes back as an
     * error. The server applies its own order regardless, but sending them this way keeps a
     * partial/retried batch from tripping the same dependency.
     */
    val PUSH_ORDER = listOf(BOOKS_METADATA, HIGHLIGHTS, PROGRESS, VOCABULARY)
}
