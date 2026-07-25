package app.berilo.reader.interpretation

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.berilo.reader.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2.9: mirrors [app.berilo.reader.dictionary.DictionarySheetTest] — the interpretation sheet
 * had the same missing-dismiss-affordance gap as the dictionary sheet, fixed with the same
 * icon+close header pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InterpretationSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `close button is labelled and dismisses the sheet`() {
        var dismissed = false

        composeRule.setContent {
            InterpretationSheet(uiState = InterpretationUiState.Loading, onDismiss = { dismissed = true })
        }

        composeRule.waitForIdle()
        val dismissLabel = composeRule.activity.getString(R.string.interpretation_dismiss_cd)
        composeRule.onNodeWithContentDescription(dismissLabel).assertExists()
        // Direct semantics-action invocation, not a simulated touch — see
        // DictionarySheetTest for why (ModalBottomSheet's reveal animation under Robolectric).
        composeRule.onNodeWithContentDescription(dismissLabel).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assert(dismissed) { "expected onDismiss to be invoked by the header's close button" }
    }

    @Test
    fun `header title renders for the loading state`() {
        composeRule.setContent {
            InterpretationSheet(uiState = InterpretationUiState.Loading, onDismiss = {})
        }

        val title = composeRule.activity.getString(R.string.interpretation_title)
        composeRule.onNodeWithText(title).assertExists()
    }

    @Test
    fun `header title renders for a success state`() {
        composeRule.setContent {
            InterpretationSheet(
                uiState =
                    InterpretationUiState.Success(
                        text = "The passage contrasts the character's public and private selves.",
                        passage = "He smiled for the crowd and, alone, let it fall.",
                        costEur = 0.0002,
                        fromCache = false,
                    ),
                onDismiss = {},
            )
        }

        val title = composeRule.activity.getString(R.string.interpretation_title)
        composeRule.onNodeWithText(title).assertExists()
    }

    @Test
    fun `idle state renders no sheet content`() {
        composeRule.setContent {
            InterpretationSheet(uiState = InterpretationUiState.Idle, onDismiss = {})
        }

        val title = composeRule.activity.getString(R.string.interpretation_title)
        composeRule.onNodeWithText(title).assertDoesNotExist()
    }
}
