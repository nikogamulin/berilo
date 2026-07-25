package app.berilo.reader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * S2.11 regression cover: `Theme.kt` pins only 11 roles until this story, so Material3
 * component *defaults* (FloatingActionButton -> primaryContainer, LinearProgressIndicator ->
 * secondaryContainer, HorizontalDivider -> outlineVariant, …) reached the ~35 roles nobody
 * named and fell back to baseline violet — invisible to any grep of app code, because every
 * role app code references explicitly through `colorScheme.` *was* pinned
 * (docs/findings.md, 2026-07-25).
 *
 * This asserts, by CLASS rather than by instance (CLAUDE.md §9), that every one of the 48
 * [ColorScheme] roles [LightColors] and [DarkColors] set avoids the M3 baseline-violet hue
 * band the S2.10 harness's pixel scan flags: hue 0.63-0.85, saturation > 0.05, value > 0.15.
 * A future role left unpinned (e.g. after a Material3 upgrade adds one) would need adding to
 * [allRoles] to be covered — the same maintenance the upgrade already requires in `Theme.kt`.
 */
class ThemeContrastTest {

    /** hue in [0,1], saturation in [0,1], value in [0,1] — same convention as the HSV scan. */
    private data class Hsv(val hue: Float, val saturation: Float, val value: Float)

    /**
     * Standard RGB->HSV (not java.awt.Color.RGBtoHSB: java.awt isn't resolvable against the
     * android.jar stub this module's JVM unit tests compile against). [Color.red]/[green]/
     * [blue] are already 0..1 floats, so this skips the 0..255 round-trip entirely.
     */
    private fun Color.toHsv(): Hsv {
        val maxC = max(red, max(green, blue))
        val minC = min(red, min(green, blue))
        val delta = maxC - minC
        val value = maxC
        val saturation = if (maxC == 0f) 0f else delta / maxC
        val hueDegrees =
            when {
                delta == 0f -> 0f
                maxC == red -> 60f * (((green - blue) / delta) % 6f)
                maxC == green -> 60f * ((blue - red) / delta + 2f)
                else -> 60f * ((red - green) / delta + 4f)
            }.let { if (it < 0f) it + 360f else it }
        return Hsv(hueDegrees / 360f, saturation, value)
    }

    private fun isBaselineViolet(color: Color): Boolean {
        val (hue, saturation, value) = color.toHsv()
        return hue in 0.63f..0.85f && saturation > 0.05f && value > 0.15f
    }

    /** All 48 named roles `lightColorScheme()`/`darkColorScheme()` accept, by name. */
    private fun ColorScheme.allRoles(): List<Pair<String, Color>> =
        listOf(
            "primary" to primary,
            "onPrimary" to onPrimary,
            "primaryContainer" to primaryContainer,
            "onPrimaryContainer" to onPrimaryContainer,
            "inversePrimary" to inversePrimary,
            "secondary" to secondary,
            "onSecondary" to onSecondary,
            "secondaryContainer" to secondaryContainer,
            "onSecondaryContainer" to onSecondaryContainer,
            "tertiary" to tertiary,
            "onTertiary" to onTertiary,
            "tertiaryContainer" to tertiaryContainer,
            "onTertiaryContainer" to onTertiaryContainer,
            "background" to background,
            "onBackground" to onBackground,
            "surface" to surface,
            "onSurface" to onSurface,
            "surfaceVariant" to surfaceVariant,
            "onSurfaceVariant" to onSurfaceVariant,
            "surfaceTint" to surfaceTint,
            "inverseSurface" to inverseSurface,
            "inverseOnSurface" to inverseOnSurface,
            "error" to error,
            "onError" to onError,
            "errorContainer" to errorContainer,
            "onErrorContainer" to onErrorContainer,
            "outline" to outline,
            "outlineVariant" to outlineVariant,
            "scrim" to scrim,
            "surfaceBright" to surfaceBright,
            "surfaceContainer" to surfaceContainer,
            "surfaceContainerHigh" to surfaceContainerHigh,
            "surfaceContainerHighest" to surfaceContainerHighest,
            "surfaceContainerLow" to surfaceContainerLow,
            "surfaceContainerLowest" to surfaceContainerLowest,
            "surfaceDim" to surfaceDim,
            "primaryFixed" to primaryFixed,
            "primaryFixedDim" to primaryFixedDim,
            "onPrimaryFixed" to onPrimaryFixed,
            "onPrimaryFixedVariant" to onPrimaryFixedVariant,
            "secondaryFixed" to secondaryFixed,
            "secondaryFixedDim" to secondaryFixedDim,
            "onSecondaryFixed" to onSecondaryFixed,
            "onSecondaryFixedVariant" to onSecondaryFixedVariant,
            "tertiaryFixed" to tertiaryFixed,
            "tertiaryFixedDim" to tertiaryFixedDim,
            "onTertiaryFixed" to onTertiaryFixed,
            "onTertiaryFixedVariant" to onTertiaryFixedVariant,
        )

    @Test
    fun `every LightColors role avoids the M3 baseline-violet hue band`() {
        LightColors.allRoles().forEach { (name, color) ->
            assertTrue("LightColors.$name ($color) is baseline violet", !isBaselineViolet(color))
        }
    }

    @Test
    fun `every DarkColors role avoids the M3 baseline-violet hue band`() {
        DarkColors.allRoles().forEach { (name, color) ->
            assertTrue("DarkColors.$name ($color) is baseline violet", !isBaselineViolet(color))
        }
    }

    @Test
    fun `all 48 ColorScheme roles are enumerated`() {
        // Guards the test itself: if a future M3 bump adds/removes a role and this list falls
        // out of sync, fail loudly here rather than silently covering fewer roles than exist.
        assertTrue(
            "expected 48 roles, found ${LightColors.allRoles().size} — update allRoles() to match",
            LightColors.allRoles().size == 48,
        )
    }
}
