package app.berilo.reader.store.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A book imported into the library.
 *
 * [id] is the SHA-256 content hash of the EPUB file, which doubles as the
 * dedupe key: importing the same file twice never creates a second row.
 *
 * @property id Content hash of the source file (hex-encoded SHA-256).
 * @property title Book title, from EPUB metadata or the filename fallback.
 * @property authors Comma-joined author names ("Unknown" if none present).
 * @property filePath Absolute path to the app-private copy under `filesDir/books/`.
 * @property coverPath Absolute path to the extracted cover image, or null.
 * @property addedAt Epoch-millis timestamp of import.
 * @property lastOpenedAt Epoch-millis timestamp of the most recent open, or null.
 * @property progressionJson Readium Locator JSON of the last reading position, or null.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val authors: String,
    val filePath: String,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progressionJson: String?,
)
