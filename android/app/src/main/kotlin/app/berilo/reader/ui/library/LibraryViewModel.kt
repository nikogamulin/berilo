package app.berilo.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.ImportOutcome
import app.berilo.reader.store.repository.Book
import app.berilo.reader.store.repository.BookRepository
import java.io.InputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val UI_STATE_TIMEOUT_MS = 5_000L

/** UI state for the library grid. */
data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && books.isEmpty()
}

/** One-shot events the UI reacts to (snackbars, dialogs) but doesn't hold as state. */
sealed interface LibraryEvent {
    data object Imported : LibraryEvent

    data object AlreadyInLibrary : LibraryEvent

    data class ImportFailed(val reason: String) : LibraryEvent
}

/**
 * Library screen view model: streams the book list from Room and orchestrates
 * SAF imports through [BookImporter]. Deliberately holds no [android.net.Uri]
 * or [android.content.ContentResolver] — the caller supplies a factory that
 * opens the picked document, which keeps this class host-JVM testable without
 * Robolectric.
 *
 * The factory is deliberately not an already-open [InputStream]: imports run in
 * [viewModelScope] and outlive the picker callback, so a caller that opened the
 * stream itself would close it before the first read (see [importBook]).
 */
class LibraryViewModel(
    private val repository: BookRepository,
    private val importer: BookImporter,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> =
        repository
            .observeBooks()
            .map { books -> LibraryUiState(books = books, isLoading = false) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(UI_STATE_TIMEOUT_MS),
                initialValue = LibraryUiState(isLoading = true),
            )

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LibraryEvent> = _events

    fun importBook(openStream: () -> InputStream?, suggestedFileName: String) {
        viewModelScope.launch {
            when (val outcome = importer.import(openStream, suggestedFileName)) {
                is ImportOutcome.Imported -> _events.emit(LibraryEvent.Imported)
                is ImportOutcome.Duplicate -> _events.emit(LibraryEvent.AlreadyInLibrary)
                is ImportOutcome.Failed -> _events.emit(LibraryEvent.ImportFailed(outcome.reason))
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch { repository.deleteBook(book) }
    }

    class Factory(
        private val repository: BookRepository,
        private val importer: BookImporter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repository, importer) as T
    }
}
