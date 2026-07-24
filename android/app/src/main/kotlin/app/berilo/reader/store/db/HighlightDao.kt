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

    /** All highlights/notes for [bookId], oldest-created first; the notebook screen groups
     * this by chapter in the UI layer ([app.berilo.reader.annotations]). */
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY createdAt ASC")
    fun observeForBook(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun getById(id: String): HighlightEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HighlightEntity)

    @Update
    suspend fun update(entity: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)
}
