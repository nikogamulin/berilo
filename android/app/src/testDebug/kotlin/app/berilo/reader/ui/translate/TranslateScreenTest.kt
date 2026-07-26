package app.berilo.reader.ui.translate

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.berilo.reader.screenshot.ScreenshotQualifiers
import app.berilo.reader.translate.engine.CostEstimate
import app.berilo.reader.translate.job.SourceBook
import app.berilo.reader.translate.job.TierOffer
import app.berilo.reader.translate.job.TranslationPlan
import app.berilo.reader.translate.job.TranslationProgress
import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.REVISE
import app.berilo.reader.translate.prompts.StyleTier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The confirmation control, where it lives and where it does not.
 *
 * `docs/findings.md` (2026-07-26) records the S2.6 failure in its general form: **an action
 * that consumes transient state must be hosted ON that state.** The state this action consumes
 * is a *price the user has just read*, so the confirm button belongs on the estimate surface
 * and must exist nowhere else — otherwise there would be a reachable route to spending that
 * never showed a figure. The fifth test here is the one that pins that.
 *
 * Rendered at the Boox viewport: the estimate is a long, scrolling screen, and on a
 * phone-height viewport the confirm button lays out below the fold where `performClick` cannot
 * reach it — which would make these tests pass or fail on layout rather than on wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
class TranslateScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val state = mutableStateOf<TranslateUiState>(TranslateUiState.Idle)
    private var confirmed = 0
    private val tiersSelected = mutableListOf<StyleTier>()

    /**
     * Hosts the screen once.
     *
     * `ComposeTestRule.setContent` may be called at most once per test, so tests that walk
     * several states drive [show] instead of re-hosting — which is also what the real flow does,
     * since one activity hosts every step.
     */
    @Before
    fun setUp() {
        composeRule.setContent {
            TranslateScreen(
                uiState = state.value,
                onPickSource = {},
                onTierSelected = tiersSelected::add,
                onConfirm = { confirmed++ },
                onCancelRun = {},
                onRetry = {},
                onStartOver = {},
                onBack = {},
            )
        }
    }

    private fun show(next: TranslateUiState) {
        state.value = next
        composeRule.waitForIdle()
    }

    private fun offer(styleName: String, version: String, revising: Boolean, cost: Double) =
        TierOffer(
            tier = if (revising) StyleTier.QUALITY else StyleTier.ECONOMY,
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
                    reasoningTokens = 0,
                    inputTokens = 412_000,
                    outputTokens = 268_000,
                    costEur = cost,
                    revisionCalls = if (revising) 162 else 0,
                ),
        )

    private fun plan() =
        TranslationPlan(
            source = SourceBook("abc123", File("/sources/abc123.epub"), "The Revenge of Geography"),
            bookTitle = "The Revenge of Geography",
            sourceLang = "en-US",
            targetLang = "sl",
            model = "gpt-5-mini",
            chapterCount = 47,
            totalSegments = 1294,
            economy = offer(BASELINE.name, BASELINE.version, revising = false, cost = 0.70),
            quality = offer(REVISE.name, REVISE.version, revising = true, cost = 1.45),
            alreadyTargetLanguage = false,
        )

    /** Both prices, the model and the resolved style are all on screen before the button is. */
    @Test
    fun `the estimate shows both tier costs, the model and the resolved style`() {
        show(TranslateUiState.Estimate(plan(), StyleTier.ECONOMY))

        composeRule.onNodeWithTag(TRANSLATE_ESTIMATE_TAG).assertIsDisplayed()
        // Each figure appears more than once (headline line, tier row, button label), so this
        // asserts presence rather than uniqueness.
        composeRule.onAllNodesWithText("€0.70", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("€1.45", substring = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Model: gpt-5-mini").assertExists()
        composeRule.onNodeWithText("Style: ${BASELINE.name}").assertExists()
        composeRule.onNodeWithText("47 chapters").assertExists()
        composeRule.onNodeWithText("1180 of 1294 segments will be translated").assertExists()
        composeRule.onNodeWithTag(TRANSLATE_TIER_ECONOMY_TAG).assertExists()
        composeRule.onNodeWithTag(TRANSLATE_TIER_QUALITY_TAG).assertExists()
    }

    /**
     * The confirm button names the amount it authorizes, and switching tier changes both the
     * figure and the named style.
     */
    @Test
    fun `the confirm button spells out the price it would spend`() {
        show(TranslateUiState.Estimate(plan(), StyleTier.ECONOMY))
        composeRule.onNodeWithText("Translate for €0.70").assertExists()
        composeRule.onNodeWithText("Style: ${BASELINE.name}").assertExists()

        show(TranslateUiState.Estimate(plan(), StyleTier.QUALITY))
        composeRule.onNodeWithText("Translate for €1.45").assertExists()
        composeRule.onNodeWithText("Style: ${REVISE.name}").assertExists()
    }

    /** Choosing the dearer tier reports the choice; it does not itself confirm anything. */
    @Test
    fun `selecting a tier reports the tier and never confirms`() {
        show(TranslateUiState.Estimate(plan(), StyleTier.ECONOMY))

        composeRule.onNodeWithTag(TRANSLATE_TIER_QUALITY_TAG).performClick()

        assertEquals(listOf(StyleTier.QUALITY), tiersSelected)
        assertEquals("choosing a tier is not confirming", 0, confirmed)
    }

    /** The button is wired to the one callback that can spend. */
    @Test
    fun `tapping confirm invokes the confirmation callback`() {
        show(TranslateUiState.Estimate(plan(), StyleTier.ECONOMY))

        composeRule.onNodeWithTag(TRANSLATE_CONFIRM_TAG).performClick()

        assertEquals(1, confirmed)
    }

    /**
     * **No other screen in the flow carries a confirm control.**
     *
     * This is the UI half of CLAUDE.md §4's gate: the estimate surface is not merely *a* route
     * to spending, it is the only one. A confirm button added to, say, the failure screen as a
     * convenience would be a path to a paid run that never showed a price — and it would be
     * invisible to the view-model tests, which cannot see what a composable draws.
     */
    @Test
    fun `no state other than the estimate offers a way to start spending`() {
        val progress = TranslationProgress(totalSegments = 1294, processedSegments = 561)
        val others =
            listOf(
                TranslateUiState.Idle,
                TranslateUiState.Preparing,
                TranslateUiState.Waiting("The Revenge of Geography"),
                TranslateUiState.Running("The Revenge of Geography", progress),
                TranslateUiState.Running("The Revenge of Geography", null),
                TranslateUiState.Done("The Revenge of Geography", progress),
                TranslateUiState.Failed("stopped", retryable = true),
                TranslateUiState.Failed("no API key", retryable = false),
            )

        others.forEach { next ->
            show(next)
            composeRule.onNodeWithTag(TRANSLATE_CONFIRM_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag(TRANSLATE_ESTIMATE_TAG).assertDoesNotExist()
        }
    }

    /** The progress screen reports position and running spend from the engine's own numbers. */
    @Test
    fun `the progress screen shows the chapter, the segments and the running cost`() {
        show(
            TranslateUiState.Running(
                "The Revenge of Geography",
                TranslationProgress(
                    totalSegments = 1294,
                    processedSegments = 561,
                    chapterIndex = 12,
                    chapterTitle = "Herodotus and His Successors",
                    apiCalls = 71,
                    costEur = 0.3121,
                ),
            ),
        )

        composeRule.onNodeWithTag(TRANSLATE_PROGRESS_TAG).assertExists()
        composeRule.onNodeWithText("Chapter 12 — Herodotus and His Successors").assertExists()
        composeRule.onNodeWithText("561 of 1294 segments").assertExists()
        // A book-sized figure renders in cents; the four-digit form is reserved for sub-cent
        // costs, which `TranslateFormattingTest` covers.
        composeRule.onNodeWithText("€0.31 spent · 71 API calls").assertExists()
    }

    /**
     * The resumability promise is on screen while the job runs.
     *
     * A multi-hour job on a tablet *will* be interrupted, and a user who does not know the work
     * survives it will not leave it running — so this copy is load-bearing, not decoration.
     */
    @Test
    fun `the running screen tells the user interrupted work resumes`() {
        show(TranslateUiState.Running("The Revenge of Geography", null))

        composeRule
            .onNodeWithText("never paid for twice", substring = true)
            .assertExists()
    }
}
