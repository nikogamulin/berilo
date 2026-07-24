package app.berilo.reader.ui.theme

import androidx.compose.ui.graphics.Color

// One accent color per docs/design_guidelines.md: deep amber, bookish, still
// legible as dark gray on e-ink.
val BerilloAmber = Color(0xFFB45309)
val BerilloAmberDark = Color(0xFFF3AA5C)

val Ink = Color(0xFF1A1A1A)
val Paper = Color(0xFFFFFFFF)
val PaperDark = Color(0xFF121212)
val InkLight = Color(0xFFF2F2F2)

// Neutral grays for secondary text / borders, tuned to WCAG AA against their
// paired surface (S2.7 design pass — the Material3 baseline defaults these
// override are hue-tinted purple, which reads as a second accent color and
// violates docs/design_guidelines.md principle 4 ("one accent color")).
// OnSurfaceVariant: 6.9:1 (light) / 11.1:1 (dark), both well above the 4.5:1
// text minimum. Outline: 4.6:1 (light) / 4.1:1 (dark), above the 3:1 non-text
// UI-component minimum.
val OnSurfaceVariantLight = Color(0xFF5A5A5A)
val OutlineLight = Color(0xFF757575)
val OnSurfaceVariantDark = Color(0xFFC7C7C7)
val OutlineDark = Color(0xFF757575)
val InkDarkVariant = Color(0xFF2A2A2A)

// Standard Material error red, pinned explicitly (matches the M3 baseline we
// relied on implicitly before) so the palette is fully intentional rather
// than partly inherited defaults.
val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
