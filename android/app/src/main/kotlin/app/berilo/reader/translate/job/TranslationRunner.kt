package app.berilo.reader.translate.job

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** State of the one translation run the app allows at a time. */
sealed interface TranslationRunUpdate {

    /** No run has been started, or the last one has been acknowledged. */
    data object Idle : TranslationRunUpdate

    /** Enqueued, waiting for the network constraint or a scheduling window. */
    data object Waiting : TranslationRunUpdate

    /** Running; [progress] is `null` until the first batch commits. */
    data class Running(val progress: TranslationProgress?) : TranslationRunUpdate

    /** Finished; [progress] is the last snapshot the run published. */
    data class Completed(val progress: TranslationProgress?) : TranslationRunUpdate

    /** Stopped. [retryable] runs are resumed by re-confirming — nothing already paid is lost. */
    data class Failed(val retryable: Boolean, val progress: TranslationProgress?) : TranslationRunUpdate
}

/**
 * Starts a confirmed paid run and reports its progress.
 *
 * **[start] is the app's only entry to a billed whole-book translation**, and it is called from
 * exactly one place: [app.berilo.reader.ui.translate.TranslateViewModel.confirmAndTranslate].
 * Keeping it behind an interface is what lets `TranslateViewModelSpendGateTest` hand the view
 * model a runner that records calls, drive every other public method, and prove none of them
 * reaches this one (CLAUDE.md §4).
 */
interface TranslationRunner {

    /** Live state of the current or most recent run. */
    val updates: Flow<TranslationRunUpdate>

    /** Begin a paid run. Only ever called after explicit user confirmation. */
    fun start(request: TranslationRequest)

    /** Abandon the current run. Everything already committed to the cache stays paid for. */
    fun cancel()
}

/**
 * The production [TranslationRunner]: hands the run to WorkManager and reads its progress back.
 *
 * @param context Any context; the application context is taken from it.
 */
class WorkManagerTranslationRunner(context: Context) : TranslationRunner {

    private val appContext = context.applicationContext

    /**
     * Resolved on first use, never at construction.
     *
     * `WorkManager.getInstance` throws unless the framework has been initialized, and the
     * `AppContainer` that owns this object is built by every Robolectric-hosted test — where it
     * has not been. S3.2 hit the same wall and answered it by scheduling from the launcher
     * activity rather than `Application.onCreate`; the same rule applies to *reaching for*
     * WorkManager at all. Costs nothing at runtime: the first `start` or `updates` collection
     * resolves it.
     */
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    override val updates: Flow<TranslationRunUpdate> by lazy {
        workManager
            .getWorkInfosForUniqueWorkFlow(TranslateWorker.WORK_NAME)
            .map { infos -> updateFor(infos.lastOrNull()) }
    }

    override fun start(request: TranslationRequest) = TranslateWorker.enqueue(appContext, request)

    override fun cancel() = TranslateWorker.cancel(appContext)
}

/**
 * Map WorkManager's view of the job onto [TranslationRunUpdate].
 *
 * `ENQUEUED`/`BLOCKED` is [TranslationRunUpdate.Waiting] rather than Running: on a dozing e-ink
 * tablet with the network constraint unmet, that state can last a long time, and showing a
 * progress bar that never moves reads as a hang.
 *
 * A cancelled run reports as a **non-retryable** failure — the user asked it to stop, so
 * offering to resume automatically would override that; re-confirming from the estimate screen
 * is still available and still costs nothing already paid.
 */
internal fun updateFor(info: WorkInfo?): TranslationRunUpdate =
    when (info?.state) {
        null -> TranslationRunUpdate.Idle
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> TranslationRunUpdate.Waiting
        WorkInfo.State.RUNNING -> TranslationRunUpdate.Running(progressFrom(info.progress))
        WorkInfo.State.SUCCEEDED -> TranslationRunUpdate.Completed(progressFrom(info.progress))
        WorkInfo.State.FAILED -> TranslationRunUpdate.Failed(retryable = false, progress = progressFrom(info.progress))
        WorkInfo.State.CANCELLED -> TranslationRunUpdate.Failed(retryable = false, progress = progressFrom(info.progress))
    }
