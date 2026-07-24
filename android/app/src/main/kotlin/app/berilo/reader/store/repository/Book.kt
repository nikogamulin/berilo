package app.berilo.reader.store.repository

import app.berilo.reader.store.db.BookEntity

/** UI-layer book model, decoupled from the Room [BookEntity] annotations. */
data class Book(
    val id: String,
    val title: String,
    val authors: String,
    val filePath: String,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progressFraction: Float?,
)

/** Maps a persisted [BookEntity] to the [Book] the UI layer consumes. */
fun BookEntity.toDomain(): Book =
    Book(
        id = id,
        title = title,
        authors = authors,
        filePath = filePath,
        coverPath = coverPath,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        progressFraction = null, // Locator-JSON parsing lands with S2.2 position persistence.
    )
