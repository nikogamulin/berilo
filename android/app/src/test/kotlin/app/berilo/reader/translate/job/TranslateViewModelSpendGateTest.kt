package app.berilo.reader.translate.job

import app.berilo.reader.settings.FakeKeyValueStore
import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.settings.SettingsRepository
import app.berilo.reader.translate.epub.EpubReader
import app.berilo.reader.translate.prompts.StyleTier
import app.berilo.reader.ui.translate.TranslateUiState
import app.berilo.reader.ui.translate.TranslateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **CLAUDE.md §4: no path reaches a paid run without an explicit confirmation.**
 *
 * The gate has two halves and this file asserts both:
 *
 * 1. *Behavioural* — every public method of [TranslateViewModel] except [TranslateViewModel.confirmAndTranslate]
 *    is driven, in the states where each is meaningful, and the runner is never started.
 * 2. *Structural* — [TranslationPlanner], the object the estimate screen actually uses, is
 *    constructed here with **no client and no client factory**, because it has no parameter for
 *    one. Building an estimate cannot reach a provider because there is nothing to reach it
 *    with, which is a stronger guarantee than any assertion about call counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModelSpendGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var runner: RecordingTranslationRunner
    private lateinit var viewModel: TranslateViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        runner = RecordingTranslationRunner()
        val repository = SettingsRepository(FakeKeyValueStore())
        repository.save(LlmSettings(openaiKey = "fixture-key", model = JOB_TEST_MODEL, targetLang = "sl"))
        viewModel =
            TranslateViewModel(
                sourceImporter = SourceBookImporter(folder.newFolder("sources"), dispatcher),
                // No client, no factory — there is no constructor parameter for one.
                planner = TranslationPlanner(EpubReader(), dispatcher),
                settingsRepository = repository,
                runner = runner,
            )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Everything short of the confirm button leaves the runner untouched.
     *
     * **Mutation-proof:** move `runner.start(request)` from `confirmAndTranslate` into
     * `planFor` (i.e. start as soon as an estimate exists) and this fails on the first
     * assertion; add it to `onTierSelected` and it fails on the second.
     */
    @Test
    fun `picking, pricing, switching tier, retrying and resetting start nothing`() =
        runTest(dispatcher) {
            val picked = writeSourceEpub(folder.newFile("picked.epub"))

            viewModel.onSourcePicked({ picked.inputStream() }, "Picked.epub")
            advanceUntilIdle()
            assertTrue("an estimate exists", viewModel.uiState.value is TranslateUiState.Estimate)
            assertTrue("but nothing was started by pricing it", runner.started.isEmpty())

            viewModel.onTierSelected(StyleTier.QUALITY)
            advanceUntilIdle()
            assertTrue("nor by choosing the dearer tier", runner.started.isEmpty())

            viewModel.onTierSelected(StyleTier.ECONOMY)
            viewModel.onTierSelected(StyleTier.QUALITY)
            advanceUntilIdle()
            assertTrue("nor by flipping between them", runner.started.isEmpty())

            // `retry` before any confirmation has nothing to resume, and must not invent one.
            viewModel.retry()
            advanceUntilIdle()
            assertTrue("nor by an unbacked retry", runner.started.isEmpty())

            viewModel.reset()
            viewModel.onSourcePicked({ picked.inputStream() }, "Picked.epub")
            advanceUntilIdle()
            assertTrue("nor by re-entering the flow", runner.started.isEmpty())

            // ...and the confirm button is what breaks the seal.
            viewModel.confirmAndTranslate()
            advanceUntilIdle()
            assertEquals("exactly one run, from the one confirming call", 1, runner.started.size)
        }

    /**
     * A confirmation is only honoured from the estimate screen.
     *
     * A stray or replayed `confirmAndTranslate` — a double tap racing a state change, a
     * restored activity re-firing a click — must not start a run the user has not just seen a
     * price for.
     */
    @Test
    fun `confirming outside the estimate screen does nothing`() =
        runTest(dispatcher) {
            viewModel.confirmAndTranslate()
            advanceUntilIdle()
            assertTrue("nothing to confirm from Idle", runner.started.isEmpty())

            val picked = writeSourceEpub(folder.newFile("picked.epub"))
            viewModel.onSourcePicked({ picked.inputStream() }, "Picked.epub")
            advanceUntilIdle()
            viewModel.confirmAndTranslate()
            advanceUntilIdle()
            assertEquals(1, runner.started.size)

            // The screen has moved on to Waiting; a second tap must not enqueue a second run.
            viewModel.confirmAndTranslate()
            viewModel.confirmAndTranslate()
            advanceUntilIdle()
            assertEquals("a double tap is still one run", 1, runner.started.size)
        }

    /**
     * The confirmed request carries the tier the user selected, and the app is never described
     * as anything but a device.
     *
     * **Mutation-proof:** hardcode `tier = StyleTier.ECONOMY` in `confirmAndTranslate` and the
     * QUALITY assertion fails — a user who chose to pay double would silently get the cheap
     * run. Swap `ExecutionContext.DEVICE` for `WORKSTATION` in [TranslationPlanner.styleFor]
     * and the ECONOMY-default assertion fails, because the workstation default is QUALITY.
     */
    @Test
    fun `the confirmed request carries the chosen tier, and ECONOMY is what a device defaults to`() =
        runTest(dispatcher) {
            val picked = writeSourceEpub(folder.newFile("picked.epub"))
            viewModel.onSourcePicked({ picked.inputStream() }, "Picked.epub")
            advanceUntilIdle()

            val arrived = viewModel.uiState.value as TranslateUiState.Estimate
            assertEquals("the device default", StyleTier.ECONOMY, arrived.tier)
            assertNotEquals(
                "and the two tiers really are different styles",
                arrived.plan.economy.styleName,
                arrived.plan.quality.styleName,
            )

            viewModel.onTierSelected(StyleTier.QUALITY)
            viewModel.confirmAndTranslate()
            advanceUntilIdle()

            assertEquals(
                "the run bills for what the user chose",
                StyleTier.QUALITY,
                runner.started.single().tier,
            )
        }
}
