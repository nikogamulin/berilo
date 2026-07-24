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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Same `Dispatchers.setMain`/`resetMain` pattern as `DictionaryViewModelTest` —
 * `viewModelScope.launch` failures are silently swallowed without it (`docs/findings.md`). */
@OptIn(ExperimentalCoroutinesApi::class)
class HighlightViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(dao: FakeHighlightDao = FakeHighlightDao()) =
        AnnotationsRepository(dao = dao, ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler))

    @Test
    fun `initial editor state is Idle`() {
        val viewModel = HighlightViewModel("book-1", repository())

        assertEquals(AnnotationEditorUiState.Idle, viewModel.editorState.value)
    }

    @Test
    fun `beginHighlight opens the color picker with the captured selection`() {
        val viewModel = HighlightViewModel("book-1", repository())

        viewModel.beginHighlight("a line of text", "{\"href\":\"c1\"}", "Chapter One")

        assertEquals(
            AnnotationEditorUiState.ColorPicker("a line of text", "{\"href\":\"c1\"}", "Chapter One"),
            viewModel.editorState.value,
        )
    }

    @Test
    fun `confirmColor persists a plain highlight and returns to Idle`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val viewModel = HighlightViewModel("book-1", repository(dao))
            viewModel.beginHighlight("a line of text", "{}", "Chapter One")

            viewModel.confirmColor(HighlightColor.SKY)
            advanceUntilIdle()

            assertEquals(AnnotationEditorUiState.Idle, viewModel.editorState.value)
            assertEquals(1, dao.count())
        }

    @Test
    fun `confirmColor outside ColorPicker state is a no-op`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val viewModel = HighlightViewModel("book-1", repository(dao))

            viewModel.confirmColor(HighlightColor.SKY)
            advanceUntilIdle()

            assertEquals(0, dao.count())
        }

    @Test
    fun `beginNote opens the note editor with the default color and empty text`() {
        val viewModel = HighlightViewModel("book-1", repository())

        viewModel.beginNote("quoted passage", "{}", null)

        val state = viewModel.editorState.value
        assertTrue(state is AnnotationEditorUiState.NoteEditor)
        state as AnnotationEditorUiState.NoteEditor
        assertEquals("quoted passage", state.selectedText)
        assertEquals("", state.noteText)
    }

    @Test
    fun `onNoteTextChanged and onNoteColorChanged update the in-progress NoteEditor`() {
        val viewModel = HighlightViewModel("book-1", repository())
        viewModel.beginNote("quoted passage", "{}", null)

        viewModel.onNoteTextChanged("a thought")
        viewModel.onNoteColorChanged(HighlightColor.ROSE)

        val state = viewModel.editorState.value as AnnotationEditorUiState.NoteEditor
        assertEquals("a thought", state.noteText)
        assertEquals(HighlightColor.ROSE, state.color)
    }

    @Test
    fun `confirmNote persists the highlight with note text and returns to Idle`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val viewModel = HighlightViewModel("book-1", repository(dao))
            viewModel.beginNote("quoted passage", "{}", "Chapter One")
            viewModel.onNoteTextChanged("a thought")

            viewModel.confirmNote()
            advanceUntilIdle()

            assertEquals(AnnotationEditorUiState.Idle, viewModel.editorState.value)
            val stored = dao.count()
            assertEquals(1, stored)
        }

    @Test
    fun `dismissEditor discards the in-progress note without persisting`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val viewModel = HighlightViewModel("book-1", repository(dao))
            viewModel.beginNote("quoted passage", "{}", null)
            viewModel.onNoteTextChanged("a thought")

            viewModel.dismissEditor()
            advanceUntilIdle()

            assertEquals(AnnotationEditorUiState.Idle, viewModel.editorState.value)
            assertEquals(0, dao.count())
        }

    @Test
    fun `highlights reflects newly created rows for this book`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val viewModel = HighlightViewModel("book-1", repo)
            // `highlights` is `stateIn(..., WhileSubscribed(...))`: it only starts collecting
            // the repository Flow while someone is collecting it, so a background collector
            // is required before `.value` reflects anything beyond the seed empty list.
            backgroundScope.launch { viewModel.highlights.collect {} }
            advanceUntilIdle()

            viewModel.beginHighlight("text", "{}", null)
            viewModel.confirmColor(HighlightColor.AMBER)
            advanceUntilIdle()

            assertEquals(1, viewModel.highlights.value.size)
            assertEquals("text", viewModel.highlights.value.first().selectedText)
        }
}
