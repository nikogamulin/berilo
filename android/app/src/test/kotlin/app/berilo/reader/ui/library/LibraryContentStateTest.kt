package app.berilo.reader.ui.library

import app.berilo.reader.store.repository.Book
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryContentStateTest {
    private val book =
        Book(
            id = "1",
            title = "Sandworm",
            authors = "Andy Greenberg",
            filePath = "/books/sandworm.epub",
            coverPath = null,
            addedAt = 0L,
            lastOpenedAt = null,
            progressFraction = null,
        )

    @Test
    fun `a loading library never falls into the empty state`() {
        // Before this fix, LibraryUiState(isLoading = true, books = emptyList())
        // rendered the book grid (silently, with zero items) because the old
        // `when` only branched on isEmpty, which is false while isLoading is
        // true. That left the loader with no visible feedback at all.
        val loading = LibraryUiState(books = emptyList(), isLoading = true)

        assertEquals(LibraryContentState.LOADING, libraryContentState(loading))
    }

    @Test
    fun `an empty, finished load shows the empty state`() {
        val empty = LibraryUiState(books = emptyList(), isLoading = false)

        assertEquals(LibraryContentState.EMPTY, libraryContentState(empty))
    }

    @Test
    fun `a finished load with books shows the grid`() {
        val populated = LibraryUiState(books = listOf(book), isLoading = false)

        assertEquals(LibraryContentState.BOOKS, libraryContentState(populated))
    }

    @Test
    fun `books present while still loading still reports loading`() {
        // Guards against a future refactor that infers the state from
        // book-list emptiness alone instead of the explicit isLoading flag.
        val loadingWithStaleBooks = LibraryUiState(books = listOf(book), isLoading = true)

        assertEquals(LibraryContentState.LOADING, libraryContentState(loadingWithStaleBooks))
    }
}
