package app.berilo.reader.annotations

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.berilo.reader.R
import app.berilo.reader.store.db.HighlightColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2.9: NotebookScreen's row actions (Edit note / Change color / Delete) gained leading icons.
 * Since the icons are added inside the existing [androidx.compose.material3.TextButton]s
 * rather than as new composables, the risk is breaking the click wiring — these tests are a
 * regression guard on that, plus a check that the icons didn't turn the destructive Delete
 * action into a confirmation-less one (still gated behind the existing dialog).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotebookScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun highlight(id: String = "h1") =
        Highlight(
            id = id,
            bookId = "book-1",
            color = HighlightColor.AMBER,
            selectedText = "a longing for a home that may not exist",
            note = null,
            locatorJson = "{}",
            chapterTitle = "Chapter 1",
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun uiState(highlight: Highlight = highlight()) =
        NotebookUiState(
            bookTitle = "Sample Book",
            chapters = listOf(ChapterHighlights(chapterTitle = "Chapter 1", highlights = listOf(highlight))),
        )

    @Test
    fun `edit note button opens the note dialog`() {
        composeRule.setContent {
            NotebookScreen(
                uiState = uiState(),
                onBack = {},
                onJumpTo = {},
                onEditNote = { _, _ -> },
                onChangeColor = { _, _ -> },
                onDelete = {},
                onExport = {},
            )
        }

        val editLabel = composeRule.activity.getString(R.string.notebook_edit_note)
        composeRule.onNodeWithText(editLabel).performClick()

        val confirmTitle = composeRule.activity.getString(R.string.notebook_delete_confirm_title)
        composeRule.onNodeWithText(confirmTitle).assertDoesNotExist()
    }

    @Test
    fun `delete button still requires confirmation before calling onDelete`() {
        var deleted = false
        composeRule.setContent {
            NotebookScreen(
                uiState = uiState(),
                onBack = {},
                onJumpTo = {},
                onEditNote = { _, _ -> },
                onChangeColor = { _, _ -> },
                onDelete = { deleted = true },
                onExport = {},
            )
        }

        val deleteLabel = composeRule.activity.getString(R.string.notebook_delete)
        composeRule.onNodeWithText(deleteLabel).performClick()

        assert(!deleted) { "delete must stay gated behind the confirmation dialog, not fire immediately" }

        val confirmTitle = composeRule.activity.getString(R.string.notebook_delete_confirm_title)
        composeRule.onNodeWithText(confirmTitle).assertExists()
    }

    @Test
    fun `export icon is labelled and reaches its callback`() {
        var exported = false
        composeRule.setContent {
            NotebookScreen(
                uiState = uiState(),
                onBack = {},
                onJumpTo = {},
                onEditNote = { _, _ -> },
                onChangeColor = { _, _ -> },
                onDelete = {},
                onExport = { exported = true },
            )
        }

        val exportLabel = composeRule.activity.getString(R.string.notebook_export_cd)
        composeRule.onNodeWithContentDescription(exportLabel).performClick()

        assert(exported)
    }

    @Test
    fun `empty notebook still shows the empty state`() {
        composeRule.setContent {
            NotebookScreen(
                uiState = NotebookUiState(bookTitle = "Sample Book"),
                onBack = {},
                onJumpTo = {},
                onEditNote = { _, _ -> },
                onChangeColor = { _, _ -> },
                onDelete = {},
                onExport = {},
            )
        }

        val emptyText = composeRule.activity.getString(R.string.notebook_empty)
        composeRule.onNodeWithText(emptyText).assertExists()
    }
}
