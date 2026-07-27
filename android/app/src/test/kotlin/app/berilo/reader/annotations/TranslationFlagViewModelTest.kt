package app.berilo.reader.annotations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B9's flag editor state machine, over an in-memory DAO — the same shape
 * [NotebookViewModelTest] uses. Room itself is exercised by `TranslationFlagDaoTest` and by the
 * end-to-end walk; a real database here would only add Room's own executor to the mix, which
 * `runTest`'s virtual clock does not own (`advanceUntilIdle` returns while the insert is still
 * in flight, and the assertion reads a row that has not landed yet).
 *
 * `Dispatchers.setMain` is not optional: without it `viewModelScope.launch` failures are
 * silently swallowed and a broken [TranslationFlagViewModel.confirmFlag] would look like a
 * passing test (`docs/findings.md`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationFlagViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeTranslationFlagDao()
    private lateinit var repository: TranslationFlagRepository
    private lateinit var viewModel: TranslationFlagViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository =
            TranslationFlagRepository(
                dao = dao,
                // Provenance has its own suite against the real cache; what matters here is
                // that the flag stores either way, so the never-matching resolver is the
                // stricter of the two stand-ins.
                provenanceResolver = NoProvenance,
                ioDispatcher = UnconfinedTestDispatcher(dispatcher.scheduler),
                clock = { 5_000L },
                idGenerator = { "flag-1" },
            )
        viewModel = TranslationFlagViewModel("book-1", repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `begins idle`() =
        runTest(dispatcher) {
            assertEquals(FlagEditorUiState.Idle, viewModel.editorState.value)
        }

    @Test
    fun `beginFlag opens the composer with the captured selection`() =
        runTest(dispatcher) {
            viewModel.beginFlag("slab prevod", "{\"href\":\"/c1\"}", "Poglavje ena")

            assertEquals(
                FlagEditorUiState.Composer("slab prevod", "{\"href\":\"/c1\"}", "Poglavje ena", comment = ""),
                viewModel.editorState.value,
            )
        }

    @Test
    fun `confirming with no comment stores a bare flag and closes the sheet`() =
        runTest(dispatcher) {
            viewModel.beginFlag("slab prevod", "{}", "Poglavje ena")

            viewModel.confirmFlag()
            advanceUntilIdle()

            val stored = dao.getById("flag-1")
            assertEquals("slab prevod", stored?.selectedText)
            assertNull("an untouched comment field must store as null, not as empty text", stored?.comment)
            assertEquals(5_000L, stored?.createdAt)
            assertEquals(5_000L, stored?.updatedAt)
            assertNull(stored?.deletedAt)
            assertEquals(FlagEditorUiState.Idle, viewModel.editorState.value)
        }

    @Test
    fun `confirming with a comment stores the suggestion alongside the passage`() =
        runTest(dispatcher) {
            viewModel.beginFlag("slab prevod", "{}", "Poglavje ena")
            viewModel.onCommentChanged("Raje »obala« kot »breg«.")

            viewModel.confirmFlag()
            advanceUntilIdle()

            assertEquals("Raje »obala« kot »breg«.", dao.getById("flag-1")?.comment)
        }

    @Test
    fun `a whitespace-only comment stores as no comment`() =
        runTest(dispatcher) {
            viewModel.beginFlag("slab prevod", "{}", null)
            viewModel.onCommentChanged("   \n ")

            viewModel.confirmFlag()
            advanceUntilIdle()

            assertNull(dao.getById("flag-1")?.comment)
        }

    @Test
    fun `dismissing stores nothing`() =
        runTest(dispatcher) {
            viewModel.beginFlag("slab prevod", "{}", null)
            viewModel.onCommentChanged("nekaj besedila")

            viewModel.dismissEditor()
            advanceUntilIdle()

            assertEquals(FlagEditorUiState.Idle, viewModel.editorState.value)
            assertTrue(repository.observeForBook("book-1").first().isEmpty())
        }

    @Test
    fun `confirming outside the composer is a no-op, not a blank row`() =
        runTest(dispatcher) {
            viewModel.confirmFlag()
            advanceUntilIdle()

            assertTrue(repository.observeForBook("book-1").first().isEmpty())
        }

    @Test
    fun `onCommentChanged outside the composer does not resurrect it`() =
        runTest(dispatcher) {
            viewModel.onCommentChanged("besedilo")

            assertEquals(FlagEditorUiState.Idle, viewModel.editorState.value)
        }
}
