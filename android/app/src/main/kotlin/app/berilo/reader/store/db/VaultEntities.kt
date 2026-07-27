package app.berilo.reader.store.db

import androidx.room.Entity

/**
 * The device's record of one book's vault state, for one account (S3.7, schema v8).
 *
 * Two jobs in one row, both of which are per `(user, book)`:
 * - **the opt-in decision.** [enabled] is `false` for every book until the user turns the vault
 *   on for that specific book (`docs/sync_api.md` §8.2(4)); the absence of a row means the same
 *   thing. Nothing uploads silently.
 * - **upload bookkeeping.** [uploadedAt], [kdfSalt] and [algorithm] record what was pushed, so a
 *   re-upload is a no-op and a restore knows which salt to derive with.
 *
 * `userId` leads the primary key, per §8.2(1): not a `WHERE` clause and not an RLS policy alone,
 * but the key, so that a refactor which drops a predicate cannot merge two users' books. Two
 * accounts on one device that own the same ISBN hold two rows here, never one.
 *
 * @property userId Owning account ([app.berilo.reader.vault.UserId]).
 * @property bookHash Book identity hash ([app.berilo.reader.translate.model.bookHash]).
 * @property enabled Whether the user has opted this book into the vault. Default `false`.
 * @property objectPath `vault/{userId}/{bookHash}.enc`
 *   ([app.berilo.reader.vault.VaultObjectPath.forBook]).
 * @property kdfSalt Non-secret PBKDF2 salt the ciphertext was derived with; null before upload.
 * @property algorithm [app.berilo.reader.vault.VaultCrypto.ALGORITHM]; null before upload.
 * @property sizeBytes Ciphertext size in bytes; 0 before upload.
 * @property uploadedAt Epoch millis of the last successful push; null if never pushed.
 * @property updatedAt Epoch millis of the last local change to this row.
 * @property deletedAt Tombstone timestamp, or null.
 */
@Entity(tableName = "vault_books", primaryKeys = ["userId", "bookHash"])
data class VaultBookEntity(
    val userId: String,
    val bookHash: String,
    val enabled: Boolean,
    val objectPath: String,
    val kdfSalt: ByteArray?,
    val algorithm: String?,
    val sizeBytes: Long,
    val uploadedAt: Long?,
    val updatedAt: Long,
    val deletedAt: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultBookEntity) return false
        return userId == other.userId &&
            bookHash == other.bookHash &&
            enabled == other.enabled &&
            objectPath == other.objectPath &&
            kdfSalt.contentEqualsNullable(other.kdfSalt) &&
            algorithm == other.algorithm &&
            sizeBytes == other.sizeBytes &&
            uploadedAt == other.uploadedAt &&
            updatedAt == other.updatedAt &&
            deletedAt == other.deletedAt
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + bookHash.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + objectPath.hashCode()
        result = 31 * result + (kdfSalt?.contentHashCode() ?: 0)
        result = 31 * result + (algorithm?.hashCode() ?: 0)
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + (uploadedAt?.hashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return 31 * result + (deletedAt?.hashCode() ?: 0)
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

/**
 * The device's record that one cached segment translation is in the vault, for one account
 * (S3.7, schema v8).
 *
 * The key is `userId` **plus** the on-device cache's whole six-column key. That prefix is the
 * single most load-bearing line of schema in this story: the six columns are content-addressed
 * with no user column, which is correct on one machine and, hosted, means two users importing
 * the same ISBN collide on `bookHash` and the second is served the first's translated text —
 * reproduction plus communication to the public, and the exact defect `docs/sync_api.md` §8.3(1)
 * forbids. **Cross-user deduplication is that same defect wearing a cost-optimisation hat**, so
 * there is no path here that reads a row without an owner.
 *
 * The plaintext lives in [TranslationEntity]; this table stores only *that it was uploaded*, so
 * a re-push is a no-op.
 *
 * @property userId Owning account.
 * @property bookHash Book identity hash.
 * @property segmentHash Content hash of the segment's stripped source text.
 * @property model Model the translation was produced with.
 * @property lang Target language code.
 * @property promptVersion Identity of the translation-style prompt.
 * @property glossaryHash Identity of the glossary injected into the prompt.
 * @property uploadedAt Epoch millis of the successful push.
 */
@Entity(
    tableName = "vault_translations",
    primaryKeys = [
        "userId",
        "bookHash",
        "segmentHash",
        "model",
        "lang",
        "promptVersion",
        "glossaryHash",
    ],
)
data class VaultTranslationEntity(
    val userId: String,
    val bookHash: String,
    val segmentHash: String,
    val model: String,
    val lang: String,
    val promptVersion: String,
    val glossaryHash: String,
    val uploadedAt: Long,
)

/**
 * The device's record that one cached glossary is in the vault, for one account (S3.7, v8).
 *
 * Keyed `userId` plus [GlossaryEntity]'s four columns, for the same reason as
 * [VaultTranslationEntity]. Present at all because `buildGlossary` bills a call *before*
 * `translateBook` is entered (`docs/findings.md`, 2026-07-27): a restore that carried only
 * translations would still cost one API call per book, so "don't pay to translate twice" would
 * be false by exactly one call.
 *
 * @property userId Owning account.
 * @property bookHash Book identity hash.
 * @property model Model the glossary was built with.
 * @property lang Target language code.
 * @property promptVersion Identity of the glossary-extraction prompt.
 * @property uploadedAt Epoch millis of the successful push.
 */
@Entity(
    tableName = "vault_glossaries",
    primaryKeys = ["userId", "bookHash", "model", "lang", "promptVersion"],
)
data class VaultGlossaryEntity(
    val userId: String,
    val bookHash: String,
    val model: String,
    val lang: String,
    val promptVersion: String,
    val uploadedAt: Long,
)
