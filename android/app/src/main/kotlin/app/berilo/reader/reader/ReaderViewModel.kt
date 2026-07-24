package app.berilo.reader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.store.repository.Book
import app.berilo.reader.store.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the [ReaderActivity] stub: loads the book by id and records the open.
 * Paginated Readium rendering is S2.2 — this only proves the library → reader
 * handoff and shows the title.
 */
class ReaderViewModel(
    private val repository: BookRepository,
    private val bookId: String,
) : ViewModel() {

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    init {
        viewModelScope.launch {
            _book.value = repository.getBook(bookId)
            repository.markOpened(bookId, System.currentTimeMillis())
        }
    }

    class Factory(
        private val repository: BookRepository,
        private val bookId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(repository, bookId) as T
    }
}
