package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room data-access object for [DictionaryEntryEntity]. */
@Dao
interface DictionaryDao {

    /** Looks up a cached definition by its full key; `null` on a cache miss or a tombstone. */
    @Query(
        "SELECT * FROM dictionary_entries " +
            "WHERE word = :word AND sentenceHash = :sentenceHash AND lang = :lang AND model = :model " +
            "AND deletedAt IS NULL",
    )
    suspend fun find(word: String, sentenceHash: String, lang: String, model: String): DictionaryEntryEntity?

    /** Writes [entry], replacing any existing row with the same key (re-fetch overwrite). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DictionaryEntryEntity)

    // --- sync (S3.2) -----------------------------------------------------------------

    /**
     * Rows changed since the push watermark, oldest first.
     *
     * Rows with an empty [DictionaryEntryEntity.sentence] are excluded: they predate the
     * column (schema 4 -> 5 could not invert the hash to recover the text) and
     * `vocabulary.sentence` is `not null` server-side, so pushing them would earn a
     * `missing_sentence` rejection on every attempt forever. Skipping keeps them as a local
     * cache, which is all they ever were.
     */
    @Query(
        "SELECT * FROM dictionary_entries WHERE updatedAt > :since AND sentence != '' " +
            "ORDER BY updatedAt ASC, word ASC, sentenceHash ASC, lang ASC, model ASC LIMIT :limit",
    )
    suspend fun dirtySince(since: Long, limit: Int): List<DictionaryEntryEntity>

    /** Counts rows the migration left without a sentence, so sync can report what it skipped. */
    @Query("SELECT COUNT(*) FROM dictionary_entries WHERE sentence = '' AND deletedAt IS NULL")
    suspend fun countWithoutSentence(): Int

    /** Reads a row whether or not it is tombstoned, for last-write-wins comparison on pull. */
    @Query(
        "SELECT * FROM dictionary_entries " +
            "WHERE word = :word AND sentenceHash = :sentenceHash AND lang = :lang AND model = :model",
    )
    suspend fun getAny(word: String, sentenceHash: String, lang: String, model: String): DictionaryEntryEntity?
}
