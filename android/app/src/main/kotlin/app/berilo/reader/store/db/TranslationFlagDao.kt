package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room data-access object for [TranslationFlagEntity] (B9). */
@Dao
interface TranslationFlagDao {

    /** Live flags for [bookId], oldest-created first; the notebook groups them by chapter in
     * the UI layer. Tombstones are filtered out, matching [HighlightDao.observeForBook]. */
    @Query(
        "SELECT * FROM translation_flags WHERE bookId = :bookId AND deletedAt IS NULL " +
            "ORDER BY createdAt ASC",
    )
    fun observeForBook(bookId: String): Flow<List<TranslationFlagEntity>>

    @Query("SELECT * FROM translation_flags WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): TranslationFlagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TranslationFlagEntity)

    /** Tombstones [id] so a later sync client can propagate the delete rather than have the row
     * silently reappear on the next pull (the S3.2 rule, applied to a table sync does not carry
     * yet). */
    @Query("UPDATE translation_flags SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    /** Reads a row whether or not it is tombstoned — the shape last-write-wins needs. */
    @Query("SELECT * FROM translation_flags WHERE id = :id")
    suspend fun getAnyById(id: String): TranslationFlagEntity?
}
