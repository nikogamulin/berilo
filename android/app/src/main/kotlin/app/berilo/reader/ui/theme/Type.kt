package app.berilo.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Literata (serif, full Slovenian diacritics) for body text, Inter (sans) for
// UI chrome per docs/design_guidelines.md. Custom font files land with the
// S2.2/S2.7 design pass; system serif/sans-serif are safe defaults until then.
private val BodySerif = FontFamily.Serif
private val UiSans = FontFamily.SansSerif

// Modular scale ~1.25 (design_guidelines.md §"Typographic restraint"), UI sans
// for titles/labels/chrome, body serif for reading content. Every Typography
// slot the app actually references (grep `MaterialTheme.typography.`) is
// defined here so nothing silently falls back to Material3's default Roboto
// baseline (S2.7: titleSmall/labelSmall were previously undefined).
val BeriloTypography =
    Typography(
        headlineSmall =
            TextStyle(fontFamily = UiSans, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge =
            TextStyle(fontFamily = UiSans, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium =
            TextStyle(fontFamily = UiSans, fontSize = 17.sp, lineHeight = 24.sp),
        titleSmall =
            TextStyle(fontFamily = UiSans, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge =
            TextStyle(fontFamily = BodySerif, fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium =
            TextStyle(fontFamily = BodySerif, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall =
            TextStyle(fontFamily = BodySerif, fontSize = 13.sp, lineHeight = 20.sp),
        labelLarge =
            TextStyle(fontFamily = UiSans, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium =
            TextStyle(fontFamily = UiSans, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall =
            TextStyle(fontFamily = UiSans, fontSize = 12.sp, lineHeight = 16.sp),
    )
