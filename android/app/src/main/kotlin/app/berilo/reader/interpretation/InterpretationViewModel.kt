package app.berilo.reader.interpretation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.llm.LlmError
import app.berilo.reader.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coarse classification the interpretation sheet branches its error copy on. */
enum class InterpretationErrorKind { NETWORK, AUTH, OTHER }

/** State of the paragraph interpretation bottom sheet. */
sealed interface InterpretationUiState {
    /** Sheet not shown. */
    data object Idle : InterpretationUiState

    /** Sheet shown, interpretation call in flight. */
    data object Loading : InterpretationUiState

    /** Sheet shown with a resolved interpretation. */
    data class Success(
        val text: String,
        val passage: String,
        val costEur: Double,
        val fromCache: Boolean,
    ) : InterpretationUiState

    /** Sheet shown with an actionable error. */
    data class Error(val kind: InterpretationErrorKind, val message: String) : InterpretationUiState
}

/**
 * Drives the paragraph interpretation bottom sheet: takes the passage text captured by the
 * reader (the whole selection, or the visible locator's text as a fallback), runs it
 * through [InterpretationRepository] (cache-first), and exposes the resulting
 * [InterpretationUiState] for [InterpretationSheet] to render. Mirrors
 * [app.berilo.reader.dictionary.DictionaryViewModel]'s cancellable-job shape (S2.4).
 */
class InterpretationViewModel(
    private val settingsRepository: SettingsRepository,
    private val interpretationRepository: InterpretationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<InterpretationUiState>(InterpretationUiState.Idle)
    val uiState: StateFlow<InterpretationUiState> = _uiState.asStateFlow()

    private var interpretJob: Job? = null

    /** Starts an interpretation of [passage] from [bookTitle], opening the sheet in its
     * loading state. */
    fun interpret(passage: String, bookTitle: String) {
        interpretJob?.cancel()
        _uiState.value = InterpretationUiState.Loading
        interpretJob = viewModelScope.launch {
            val settings = settingsRepository.load()
            try {
                val result = interpretationRepository.interpret(settings, passage, bookTitle)
                _uiState.value =
                    InterpretationUiState.Success(
                        text = result.text,
                        passage = passage,
                        costEur = result.costEur,
                        fromCache = result.fromCache,
                    )
            } catch (error: LlmError) {
                _uiState.value = InterpretationUiState.Error(error.kind.toErrorKind(), error.message.orEmpty())
            }
        }
    }

    /** Dismisses the sheet, returning to [InterpretationUiState.Idle].
     *
     * Cancels any in-flight interpretation so a late result cannot reopen the sheet.
     */
    fun dismiss() {
        interpretJob?.cancel()
        interpretJob = null
        _uiState.value = InterpretationUiState.Idle
    }

    private fun LlmError.Kind.toErrorKind(): InterpretationErrorKind =
        when (this) {
            LlmError.Kind.NETWORK -> InterpretationErrorKind.NETWORK
            LlmError.Kind.AUTH -> InterpretationErrorKind.AUTH
            LlmError.Kind.RATE_LIMIT, LlmError.Kind.PROVIDER, LlmError.Kind.PARSE -> InterpretationErrorKind.OTHER
        }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val interpretationRepository: InterpretationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InterpretationViewModel(settingsRepository, interpretationRepository) as T
    }
}
