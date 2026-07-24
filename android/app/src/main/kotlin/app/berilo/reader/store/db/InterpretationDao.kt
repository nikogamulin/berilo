package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room data-access object for [InterpretationEntryEntity]. */
@Dao
interface InterpretationDao {

    /** Looks up a cached interpretation by its full key; `null` on a cache miss. */
    @Query(
        "SELECT * FROM interpretation_entries " +
            "WHERE passageHash = :passageHash AND lang = :lang AND model = :model",
    )
    suspend fun find(passageHash: String, lang: String, model: String): InterpretationEntryEntity?

    /** Writes [entry], replacing any existing row with the same key (re-fetch overwrite). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: InterpretationEntryEntity)
}
