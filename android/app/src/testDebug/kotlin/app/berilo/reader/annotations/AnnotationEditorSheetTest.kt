package app.berilo.reader.annotations

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.berilo.reader.R
import app.berilo.reader.store.db.HighlightColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2.9: [NoteEditorSheet] deliberately did NOT gain an icon+title header (unlike
 * DictionarySheet/InterpretationSheet) — the note field below already labels itself "Note", and
 * Cancel/Save already cover dismissal, so there was no gap to close. What did change: the color
 * swatches ([ColorSwatch]) gained real accessible labels (`selectable` + a content description)
 * where previously there was none at all. These tests cover that, plus a regression check on
 * the existing Cancel/Save/note-text wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnnotationEditorSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `note editor save button reaches onConfirmNote`() {
        var confirmed = false
        composeRule.setContent {
            AnnotationEditorHost(
                state =
                    AnnotationEditorUiState.NoteEditor(
                        selectedText = "a longing for a home that may not exist",
                        locatorJson = "{}",
                        chapterTitle = null,
                        color = HighlightColor.AMBER,
                        noteText = "",
                    ),
                actions =
                    AnnotationEditorActions(
                        onColorSelected = {},
                        onNoteColorChanged = {},
                        onNoteTextChanged = {},
                        onConfirmNote = { confirmed = true },
                        onDismiss = {},
                    ),
            )
        }

        // Direct semantics-action invocation, not a simulated touch — see
        // DictionarySheetTest for why (ModalBottomSheet's reveal animation under Robolectric).
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.annotation_save))
            .performSemanticsAction(SemanticsActions.OnClick)

        assert(confirmed)
    }

    @Test
    fun `note editor text field reaches onNoteTextChanged`() {
        var lastText: String? = null
        composeRule.setContent {
            AnnotationEditorHost(
                state =
                    AnnotationEditorUiState.NoteEditor(
                        selectedText = "a longing for a home that may not exist",
                        locatorJson = "{}",
                        chapterTitle = null,
                        color = HighlightColor.AMBER,
                        noteText = "",
                    ),
                actions =
                    AnnotationEditorActions(
                        onColorSelected = {},
                        onNoteColorChanged = {},
                        onNoteTextChanged = { lastText = it },
                        onConfirmNote = {},
                        onDismiss = {},
                    ),
            )
        }

        val noteLabel = composeRule.activity.getString(R.string.annotation_note_label)
        composeRule.onNodeWithText(noteLabel).performTextInput("Reads like Cynan Jones.")

        assert(lastText == "Reads like Cynan Jones.") { "expected the note text callback to fire, got $lastText" }
    }

    @Test
    fun `color picker row selecting a color reaches onColorSelected`() {
        var selected: HighlightColor? = null
        composeRule.setContent {
            AnnotationEditorHost(
                state = AnnotationEditorUiState.ColorPicker(selectedText = "text", locatorJson = "{}", chapterTitle = null),
                actions =
                    AnnotationEditorActions(
                        onColorSelected = { selected = it },
                        onNoteColorChanged = {},
                        onNoteTextChanged = {},
                        onConfirmNote = {},
                        onDismiss = {},
                    ),
            )
        }

        // S2.9: swatches previously had no accessible label at all; they now expose a
        // "<Color> highlight color" description, which doubles as this test's selector.
        val sageLabel = composeRule.activity.getString(R.string.highlight_color_cd, "Sage")
        composeRule.onNodeWithContentDescription(sageLabel).performClick()

        assert(selected == HighlightColor.SAGE) { "expected onColorSelected(SAGE), got $selected" }
    }

    @Test
    fun `color picker row cancel reaches onDismiss`() {
        var dismissed = false
        composeRule.setContent {
            AnnotationEditorHost(
                state = AnnotationEditorUiState.ColorPicker(selectedText = "text", locatorJson = "{}", chapterTitle = null),
                actions =
                    AnnotationEditorActions(
                        onColorSelected = {},
                        onNoteColorChanged = {},
                        onNoteTextChanged = {},
                        onConfirmNote = {},
                        onDismiss = { dismissed = true },
                    ),
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.annotation_cancel)).performClick()

        assert(dismissed)
    }

    @Test
    fun `note editor's selected swatch is announced as selected`() {
        composeRule.setContent {
            AnnotationEditorHost(
                state =
                    AnnotationEditorUiState.NoteEditor(
                        selectedText = "text",
                        locatorJson = "{}",
                        chapterTitle = null,
                        color = HighlightColor.SKY,
                        noteText = "",
                    ),
                actions =
                    AnnotationEditorActions(
                        onColorSelected = {},
                        onNoteColorChanged = {},
                        onNoteTextChanged = {},
                        onConfirmNote = {},
                        onDismiss = {},
                    ),
            )
        }

        val selectedSkyLabel = composeRule.activity.getString(R.string.highlight_color_selected_cd, "Sky")
        composeRule.onNodeWithContentDescription(selectedSkyLabel).assertExists()
    }
}
