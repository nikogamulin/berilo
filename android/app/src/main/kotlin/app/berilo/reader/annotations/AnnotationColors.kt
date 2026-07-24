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
        // Perceptual luma (0.299R+0.587G+0.114B) ≈ 187 — third band.
        HighlightColor.AMBER -> Color(0xFFE8B75B)
        // ≈ 211 — lightest band.
        HighlightColor.SAGE -> Color(0xFFC9DDBB)
        // ≈ 142 — second band.
        HighlightColor.SKY -> Color(0xFF7195B8)
        // ≈ 114 — darkest band. Four bands stay apart when desaturated on e-ink.
        HighlightColor.ROSE -> Color(0xFF9E5F5F)
    }

/** The fixed display order the color row/notebook color picker present, alphabetical is
 * arbitrary here so this is the one place the order is decided. */
val HIGHLIGHT_COLOR_ORDER = listOf(HighlightColor.AMBER, HighlightColor.SAGE, HighlightColor.SKY, HighlightColor.ROSE)
