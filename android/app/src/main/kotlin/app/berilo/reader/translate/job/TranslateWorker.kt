package app.berilo.reader.translate.job

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.berilo.reader.BeriloApplication
import app.berilo.reader.translate.engine.TranslationStats
import app.berilo.reader.translate.prompts.StyleTier
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Input/progress `Data` keys. Package-visible so the round-trip tests name the same strings. */
internal const val KEY_SOURCE_ID = "source_id"
internal const val KEY_SOURCE_PATH = "source_path"
internal const val KEY_DISPLAY_NAME = "display_name"
internal const val KEY_TARGET_LANG = "target_lang"
internal const val KEY_MODEL = "model"
internal const val KEY_TIER = "tier"

internal const val KEY_PROGRESS_TOTAL = "progress_total"
internal const val KEY_PROGRESS_PROCESSED = "progress_processed"
internal const val KEY_PROGRESS_CHAPTER_INDEX = "progress_chapter_index"
internal const val KEY_PROGRESS_CHAPTER_TITLE = "progress_chapter_title"
internal const val KEY_PROGRESS_API_CALLS = "progress_api_calls"
internal const val KEY_PROGRESS_COST_EUR = "progress_cost_eur"

/** Sentinel for "no chapter reported yet", since `Data` cannot carry a nullable Int. */
internal const val NO_CHAPTER = -1

/** What the worker decided to tell WorkManager. Extracted so the branching is testable. */
internal enum class WorkerVerdict { SUCCESS, RETRY, FAILURE }

/**
 * Map a job outcome onto a WorkManager verdict.
 *
 * A retryable failure is a **wait**, not a restart: the engine commits each batch to the cache
 * as it goes, so the next attempt resumes and re-bills nothing. Returning `failure()` for a
 * network blip would instead abandon a part-paid book and force the user to start the flow
 * again, which is the one outcome the resumable design exists to prevent.
 */
internal fun verdictFor(outcome: TranslationJobOutcome): WorkerVerdict =
    when (outcome) {
        is TranslationJobOutcome.Completed -> WorkerVerdict.SUCCESS
        is TranslationJobOutcome.Failed ->
            if (outcome.retryable) WorkerVerdict.RETRY else WorkerVerdict.FAILURE
    }

/** Render [request] as WorkManager input. Inverse of [translationRequestFrom]. */
internal fun requestData(request: TranslationRequest): Data =
    Data.Builder()
        .putString(KEY_SOURCE_ID, request.sourceId)
        .putString(KEY_SOURCE_PATH, request.sourceFilePath)
        .putString(KEY_DISPLAY_NAME, request.displayName)
        .putString(KEY_TARGET_LANG, request.targetLang)
        .putString(KEY_MODEL, request.model)
        .putString(KEY_TIER, request.tier.name)
        .build()

/**
 * Rebuild a [TranslationRequest] from WorkManager input.
 *
 * @return The request, or `null` if any field is missing or the tier does not name a
 *   [StyleTier] — an unreadable job must fail, never silently fall back to a tier the user did
 *   not confirm. Getting the cheaper tier by accident is still an unconfirmed run; getting the
 *   dearer one bills double.
 */
internal fun translationRequestFrom(data: Data): TranslationRequest? {
    val tier = data.getString(KEY_TIER)?.let { name -> StyleTier.entries.firstOrNull { it.name == name } }
    return TranslationRequest(
        sourceId = data.getString(KEY_SOURCE_ID) ?: return null,
        sourceFilePath = data.getString(KEY_SOURCE_PATH) ?: return null,
        displayName = data.getString(KEY_DISPLAY_NAME) ?: return null,
        targetLang = data.getString(KEY_TARGET_LANG) ?: return null,
        model = data.getString(KEY_MODEL) ?: return null,
        tier = tier ?: return null,
    )
}

/** Render a progress snapshot for [CoroutineWorker.setProgress]. */
internal fun progressData(stats: TranslationStats): Data =
    Data.Builder()
        .putInt(KEY_PROGRESS_TOTAL, stats.totalSegments)
        .putInt(KEY_PROGRESS_PROCESSED, stats.processedSegments)
        .putInt(KEY_PROGRESS_CHAPTER_INDEX, stats.currentChapterIndex ?: NO_CHAPTER)
        .putString(KEY_PROGRESS_CHAPTER_TITLE, stats.currentChapterTitle.orEmpty())
        .putInt(KEY_PROGRESS_API_CALLS, stats.apiCalls)
        .putDouble(KEY_PROGRESS_COST_EUR, stats.costEur)
        .build()

/**
 * Runs one book translation in the background (B7), mirroring `SyncWorker`.
 *
 * WorkManager rather than a coroutine the app owns, for the reason a full book demands it: at
 * `gpt-5-mini` a book is ~162 calls over hours on battery, and an e-ink tablet dozes hard. Work
 * survives the app being backgrounded, killed or the device rebooted, and the
 * [NetworkType.CONNECTED] constraint keeps it from burning a window on calls that cannot reach
 * the API.
 *
 * **Unique work, [ExistingWorkPolicy.KEEP].** Two concurrent book translations on a tablet
 * would compete for the same rate limit and double the wall-clock of both; KEEP means a second
 * confirmation while one is running is ignored rather than queued behind it or, worse, replacing
 * it mid-book.
 *
 * The body is a five-line delegation to [BookTranslationJob] on purpose. Everything worth
 * asserting — the run itself, resumability, the cost accounting — is asserted against that
 * object directly, which keeps the suite off `androidx.work:work-testing`.
 */
class TranslateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = coroutineScope {
        val container =
            (applicationContext as? BeriloApplication)?.container ?: return@coroutineScope Result.failure()
        val request = translationRequestFrom(inputData) ?: return@coroutineScope Result.failure()

        // The engine's callback is synchronous but `setProgress` suspends, so snapshots are
        // handed to a publisher coroutine through a conflating StateFlow rather than awaited
        // inline. Conflation is the right loss here: if publishing falls behind, the user wants
        // the newest position, not a replay of every batch — and blocking the engine on a
        // WorkManager write would make progress reporting slow down the paid run itself.
        val snapshots = MutableStateFlow<TranslationStats?>(null)
        val publisher = launch { snapshots.filterNotNull().collect { setProgress(progressData(it)) } }

        val outcome =
            try {
                container.bookTranslationJob.run(
                    request = request,
                    settings = container.settingsRepository.load(),
                    onProgress = { stats -> snapshots.value = stats },
                )
            } finally {
                publisher.cancel()
            }

        when (verdictFor(outcome)) {
            WorkerVerdict.SUCCESS -> Result.success()
            WorkerVerdict.RETRY -> Result.retry()
            WorkerVerdict.FAILURE -> Result.failure()
        }
    }

    companion object {
        /** Unique work name — one book translation at a time (see the class docstring). */
        const val WORK_NAME: String = "berilo-translate"

        /**
         * Enqueue [request] as unique background work.
         *
         * The only caller is
         * [app.berilo.reader.ui.translate.TranslateViewModel.confirmAndTranslate], reached only
         * from the estimate screen's explicit confirm action (CLAUDE.md §4).
         */
        fun enqueue(context: Context, request: TranslationRequest) {
            val work =
                OneTimeWorkRequestBuilder<TranslateWorker>()
                    .setInputData(requestData(request))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, work)
        }

        /** Cancel the running translation — the user's stop control. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
