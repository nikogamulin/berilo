package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.annotations.AnnotationEditorUiState
import app.berilo.reader.dictionary.DictionaryUiState
import app.berilo.reader.interpretation.InterpretationUiState
import app.berilo.reader.reader.ReaderChromeActions
import app.berilo.reader.reader.ReaderChromeOverlay
import app.berilo.reader.reader.ReaderChromeState
import app.berilo.reader.reader.ReaderPanel
import app.berilo.reader.reader.ReaderPreferences
import app.berilo.reader.reader.TocChapter
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2.10: renders [ReaderChromeOverlay] — the reader's top/bottom bar and settings panel, the
 * one Phase 2 surface the S2.9 UI tests don't already cover. Two states per width/theme
 * combo: the default reading chrome (top+bottom bars only) and the settings panel open (which
 * is where [ReaderPreferences.einkMode] — the "e-ink mode" toggle distinct from this harness's
 * own light/dark theme axis — actually renders).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderChromeScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions =
        ReaderChromeActions(
            onToggleChrome = {},
            onOpenChapters = {},
            onOpenSettings = {},
            onClosePanel = {},
            onChapterSelected = {},
            onFontSmaller = {},
            onFontLarger = {},
            onMarginsNarrower = {},
            onMarginsWider = {},
            onEinkModeChange = {},
            onDarkThemeChange = {},
        )

    private fun baseState(panel: ReaderPanel) =
        ReaderChromeState(
            chromeVisible = true,
            panel = panel,
            chapterTitle = "Chapter 4 — The Sandworm Team",
            progressFraction = 0.37f,
            preferences = ReaderPreferences(),
            chapters =
                listOf(
                    TocChapter(title = "Prologue", href = "prologue.xhtml", depth = 0),
                    TocChapter(title = "Chapter 1 — Iron Curtain", href = "ch1.xhtml", depth = 0),
                    TocChapter(title = "Chapter 4 — The Sandworm Team", href = "ch4.xhtml", depth = 0),
                ),
            dictionaryState = DictionaryUiState.Idle,
            interpretationState = InterpretationUiState.Idle,
            annotationEditorState = AnnotationEditorUiState.Idle,
        )

    private fun render(surface: String, width: String, panel: ReaderPanel, useDarkTheme: Boolean, theme: String) {
        ScreenshotOutput.assumeRecording()
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    ReaderChromeOverlay(state = baseState(panel), actions = actions)
                }
            }
        }
        composeRule.waitForIdle()
        val file = ScreenshotOutput.file(surface, width, theme)
        composeRule.onRoot().captureRoboImage(file)
        ScreenshotOutput.assertCaptured(file)
    }

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light`() = render("reader_chrome", "phone", ReaderPanel.NONE, useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark`() = render("reader_chrome", "phone", ReaderPanel.NONE, useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light`() = render("reader_chrome", "boox", ReaderPanel.NONE, useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark`() = render("reader_chrome", "boox", ReaderPanel.NONE, useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light settings panel`() =
        render("reader_chrome_settings", "phone", ReaderPanel.SETTINGS, useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light settings panel`() =
        render("reader_chrome_settings", "boox", ReaderPanel.SETTINGS, useDarkTheme = false, theme = "light")
}
