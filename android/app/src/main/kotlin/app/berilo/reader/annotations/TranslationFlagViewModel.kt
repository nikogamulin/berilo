package app.berilo.reader.annotations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State of the reader's "Bad translation" flow, driven off the navigator's current text
 * selection (captured by the host `ReaderActivity`, device territory).
 */
sealed interface FlagEditorUiState {
    /** No flagging in progress. */
    data object Idle : FlagEditorUiState

    /**
     * The selection has been captured and the sheet is open. One state serves both variants the
     * story asks for: confirming with [comment] still empty stores a bare flag, confirming with
     * text stores the suggestion alongside it. Two states would force the user to declare which
     * kind of flag they are making before they have decided.
     */
    data class Composer(
        val selectedText: String,
        val locatorJson: String,
        val chapterTitle: String?,
        val comment: String = "",
    ) : FlagEditorUiState
}

/**
 * Drives the reader's "Bad translation" selection action and persists the result (B9).
 *
 * Mirrors [HighlightViewModel]: no settings dependency, no LLM call, `€0` by construction — the
 * feature captures a signal, it does not act on it.
 */
class TranslationFlagViewModel(
    private val bookId: String,
    private val repository: TranslationFlagRepository,
) : ViewModel() {

    private val _editorState = MutableStateFlow<FlagEditorUiState>(FlagEditorUiState.Idle)
    val editorState: StateFlow<FlagEditorUiState> = _editorState.asStateFlow()

    /** Opens the sheet for a freshly captured selection. */
    fun beginFlag(selectedText: String, locatorJson: String, chapterTitle: String?) {
        _editorState.value = FlagEditorUiState.Composer(selectedText, locatorJson, chapterTitle)
    }

    /** Updates the in-progress comment; no-op outside [FlagEditorUiState.Composer]. */
    fun onCommentChanged(text: String) {
        (_editorState.value as? FlagEditorUiState.Composer)?.let {
            _editorState.value = it.copy(comment = text)
        }
    }

    /**
     * Persists the flag — with the typed comment if there is one, bare if there is not — and
     * closes the sheet. No-op outside [FlagEditorUiState.Composer].
     */
    fun confirmFlag() {
        val current = _editorState.value as? FlagEditorUiState.Composer ?: return
        viewModelScope.launch {
            repository.create(
                bookId = bookId,
                selectedText = current.selectedText,
                locatorJson = current.locatorJson,
                chapterTitle = current.chapterTitle,
                comment = current.comment,
            )
            _editorState.value = FlagEditorUiState.Idle
        }
    }

    /** Cancels without storing anything, discarding any typed comment. */
    fun dismissEditor() {
        _editorState.value = FlagEditorUiState.Idle
    }

    class Factory(
        private val bookId: String,
        private val repository: TranslationFlagRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranslationFlagViewModel(bookId, repository) as T
    }
}
