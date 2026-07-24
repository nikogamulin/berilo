package app.berilo.reader.store.db

import androidx.room.TypeConverter

/**
 * The 4 highlight colors offered by the reader (S2.6), per `docs/design_guidelines.md`'s "4
 * muted fills that stay legible in grayscale" — chosen to read as distinct gray levels on
 * e-ink, not just distinct hues. Compose color values live in
 * `app.berilo.reader.annotations` (UI layer); this enum is the persisted, UI-free identity.
 *
 * Never reorder or rename members: [HighlightColorConverters] persists [Enum.name], not
 * [Enum.ordinal], but a rename still orphans existing rows.
 */
enum class HighlightColor { AMBER, SAGE, SKY, ROSE }

/** Room [TypeConverter]s for [HighlightColor], stored as its stable [Enum.name]. */
class HighlightColorConverters {

    @TypeConverter
    fun fromHighlightColor(color: HighlightColor): String = color.name

    @TypeConverter
    fun toHighlightColor(value: String): HighlightColor = HighlightColor.valueOf(value)
}
