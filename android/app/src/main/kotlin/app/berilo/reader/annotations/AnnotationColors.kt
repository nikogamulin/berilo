package app.berilo.reader.annotations

import androidx.compose.ui.graphics.Color
import app.berilo.reader.store.db.HighlightColor

/**
 * Compose fill for each [HighlightColor] — muted, distinct-luminance swatches per
 * `docs/design_guidelines.md` ("4 muted fills that stay legible in grayscale"). Distinct hues
 * at broadly separated lightness so the 4 still read apart when desaturated on e-ink.
 */
fun HighlightColor.toComposeColor(): Color =
    when (this) {
        HighlightColor.AMBER -> Color(0xFFE8B75B)
        HighlightColor.SAGE -> Color(0xFFA9C79A)
        HighlightColor.SKY -> Color(0xFF93B7D6)
        HighlightColor.ROSE -> Color(0xFFD79A9A)
    }

/** The fixed display order the color row/notebook color picker present, alphabetical is
 * arbitrary here so this is the one place the order is decided. */
val HIGHLIGHT_COLOR_ORDER = listOf(HighlightColor.AMBER, HighlightColor.SAGE, HighlightColor.SKY, HighlightColor.ROSE)
