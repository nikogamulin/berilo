package app.berilo.reader.interpretation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassageSelectionTest {

    @Test
    fun `a non-blank selection is used as-is`() {
        val resolved = resolveInterpretationPassage("She walked into the fog.", "Some other visible text.")

        assertEquals("She walked into the fog.", resolved)
    }

    @Test
    fun `an empty selection falls back to the visible locator's highlight`() {
        val resolved = resolveInterpretationPassage("", "The currently visible paragraph.")

        assertEquals("The currently visible paragraph.", resolved)
    }

    @Test
    fun `a null selection falls back to the visible locator's highlight`() {
        val resolved = resolveInterpretationPassage(null, "The currently visible paragraph.")

        assertEquals("The currently visible paragraph.", resolved)
    }

    @Test
    fun `a blank (whitespace-only) selection falls back too`() {
        val resolved = resolveInterpretationPassage("   \n", "The currently visible paragraph.")

        assertEquals("The currently visible paragraph.", resolved)
    }

    @Test
    fun `neither selection nor visible highlight resolves to null`() {
        assertNull(resolveInterpretationPassage(null, null))
        assertNull(resolveInterpretationPassage("", "  "))
    }
}
