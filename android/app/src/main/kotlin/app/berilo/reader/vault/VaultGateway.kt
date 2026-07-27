package app.berilo.reader.vault

/**
 * The app's whole view of the personal book vault (S3.7).
 *
 * The seam, exactly as [app.berilo.reader.sync.auth.AuthGateway] is the seam for Clerk: the
 * server half lives in the private `berilo-cloud` repo (CLAUDE.md §5) and is not implemented
 * here, so everything on this side is written against this interface and tested against a fake.
 * A real backend drops in behind it.
 *
 * **Every method takes a [UserId], and there is no overload that does not.** That is not a style
 * choice: `docs/sync_api.md` §8.2(1) requires per-user isolation to live in the key, and §8.3(1)
 * forbids sharing a translation row across accounts under any circumstances, including
 * deduplication. An interface with a `getTranslations(bookHash)` method would make the forbidden
 * call expressible.
 *
 * **What this interface deliberately does not have**, per §8.3(3): any way to publish, link,
 * share or grant access to a vault object. There is no `share`, no `publicUrl`, no
 * `grantAccess`. A sharing path converts storage into making-available and moves the service off
 * the *Austro-Mechana* side of the line onto *VCAST*'s. `VaultIsolationTest` asserts the absence.
 *
 * **Upload is always user-initiated** (§8.2(3)): nothing here is a subscription, a poll or a
 * server callback. The device pushes when the user asks it to; the server never fetches, never
 * ingests and never translates.
 */
interface VaultGateway {

    /** Store [book]'s encrypted bytes and metadata in [userId]'s namespace. */
    suspend fun putBook(userId: UserId, book: VaultBookObject): Result<Unit>

    /** The stored book object for [bookHash] in [userId]'s namespace, or null if absent. */
    suspend fun getBook(userId: UserId, bookHash: String): Result<VaultBookObject?>

    /** Store encrypted translation rows in [userId]'s namespace. */
    suspend fun putTranslations(
        userId: UserId,
        rows: List<VaultTranslationObject>,
    ): Result<Unit>

    /** Every stored translation row for [bookHash] **in [userId]'s namespace only**. */
    suspend fun getTranslations(
        userId: UserId,
        bookHash: String,
    ): Result<List<VaultTranslationObject>>

    /** Store encrypted glossary rows in [userId]'s namespace. */
    suspend fun putGlossaries(userId: UserId, rows: List<VaultGlossaryObject>): Result<Unit>

    /** Every stored glossary row for [bookHash] **in [userId]'s namespace only**. */
    suspend fun getGlossaries(
        userId: UserId,
        bookHash: String,
    ): Result<List<VaultGlossaryObject>>

    /** Remove [bookHash] and everything derived from it from [userId]'s namespace. */
    suspend fun deleteBook(userId: UserId, bookHash: String): Result<Unit>
}

/**
 * One encrypted book file as it sits in the vault.
 *
 * @property userId Owner. Part of [objectPath] and of the server-side primary key.
 * @property bookHash The book's identity hash ([app.berilo.reader.translate.model.bookHash]).
 * @property objectPath Always [VaultObjectPath.forBook] — `vault/{user_id}/{book_hash}.enc`.
 *   Never a content-addressed path keyed on [bookHash] alone (§8.3(2)).
 * @property kdfSalt The **non-secret** PBKDF2 salt a second device replays to derive the same
 *   key from the same passphrase. Storing it is what makes cross-device restore possible without
 *   ever transmitting the key.
 * @property algorithm [VaultCrypto.ALGORITHM], recorded so a future scheme can migrate.
 * @property sealed Nonce and ciphertext. The only book bytes that leave the device.
 * @property updatedAt Epoch millis of the last write.
 */
data class VaultBookObject(
    val userId: UserId,
    val bookHash: String,
    val objectPath: String,
    val kdfSalt: ByteArray,
    val algorithm: String,
    val sealed: VaultSealed,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultBookObject) return false
        return userId == other.userId &&
            bookHash == other.bookHash &&
            objectPath == other.objectPath &&
            kdfSalt.contentEquals(other.kdfSalt) &&
            algorithm == other.algorithm &&
            sealed == other.sealed &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + bookHash.hashCode()
        result = 31 * result + objectPath.hashCode()
        result = 31 * result + kdfSalt.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + sealed.hashCode()
        return 31 * result + updatedAt.hashCode()
    }

    /** Never renders bytes or salt. */
    override fun toString(): String = "VaultBookObject($objectPath, ${sealed.ciphertext.size} bytes)"
}

/**
 * One encrypted cached segment translation as it sits in the vault.
 *
 * [key] is a [VaultTranslationKey], so the owner is structurally part of the row's identity —
 * this is the row §8.3(1) says must never be shared across accounts.
 *
 * @property key Owner plus the six-column cache key.
 * @property sealed Nonce and ciphertext of the translated text.
 * @property costEur The original spend, carried so a restored row reports honest provenance.
 * @property updatedAt Epoch millis of the last write.
 */
data class VaultTranslationObject(
    val key: VaultTranslationKey,
    val sealed: VaultSealed,
    val costEur: Double,
    val updatedAt: Long,
)

/**
 * One encrypted cached glossary as it sits in the vault.
 *
 * Carried alongside translations because `buildGlossary` bills a call *before* `translateBook`
 * runs, so a restore without it would still cost one call per book.
 *
 * @property key Owner plus the four-column glossary key.
 * @property sealed Nonce and ciphertext of the JSON-encoded term map.
 * @property updatedAt Epoch millis of the last write.
 */
data class VaultGlossaryObject(
    val key: VaultGlossaryKey,
    val sealed: VaultSealed,
    val updatedAt: Long,
)
