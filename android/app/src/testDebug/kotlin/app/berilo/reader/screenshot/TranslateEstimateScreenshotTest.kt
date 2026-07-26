package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.translate.engine.ChapterEstimate
import app.berilo.reader.translate.engine.CostEstimate
import app.berilo.reader.translate.job.SourceBook
import app.berilo.reader.translate.job.TierOffer
import app.berilo.reader.translate.job.TranslationPlan
import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.REVISE
import app.berilo.reader.translate.prompts.StyleTier
import app.berilo.reader.ui.theme.BeriloTheme
import app.berilo.reader.ui.translate.TranslateScreen
import app.berilo.reader.ui.translate.TranslateUiState
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B7's spending gate, rendered (S2.10 harness).
 *
 * This is the screen a user reads before authorizing a multi-euro, multi-hour run, so it is the
 * one that most needs looking at rather than only asserting about: both tier prices, the
 * resolved model and style, and a confirm button that names the amount on its face. Rendered at
 * phone and Boox widths in both themes; every colour comes from a pinned Material3 role
 * (S2.11), never an ad-hoc value.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslateEstimateScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun offer(tier: StyleTier, styleName: String, version: String, revising: Boolean, cost: Double) =
        TierOffer(
            tier = tier,
            styleName = styleName,
            styleVersion = version,
            revising = revising,
            estimate =
                CostEstimate(
                    model = "gpt-5-mini",
                    targetLang = "sl",
                    promptVersion = version,
                    totalSegments = 1294,
                    translatableSegments = 1180,
                    skippedSegments = 108,
                    emptySegments = 6,
                    batches = 162,
                    reasoningTokens = 75_168,
                    inputTokens = 412_000,
                    outputTokens = 268_000,
                    costEur = cost,
                    chapters = listOf(ChapterEstimate(0, "Chapter One", 42, 1200, 1400, 0.004)),
                    revisionCalls = if (revising) 162 else 0,
                    bookContextCalls = 0,
                ),
        )

    /** Figures from the real ledger (2026-07-25): a book is ~EUR 0.70 single-pass, ~EUR 1.45 revised. */
    private fun samplePlan() =
        TranslationPlan(
            source = SourceBook("abc123", File("/sources/abc123.epub"), "The Revenge of Geography"),
            bookTitle = "The Revenge of Geography",
            sourceLang = "en-US",
            targetLang = "sl",
            model = "gpt-5-mini",
            chapterCount = 47,
            totalSegments = 1294,
            economy = offer(StyleTier.ECONOMY, BASELINE.name, BASELINE.version, revising = false, cost = 0.70),
            quality = offer(StyleTier.QUALITY, REVISE.name, REVISE.version, revising = true, cost = 1.45),
            alreadyTargetLanguage = false,
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

    private fun renderEstimate(width: String, useDarkTheme: Boolean, theme: String) =
        render(
            TranslateUiState.Estimate(samplePlan(), StyleTier.ECONOMY),
            surface = "translate_estimate",
            width = width,
            useDarkTheme = useDarkTheme,
            theme = theme,
        )

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light`() = renderEstimate("phone", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark`() = renderEstimate("phone", useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light`() = renderEstimate("boox", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark`() = renderEstimate("boox", useDarkTheme = true, theme = "dark")

    /**
     * The already-in-target-language notice, which uses `errorContainer`/`onErrorContainer`.
     *
     * Rendered on its own because those two roles reach a surface nowhere else in the app, and
     * an unpinned role only shows up once something actually paints it (docs/findings.md,
     * 2026-07-25: the baseline-violet leak came from component defaults no grep could see).
     */
    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light, already in the target language`() =
        render(
            TranslateUiState.Estimate(samplePlan().copy(targetLang = "en", alreadyTargetLanguage = true), StyleTier.QUALITY),
            surface = "translate_estimate_same_language",
            width = "boox",
            useDarkTheme = false,
            theme = "light",
        )

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark, already in the target language`() =
        render(
            TranslateUiState.Estimate(samplePlan().copy(targetLang = "en", alreadyTargetLanguage = true), StyleTier.QUALITY),
            surface = "translate_estimate_same_language",
            width = "boox",
            useDarkTheme = true,
            theme = "dark",
        )
}
