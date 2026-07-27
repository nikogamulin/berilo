package app.berilo.reader.annotations

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.berilo.reader.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B9's flag sheet: both variants the story asks for must be reachable from the same surface —
 * confirm with the field untouched (flag only) or with a suggestion typed (flag plus comment).
 *
 * Rendered at the Boox qualifier: at phone height a control can lay out below the fold and a
 * click then silently does nothing, with no error (`docs/findings.md`, B7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w990dp-h1319dp-xlarge-notlong-notround-any-227dpi-keyshidden-nonav")
class FlagEditorSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val composer =
        FlagEditorUiState.Composer(
            selectedText = "Geografija je usoda, ki je nihče ne izbere.",
            locatorJson = "{}",
            chapterTitle = "Poglavje ena",
        )

    private fun host(
        state: FlagEditorUiState = composer,
        onCommentChanged: (String) -> Unit = {},
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            FlagEditorHost(
                state = state,
                actions = FlagEditorActions(onCommentChanged, onConfirm, onDismiss),
            )
        }
    }

    private fun string(resId: Int) = composeRule.activity.getString(resId)

    @Test
    fun `the sheet shows the flagged passage back to the reader`() {
        host()

        // Without it the sheet is an unanchored dialog: by the time it opens the selection
        // highlight is gone, so this is the only confirmation that the right passage was caught.
        composeRule.onNodeWithText(composer.selectedText).assertExists()
        composeRule.onNodeWithText(string(R.string.flag_title)).assertExists()
    }

    @Test
    fun `confirming with the comment field untouched still reaches onConfirm`() {
        var confirmed = false
        host(onConfirm = { confirmed = true })

        // The flag-only variant. If the confirm button were gated on non-empty comment text,
        // "flagged as incorrect with no further input" would be unreachable.
        composeRule.onNodeWithText(string(R.string.flag_confirm))
            .performSemanticsAction(SemanticsActions.OnClick)

        assert(confirmed) { "a bare flag must be confirmable without typing anything" }
    }

    @Test
    fun `typing a suggestion reaches onCommentChanged`() {
        var lastComment: String? = null
        host(onCommentChanged = { lastComment = it })

        composeRule.onNodeWithText(string(R.string.flag_comment_label))
            .performTextInput("Raje »obala« kot »breg«.")

        assert(lastComment == "Raje »obala« kot »breg«.") { "expected the comment callback to fire, got $lastComment" }
    }

    @Test
    fun `the comment field is labelled optional`() {
        host()

        composeRule.onNodeWithText(string(R.string.flag_comment_optional)).assertExists()
    }

    @Test
    fun `cancel reaches onDismiss`() {
        var dismissed = false
        host(onDismiss = { dismissed = true })

        composeRule.onNodeWithText(string(R.string.annotation_cancel))
            .performSemanticsAction(SemanticsActions.OnClick)

        assert(dismissed)
    }

    @Test
    fun `an idle state renders nothing`() {
        host(state = FlagEditorUiState.Idle)

        composeRule.onNodeWithText(string(R.string.flag_title)).assertDoesNotExist()
    }
}
