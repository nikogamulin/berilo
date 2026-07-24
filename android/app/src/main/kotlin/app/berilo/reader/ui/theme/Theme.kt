package app.berilo.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// High-contrast, restrained palette (docs/design_guidelines.md): true black
// on true white in light mode (the Boox e-ink default); OLED phones get the
// same layout with the dark scheme below. No low-contrast grays for body text.
private val LightColors =
    lightColorScheme(
        primary = BerilloAmber,
        onPrimary = Paper,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = InkLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        error = ErrorLight,
        onError = OnErrorLight,
    )

private val DarkColors =
    darkColorScheme(
        primary = BerilloAmberDark,
        onPrimary = Ink,
        background = PaperDark,
        onBackground = Paper,
        surface = PaperDark,
        onSurface = Paper,
        surfaceVariant = InkDarkVariant,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        error = ErrorDark,
        onError = OnErrorDark,
    )

@Composable
fun BeriloTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, typography = BeriloTypography, content = content)
}
