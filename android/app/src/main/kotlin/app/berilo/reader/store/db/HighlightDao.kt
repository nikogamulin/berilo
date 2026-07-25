package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room data-access object for [HighlightEntity]. */
@Dao
interface HighlightDao {

    /** Live highlights/notes for [bookId], oldest-created first; the notebook screen groups
     * this by chapter in the UI layer ([app.berilo.reader.annotations]). Tombstones (S3.2) are
     * filtered out — a deleted highlight must not reappear in the notebook or as a decoration. */
    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt ASC")
    fun observeForBook(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): HighlightEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HighlightEntity)

    @Update
    suspend fun update(entity: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)

    // --- sync (S3.2) -----------------------------------------------------------------

    /** Tombstones [id] so the delete reaches the other devices on the next push. */
    @Query("UPDATE highlights SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    /** Rows changed since the push watermark, oldest first; includes tombstones by design. */
    @Query("SELECT * FROM highlights WHERE updatedAt > :since ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun dirtySince(since: Long, limit: Int): List<HighlightEntity>

    /** Reads a row whether or not it is tombstoned, for last-write-wins comparison on pull. */
    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun getAnyById(id: String): HighlightEntity?

    /** Writes a pulled row wholesale — the server owns every column of a highlight. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HighlightEntity)
}
