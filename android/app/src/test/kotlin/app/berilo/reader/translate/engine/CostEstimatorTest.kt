package app.berilo.reader.translate.engine

import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.BOOK_CONTEXT
import app.berilo.reader.translate.prompts.REVISE
import app.berilo.reader.translate.prompts.StyleLanguageError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dry-run cost estimator (B5), which B7 gates all spending on (CLAUDE.md §4).
 *
 * Every figure below is arithmetic over the ported constants — no API call, no key, EUR 0.
 */
class CostEstimatorTest {

    private val cheapModel = "claude-haiku-4-5" // not a reasoning-billing model

    @Test
    fun `an estimate makes no API calls and counts every segment`() {
        val book = bookOf(0 to "A".repeat(400), 0 to "   ", 1 to "B".repeat(400))

        val estimate = estimateCost(book, cheapModel, "sl", style = BASELINE)

        assertEquals(3, estimate.totalSegments)
        assertEquals(2, estimate.translatableSegments)
        assertEquals(1, estimate.emptySegments)
        assertEquals(0, estimate.skippedSegments)
        assertEquals("one batch per chapter", 2, estimate.batches)
        assertEquals(BASELINE.version, estimate.promptVersion)
    }

    @Test
    fun `skipped segments are excluded from the priced total`() {
        val book = bookOf(0 to "A".repeat(400), 0 to "B".repeat(400))

        val estimate =
            estimateCost(
                book, cheapModel, "sl", style = BASELINE,
                skipSegmentIds = setOf(book.segments[1].id),
            )

        assertEquals(1, estimate.translatableSegments)
        assertEquals(1, estimate.skippedSegments)
    }

    /** Chars/4 source, x1.1 target, plus 400 prompt-overhead tokens per batch. */
    @Test
    fun `chapter token counts follow the chars over four heuristic`() {
        val book = bookOf(0 to "x".repeat(4_000))

        val chapter = estimateCost(book, cheapModel, "sl", style = BASELINE).chapters.single()

        assertEquals(1_000 + PROMPT_OVERHEAD_TOKENS_PER_BATCH, chapter.inputTokens)
        assertEquals(1_100, chapter.outputTokens)
        assertEquals(1, chapter.segments)
    }

    @Test
    fun `batches are ceiling-divided per chapter, never across chapters`() {
        val book =
            bookOf(
                *(1..11).map { 0 to "A$it" }.toTypedArray(),
                *(1..3).map { 1 to "B$it" }.toTypedArray(),
            )

        val estimate = estimateCost(book, cheapModel, "sl", style = BASELINE)

        // Chapter 0: ceil(11/10) = 2. Chapter 1: ceil(3/10) = 1. Eleven segments in one chapter
        // cost two batches; the same eleven split across two chapters would cost two as well —
        // the point is that the division never runs across a boundary.
        assertEquals(3, estimate.batches)
        assertEquals(listOf(11, 3), estimate.chapters.map(ChapterEstimate::segments))
        assertEquals(listOf(0, 1), estimate.chapters.map(ChapterEstimate::index))
    }

    /**
     * A revising style adds one editor call per batch and roughly doubles the bill. That is the
     * "~2x cost" figure B7's quality toggle must show the user before they can approve it.
     */
    @Test
    fun `a revising style prices one extra editor call per batch`() {
        val book = bookOf(0 to "x".repeat(4_000))

        val economy = estimateCost(book, cheapModel, "sl", style = BASELINE)
        val quality = estimateCost(book, cheapModel, "sl", style = REVISE)

        assertEquals(0, economy.revisionCalls)
        assertEquals(1, quality.revisionCalls)
        assertTrue(
            "quality must cost more (${quality.costEur} vs ${economy.costEur})",
            quality.costEur > economy.costEur,
        )
        assertTrue("and roughly twice as much", quality.costEur > 1.5 * economy.costEur)
    }

    @Test
    fun `a book-context style prices exactly one memo call`() {
        val book = bookOf(0 to "x".repeat(400))

        assertEquals(0, estimateCost(book, cheapModel, "sl", style = BASELINE).bookContextCalls)
        assertEquals(1, estimateCost(book, cheapModel, "sl", style = BOOK_CONTEXT).bookContextCalls)
    }

    /**
     * Reasoning models bill hidden reasoning tokens as output; a naive chars/4 estimate
     * underestimates them badly (`docs/findings.md`, 2026-07-24: 479 output tokens for a
     * ~15-token visible translation).
     */
    @Test
    fun `reasoning-billing models carry a fixed per-call output surcharge`() {
        val book = bookOf(0 to "x".repeat(4_000))

        val plain = estimateCost(book, cheapModel, "sl", style = BASELINE, glossary = false)
        val reasoning = estimateCost(book, "gpt-5-mini", "sl", style = BASELINE, glossary = false)

        assertEquals(0, plain.reasoningTokens)
        assertEquals("one batch call", REASONING_TOKENS_PER_CALL, reasoning.reasoningTokens)
        assertEquals(
            "the surcharge lands in output tokens",
            plain.outputTokens + REASONING_TOKENS_PER_CALL,
            reasoning.outputTokens,
        )
    }

    @Test
    fun `the glossary call is priced when requested and omitted when not`() {
        val book = bookOf(0 to "x".repeat(400))

        val with = estimateCost(book, cheapModel, "sl", style = BASELINE, glossary = true)
        val without = estimateCost(book, cheapModel, "sl", style = BASELINE, glossary = false)

        assertEquals(
            DEFAULT_MAX_SAMPLE_CHARS / CHARS_PER_TOKEN,
            with.inputTokens - without.inputTokens,
        )
        assertTrue(with.costEur > without.costEur)
    }

    @Test
    fun `a book with nothing translatable is priced at zero, glossary included`() {
        val book = bookOf(0 to "   ", 0 to "")

        val estimate = estimateCost(book, cheapModel, "sl", style = BASELINE)

        assertEquals(0, estimate.batches)
        assertEquals(0, estimate.translatableSegments)
        assertEquals("no chapters means no glossary call either", 0.0, estimate.costEur, 1e-12)
    }

    /** The dry run refuses a contradictory pair for the same reason the paid run does. */
    @Test
    fun `a style that contradicts the target language is refused before a price is quoted`() {
        assertThrows(StyleLanguageError::class.java) {
            estimateCost(bookOf(0 to "x"), cheapModel, "de", style = REVISE)
        }
    }

    /**
     * `TARGET_EXPANSION = 1.1` is a **Slovenian** assumption applied to every language (A3
     * reported it; ported as-is deliberately). Pinned here so a silent change is caught: the CLI
     * and the tablet must quote the same price for the same job.
     */
    @Test
    fun `the target expansion constant stays pinned to the CLI's value`() {
        assertEquals(1.1, TARGET_EXPANSION, 1e-12)
        assertEquals(4, CHARS_PER_TOKEN)
        assertEquals(400, PROMPT_OVERHEAD_TOKENS_PER_BATCH)
        assertEquals(464, REASONING_TOKENS_PER_CALL)
        assertEquals(listOf("gpt-5", "o1", "o3", "o4"), REASONING_BILLING_MODEL_PREFIXES)
    }
}
