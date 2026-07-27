package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.annotations.FlagEditorActions
import app.berilo.reader.annotations.FlagEditorHost
import app.berilo.reader.annotations.FlagEditorUiState
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B9's "Bad translation" sheet, rendered with a typed suggestion so the passage echo, the
 * comment field and both buttons are all visible — the e-ink review looks for the same things
 * S2.10 established: no baseline-violet leak, readable contrast at 227 dpi, nothing clipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagEditorScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleState() =
        FlagEditorUiState.Composer(
            selectedText = "Geografija je usoda, ki je nihče ne izbere, in reke so starejše od vsake meje.",
            locatorJson = "{}",
            chapterTitle = "Poglavje ena",
            comment = "Raje »obala« kot »breg« — izvirnik govori o morski obali.",
        )

    private fun render(width: String, useDarkTheme: Boolean, theme: String) {
        ScreenshotOutput.assumeRecording()
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    FlagEditorHost(
                        state = sampleState(),
                        actions = FlagEditorActions(onCommentChanged = {}, onConfirm = {}, onDismiss = {}),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val file = ScreenshotOutput.file("flag_editor", width, theme)
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
