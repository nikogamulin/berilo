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
 * @property sourceLang BCP-47 primary subtag of the original language (e.g. `"en"`), or null
 *   when the EPUB declared none. S3.2: fills `books_metadata.source_lang` ([OPEN-2]).
 * @property targetLang BCP-47 primary subtag of the translation (e.g. `"sl"`), or null.
 * @property updatedAt Epoch-millis timestamp of the last change to *syncable* fields. Drives
 *   last-write-wins on push (`docs/sync_api.md` §1.3) and, compared against the entity's push
 *   watermark, is what marks a row dirty. Deliberately not bumped by a page turn: reading
 *   position rides the `progress` entity, which has its own row and its own timestamp.
 * @property deletedAt Epoch-millis tombstone, or null for a live row. A deleted book keeps its
 *   metadata row so the delete propagates to other devices; only the on-disk file is destroyed.
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
    val sourceLang: String? = null,
    val targetLang: String? = null,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
)
