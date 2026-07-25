package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.settings.SettingsScreen
import app.berilo.reader.settings.SettingsUiState
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2.10: renders [SettingsScreen] with fixture (non-secret) key text filled in, so the
 * populated-field layout — not just the empty-state placeholders — is what a visual review
 * sees. `sk-fixture-...` is deterministic test fixture text, never a live key (§4 of
 * CLAUDE.md — never a real secret in tests/fixtures/logs).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleUiState() =
        SettingsUiState(
            openaiKey = "sk-fixture-0000000000000000000000000000000000000000000000",
            anthropicKey = "",
            model = "gpt-5-mini",
            targetLang = "sl",
        )

    private fun render(width: String, useDarkTheme: Boolean, theme: String) {
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    SettingsScreen(
                        uiState = sampleUiState(),
                        onOpenAiKeyChanged = {},
                        onAnthropicKeyChanged = {},
                        onModelChanged = {},
                        onTargetLangChanged = {},
                        onTestOpenAiKey = {},
                        onTestAnthropicKey = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(ScreenshotOutput.file("settings", width, theme))
    }

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light`() = render("phone", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark`() = render("phone", useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light`() = render("boox", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark`() = render("boox", useDarkTheme = true, theme = "dark")
}
