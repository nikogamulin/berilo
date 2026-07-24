package app.berilo.reader.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists [ReaderPreferences]. An interface so the [ReaderViewModel] can be
 * unit-tested against an in-memory fake without a real Android [Context].
 */
interface ReaderSettingsStore {
    fun load(): ReaderPreferences

    fun save(preferences: ReaderPreferences)
}

/**
 * [ReaderSettingsStore] backed by a private [SharedPreferences] file. Reader
 * typography settings are non-secret and device-local, so plain
 * SharedPreferences is the right store (API keys use EncryptedSharedPreferences
 * — see S2.3, a different concern).
 *
 * Values read back through the min/max clamps in [ReaderPreferences], so a file
 * hand-edited or written by an older build can never push the reader into an
 * unusable font size or margin.
 */
class SharedPrefsReaderSettingsStore(
    private val prefs: SharedPreferences,
) : ReaderSettingsStore {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    override fun load(): ReaderPreferences {
        val defaults = ReaderPreferences()
        return ReaderPreferences(
            einkMode = prefs.getBoolean(KEY_EINK, defaults.einkMode),
            darkTheme = prefs.getBoolean(KEY_DARK, defaults.darkTheme),
        )
            .withFontSizePercent(prefs.getInt(KEY_FONT_SIZE, defaults.fontSizePercent))
            .withPageMarginsPercent(prefs.getInt(KEY_MARGINS, defaults.pageMarginsPercent))
    }

    override fun save(preferences: ReaderPreferences) {
        prefs.edit {
            putInt(KEY_FONT_SIZE, preferences.fontSizePercent)
            putInt(KEY_MARGINS, preferences.pageMarginsPercent)
            putBoolean(KEY_EINK, preferences.einkMode)
            putBoolean(KEY_DARK, preferences.darkTheme)
        }
    }

    private companion object {
        const val PREFS_NAME = "reader_settings"
        const val KEY_FONT_SIZE = "font_size_percent"
        const val KEY_MARGINS = "page_margins_percent"
        const val KEY_EINK = "eink_mode"
        const val KEY_DARK = "dark_theme"
    }
}
