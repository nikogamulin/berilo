package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Room data-access object for the on-device translation cache (B4): [TranslationEntity],
 * [GlossaryEntity] and [TranslationCallEntity].
 */
@Dao
interface TranslationCacheDao {

    /** Returns the cached translation for a segment, or `null` on a cache miss. */
    @Query(
        "SELECT text FROM translations WHERE bookHash = :bookHash AND segmentHash = :segmentHash " +
            "AND model = :model AND lang = :lang AND promptVersion = :promptVersion " +
            "AND glossaryHash = :glossaryHash",
    )
    suspend fun getTranslation(
        bookHash: String,
        segmentHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        glossaryHash: String,
    ): String?

    /** Every `segmentHash` already translated for this key. */
    @Query(
        "SELECT segmentHash FROM translations WHERE bookHash = :bookHash AND model = :model " +
            "AND lang = :lang AND promptVersion = :promptVersion AND glossaryHash = :glossaryHash",
    )
    suspend fun cachedHashes(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        glossaryHash: String,
    ): List<String>

    /** Existing rows are left untouched, matching `cache.py`'s `INSERT OR IGNORE` semantics. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTranslations(translations: List<TranslationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCall(call: TranslationCallEntity)

    /**
     * Persist a batch's translations and its call accounting atomically.
     *
     * Mirrors `TranslationCache.store_batch`: written in one transaction so a process killed
     * mid-batch (or a constraint failure on [call]) leaves no partial rows — the resumability
     * guarantee behind `translate.py` committing per batch (CLAUDE.md §2). Room commits the
     * default-method body of an interface `@Dao` as a single transaction when annotated
     * [Transaction], rolling both inserts back together on any failure.
     */
    @Transaction
    suspend fun storeBatch(translations: List<TranslationEntity>, call: TranslationCallEntity) {
        insertTranslations(translations)
        insertCall(call)
    }

    /** Returns the cached glossary term map (JSON-encoded), or `null` if not built yet. */
    @Query(
        "SELECT termsJson FROM glossaries WHERE bookHash = :bookHash AND model = :model " +
            "AND lang = :lang AND promptVersion = :promptVersion",
    )
    suspend fun getGlossaryTermsJson(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
    ): String?

    /** Writes or replaces the per-book glossary term map. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossary(glossary: GlossaryEntity)

    /** Total EUR cost recorded for [bookHash] across every call. */
    @Query("SELECT COALESCE(SUM(costEur), 0.0) FROM calls WHERE bookHash = :bookHash")
    suspend fun totalCost(bookHash: String): Double

    // --- reverse lookup, target text -> cache key (B9) ---------------------------------
    //
    // The reader only ever holds the *translated* text: the library row is keyed on the
    // sha256 of the translated EPUB, while `translations` is keyed on `bookHash`, a hash over
    // the *source* book's segment ids. Nothing joins the two, so a flagged passage can only be
    // matched back to the run that produced it by its content. Both queries take the newest
    // row, so a segment re-translated under a newer prompt reports the key actually on screen.

    /** The most recently cached translation whose text is exactly [text], or null. */
    @Query("SELECT * FROM translations WHERE text = :text ORDER BY createdAt DESC LIMIT 1")
    suspend fun findTranslationByExactText(text: String): TranslationEntity?

    /**
     * The most recently cached translation whose text matches [pattern], or null.
     *
     * [pattern] is a SQL `LIKE` pattern; the caller builds it (see
     * [app.berilo.reader.annotations.likeContainsPattern]) and is responsible for escaping any
     * `%`, `_` or `\` the user's selection contains — unescaped, a passage containing a literal
     * `%` would match half the book.
     */
    @Query(
        "SELECT * FROM translations WHERE text LIKE :pattern ESCAPE '\\' " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun findTranslationMatching(pattern: String): TranslationEntity?
}
