package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room data-access object for [DictionaryEntryEntity]. */
@Dao
interface DictionaryDao {

    /** Looks up a cached definition by its full key; `null` on a cache miss. */
    @Query(
        "SELECT * FROM dictionary_entries " +
            "WHERE word = :word AND sentenceHash = :sentenceHash AND lang = :lang AND model = :model",
    )
    suspend fun find(word: String, sentenceHash: String, lang: String, model: String): DictionaryEntryEntity?

    /** Writes [entry], replacing any existing row with the same key (re-fetch overwrite). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DictionaryEntryEntity)
}
