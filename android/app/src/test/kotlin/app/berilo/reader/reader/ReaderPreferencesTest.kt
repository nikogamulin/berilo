package app.berilo.reader.reader

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.robolectric.RobolectricTestRunner

/**
 * Reader preferences: defaults, clamping, SharedPreferences persistence, and the
 * e-ink → Readium [org.readium.r2.navigator.epub.EpubPreferences] mapping that
 * S2.2's e-ink mode depends on. Robolectric supplies the real SharedPreferences
 * implementation.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderPreferencesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults put the reader in e-ink mode at 100 percent`() {
        val prefs = ReaderPreferences()
        assertTrue(prefs.einkMode)
        assertFalse(prefs.darkTheme)
        assertEquals(100, prefs.fontSizePercent)
        assertEquals(100, prefs.pageMarginsPercent)
    }

    @Test
    fun `font size and margins clamp to their supported ranges`() {
        assertEquals(
            ReaderPreferences.MAX_FONT_SIZE_PERCENT,
            ReaderPreferences().withFontSizePercent(9_999).fontSizePercent,
        )
        assertEquals(
            ReaderPreferences.MIN_FONT_SIZE_PERCENT,
            ReaderPreferences().withFontSizePercent(0).fontSizePercent,
        )
        assertEquals(
            ReaderPreferences.MAX_MARGINS_PERCENT,
            ReaderPreferences().withPageMarginsPercent(9_999).pageMarginsPercent,
        )
        assertEquals(
            ReaderPreferences.MIN_MARGINS_PERCENT,
            ReaderPreferences().withPageMarginsPercent(-5).pageMarginsPercent,
        )
    }

    @Test
    fun `SharedPreferences store round-trips custom settings`() {
        val store = SharedPrefsReaderSettingsStore(context)
        val custom = ReaderPreferences(
            fontSizePercent = 130,
            pageMarginsPercent = 150,
            einkMode = false,
            darkTheme = true,
        )

        store.save(custom)
        val reloaded = SharedPrefsReaderSettingsStore(context).load()

        assertEquals(custom, reloaded)
    }

    @Test
    fun `an empty store loads the e-ink defaults`() {
        val loaded = SharedPrefsReaderSettingsStore(context).load()
        assertEquals(ReaderPreferences(), loaded)
    }

    @Test
    fun `e-ink mode maps to a pure black-on-white paginated light theme`() {
        val epub = ReaderPreferences(einkMode = true, fontSizePercent = 100, pageMarginsPercent = 100)
            .toEpubPreferences()

        assertEquals(Theme.LIGHT, epub.theme)
        assertEquals(false, epub.scroll)
        assertEquals("publisher styles off so our B/W colors win", false, epub.publisherStyles)
        assertEquals(FontFamily.SERIF, epub.fontFamily)
        assertEquals(ReadiumColor(AndroidColor.BLACK), epub.textColor)
        assertEquals(ReadiumColor(AndroidColor.WHITE), epub.backgroundColor)
        assertEquals(1.0, epub.fontSize!!, DELTA)
        assertEquals(1.0, epub.pageMargins!!, DELTA)
    }

    @Test
    fun `font size percentage maps to a Readium multiplier`() {
        val epub = ReaderPreferences(fontSizePercent = 150).toEpubPreferences()
        assertEquals(1.5, epub.fontSize!!, DELTA)
    }

    @Test
    fun `OLED dark mode maps to the dark theme without forcing colors`() {
        val epub = ReaderPreferences(einkMode = false, darkTheme = true).toEpubPreferences()

        assertEquals(Theme.DARK, epub.theme)
        assertNull("publisher styles left to the theme in OLED mode", epub.publisherStyles)
        assertNull(epub.textColor)
        assertNull(epub.backgroundColor)
    }

    private companion object {
        const val DELTA = 1e-9
    }
}
