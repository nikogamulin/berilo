package app.berilo.reader.reader

import app.berilo.reader.annotations.AnnotationEditorUiState
import app.berilo.reader.annotations.FlagEditorUiState
import app.berilo.reader.dictionary.DictionaryErrorKind
import app.berilo.reader.dictionary.DictionaryUiState
import app.berilo.reader.interpretation.InterpretationErrorKind
import app.berilo.reader.interpretation.InterpretationUiState
import app.berilo.reader.store.db.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2.12: the reader's chrome `ComposeView` also hosts the annotation editor and the
 * dictionary/interpretation sheets. Before S2.12 its visibility tracked `chromeVisible`
 * alone, which was harmless only because every action that opens a sheet lived *in* the
 * chrome. Now that actions fire from the text selection — with chrome hidden — a sheet
 * would open inside a `GONE` view. This predicate is what keeps that from happening.
 */
class ReaderOverlayVisibilityTest {

    private val colorPicker =
        AnnotationEditorUiState.ColorPicker(selectedText = "sandworm", locatorJson = "{}", chapterTitle = "Ch 4")

    private val noteEditor =
        AnnotationEditorUiState.NoteEditor(
            selectedText = "sandworm",
            locatorJson = "{}",
            chapterTitle = "Ch 4",
            color = HighlightColor.AMBER,
            noteText = "",
        )

    private val flagComposer =
        FlagEditorUiState.Composer(selectedText = "sandworm", locatorJson = "{}", chapterTitle = "Ch 4")

    private fun visible(
        chromeVisible: Boolean = false,
        dictionary: DictionaryUiState = DictionaryUiState.Idle,
        interpretation: InterpretationUiState = InterpretationUiState.Idle,
        editor: AnnotationEditorUiState = AnnotationEditorUiState.Idle,
        flagEditor: FlagEditorUiState = FlagEditorUiState.Idle,
    ) = readerOverlayVisible(chromeVisible, dictionary, interpretation, editor, flagEditor)

    @Test
    fun `hidden while reading with nothing open`() {
        assertFalse(visible())
    }

    @Test
    fun `visible when the chrome bars are shown`() {
        assertTrue(visible(chromeVisible = true))
    }

    @Test
    fun `visible for the colour picker with chrome hidden`() {
        assertTrue(visible(editor = colorPicker))
    }

    @Test
    fun `visible for the note editor with chrome hidden`() {
        assertTrue(visible(editor = noteEditor))
    }

    @Test
    fun `visible for every non-idle dictionary state with chrome hidden`() {
        assertTrue(visible(dictionary = DictionaryUiState.Loading("sandworm")))
        assertTrue(
            visible(dictionary = DictionaryUiState.Error("sandworm", DictionaryErrorKind.AUTH, "no key")),
        )
    }

    @Test
    fun `visible for every non-idle interpretation state with chrome hidden`() {
        assertTrue(visible(interpretation = InterpretationUiState.Loading))
        assertTrue(visible(interpretation = InterpretationUiState.Error(InterpretationErrorKind.AUTH, "no key")))
    }

    @Test
    fun `visible for the flag editor with chrome hidden`() {
        // B9's sheet opens from the selection popup, so chrome is hidden by definition. Left
        // out of the predicate, it would compose inside a GONE view: the user taps "Bad
        // translation", nothing appears, and the gesture reads as broken — the exact S2.6
        // failure this predicate exists to prevent (CLAUDE.md §9).
        assertTrue(visible(flagEditor = flagComposer))
    }

    @Test
    fun `hidden again once every surface returns to idle`() {
        assertFalse(
            visible(
                chromeVisible = false,
                dictionary = DictionaryUiState.Idle,
                interpretation = InterpretationUiState.Idle,
                editor = AnnotationEditorUiState.Idle,
                flagEditor = FlagEditorUiState.Idle,
            ),
        )
    }

    @Test
    fun `a scrim tap dismisses the colour picker instead of summoning chrome`() {
        // The inline colour row is not a modal sheet — without this the only full-screen
        // target behind it toggles chrome on, leaving the picker stranded on top of it.
        assertEquals(ScrimTap.DISMISS_ANNOTATION_EDITOR, scrimTapAction(chromeVisible = false, editor = colorPicker))
    }

    @Test
    fun `a scrim tap toggles chrome in every other state`() {
        assertEquals(
            ScrimTap.TOGGLE_CHROME,
            scrimTapAction(chromeVisible = false, editor = AnnotationEditorUiState.Idle),
        )
        assertEquals(ScrimTap.TOGGLE_CHROME, scrimTapAction(chromeVisible = true, editor = colorPicker))
        // The note editor is a ModalBottomSheet: it brings its own scrim and dismiss handling.
        assertEquals(ScrimTap.TOGGLE_CHROME, scrimTapAction(chromeVisible = false, editor = noteEditor))
    }
}
