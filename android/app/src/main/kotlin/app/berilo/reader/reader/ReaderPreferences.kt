package app.berilo.reader.reader

import android.graphics.Color as AndroidColor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

/**
 * User-adjustable reader typography and rendering mode, independent of Readium
 * so it can be persisted and unit-tested without the navigator runtime.
 *
 * E-ink mode is the default because the primary device is a Boox e-ink tablet
 * (design_guidelines.md §2): it forces a pure black-on-white theme with no
 * publisher colors, which [toEpubPreferences] maps onto the navigator.
 *
 * @property fontSizePercent Body font size as a percentage of the base size,
 *   clamped to [MIN_FONT_SIZE_PERCENT]..[MAX_FONT_SIZE_PERCENT].
 * @property pageMarginsPercent Page margin factor as a percentage, clamped to
 *   [MIN_MARGINS_PERCENT]..[MAX_MARGINS_PERCENT].
 * @property einkMode When true, render pure B/W with no page-turn animation and
 *   a full-refresh hint (the Boox default). When false, publisher/theme colors.
 * @property darkTheme Only consulted when [einkMode] is false: OLED dark theme.
 */
data class ReaderPreferences(
    val fontSizePercent: Int = DEFAULT_FONT_SIZE_PERCENT,
    val pageMarginsPercent: Int = DEFAULT_MARGINS_PERCENT,
    val einkMode: Boolean = true,
    val darkTheme: Boolean = false,
) {

    /** Returns a copy with [percent] clamped into the supported font-size range. */
    fun withFontSizePercent(percent: Int): ReaderPreferences =
        copy(fontSizePercent = percent.coerceIn(MIN_FONT_SIZE_PERCENT, MAX_FONT_SIZE_PERCENT))

    /** Returns a copy with [percent] clamped into the supported margins range. */
    fun withPageMarginsPercent(percent: Int): ReaderPreferences =
        copy(pageMarginsPercent = percent.coerceIn(MIN_MARGINS_PERCENT, MAX_MARGINS_PERCENT))

    /**
     * Projects these preferences onto Readium [EpubPreferences].
     *
     * In e-ink mode the mapping pins a light theme, disables publisher styles
     * and sets pure black text on a pure white background so nothing renders in
     * a low-contrast gray on the Boox. Body text always uses a serif family
     * (Literata is the design's serif; the device falls back to its system
     * serif when Literata is absent). Scrolling is always off — the reader is
     * paginated. The no-animation / full-refresh behaviour of e-ink mode is not
     * expressible as an [EpubPreferences] field; it is applied by the navigator
     * host (animated=false page turns + a full-refresh invalidate).
     */
    @OptIn(ExperimentalReadiumApi::class)
    fun toEpubPreferences(): EpubPreferences {
        val theme = when {
            einkMode -> Theme.LIGHT
            darkTheme -> Theme.DARK
            else -> Theme.LIGHT
        }
        return EpubPreferences(
            theme = theme,
            fontFamily = FontFamily.SERIF,
            fontSize = fontSizePercent / PERCENT,
            pageMargins = pageMarginsPercent / PERCENT,
            scroll = false,
            publisherStyles = if (einkMode) false else null,
            textColor = if (einkMode) ReadiumColor(AndroidColor.BLACK) else null,
            backgroundColor = if (einkMode) ReadiumColor(AndroidColor.WHITE) else null,
        )
    }

    companion object {
        const val DEFAULT_FONT_SIZE_PERCENT = 100
        const val MIN_FONT_SIZE_PERCENT = 50
        const val MAX_FONT_SIZE_PERCENT = 250
        const val FONT_SIZE_STEP_PERCENT = 10

        const val DEFAULT_MARGINS_PERCENT = 100
        const val MIN_MARGINS_PERCENT = 50
        const val MAX_MARGINS_PERCENT = 300
        const val MARGINS_STEP_PERCENT = 25

        private const val PERCENT = 100.0
    }
}
