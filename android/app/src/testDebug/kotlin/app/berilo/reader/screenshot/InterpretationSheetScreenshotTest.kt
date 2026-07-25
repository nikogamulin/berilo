package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.interpretation.InterpretationSheet
import app.berilo.reader.interpretation.InterpretationUiState
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2.10: renders [InterpretationSheet] in its populated Success state, mirroring
 * [DictionarySheetScreenshotTest] — see that class for why Success (not Loading/Idle) is the
 * representative state for a visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterpretationSheetScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleUiState() =
        InterpretationUiState.Success(
            text = "The passage contrasts the character's public composure with the private grief " +
                "he never lets the crowd see, a split the narrator returns to throughout the book.",
            passage = "He smiled for the crowd and, alone, let it fall.",
            costEur = 0.0002,
            fromCache = false,
        )

    private fun render(width: String, useDarkTheme: Boolean, theme: String) {
        ScreenshotOutput.assumeRecording()
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    InterpretationSheet(uiState = sampleUiState(), onDismiss = {})
                }
            }
        }
        composeRule.waitForIdle()
        val file = ScreenshotOutput.file("interpretation", width, theme)
        composeRule.onRoot().captureRoboImage(file)
        ScreenshotOutput.assertCaptured(file)
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
