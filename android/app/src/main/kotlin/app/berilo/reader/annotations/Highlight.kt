package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightColor
import app.berilo.reader.store.db.HighlightEntity

/** UI-layer highlight/note model, decoupled from the Room [HighlightEntity] annotations. */
data class Highlight(
    val id: String,
    val bookId: String,
    val color: HighlightColor,
    val selectedText: String,
    val note: String?,
    val locatorJson: String,
    val chapterTitle: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Maps a persisted [HighlightEntity] to the [Highlight] the UI layer consumes. */
fun HighlightEntity.toDomain(): Highlight =
    Highlight(
        id = id,
        bookId = bookId,
        color = color,
        selectedText = selectedText,
        note = note,
        locatorJson = locatorJson,
        chapterTitle = chapterTitle,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** Maps a [Highlight] back to the [HighlightEntity] Room persists. */
fun Highlight.toEntity(): HighlightEntity =
    HighlightEntity(
        id = id,
        bookId = bookId,
        color = color,
        selectedText = selectedText,
        note = note,
        locatorJson = locatorJson,
        chapterTitle = chapterTitle,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
