package app.berilo.reader.dictionary

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
 * S2.9: the dictionary sheet previously had no visible dismiss affordance (swipe/scrim-tap
 * only) and no way to tell it apart from [app.berilo.reader.interpretation.InterpretationSheet]
 * at a glance. Covers the header's close button (contentDescription + wiring to [onDismiss])
 * and that the headword still renders per state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictionarySheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `close button is labelled and dismisses the sheet`() {
        var dismissed = false
        val state = DictionaryUiState.Loading(word = "hiraeth")

        composeRule.setContent {
            DictionarySheet(uiState = state, onDismiss = { dismissed = true })
        }

        composeRule.waitForIdle()
        val dismissLabel = composeRule.activity.getString(R.string.dictionary_dismiss_cd)
        // performClick() simulates a real touch at the node's on-screen position, which is
        // unreliable here while ModalBottomSheet is mid-reveal-animation under Robolectric;
        // invoking the click semantics action directly exercises the same onClick wiring
        // without depending on the sheet's animated position settling first.
        composeRule.onNodeWithContentDescription(dismissLabel).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assert(dismissed) { "expected onDismiss to be invoked by the header's close button" }
    }

    @Test
    fun `header shows the headword for a loading state`() {
        composeRule.setContent {
            DictionarySheet(uiState = DictionaryUiState.Loading(word = "hiraeth"), onDismiss = {})
        }

        composeRule.onNodeWithText("hiraeth").assertExists()
    }

    @Test
    fun `header shows the definition's headword for a success state`() {
        val definition =
            DictionaryDefinition(
                word = "hiraeth",
                definition = "a longing for a home that may not exist",
                contextMeaning = "used here to describe the narrator's homesickness",
                baseForm = "",
                usageNote = "",
            )
        composeRule.setContent {
            DictionarySheet(
                uiState =
                    DictionaryUiState.Success(
                        definition = definition,
                        sentence = "He felt hiraeth for the coast.",
                        costEur = 0.0001,
                        fromCache = false,
                    ),
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("hiraeth").assertExists()
    }

    @Test
    fun `idle state renders no sheet content`() {
        composeRule.setContent {
            DictionarySheet(uiState = DictionaryUiState.Idle, onDismiss = {})
        }

        composeRule.onNodeWithText("hiraeth").assertDoesNotExist()
    }
}
