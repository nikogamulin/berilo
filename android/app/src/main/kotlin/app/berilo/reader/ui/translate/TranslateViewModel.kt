package app.berilo.reader.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.settings.SettingsRepository
import app.berilo.reader.translate.job.SourceBook
import app.berilo.reader.translate.job.SourceBookImporter
import app.berilo.reader.translate.job.SourceImportOutcome
import app.berilo.reader.translate.job.TranslationPlan
import app.berilo.reader.translate.job.TranslationPlanner
import app.berilo.reader.translate.job.TranslationProgress
import app.berilo.reader.translate.job.TranslationRequest
import app.berilo.reader.translate.job.TranslationRunUpdate
import app.berilo.reader.translate.job.TranslationRunner
import app.berilo.reader.translate.prompts.StyleTier
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The translate flow's screens, in the order a user walks them. */
sealed interface TranslateUiState {

    /** Nothing picked yet — the screen explains what to pick. */
    data object Idle : TranslateUiState

    /** Reading and pricing the picked EPUB. **No API call happens in this state.** */
    data object Preparing : TranslateUiState

    /**
     * The dry run (CLAUDE.md §4). Both tiers priced; nothing has been spent, and nothing will be
     * until [TranslateViewModel.confirmAndTranslate].
     *
     * @property plan Chapters, segments, resolved model and style, and both tier costs.
     * @property tier The tier currently selected — [StyleTier.ECONOMY] on arrival.
     */
    data class Estimate(val plan: TranslationPlan, val tier: StyleTier) : TranslateUiState

    /** Confirmed and handed to WorkManager; waiting for a window or the network. */
    data class Waiting(val bookTitle: String) : TranslateUiState

    /** Translating. [progress] is `null` until the first batch commits. */
    data class Running(val bookTitle: String, val progress: TranslationProgress?) : TranslateUiState

    /** Done; the translated book is in the library. */
    data class Done(val bookTitle: String, val progress: TranslationProgress?) : TranslateUiState

    /**
     * Stopped.
     *
     * @property retryable Whether re-confirming would resume. Everything already committed to
     *   the cache is already paid for and comes back free, so a resume is never a re-bill.
     */
    data class Failed(val message: String, val retryable: Boolean) : TranslateUiState
}

/**
 * Drives the on-device translation flow: pick a source EPUB, see what it would cost, confirm,
 * watch it run (B7).
 *
 * ### The spending gate
 *
 * Exactly one method — [confirmAndTranslate] — can cause an API call, and it is reachable only
 * from the estimate screen's explicit confirm action. [onSourcePicked], [onTierSelected],
 * [retry] and [reset] cannot: the planner they use holds no client and takes no factory for
 * one, so "the estimate screen cannot spend" is enforced by what
 * [TranslationPlanner] *is*, not by care at the call site.
 * `TranslateViewModelSpendGateTest` exercises every other public method and asserts the runner
 * was never started.
 *
 * @param sourceImporter Stages the picked EPUB.
 * @param planner Builds the dry-run estimate. Cannot make an API call.
 * @param settingsRepository Supplies the target language and the translation model.
 * @param runner Starts and reports the confirmed run. Injectable so tests never enqueue work.
 */
class TranslateViewModel(
    private val sourceImporter: SourceBookImporter,
    private val planner: TranslationPlanner,
    private val settingsRepository: SettingsRepository,
    private val runner: TranslationRunner,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TranslateUiState>(TranslateUiState.Idle)
    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()

    /** The plan the user confirmed, kept so [retry] can resume without re-picking the file. */
    private var confirmed: TranslationRequest? = null

    init {
        viewModelScope.launch {
            runner.updates.collect(::onRunUpdate)
        }
    }

    /**
     * Stage the picked document and price it. **Makes no API call.**
     *
     * @param openStream Factory that opens the picked document — not an open stream, since this
     *   returns before the copy runs (see [SourceBookImporter]).
     * @param suggestedFileName Display name from the picker.
     */
    fun onSourcePicked(openStream: () -> InputStream?, suggestedFileName: String) {
        _uiState.value = TranslateUiState.Preparing
        viewModelScope.launch {
            when (val outcome = sourceImporter.import(openStream, suggestedFileName)) {
                is SourceImportOutcome.Imported -> planFor(outcome.source)
                is SourceImportOutcome.AlreadyStaged -> planFor(outcome.source)
                is SourceImportOutcome.Failed ->
                    _uiState.value = TranslateUiState.Failed(outcome.reason, retryable = false)
            }
        }
    }

    /** Switch the priced tier. Changes what a later confirmation would buy; spends nothing. */
    fun onTierSelected(tier: StyleTier) {
        _uiState.update { state ->
            if (state is TranslateUiState.Estimate) state.copy(tier = tier) else state
        }
    }

    /**
     * **The confirmation.** The only path in the app from a user gesture to a billed run.
     *
     * A no-op unless the estimate is on screen, so a stray call cannot start a run the user
     * never saw a price for.
     */
    fun confirmAndTranslate() {
        val estimate = _uiState.value as? TranslateUiState.Estimate ?: return
        val settings = settingsRepository.load()
        val request =
            TranslationRequest(
                sourceId = estimate.plan.source.id,
                sourceFilePath = estimate.plan.source.file.absolutePath,
                displayName = estimate.plan.source.displayName,
                targetLang = estimate.plan.targetLang,
                model = settings.resolvedTranslationModel,
                tier = estimate.tier,
            )
        confirmed = request
        _uiState.value = TranslateUiState.Waiting(estimate.plan.bookTitle)
        runner.start(request)
    }

    /**
     * Resume a stopped run.
     *
     * Re-confirmation, not a fresh decision: it re-submits the request the user already approved
     * and priced. The cache makes it a resume — every batch committed before the failure comes
     * back free.
     */
    fun retry() {
        val request = confirmed ?: return
        val state = _uiState.value
        if (state !is TranslateUiState.Failed || !state.retryable) return
        _uiState.value = TranslateUiState.Waiting(request.displayName)
        runner.start(request)
    }

    /** Stop the run. */
    fun cancel() {
        runner.cancel()
    }

    /** Return to the start of the flow, forgetting the picked book. */
    fun reset() {
        confirmed = null
        _uiState.value = TranslateUiState.Idle
    }

    private suspend fun planFor(source: SourceBook) {
        val settings = settingsRepository.load()
        val plan =
            runCatching {
                planner.plan(
                    source = source,
                    targetLang = settings.targetLang,
                    model = settings.resolvedTranslationModel,
                )
            }
        _uiState.value =
            plan.fold(
                onSuccess = { TranslateUiState.Estimate(it, StyleTier.ECONOMY) },
                onFailure = { error ->
                    TranslateUiState.Failed(
                        message = error.message ?: "Could not read ${source.displayName}.",
                        retryable = false,
                    )
                },
            )
    }

    /**
     * Fold a runner update into the UI state.
     *
     * [TranslationRunUpdate.Idle] is deliberately ignored once a run has been confirmed:
     * WorkManager reports no work info for a moment after `enqueueUniqueWork`, and letting that
     * gap reset the screen to [TranslateUiState.Idle] would drop the user back to the file
     * picker one frame after they confirmed.
     */
    private fun onRunUpdate(update: TranslationRunUpdate) {
        val title = confirmed?.displayName ?: return
        _uiState.value =
            when (update) {
                TranslationRunUpdate.Idle -> return
                TranslationRunUpdate.Waiting -> TranslateUiState.Waiting(title)
                is TranslationRunUpdate.Running -> TranslateUiState.Running(title, update.progress)
                is TranslationRunUpdate.Completed -> TranslateUiState.Done(title, update.progress)
                is TranslationRunUpdate.Failed ->
                    TranslateUiState.Failed(
                        message = "Translation of $title stopped.",
                        retryable = update.retryable,
                    )
            }
    }

    class Factory(
        private val sourceImporter: SourceBookImporter,
        private val planner: TranslationPlanner,
        private val settingsRepository: SettingsRepository,
        private val runner: TranslationRunner,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranslateViewModel(sourceImporter, planner, settingsRepository, runner) as T
    }
}
