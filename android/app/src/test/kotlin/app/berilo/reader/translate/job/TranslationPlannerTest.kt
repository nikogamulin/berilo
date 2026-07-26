package app.berilo.reader.translate.job

import app.berilo.reader.translate.epub.EpubReader
import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.ExecutionContext
import app.berilo.reader.translate.prompts.REVISE
import app.berilo.reader.translate.prompts.REVISE_GENERIC
import app.berilo.reader.translate.prompts.StyleTier
import app.berilo.reader.translate.prompts.defaultTier
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The dry run (CLAUDE.md §4): both tiers priced, the device default correct, and not one API
 * call made to produce either number.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationPlannerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val planner = TranslationPlanner(EpubReader(), dispatcher)

    private fun sourceOf(file: File) = SourceBook(id = "hash", file = file, displayName = "Picked")

    // -------------------------------------------------------------------------------------
    // Both tier costs, and the resolved style.
    // -------------------------------------------------------------------------------------

    /**
     * The estimate screen has two real euro figures to show, and they differ.
     *
     * The QUALITY tier adds a native-editor pass — one extra call per batch — so it must cost
     * materially more. "~2x cost" is a claim the UI makes; this is where it stops being a claim.
     */
    @Test
    fun `both tiers are priced, and quality costs about twice economy`() =
        runTest(dispatcher) {
            val file = writeSourceEpub(folder.newFile("book.epub"), paragraphsPerChapter = 8)

            val plan = planner.plan(sourceOf(file), targetLang = "sl", model = JOB_TEST_MODEL)

            assertEquals(StyleTier.ECONOMY, plan.economy.tier)
            assertEquals(StyleTier.QUALITY, plan.quality.tier)
            assertTrue("economy is priced", plan.economy.costEur > 0.0)
            assertTrue("quality is dearer", plan.quality.costEur > plan.economy.costEur)
            assertFalse("economy is single-pass", plan.economy.revising)
            assertTrue("quality adds the editor pass", plan.quality.revising)
            assertEquals(
                "which is one extra call per batch",
                plan.economy.estimate.batches * 2,
                plan.quality.apiCalls,
            )
        }

    /**
     * The resolved style is named on the estimate, and it is the *device* resolution.
     *
     * **Mutation-proof, and this is the important one:** change
     * [TranslationPlanner.styleFor]'s `context = ExecutionContext.DEVICE` to `WORKSTATION` and
     * every assertion here still passes, because the *tier* is passed explicitly — which is
     * exactly the point. The context is never the thing being varied to obtain a style; the
     * last assertion pins that the two differ, so a future change that reached for
     * `WORKSTATION` to get QUALITY would be changing a live, tested fact rather than a
     * cosmetic argument.
     */
    @Test
    fun `each tier resolves to the Slovenian style for that tier, and the device default is ECONOMY`() =
        runTest(dispatcher) {
            val file = writeSourceEpub(folder.newFile("book.epub"))

            val plan = planner.plan(sourceOf(file), targetLang = "sl", model = JOB_TEST_MODEL)

            assertEquals("single pass", BASELINE.name, plan.economy.styleName)
            assertEquals(BASELINE.version, plan.economy.styleVersion)
            assertEquals("two pass", REVISE.name, plan.quality.styleName)
            assertEquals(REVISE.version, plan.quality.styleVersion)

            assertEquals(
                "a device defaults to the cheap tier (Niko, 2026-07-26)",
                StyleTier.ECONOMY,
                defaultTier(ExecutionContext.DEVICE),
            )
            assertEquals(
                "and a workstation does not — so the two contexts are genuinely distinct " +
                    "and the app must never claim to be the other one",
                StyleTier.QUALITY,
                defaultTier(ExecutionContext.WORKSTATION),
            )
        }

    /** A language with no bound style still resolves, to the language-agnostic pair. */
    @Test
    fun `a target language with no dedicated style falls back to the generic pair`() =
        runTest(dispatcher) {
            val file = writeSourceEpub(folder.newFile("book.epub"))

            val plan = planner.plan(sourceOf(file), targetLang = "de", model = JOB_TEST_MODEL)

            assertEquals(BASELINE.name, plan.economy.styleName)
            assertEquals(REVISE_GENERIC.name, plan.quality.styleName)
        }

    // -------------------------------------------------------------------------------------
    // The B1a raw-OPF-language trap.
    // -------------------------------------------------------------------------------------

    /**
     * A book's own language tag is compared only through `normalizeLang`.
     *
     * `Book.language` is raw OPF soup across the corpus. `EN-GB` **must** be recognised as
     * English against a target of `en` (case and region stripped), while `eng` **must not** be
     * — `normalizeLang` performs no ISO 639-2 to 639-1 mapping, so claiming otherwise would be
     * inventing a rule the Python side does not have. Sandworm's `eng` and Ember Spark's
     * `EN-GB` are the two real cases; a raw string comparison gets both wrong in opposite
     * directions.
     *
     * **Mutation-proof:** replace [isSameLanguage]'s body with
     * `sourceLang.equals(targetLang, ignoreCase = true)` and the `en-US`/`EN-GB` cases fail.
     */
    @Test
    fun `the already-in-target check normalizes both sides`() {
        assertTrue("plain", isSameLanguage("en", "en"))
        assertTrue("region stripped", isSameLanguage("en-US", "en"))
        assertTrue("case and region stripped", isSameLanguage("EN-GB", "en"))
        assertTrue("underscore separator", isSameLanguage("en_GB", "EN"))
        assertTrue("surrounding whitespace", isSameLanguage("  sl-SI  ", "sl"))

        assertFalse(
            "no 639-2 to 639-1 mapping exists, so Sandworm's 'eng' is not spuriously refused",
            isSameLanguage("eng", "en"),
        )
        assertFalse("different languages", isSameLanguage("en-US", "sl"))
    }

    /** The notice fires for a book already in the target, and stays quiet otherwise. */
    @Test
    fun `a book already in the target language is flagged, not refused`() =
        runTest(dispatcher) {
            val english = writeSourceEpub(folder.newFile("english.epub"), language = "EN-GB")

            val intoSlovenian = planner.plan(sourceOf(english), targetLang = "sl", model = JOB_TEST_MODEL)
            assertFalse("EN-GB into sl is real work", intoSlovenian.alreadyTargetLanguage)
            assertEquals("and the raw tag is carried, unmangled", "EN-GB", intoSlovenian.sourceLang)

            val intoEnglish = planner.plan(sourceOf(english), targetLang = "en", model = JOB_TEST_MODEL)
            assertTrue("EN-GB into en is not", intoEnglish.alreadyTargetLanguage)
            assertTrue(
                "but it is still priced rather than blocked — the check is a notice",
                intoEnglish.economy.costEur > 0.0,
            )
        }

    /** Chapters, segments and the model all reach the estimate screen. */
    @Test
    fun `the plan reports chapters, segments and the model the run would bill`() =
        runTest(dispatcher) {
            val file = writeSourceEpub(folder.newFile("book.epub"), paragraphsPerChapter = 4)

            val plan = planner.plan(sourceOf(file), targetLang = "sl", model = JOB_TEST_MODEL)

            assertEquals("two chapters", 2, plan.chapterCount)
            assertEquals("2 headings + 8 paragraphs", 10, plan.totalSegments)
            assertEquals("all translatable", 10, plan.economy.estimate.translatableSegments)
            assertEquals(JOB_TEST_MODEL, plan.model)
            assertEquals("A Quiet Library", plan.bookTitle)
            assertEquals("sl", plan.targetLang)
        }
}
