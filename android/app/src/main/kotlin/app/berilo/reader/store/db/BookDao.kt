package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room data-access object for [BookEntity]. */
@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /** Reads back the persisted reading position (Readium Locator JSON), or null. */
    @Query("SELECT progressionJson FROM books WHERE id = :id")
    suspend fun getProgression(id: String): String?

    /**
     * Persists the reading position and bumps [BookEntity.lastOpenedAt] in one
     * write, so a page turn does not read-modify-write the whole row.
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
}
