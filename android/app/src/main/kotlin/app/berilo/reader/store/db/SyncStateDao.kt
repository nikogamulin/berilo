package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room data-access object for [SyncStateEntity]. */
@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE entity = :entity")
    suspend fun get(entity: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state")
    suspend fun getAll(): List<SyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    /** Clears all sync bookkeeping — used on sign-out, so the next account starts clean. */
    @Query("DELETE FROM sync_state")
    suspend fun clear()
}
