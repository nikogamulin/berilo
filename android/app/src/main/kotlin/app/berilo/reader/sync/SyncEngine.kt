package app.berilo.reader.sync

import android.util.Log
import app.berilo.reader.store.db.BookDao
import app.berilo.reader.store.db.DictionaryDao
import app.berilo.reader.store.db.HighlightDao
import app.berilo.reader.store.db.SyncStateDao
import app.berilo.reader.store.db.SyncStateEntity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** What one sync round did, for the settings screen and the logs. */
data class SyncReport(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val skippedVocabulary: Int = 0,
    val outcome: SyncOutcome = SyncOutcome.Success,
) {
    val isSuccess: Boolean
        get() = outcome == SyncOutcome.Success
}

/** Why a sync round stopped. */
sealed interface SyncOutcome {
    data object Success : SyncOutcome

    data object Offline : SyncOutcome

    data object SignedOut : SyncOutcome

    data class RateLimited(val retryAfterSeconds: Long?) : SyncOutcome

    data class Failed(val message: String) : SyncOutcome
}

/**
 * One sync round: pull every entity to completion, then push everything local that is newer
 * than its watermark (`docs/sync_api.md` §1.3, §4).
 *
 * **Pull before push** is deliberate. Pulling first means a local edit is compared against the
 * server's current state before it is sent, so the common case — the other device edited
 * something this device has not touched — resolves without a conflict round trip at all.
 *
 * **There is no outbox table.** Every synced row already carries the `updatedAt` that decides
 * last-write-wins, so "dirty" is just `updatedAt > lastPushedAt`. A second copy of each pending
 * mutation would add nothing except a way for the two records to disagree after a crash.
 *
 * @param clock Time source for tombstones and watermarks, injectable for tests.
 */
class SyncEngine(
    private val api: SyncApi,
    private val bookDao: BookDao,
    private val highlightDao: HighlightDao,
    private val dictionaryDao: DictionaryDao,
    private val syncStateDao: SyncStateDao,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Runs a full round. Never throws: every failure is reported through [SyncReport.outcome]
     * so a background worker can decide whether to retry without unwinding a coroutine.
     */
    suspend fun sync(): SyncReport =
        withContext(ioDispatcher) {
            var report = SyncReport()
            for (entity in SyncEntities.ALL) {
                when (val stage = drain(entity, report)) {
                    is StageResult.Continue -> report = stage.report
                    is StageResult.Stop -> return@withContext report.copy(outcome = stage.outcome)
                }
            }
            for (entity in SyncEntities.PUSH_ORDER) {
                when (val stage = pushEntity(entity, report)) {
                    is StageResult.Continue -> report = stage.report
                    is StageResult.Stop -> return@withContext report.copy(outcome = stage.outcome)
                }
            }
            report.copy(skippedVocabulary = dictionaryDao.countWithoutSentence())
        }

    /**
     * Outcome of one entity's pull or push: either the running report, or the reason the round
     * has to stop. A sealed result rather than a nullable return so "stopped, and here is why"
     * cannot be confused with "nothing to do".
     */
    private sealed interface StageResult {
        data class Continue(val report: SyncReport) : StageResult

        data class Stop(val outcome: SyncOutcome) : StageResult
    }

    /** Maps a transport-level failure onto the reason this round stops. */
    private fun stopFor(result: SyncCallResult<*>): StageResult.Stop =
        StageResult.Stop(
            when (result) {
                SyncCallResult.Offline -> SyncOutcome.Offline
                SyncCallResult.Unauthorized -> SyncOutcome.SignedOut
                is SyncCallResult.RateLimited ->
                    SyncOutcome.RateLimited(result.retryAfterSeconds)
                is SyncCallResult.Failed -> SyncOutcome.Failed(result.message)
                is SyncCallResult.Success -> SyncOutcome.Success
            },
        )

    // --- pull -------------------------------------------------------------------------

    /**
     * Drains one entity, following §4's protocol exactly: pages are fed back in memory and the
     * cursor is persisted **only** when a page returns `has_more: false`.
     *
     * Persisting mid-drain would make the stored cursor mean "somewhere in the middle of a
     * pull" — and a crash right after would leave no way to tell that from "fully caught up".
     * Keeping it in memory means a crash simply re-drains from the last complete baseline,
     * which is safe because keyset pagination is idempotent to resume from any earlier point.
     */
    private suspend fun drain(entity: String, initial: SyncReport): StageResult {
        var report = initial
        var cursor = syncStateDao.get(entity)?.cursor
        while (true) {
            val request =
                PullRequest(
                    entities = listOf(entity),
                    cursors = mapOf(entity to cursor),
                    limit = PAGE_SIZE,
                )
            val result = api.pull(request)
            if (result !is SyncCallResult.Success) return stopFor(result)
            val page = result.value.entities[entity] ?: PullEntityPage()

            report = report.copy(pulled = report.pulled + applyRows(entity, page.rows))
            cursor = page.nextCursor ?: cursor
            if (!page.hasMore) {
                val state = syncStateDao.get(entity) ?: SyncStateEntity(entity)
                syncStateDao.upsert(state.copy(cursor = cursor))
                return StageResult.Continue(report)
            }
        }
    }

    private suspend fun applyRows(entity: String, rows: List<JsonObject>): Int {
        var applied = 0
        for (row in rows) {
            val didApply =
                when (entity) {
                    SyncEntities.BOOKS_METADATA -> applyBookMetadata(row)
                    SyncEntities.HIGHLIGHTS -> applyHighlight(row)
                    SyncEntities.VOCABULARY -> applyVocabulary(row)
                    SyncEntities.PROGRESS -> applyProgress(row)
                    else -> false
                }
            if (didApply) applied++
        }
        return applied
    }

    /**
     * Applies a pulled book row. Only the server-owned columns move: the local file path,
     * cover and reading position are device facts, and a row arriving from another device must
     * not blank the path to the copy sitting on this one.
     */
    private suspend fun applyBookMetadata(row: JsonObject): Boolean {
        val existing = SyncMapper.bookMetadataFrom(row, null)?.id?.let { bookDao.getAnyById(it) }
        val incoming = SyncMapper.bookMetadataFrom(row, existing) ?: return false
        if (existing == null) {
            // A book only the server knows about. Its metadata lands so highlights have a
            // parent row and the title is on record, but it is not shown in the library —
            // there is no file to open and none can be fetched (CLAUDE.md §2).
            bookDao.insert(incoming)
            return true
        }
        if (incoming.updatedAt <= existing.updatedAt) return false
        bookDao.applyServerMetadata(
            id = incoming.id,
            title = incoming.title,
            authors = incoming.authors,
            sourceLang = incoming.sourceLang,
            targetLang = incoming.targetLang,
            updatedAt = incoming.updatedAt,
            deletedAt = incoming.deletedAt,
        )
        return true
    }

    private suspend fun applyHighlight(row: JsonObject): Boolean {
        val incoming = SyncMapper.highlightFrom(row) ?: return false
        val existing = highlightDao.getAnyById(incoming.id)
        // Ties keep the local row: it is still dirty, so it pushes on this same round and the
        // server accepts it as an idempotent no-op. Overwriting on a tie would be equally
        // correct but would throw away a local edit for no reason.
        if (existing != null && incoming.updatedAt <= existing.updatedAt) return false
        highlightDao.upsert(incoming)
        return true
    }

    private suspend fun applyVocabulary(row: JsonObject): Boolean {
        val incoming = SyncMapper.vocabularyFrom(row) ?: return false
        val existing =
            dictionaryDao.getAny(
                incoming.word,
                incoming.sentenceHash,
                incoming.lang,
                incoming.model,
            )
        if (existing != null && incoming.updatedAt <= existing.updatedAt) return false
        dictionaryDao.upsert(incoming)
        return true
    }

    /**
     * Applies a pulled reading position. Skipped when the book is not on this device: a
     * position is meaningless without the file it points into, and the file never syncs.
     */
    private suspend fun applyProgress(row: JsonObject): Boolean {
        val bookHash = SyncMapper.bookMetadataFrom(row, null)?.id ?: rowBookHash(row) ?: return false
        val updatedAt = SyncMapper.updatedAtOf(row) ?: return false
        val locator = row["locator_json"] as? JsonObject ?: return false
        val existing = bookDao.getById(bookHash) ?: return false
        if (existing.filePath.isEmpty()) return false
        if ((existing.lastOpenedAt ?: 0L) >= updatedAt) return false
        bookDao.applyServerProgress(bookHash, locator.toString(), updatedAt)
        return true
    }

    private fun rowBookHash(row: JsonObject): String? =
        (row["book_hash"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf {
            it.isNotEmpty()
        }

    // --- push -------------------------------------------------------------------------

    /**
     * Pushes one entity's dirty rows in `updatedAt` order, advancing the watermark only across
     * the leading run of items the server accepted.
     *
     * Stopping at the first error is what makes the queue lossless: the watermark never steps
     * over a row the server did not take, so that row is retried on the next round instead of
     * being silently forgotten.
     */
    private suspend fun pushEntity(entity: String, initial: SyncReport): StageResult {
        var report = initial
        while (true) {
            val watermark = syncStateDao.get(entity)?.lastPushedAt ?: 0L
            val pending = pendingFor(entity, watermark)
            if (pending.isEmpty()) return StageResult.Continue(report)

            val result = api.push(mapOf(entity to pending.map { it.payload }))
            if (result !is SyncCallResult.Success) return stopFor(result)

            val results = result.value.results[entity].orEmpty()
            var advancedTo = watermark
            var accepted = 0
            var conflicts = 0
            for ((index, item) in pending.withIndex()) {
                val outcome = results.getOrNull(index)
                when {
                    outcome == null -> break
                    outcome.isApplied -> {
                        accepted++
                        advancedTo = item.updatedAt
                    }
                    outcome.isConflict -> {
                        conflicts++
                        outcome.serverRow?.let { adoptServerRow(entity, it) }
                        advancedTo = item.updatedAt
                    }
                    else -> {
                        Log.w(TAG, "push $entity item rejected: ${outcome.error}")
                        break
                    }
                }
            }

            report = report.copy(pushed = report.pushed + accepted, conflicts = report.conflicts + conflicts)
            val state = syncStateDao.get(entity) ?: SyncStateEntity(entity)
            syncStateDao.upsert(state.copy(lastPushedAt = advancedTo))

            // No progress means the head of the queue is stuck (a rejected item, or a server
            // that returned fewer results than items). Returning rather than looping keeps a
            // permanently-rejected row from spinning the round forever.
            if (advancedTo <= watermark || pending.size < BATCH_SIZE) {
                return StageResult.Continue(report)
            }
        }
    }

    /** A row queued for push, paired with the timestamp its watermark advance is worth. */
    private data class PendingItem(val updatedAt: Long, val payload: JsonObject)

    private suspend fun pendingFor(entity: String, since: Long): List<PendingItem> =
        when (entity) {
            SyncEntities.BOOKS_METADATA ->
                bookDao.metadataDirtySince(since, BATCH_SIZE).map {
                    PendingItem(it.updatedAt, SyncMapper.bookMetadataItem(it))
                }
            SyncEntities.PROGRESS ->
                bookDao.progressDirtySince(since, BATCH_SIZE).mapNotNull { book ->
                    SyncMapper.progressItem(book)?.let {
                        PendingItem(book.lastOpenedAt ?: book.updatedAt, it)
                    }
                }
            SyncEntities.HIGHLIGHTS ->
                highlightDao.dirtySince(since, BATCH_SIZE).mapNotNull { highlight ->
                    SyncMapper.highlightItem(highlight)?.let {
                        PendingItem(highlight.updatedAt, it)
                    }
                }
            SyncEntities.VOCABULARY ->
                dictionaryDao.dirtySince(since, BATCH_SIZE).map {
                    PendingItem(it.updatedAt, SyncMapper.vocabularyItem(it))
                }
            else -> emptyList()
        }

    /**
     * Adopts the server's row after losing last-write-wins. The local copy is overwritten
     * wholesale, which is the contract's stated resolution (§1.3) — the alternative, keeping
     * both, would need a merge UI this app does not have and the user never asked for.
     */
    private suspend fun adoptServerRow(entity: String, row: JsonObject) {
        when (entity) {
            SyncEntities.BOOKS_METADATA -> applyBookMetadata(row)
            SyncEntities.HIGHLIGHTS -> applyHighlight(row)
            SyncEntities.VOCABULARY -> applyVocabulary(row)
            SyncEntities.PROGRESS -> applyProgress(row)
        }
    }

    // --- lifecycle --------------------------------------------------------------------

    /**
     * Clears sync bookkeeping on sign-out so the next account starts from a clean baseline
     * rather than inheriting cursors and watermarks that belong to a different user.
     *
     * Local books, highlights and vocabulary are deliberately left alone: they were usable
     * before any account existed and signing out is not a request to erase them.
     */
    suspend fun resetState() = withContext(ioDispatcher) { syncStateDao.clear() }

    private companion object {
        const val TAG = "BeriloSync"

        /**
         * Well under the contract's 1000-item cap (§3) so a batch stays inside a serverless
         * function's time budget, and a failed round costs one small retry rather than a large
         * one.
         */
        const val BATCH_SIZE = 200
        const val PAGE_SIZE = 500
    }
}
