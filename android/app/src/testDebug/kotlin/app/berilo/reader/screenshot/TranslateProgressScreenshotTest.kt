package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.translate.job.TranslationProgress
import app.berilo.reader.ui.theme.BeriloTheme
import app.berilo.reader.ui.translate.TranslateScreen
import app.berilo.reader.ui.translate.TranslateUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The running and finished states of a translation (S2.10 harness).
 *
 * Worth rendering rather than only asserting: this is the surface the e-ink rule bites hardest
 * on (`docs/design_guidelines.md` principle 2 — no animation, no spinner over content), and the
 * only way to see that the progress bar is a determinate strip rather than a continuously
 * repainting indeterminate one is to look at it. Both widths, both themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslateProgressScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Mid-book on a real-sized book: ledger figures for gpt-5-mini at ~EUR 0.70 a book. */
    private val midRun =
        TranslationProgress(
            totalSegments = 1294,
            processedSegments = 561,
            chapterIndex = 12,
            chapterTitle = "Herodotus and His Successors",
            apiCalls = 71,
            costEur = 0.3121,
        )

    private fun render(state: TranslateUiState, surface: String, width: String, useDarkTheme: Boolean, theme: String) {
        ScreenshotOutput.assumeRecording()
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    TranslateScreen(
                        uiState = state,
                        onPickSource = {},
                        onTierSelected = {},
                        onConfirm = {},
                        onCancelRun = {},
                        onRetry = {},
                        onStartOver = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val file = ScreenshotOutput.file(surface, width, theme)
        composeRule.onRoot().captureRoboImage(file)
        ScreenshotOutput.assertCaptured(file)
    }

    private fun renderRunning(width: String, useDarkTheme: Boolean, theme: String) =
        render(
            TranslateUiState.Running("The Revenge of Geography", midRun),
            surface = "translate_progress",
            width = width,
            useDarkTheme = useDarkTheme,
            theme = theme,
        )

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light`() = renderRunning("phone", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark`() = renderRunning("phone", useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light`() = renderRunning("boox", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark`() = renderRunning("boox", useDarkTheme = true, theme = "dark")

    /** The completion state, where the run's actual cost is reported (CLAUDE.md §4). */
    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light, finished`() =
        render(
            TranslateUiState.Done(
                "The Revenge of Geography",
                midRun.copy(processedSegments = 1294, apiCalls = 163, costEur = 0.7042),
            ),
            surface = "translate_done",
            width = "boox",
            useDarkTheme = false,
            theme = "light",
        )

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark, finished`() =
        render(
            TranslateUiState.Done(
                "The Revenge of Geography",
                midRun.copy(processedSegments = 1294, apiCalls = 163, costEur = 0.7042),
            ),
            surface = "translate_done",
            width = "boox",
            useDarkTheme = true,
            theme = "dark",
        )

    /**
     * The failure state, which paints `error` — a role nothing else in this flow reaches.
     *
     * The retry affordance is on screen only for a resumable failure, and its copy is the
     * user-facing half of the resumability guarantee.
     */
    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light, resumable failure`() =
        render(
            TranslateUiState.Failed("Translation of The Revenge of Geography stopped.", retryable = true),
            surface = "translate_failed",
            width = "phone",
            useDarkTheme = false,
            theme = "light",
        )

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark, resumable failure`() =
        render(
            TranslateUiState.Failed("Translation of The Revenge of Geography stopped.", retryable = true),
            surface = "translate_failed",
            width = "phone",
            useDarkTheme = true,
            theme = "dark",
        )
}
