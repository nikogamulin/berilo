package app.berilo.reader.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.llm.LlmError
import app.berilo.reader.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coarse classification the dictionary sheet branches its error copy on. */
enum class DictionaryErrorKind { NETWORK, AUTH, OTHER }

/** State of the dictionary bottom sheet. */
sealed interface DictionaryUiState {
    /** Sheet not shown. */
    data object Idle : DictionaryUiState

    /** Sheet shown, lookup in flight. */
    data class Loading(val word: String) : DictionaryUiState

    /** Sheet shown with a resolved definition. */
    data class Success(
        val definition: DictionaryDefinition,
        val sentence: String,
        val costEur: Double,
        val fromCache: Boolean,
    ) : DictionaryUiState

    /** Sheet shown with an actionable error. */
    data class Error(val word: String, val kind: DictionaryErrorKind, val message: String) : DictionaryUiState
}

/**
 * Drives the dictionary bottom sheet: takes a [SelectionContext] captured by the reader,
 * runs it through [DictionaryRepository] (cache-first), and exposes the resulting
 * [DictionaryUiState] for [DictionarySheet] to render.
 */
class DictionaryViewModel(
    private val settingsRepository: SettingsRepository,
    private val dictionaryRepository: DictionaryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DictionaryUiState>(DictionaryUiState.Idle)
    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    /** Starts a lookup for [context], opening the sheet in its loading state. */
    fun lookup(context: SelectionContext) {
        _uiState.value = DictionaryUiState.Loading(context.word)
        viewModelScope.launch {
            val settings = settingsRepository.load()
            try {
                val result = dictionaryRepository.lookup(settings, context.word, context.sentence)
                _uiState.value =
                    DictionaryUiState.Success(
                        definition = result.definition,
                        sentence = context.sentence,
                        costEur = result.costEur,
                        fromCache = result.fromCache,
                    )
            } catch (error: LlmError) {
                _uiState.value = DictionaryUiState.Error(context.word, error.kind.toErrorKind(), error.message.orEmpty())
            }
        }
    }

    /** Dismisses the sheet, returning to [DictionaryUiState.Idle]. */
    fun dismiss() {
        _uiState.value = DictionaryUiState.Idle
    }

    private fun LlmError.Kind.toErrorKind(): DictionaryErrorKind =
        when (this) {
            LlmError.Kind.NETWORK -> DictionaryErrorKind.NETWORK
            LlmError.Kind.AUTH -> DictionaryErrorKind.AUTH
            LlmError.Kind.RATE_LIMIT, LlmError.Kind.PROVIDER, LlmError.Kind.PARSE -> DictionaryErrorKind.OTHER
        }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val dictionaryRepository: DictionaryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DictionaryViewModel(settingsRepository, dictionaryRepository) as T
    }
}
