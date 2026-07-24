package app.berilo.reader.annotations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.store.db.HighlightColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val UI_STATE_TIMEOUT_MS = 5_000L

/** The notebook screen's rendered state: the book title (export heading) and its highlights
 * grouped by chapter, in reading order. */
data class NotebookUiState(val bookTitle: String, val chapters: List<ChapterHighlights> = emptyList())

/**
 * Backs the per-book notebook screen ([NotebookActivity]): streams [bookId]'s highlights from
 * [AnnotationsRepository], grouped by chapter, and exposes edit/delete/recolor actions plus a
 * Markdown export of the current snapshot.
 */
class NotebookViewModel(
    private val bookId: String,
    private val bookTitle: String,
    private val repository: AnnotationsRepository,
) : ViewModel() {

    val uiState: StateFlow<NotebookUiState> =
        repository.observeForBook(bookId)
            .map { highlights -> NotebookUiState(bookTitle, groupByChapter(highlights)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STATE_TIMEOUT_MS), NotebookUiState(bookTitle))

    /** Renders the current snapshot to Markdown ([MarkdownExporter]) for the share-sheet export. */
    fun exportMarkdown(): String = MarkdownExporter.export(uiState.value.bookTitle, uiState.value.chapters)

    /** Edits [id]'s note text (blank clears it, keeping the highlight). */
    fun updateNote(id: String, text: String) {
        viewModelScope.launch { repository.updateNote(id, text) }
    }

    /** Recolors [id]. */
    fun updateColor(id: String, color: HighlightColor) {
        viewModelScope.launch { repository.updateColor(id, color) }
    }

    /** Deletes [id]. The caller (screen) is responsible for the confirm dialog — deletion here
     * is unconditional, matching `AnnotationsRepository.delete`. */
    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    class Factory(
        private val bookId: String,
        private val bookTitle: String,
        private val repository: AnnotationsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NotebookViewModel(bookId, bookTitle, repository) as T
    }
}
