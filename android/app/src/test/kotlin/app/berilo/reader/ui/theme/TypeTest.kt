package app.berilo.reader.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BeriloTypography] mapping: docs/design_guidelines.md mandates exactly one serif (Literata,
 * system-serif fallback until fonts land) for reading content and one sans (Inter, system-sans
 * fallback) for UI chrome — never Material3's default Roboto baseline. S2.7 found titleSmall/
 * labelSmall left undefined (silently falling back to the default), which this test guards
 * against by asserting every slot the app actually references (`MaterialTheme.typography.*`
 * grep) resolves to one of the two intentional families.
 */
class TypeTest {

    @Test
    fun `title and label styles use the UI sans family`() {
        val sansStyles =
            listOf(
                BeriloTypography.headlineSmall,
                BeriloTypography.titleLarge,
                BeriloTypography.titleMedium,
                BeriloTypography.titleSmall,
                BeriloTypography.labelLarge,
                BeriloTypography.labelMedium,
                BeriloTypography.labelSmall,
            )
        sansStyles.forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
        }
    }

    @Test
    fun `body styles use the reading serif family`() {
        val serifStyles = listOf(BeriloTypography.bodyLarge, BeriloTypography.bodyMedium, BeriloTypography.bodySmall)
        serifStyles.forEach { style ->
            assertEquals(FontFamily.Serif, style.fontFamily)
        }
    }

    @Test
    fun `scale is monotonically non-increasing from headline to label-small`() {
        val descendingBySize =
            listOf(
                BeriloTypography.headlineSmall,
                BeriloTypography.titleLarge,
                BeriloTypography.titleMedium,
                BeriloTypography.titleSmall,
                BeriloTypography.labelLarge,
                BeriloTypography.labelMedium,
                BeriloTypography.labelSmall,
            )
        descendingBySize.zipWithNext { larger, smaller ->
            assertTrue(
                "${larger.fontSize} should be >= ${smaller.fontSize}",
                larger.fontSize.value >= smaller.fontSize.value,
            )
        }
    }
}
