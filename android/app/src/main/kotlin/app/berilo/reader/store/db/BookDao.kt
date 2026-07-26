package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room data-access object for [BookEntity].
 *
 * Every app-facing read filters `deletedAt IS NULL`. S3.2 turned deletion into a tombstone so
 * it can propagate to other devices, which means a deleted row is still physically present —
 * anything that forgets the filter would show the user a book they deleted. The sync-facing
 * queries at the bottom are the deliberate exception and say so in their names.
 */
@Dao
interface BookDao {

    /**
     * The library listing: live books whose EPUB is actually on this device.
     *
     * `filePath != ''` is load-bearing. Sync can create a metadata row for a book imported on
     * another device — title and authors travel, the file never does (CLAUDE.md §2) — and
     * listing that row would offer the user a book that cannot be opened. The row still exists
     * so its highlights have a parent and the title is on record; it simply is not a library
     * entry until the EPUB is imported here too.
     */
    @Query("SELECT * FROM books WHERE deletedAt IS NULL AND filePath != '' ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE id = :id AND deletedAt IS NULL)")
    suspend fun exists(id: String): Boolean

    /**
     * Reads a row whether or not it is tombstoned. The importer needs this: re-importing a
     * book the user previously deleted must revive that row rather than hit the primary-key
     * conflict a plain insert would raise.
     */
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getAnyById(id: String): BookEntity?

    /** Reads back the persisted reading position (Readium Locator JSON), or null. */
    @Query("SELECT progressionJson FROM books WHERE id = :id AND deletedAt IS NULL")
    suspend fun getProgression(id: String): String?

    /**
     * Persists the reading position and bumps [BookEntity.lastOpenedAt] in one
     * write, so a page turn does not read-modify-write the whole row.
     *
     * [BookEntity.updatedAt] is deliberately left alone: a page turn does not change the
     * book's *metadata*, and bumping it would re-push an identical `books_metadata` row on
     * every turn. The reading position syncs as its own `progress` entity, whose dirty check
     * is [progressDirtySince] against `lastOpenedAt`.
     */
    @Query("UPDATE books SET progressionJson = :progressionJson, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateProgression(id: String, progressionJson: String?, openedAt: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    // --- sync (S3.2) -----------------------------------------------------------------
    // These intentionally see tombstones: a tombstone is precisely what has to be pushed.

    /** Tombstones [id] so the delete reaches the other devices on the next push. */
    @Query("UPDATE books SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    /** Metadata rows changed since the push watermark, oldest first. */
    @Query("SELECT * FROM books WHERE updatedAt > :since ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun metadataDirtySince(since: Long, limit: Int): List<BookEntity>

    /**
     * Live books whose reading position moved since the `progress` push watermark. Tombstoned
     * and never-opened books are excluded — `progress` is upsert-only and has no delete
     * (`docs/sync_api.md` §1.3).
     */
    @Query(
        "SELECT * FROM books WHERE deletedAt IS NULL AND progressionJson IS NOT NULL " +
            "AND lastOpenedAt > :since ORDER BY lastOpenedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun progressDirtySince(since: Long, limit: Int): List<BookEntity>

    /**
     * Applies a pulled `books_metadata` row to the local copy without disturbing the columns
     * the server does not own. `filePath`, `coverPath` and `progressionJson` are device-local
     * facts: the book file itself never syncs (CLAUDE.md §2), so a row arriving from another
     * device must not blank the path to the copy sitting on *this* device.
     */
    @Query(
        "UPDATE books SET title = :title, authors = :authors, sourceLang = :sourceLang, " +
            "targetLang = :targetLang, updatedAt = :updatedAt, deletedAt = :deletedAt WHERE id = :id",
    )
    suspend fun applyServerMetadata(
        id: String,
        title: String,
        authors: String,
        sourceLang: String?,
        targetLang: String?,
        updatedAt: Long,
        deletedAt: Long?,
    )

    /**
     * Applies a pulled `progress` row. Only the position moves; a book the other device has
     * read but this one has never imported is skipped by the caller, since there is no local
     * row to attach a position to and the file cannot be fetched.
     */
    @Query(
        "UPDATE books SET progressionJson = :progressionJson, lastOpenedAt = :updatedAt " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun applyServerProgress(id: String, progressionJson: String, updatedAt: Long)
}
