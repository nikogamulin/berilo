package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.dictionary.DictionaryDefinition
import app.berilo.reader.dictionary.DictionarySheet
import app.berilo.reader.dictionary.DictionaryUiState
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2.10: renders [DictionarySheet] in its populated Success state — the state a user
 * actually reviews the dictionary lookup in, unlike the Loading/Idle states the S2.9
 * regression tests already cover.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DictionarySheetScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleUiState() =
        DictionaryUiState.Success(
            definition =
                DictionaryDefinition(
                    word = "hiraeth",
                    definition = "a longing for a home that may not exist or that is no longer accessible",
                    contextMeaning = "used here to describe the narrator's homesickness for a lost way of life",
                    baseForm = "hiraeth",
                    usageNote = "Welsh, with no direct English equivalent.",
                ),
            sentence = "He felt hiraeth for the coast he'd never see again.",
            costEur = 0.0001,
            fromCache = false,
        )

    private fun render(width: String, useDarkTheme: Boolean, theme: String) {
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    DictionarySheet(uiState = sampleUiState(), onDismiss = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(ScreenshotOutput.file("dictionary", width, theme))
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
