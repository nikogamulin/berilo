package app.berilo.reader.ui.translate

import app.berilo.reader.translate.job.TranslationProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two places the progress screen could quietly lie: a cost rounded to nothing, and a
 * fraction that is not a number.
 */
class TranslateFormattingTest {

    /**
     * A real cost is never rendered as "free".
     *
     * **Mutation-proof:** fix the pattern at `%.2f` and the sub-cent cases render `€0.00`,
     * failing here — a resumed run that legitimately cost a third of a cent would be shown to
     * the user as costing nothing, which is the one number CLAUDE.md §4 exists to keep honest.
     */
    @Test
    fun `costs below a cent keep their digits`() {
        assertEquals("€0.0031", formatEur(0.0031))
        assertEquals("€0.0001", formatEur(0.0001))
        assertEquals("just under the threshold, still four digits", "€0.0900", formatEur(0.09))
        assertEquals("at the threshold, back to cents", "€0.10", formatEur(0.10))
        assertEquals("€0.70", formatEur(0.70))
        assertEquals("€1.45", formatEur(1.45))
        assertEquals("a genuine zero is plain", "€0.00", formatEur(0.0))
    }

    /** The decimal separator never follows the device locale. */
    @Test
    fun `the decimal separator is fixed, so estimate and actual are comparable`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("€1.45", formatEur(1.45))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    /**
     * The progress bar never receives `NaN` or a value outside `0f..1f`.
     *
     * A `NaN` reaching a determinate indicator paints as a full or empty bar depending on the
     * platform — a confidently wrong number in the one place the user is watching for a real
     * one.
     */
    @Test
    fun `the progress fraction is clamped and never NaN`() {
        assertEquals(0f, TranslationProgress(totalSegments = 0, processedSegments = 0).fraction, 0f)
        assertEquals(
            "an empty book is 0, not NaN",
            0f,
            TranslationProgress(totalSegments = 0, processedSegments = 7).fraction,
            0f,
        )
        assertEquals(0.5f, TranslationProgress(totalSegments = 10, processedSegments = 5).fraction, 1e-6f)
        assertEquals(1f, TranslationProgress(totalSegments = 10, processedSegments = 10).fraction, 0f)
        assertEquals(
            "over-counting cannot overflow the bar",
            1f,
            TranslationProgress(totalSegments = 10, processedSegments = 99).fraction,
            0f,
        )
    }
}
