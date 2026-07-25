package app.berilo.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// High-contrast, restrained palette (docs/design_guidelines.md): true black
// on true white in light mode (the Boox e-ink default); OLED phones get the
// same layout with the dark scheme below. No low-contrast grays for body text.
//
// S2.11: EVERY ColorScheme role is pinned below, not only the ones app code
// references through `colorScheme.`. Material3 component defaults reach for
// roles nobody named — FloatingActionButton -> primaryContainer,
// LinearProgressIndicator -> secondaryContainer, HorizontalDivider ->
// outlineVariant — so any role left unset silently reintroduces the
// baseline violet palette. Pinning by CLASS (all 48 roles), not by instance
// (the 3 that leaked), is what stops this recurring (CLAUDE.md §9,
// docs/findings.md 2026-07-25). Secondary and tertiary collapse onto the
// same amber/neutral tones as primary rather than a second or third hue —
// this is a one-accent design (design_guidelines.md principle 4) with no
// extra tone to spend.
//
// internal (not private): ThemeContrastTest asserts every role of both
// schemes below avoids the M3 baseline-violet hue band, so this class of
// regression can't return (S2.11's Verify line).
internal val LightColors =
    lightColorScheme(
        primary = BerilloAmber,
        onPrimary = Paper,
        primaryContainer = AmberContainerLight,
        onPrimaryContainer = OnAmberContainerLight,
        inversePrimary = BerilloAmberDark,
        secondary = BerilloAmber,
        onSecondary = Paper,
        secondaryContainer = InkLight,
        onSecondaryContainer = Ink,
        tertiary = BerilloAmber,
        onTertiary = Paper,
        tertiaryContainer = InkLight,
        onTertiaryContainer = Ink,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = InkLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        // Flat by intent: a tint equal to the surface means tonal elevation
        // (Surface(tonalElevation = …), used by ReaderTopBar/BottomBar/
        // settings panels) adds no colour — no faux depth on e-ink.
        surfaceTint = Paper,
        inverseSurface = Ink,
        inverseOnSurface = Paper,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        scrim = Ink,
        surfaceBright = Paper,
        surfaceDim = SurfaceDimLight,
        surfaceContainerLowest = Paper,
        surfaceContainerLow = Paper,
        surfaceContainer = InkLight,
        surfaceContainerHigh = InkLight,
        surfaceContainerHighest = InkLight,
        // "Fixed" roles are constant across theme switches by M3 design —
        // pinned to the same amber/neutral tones both schemes already use.
        primaryFixed = BerilloAmber,
        primaryFixedDim = BerilloAmberDark,
        onPrimaryFixed = Paper,
        onPrimaryFixedVariant = Ink,
        secondaryFixed = BerilloAmber,
        secondaryFixedDim = BerilloAmberDark,
        onSecondaryFixed = Paper,
        onSecondaryFixedVariant = Ink,
        tertiaryFixed = BerilloAmber,
        tertiaryFixedDim = BerilloAmberDark,
        onTertiaryFixed = Paper,
        onTertiaryFixedVariant = Ink,
    )

internal val DarkColors =
    darkColorScheme(
        primary = BerilloAmberDark,
        onPrimary = Ink,
        primaryContainer = AmberContainerDark,
        onPrimaryContainer = OnAmberContainerDark,
        inversePrimary = BerilloAmber,
        secondary = BerilloAmberDark,
        onSecondary = Ink,
        secondaryContainer = InkDarkVariant,
        onSecondaryContainer = Paper,
        tertiary = BerilloAmberDark,
        onTertiary = Ink,
        tertiaryContainer = InkDarkVariant,
        onTertiaryContainer = Paper,
        background = PaperDark,
        onBackground = Paper,
        surface = PaperDark,
        onSurface = Paper,
        surfaceVariant = InkDarkVariant,
        onSurfaceVariant = OnSurfaceVariantDark,
        surfaceTint = PaperDark,
        inverseSurface = Paper,
        inverseOnSurface = Ink,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        scrim = Ink,
        surfaceBright = SurfaceBrightDark,
        surfaceDim = PaperDark,
        surfaceContainerLowest = PaperDark,
        surfaceContainerLow = PaperDark,
        surfaceContainer = InkDarkVariant,
        surfaceContainerHigh = InkDarkVariant,
        surfaceContainerHighest = InkDarkVariant,
        // "Fixed" roles are constant across theme switches by M3 design —
        // same values as LightColors above, deliberately theme-invariant.
        primaryFixed = BerilloAmber,
        primaryFixedDim = BerilloAmberDark,
        onPrimaryFixed = Paper,
        onPrimaryFixedVariant = Ink,
        secondaryFixed = BerilloAmber,
        secondaryFixedDim = BerilloAmberDark,
        onSecondaryFixed = Paper,
        onSecondaryFixedVariant = Ink,
        tertiaryFixed = BerilloAmber,
        tertiaryFixedDim = BerilloAmberDark,
        onTertiaryFixed = Paper,
        onTertiaryFixedVariant = Ink,
    )

@Composable
fun BeriloTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, typography = BeriloTypography, content = content)
}
