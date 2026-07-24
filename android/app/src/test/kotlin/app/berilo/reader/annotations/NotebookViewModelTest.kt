package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Same `Dispatchers.setMain`/`resetMain` pattern as `DictionaryViewModelTest`. `uiState` is
 * `stateIn(..., SharingStarted.WhileSubscribed(...))`: it only starts collecting the
 * repository Flow while subscribed, so every test that reads it starts a no-op background
 * collector first, then asserts on `.value` after `advanceUntilIdle()` — collecting into a
 * list and reading the list's history is unreliable here (a StateFlow only guarantees its
 * *latest* value reaches a collector, not every intermediate one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotebookViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(dao: FakeHighlightDao) =
        AnnotationsRepository(dao = dao, ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler))

    @Test
    fun `uiState groups highlights by chapter in reading order`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            repo.create("book-1", HighlightColor.AMBER, "prva", "{}", "Poglavje ena")
            repo.create("book-1", HighlightColor.SAGE, "druga", "{}", "Poglavje dve")

            val viewModel = NotebookViewModel("book-1", "Testna knjiga", repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val latest = viewModel.uiState.value
            assertEquals("Testna knjiga", latest.bookTitle)
            assertEquals(listOf("Poglavje ena", "Poglavje dve"), latest.chapters.map { it.chapterTitle })
        }

    @Test
    fun `uiState excludes highlights from other books`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            repo.create("book-1", HighlightColor.AMBER, "mine", "{}", "Poglavje ena")
            repo.create("book-2", HighlightColor.AMBER, "not mine", "{}", "Poglavje ena")

            val viewModel = NotebookViewModel("book-1", "Testna knjiga", repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val allTexts = viewModel.uiState.value.chapters.flatMap { it.highlights }.map { it.selectedText }
            assertEquals(listOf("mine"), allTexts)
        }

    @Test
    fun `updateNote persists through the repository`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)
            val viewModel = NotebookViewModel("book-1", "Knjiga", repo)

            viewModel.updateNote(id, "nova opomba")
            advanceUntilIdle()

            assertEquals("nova opomba", dao.getById(id)?.note)
        }

    @Test
    fun `updateColor persists through the repository`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)
            val viewModel = NotebookViewModel("book-1", "Knjiga", repo)

            viewModel.updateColor(id, HighlightColor.ROSE)
            advanceUntilIdle()

            assertEquals(HighlightColor.ROSE, dao.getById(id)?.color)
        }

    @Test
    fun `delete removes the row through the repository`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)
            val viewModel = NotebookViewModel("book-1", "Knjiga", repo)

            viewModel.delete(id)
            advanceUntilIdle()

            assertNull(dao.getById(id))
        }

    @Test
    fun `exportMarkdown renders the current snapshot via MarkdownExporter`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            repo.create("book-1", HighlightColor.AMBER, "izbrana vrstica", "{}", "Poglavje ena")
            val viewModel = NotebookViewModel("book-1", "Testna knjiga", repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val markdown = viewModel.exportMarkdown()

            assertEquals(
                "# Testna knjiga\n\n## Poglavje ena\n\n> izbrana vrstica\n",
                markdown,
            )
        }
}
