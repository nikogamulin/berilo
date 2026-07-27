package app.berilo.reader.annotations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.store.db.HighlightColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val UI_STATE_TIMEOUT_MS = 5_000L

/**
 * The notebook screen's rendered state: the book title (export heading), its highlights grouped
 * by chapter, and its flagged translations (B9) grouped the same way — both in reading order.
 */
data class NotebookUiState(
    val bookTitle: String,
    val chapters: List<ChapterHighlights> = emptyList(),
    val flagChapters: List<ChapterFlags> = emptyList(),
) {
    /** True when the book has neither highlights nor flags, i.e. the empty state applies. A
     * property rather than a `chapters.isEmpty()` check at each call site, so adding a third
     * kind of entry later cannot leave one surface showing "nothing here" over real content. */
    val isEmpty: Boolean get() = chapters.isEmpty() && flagChapters.isEmpty()
}

/**
 * Backs the per-book notebook screen ([NotebookActivity]): streams [bookId]'s highlights from
 * [AnnotationsRepository] and its flagged translations from [TranslationFlagRepository], both
 * grouped by chapter, and exposes edit/delete/recolor actions plus a Markdown export of the
 * current snapshot.
 */
class NotebookViewModel(
    private val bookId: String,
    private val bookTitle: String,
    private val repository: AnnotationsRepository,
    private val flagRepository: TranslationFlagRepository,
) : ViewModel() {

    val uiState: StateFlow<NotebookUiState> =
        combine(
            repository.observeForBook(bookId),
            flagRepository.observeForBook(bookId),
        ) { highlights, flags ->
            NotebookUiState(bookTitle, groupByChapter(highlights), groupFlagsByChapter(flags))
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STATE_TIMEOUT_MS), NotebookUiState(bookTitle))

    /** Renders the current snapshot to Markdown ([MarkdownExporter]) for the share-sheet export. */
    fun exportMarkdown(): String =
        MarkdownExporter.export(uiState.value.bookTitle, uiState.value.chapters, uiState.value.flagChapters)

    /** Deletes a flagged translation (B9). Tombstoned, like every other user-created row. */
    fun deleteFlag(id: String) {
        viewModelScope.launch { flagRepository.delete(id) }
    }

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
        private val flagRepository: TranslationFlagRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NotebookViewModel(bookId, bookTitle, repository, flagRepository) as T
    }
}
