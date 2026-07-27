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

    // --- B9: flagged translations ------------------------------------------------------

    private fun flag(
        id: String = "f1",
        comment: String? = "Raje »obala« kot »breg«.",
        provenance: TranslationProvenance? =
            TranslationProvenance("bookhash1", "seghash1", "gpt-5-mini", "sl", "revise_v1", "glosshash1"),
    ) =
        TranslationFlag(
            id = id,
            bookId = "book-1",
            selectedText = "Geografija je usoda, ki je nihče ne izbere.",
            comment = comment,
            locatorJson = "{}",
            chapterTitle = "Chapter 1",
            provenance = provenance,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun stateWithFlag(flag: TranslationFlag = flag()) =
        NotebookUiState(
            bookTitle = "Sample Book",
            flagChapters = listOf(ChapterFlags(chapterTitle = "Chapter 1", flags = listOf(flag))),
        )

    private fun renderFlags(state: NotebookUiState, onDeleteFlag: (TranslationFlag) -> Unit = {}) {
        composeRule.setContent {
            NotebookScreen(
                uiState = state,
                onBack = {},
                onJumpTo = {},
                onEditNote = { _, _ -> },
                onChangeColor = { _, _ -> },
                onDelete = {},
                onExport = {},
                onJumpToFlag = {},
                onDeleteFlag = onDeleteFlag,
            )
        }
    }

    @Config(sdk = [35], qualifiers = BOOX_QUALIFIER)
    @Test
    fun `a flag is shown under its own heading, badged and carrying its provenance`() {
        renderFlags(stateWithFlag())

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.notebook_flags_heading)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.notebook_flag_badge)).assertExists()
        composeRule.onNodeWithText("Geografija je usoda, ki je nihče ne izbere.").assertExists()
        composeRule.onNodeWithText("Raje »obala« kot »breg«.").assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.notebook_flag_provenance, "gpt-5-mini", "revise_v1", "seghash1"),
        ).assertExists()
    }

    @Config(sdk = [35], qualifiers = BOOX_QUALIFIER)
    @Test
    fun `an unmatched flag says so rather than showing a blank provenance line`() {
        renderFlags(stateWithFlag(flag(comment = null, provenance = null)))

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.notebook_flag_no_provenance)).assertExists()
    }

    @Config(sdk = [35], qualifiers = BOOX_QUALIFIER)
    @Test
    fun `a book with only flags is not shown as empty`() {
        renderFlags(stateWithFlag())

        // Reading `chapters.isEmpty()` instead of `isEmpty` would print "no highlights or
        // notes yet" over a list of real flags.
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.notebook_empty)).assertDoesNotExist()
    }

    @Config(sdk = [35], qualifiers = BOOX_QUALIFIER)
    @Test
    fun `deleting a flag requires confirmation`() {
        var deleted = false
        renderFlags(stateWithFlag(), onDeleteFlag = { deleted = true })

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.notebook_delete)).performClick()

        assert(!deleted) { "deleting a flag must stay gated behind the confirmation dialog" }
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.notebook_delete_flag_confirm_title),
        ).assertExists()
    }
}

/** Boox Tab Ultra, spelled out because `@Config` needs a compile-time constant. B9's rows sit
 * at the bottom of the notebook list, and at phone height a control below the fold makes
 * `performClick` silently do nothing (`docs/findings.md`, B7). */
private const val BOOX_QUALIFIER = "w990dp-h1319dp-xlarge-notlong-notround-any-227dpi-keyshidden-nonav"
