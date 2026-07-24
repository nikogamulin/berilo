package app.berilo.reader.store.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A highlight or note anchored to a position in a book (S2.6).
 *
 * A highlight and a note share one row: [note] is null for a plain color highlight and
 * non-null for one carrying note text — the reader's "Highlight" and "Note" chrome actions
 * both go through the same creation path, differing only in whether note text is supplied.
 *
 * [chapterTitle] is denormalized from the locator's own `title` at creation time (the same
 * source `ReaderChrome`'s chapter-title display already reads) rather than re-decoded from
 * [locatorJson] on every notebook render or export: it keeps the notebook's chapter grouping
 * and the Markdown exporter pure-Kotlin and Robolectric-free, while [locatorJson] remains the
 * source of truth for the jump-back-into-book and decoration-rendering paths, which are
 * inherently Readium/device territory.
 *
 * @property id Stable random id (UUID string) — the notebook's edit/delete key.
 * @property bookId FK-shaped reference to [BookEntity.id] (no `@ForeignKey`: no other table in
 *   this schema declares one either — see `docs/findings.md`/entity conventions).
 * @property color The highlight's fill color.
 * @property selectedText The exact source text the user selected.
 * @property note Optional note text attached to the highlight, or null for a plain highlight.
 * @property locatorJson Readium Locator JSON ([app.berilo.reader.reader.LocatorCodec]) of the
 *   selection's position.
 * @property chapterTitle The enclosing chapter's title at creation time, or null if the
 *   locator carried none (falls back to an "Untitled" bucket in the notebook/export).
 * @property createdAt Epoch-millis timestamp of creation.
 * @property updatedAt Epoch-millis timestamp of the last edit (color/note change).
 */
@Entity(tableName = "highlights", indices = [Index("bookId")])
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val color: HighlightColor,
    val selectedText: String,
    val note: String?,
    val locatorJson: String,
    val chapterTitle: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
