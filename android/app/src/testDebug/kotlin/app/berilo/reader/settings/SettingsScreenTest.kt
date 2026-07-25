package app.berilo.reader.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.berilo.reader.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2.9: SettingsScreen gained leading icons (`ic_key`/`ic_language`) on the OutlinedTextFields
 * as part of the icon-consistency pass. Since this is the first test for the screen, it
 * doubles as a regression guard on the fields' existing wiring — the icon is a `leadingIcon`
 * on the same OutlinedTextField, not a new composable, so a mistake there would show up as a
 * broken callback rather than a rendering error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(
        uiState: SettingsUiState = SettingsUiState(),
        onOpenAiKeyChanged: (String) -> Unit = {},
        onAnthropicKeyChanged: (String) -> Unit = {},
        onModelChanged: (String) -> Unit = {},
        onTargetLangChanged: (String) -> Unit = {},
        onTestOpenAiKey: () -> Unit = {},
        onTestAnthropicKey: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsScreen(
                uiState = uiState,
                onOpenAiKeyChanged = onOpenAiKeyChanged,
                onAnthropicKeyChanged = onAnthropicKeyChanged,
                onModelChanged = onModelChanged,
                onTargetLangChanged = onTargetLangChanged,
                onTestOpenAiKey = onTestOpenAiKey,
                onTestAnthropicKey = onTestAnthropicKey,
                onBack = onBack,
            )
        }
    }

    @Test
    fun `back button is labelled and navigates back`() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        val backLabel = composeRule.activity.getString(R.string.settings_back_cd)
        composeRule.onNodeWithContentDescription(backLabel).performClick()

        assert(backPressed)
    }

    @Test
    fun `typing in the openai key field still reaches the callback with the icon in place`() {
        var lastValue: String? = null
        setContent(onOpenAiKeyChanged = { lastValue = it })

        composeRule.onNodeWithTag(OPENAI_KEY_FIELD_TAG).performTextInput("sk-test")

        assert(lastValue == "sk-test") { "expected the openai key callback to fire, got $lastValue" }
    }

    @Test
    fun `target language field still reaches the callback with the icon in place`() {
        var lastValue: String? = null
        setContent(onTargetLangChanged = { lastValue = it })

        composeRule.onNodeWithTag(TARGET_LANG_FIELD_TAG).performTextInput("de")

        assert(lastValue?.contains("de") == true) { "expected the target-lang callback to fire, got $lastValue" }
    }

    @Test
    fun `show key toggle still reveals the key`() {
        setContent(uiState = SettingsUiState(openaiKey = "sk-secret"))

        // Both API key sections start with a "Show" toggle; click the first (OpenAI's) and
        // confirm a "Hide" toggle now exists somewhere on screen.
        val showLabel = composeRule.activity.getString(R.string.settings_show_key)
        composeRule.onAllNodesWithText(showLabel).onFirst().performClick()

        val hideLabel = composeRule.activity.getString(R.string.settings_hide_key)
        composeRule.onAllNodesWithText(hideLabel).onFirst().assertExists()
    }

    @Test
    fun `test key button still reaches its callback`() {
        var tested = false
        setContent(uiState = SettingsUiState(openaiKey = "sk-secret"), onTestOpenAiKey = { tested = true })

        // Both API key sections render a "Test key" button; the first match is the OpenAI one
        // since ApiKeySection(openai) is composed before ApiKeySection(anthropic).
        val testLabel = composeRule.activity.getString(R.string.settings_test_key)
        composeRule.onAllNodesWithText(testLabel).onFirst().performClick()

        assert(tested)
    }
}
