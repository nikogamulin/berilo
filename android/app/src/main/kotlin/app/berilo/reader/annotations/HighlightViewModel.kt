package app.berilo.reader.annotations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.store.db.HighlightColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the [HighlightViewModel.highlights] `StateFlow` keeps collecting after the last
 * subscriber goes away — long enough to survive a config change, matching
 * `LibraryViewModel`/`InterpretationViewModel`'s convention. */
private const val HIGHLIGHTS_STATE_TIMEOUT_MS = 5_000L

/** Default color offered when the "Note" action opens with no prior color choice. */
private val DEFAULT_NOTE_COLOR = HighlightColor.AMBER

/**
 * State of the reader's highlight/note creation flow, driven off the navigator's current text
 * selection (captured by the host `ReaderActivity`, device territory).
 */
sealed interface AnnotationEditorUiState {
    /** No creation in progress. */
    data object Idle : AnnotationEditorUiState

    /** The "Highlight" action was tapped: a color row is shown, tapping a color creates the
     * highlight immediately (no note text). */
    data class ColorPicker(val selectedText: String, val locatorJson: String, val chapterTitle: String?) :
        AnnotationEditorUiState

    /** The "Note" action was tapped: a color + text-field editor is shown; [confirmNote]
     * persists both together. */
    data class NoteEditor(
        val selectedText: String,
        val locatorJson: String,
        val chapterTitle: String?,
        val color: HighlightColor,
        val noteText: String,
    ) : AnnotationEditorUiState
}

/**
 * Drives the reader's "Highlight"/"Note" chrome actions and the live highlight list the host
 * renders as Readium decorations. Mirrors [app.berilo.reader.dictionary.DictionaryViewModel]'s
 * shape (settings-free here — highlighting has no LLM/model dependency, per the story's `€0`
 * bar) with an `editorState` machine instead of a single result state, since creation is a
 * two-step flow (capture selection → pick color / write note).
 */
class HighlightViewModel(
    private val bookId: String,
    private val repository: AnnotationsRepository,
) : ViewModel() {

    /** Live highlights for [bookId], the host's source for `applyDecorations` (device
     * territory — kept thin per the story's scope note). */
    val highlights: StateFlow<List<Highlight>> =
        repository.observeForBook(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(HIGHLIGHTS_STATE_TIMEOUT_MS), emptyList())

    private val _editorState = MutableStateFlow<AnnotationEditorUiState>(AnnotationEditorUiState.Idle)
    val editorState: StateFlow<AnnotationEditorUiState> = _editorState.asStateFlow()

    /** Opens the color-picker row for a freshly captured selection ("Highlight" action). */
    fun beginHighlight(selectedText: String, locatorJson: String, chapterTitle: String?) {
        _editorState.value = AnnotationEditorUiState.ColorPicker(selectedText, locatorJson, chapterTitle)
    }

    /** Opens the note editor for a freshly captured selection ("Note" action). */
    fun beginNote(selectedText: String, locatorJson: String, chapterTitle: String?) {
        _editorState.value =
            AnnotationEditorUiState.NoteEditor(selectedText, locatorJson, chapterTitle, DEFAULT_NOTE_COLOR, "")
    }

    /** Changes the color while the note editor is open; no-op outside [AnnotationEditorUiState.NoteEditor]. */
    fun onNoteColorChanged(color: HighlightColor) {
        (_editorState.value as? AnnotationEditorUiState.NoteEditor)?.let {
            _editorState.value = it.copy(color = color)
        }
    }

    /** Updates the in-progress note text; no-op outside [AnnotationEditorUiState.NoteEditor]. */
    fun onNoteTextChanged(text: String) {
        (_editorState.value as? AnnotationEditorUiState.NoteEditor)?.let {
            _editorState.value = it.copy(noteText = text)
        }
    }

    /** Persists a plain highlight in [color] from [AnnotationEditorUiState.ColorPicker]; no-op
     * in any other state. */
    fun confirmColor(color: HighlightColor) {
        val current = _editorState.value as? AnnotationEditorUiState.ColorPicker ?: return
        viewModelScope.launch {
            repository.create(bookId, color, current.selectedText, current.locatorJson, current.chapterTitle)
            _editorState.value = AnnotationEditorUiState.Idle
        }
    }

    /** Persists the highlight + note text from [AnnotationEditorUiState.NoteEditor]; no-op in
     * any other state. */
    fun confirmNote() {
        val current = _editorState.value as? AnnotationEditorUiState.NoteEditor ?: return
        viewModelScope.launch {
            repository.create(
                bookId = bookId,
                color = current.color,
                selectedText = current.selectedText,
                locatorJson = current.locatorJson,
                chapterTitle = current.chapterTitle,
                noteText = current.noteText,
            )
            _editorState.value = AnnotationEditorUiState.Idle
        }
    }

    /** Cancels the in-progress creation, discarding any typed note text. */
    fun dismissEditor() {
        _editorState.value = AnnotationEditorUiState.Idle
    }

    class Factory(
        private val bookId: String,
        private val repository: AnnotationsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HighlightViewModel(bookId, repository) as T
    }
}
