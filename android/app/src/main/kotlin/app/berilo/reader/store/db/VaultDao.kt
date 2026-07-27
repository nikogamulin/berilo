package app.berilo.reader.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room access to the device's vault bookkeeping tables (S3.7): [VaultBookEntity],
 * [VaultTranslationEntity] and [VaultGlossaryEntity].
 *
 * **Every single query here takes `userId` and constrains on it.** There is deliberately no
 * `booksFor(bookHash)`, no `allTranslations()` and no unscoped delete: `docs/sync_api.md` §8.3(1)
 * forbids one account ever reading another's translated text, and the cheapest way to keep a
 * forbidden read from being written by accident is for it to be unspeakable in the DAO. The
 * primary keys in [VaultBookEntity] carry the same rule at the schema layer, so a dropped
 * predicate here still cannot merge two users' rows into one.
 *
 * `VaultIsolationTest` asserts both halves — the schema (via `PRAGMA table_info`) and the
 * behaviour (user B's reads never return user A's rows).
 */
@Dao
interface VaultDao {

    /** [userId]'s vault record for [bookHash], or null if they have never opted this book in. */
    @Query("SELECT * FROM vault_books WHERE userId = :userId AND bookHash = :bookHash")
    suspend fun book(userId: String, bookHash: String): VaultBookEntity?

    /**
     * Whether [userId] has opted [bookHash] into the vault.
     *
     * Null when no row exists, which the repository reads as "off" — §8.2(4)'s default is that
     * files stay on the device, so absence of a decision is never consent.
     */
    @Query("SELECT enabled FROM vault_books WHERE userId = :userId AND bookHash = :bookHash")
    suspend fun isEnabled(userId: String, bookHash: String): Boolean?

    /** Every book [userId] has opted in and not deleted. */
    @Query(
        "SELECT * FROM vault_books WHERE userId = :userId AND enabled = 1 AND deletedAt IS NULL",
    )
    suspend fun enabledBooks(userId: String): List<VaultBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: VaultBookEntity)

    /** [userId]'s uploaded-translation records for [bookHash]. Never another account's. */
    @Query("SELECT * FROM vault_translations WHERE userId = :userId AND bookHash = :bookHash")
    suspend fun translations(userId: String, bookHash: String): List<VaultTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranslations(rows: List<VaultTranslationEntity>)

    /** [userId]'s uploaded-glossary records for [bookHash]. Never another account's. */
    @Query("SELECT * FROM vault_glossaries WHERE userId = :userId AND bookHash = :bookHash")
    suspend fun glossaries(userId: String, bookHash: String): List<VaultGlossaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossaries(rows: List<VaultGlossaryEntity>)

    /** Forget that [userId]'s [bookHash] translations were uploaded. */
    @Query("DELETE FROM vault_translations WHERE userId = :userId AND bookHash = :bookHash")
    suspend fun deleteTranslations(userId: String, bookHash: String)

    /** Forget that [userId]'s [bookHash] glossaries were uploaded. */
    @Query("DELETE FROM vault_glossaries WHERE userId = :userId AND bookHash = :bookHash")
    suspend fun deleteGlossaries(userId: String, bookHash: String)

    /** Remove [userId]'s vault record for [bookHash] entirely. */
    @Query("DELETE FROM vault_books WHERE userId = :userId AND bookHash = :bookHash")
    suspend fun deleteBook(userId: String, bookHash: String)
}
