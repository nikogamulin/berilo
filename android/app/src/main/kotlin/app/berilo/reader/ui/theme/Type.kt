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

val BeriloTypography =
    Typography(
        titleLarge =
            TextStyle(fontFamily = UiSans, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium =
            TextStyle(fontFamily = UiSans, fontSize = 17.sp, lineHeight = 24.sp),
        bodyLarge =
            TextStyle(fontFamily = BodySerif, fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium =
            TextStyle(fontFamily = BodySerif, fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge =
            TextStyle(fontFamily = UiSans, fontSize = 14.sp, lineHeight = 20.sp),
    )
