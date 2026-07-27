package app.berilo.reader.vault

import android.util.Log
import app.berilo.reader.store.db.GlossaryEntity
import app.berilo.reader.store.db.TranslationCacheDao
import app.berilo.reader.store.db.TranslationEntity
import app.berilo.reader.store.db.VaultBookEntity
import app.berilo.reader.store.db.VaultDao
import app.berilo.reader.store.db.VaultGlossaryEntity
import app.berilo.reader.store.db.VaultTranslationEntity
import javax.crypto.SecretKey

private const val TAG = "VaultRepository"

/**
 * The personal book vault, scoped to exactly one account (S3.7, `docs/sync_api.md` §8).
 *
 * A user's own book files and their own translations, encrypted on this device, stored under
 * their own namespace, so they can read on a second device without paying to translate the same
 * book twice.
 *
 * ### Why this class takes [userId] in its constructor and no method takes one
 *
 * This is the third of the three layers that make per-user keying structurally unavoidable
 * (the others are the [UserId] value class and the `userId`-leading primary keys in
 * `store/db/VaultEntities.kt`). Because the owner is bound once, at construction, **there is no
 * method on this class that can be called without an owner already fixed** — no `upload(bookHash)`
 * overload that "defaults" to the current user, no `restore(bookHash, userId)` whose argument can
 * be passed wrong. A caller with no signed-in user cannot build a `VaultRepository` at all.
 *
 * `docs/sync_api.md` §8.3(1) is the reason that matters: the on-device translation cache is
 * content-addressed on six columns with no user column, which is correct on one machine and
 * dangerous hosted — two users importing the same ISBN collide on `bookHash`, and the second
 * would be served the first's translated text. That is reproduction plus communication to the
 * public, not private copying. **Cross-user deduplication is the same defect wearing a
 * cost-optimisation hat, and there is no exception for it here however large the saving.**
 *
 * ### What leaves the device
 *
 * Salt, nonce and ciphertext. Never the passphrase, never the derived key, never plaintext
 * (§8.2(2)). The server stores bytes it cannot read.
 *
 * ### What never happens
 *
 * Nothing uploads unless the user turned the vault on **for that specific book** (§8.2(4));
 * [upload] makes zero gateway calls otherwise. Upload is always user-initiated — nothing here
 * polls, subscribes or accepts a server callback, and translation stays local under the user's
 * own API key (§8.2(3)).
 *
 * ### Known limit, deliberately not papered over
 *
 * The *local* cache tables this reads ([TranslationCacheDao]) are not user-scoped, because B4
 * shipped them for a single-user device and §8.3(1) says that is correct on one machine. If two
 * accounts sign in on the same physical device, the second could push translations the first
 * paid for into its own namespace. That is a device-sharing question, not a hosting one, and it
 * is out of this story's scope — but it is real, and it is why the owner is attached here, at
 * the boundary where rows become hosted, rather than assumed further down.
 *
 * @property userId The account every row and object path this instance touches belongs to.
 */
class VaultRepository(
    private val userId: UserId,
    private val gateway: VaultGateway,
    private val vaultDao: VaultDao,
    private val cacheDao: TranslationCacheDao,
    private val secret: VaultSecret,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Whether the user has opted [bookHash] into the vault.
     *
     * Absence of a row is `false`: §8.2(4)'s default is that files stay on the device, so "no
     * decision recorded" is never read as consent.
     */
    suspend fun isEnabled(bookHash: String): Boolean =
        vaultDao.isEnabled(userId.value, bookHash) == true

    /**
     * Turn the vault on or off for [bookHash]. Per book, never global.
     *
     * Turning it on stores the decision only — it uploads nothing. The user's next explicit
     * [upload] does that.
     */
    suspend fun setEnabled(bookHash: String, enabled: Boolean) {
        val now = clock()
        val existing = vaultDao.book(userId.value, bookHash)
        vaultDao.upsertBook(
            existing?.copy(enabled = enabled, updatedAt = now)
                ?: VaultBookEntity(
                    userId = userId.value,
                    bookHash = bookHash,
                    enabled = enabled,
                    objectPath = VaultObjectPath.forBook(userId, bookHash),
                    kdfSalt = null,
                    algorithm = null,
                    sizeBytes = 0L,
                    uploadedAt = null,
                    updatedAt = now,
                    deletedAt = null,
                ),
        )
    }

    /**
     * Encrypt [bookBytes] and every translation and glossary cached for [bookHash], and push them
     * into this user's namespace.
     *
     * **Returns [VaultUploadReport.NotEnabled] without touching [gateway] when the user has not
     * opted this book in.** That early return is the opt-in guarantee in code: `VaultOptInTest`
     * asserts the gateway received exactly zero calls.
     *
     * @param bookBytes The book file the user already possesses. Supplied by the caller — this
     *   class never fetches, and neither does the server (§8.2(3)).
     */
    suspend fun upload(bookHash: String, bookBytes: ByteArray): VaultUploadReport {
        if (!isEnabled(bookHash)) {
            Log.i(TAG, "Vault is off for this book; nothing uploaded.")
            return VaultUploadReport.NotEnabled
        }
        val existing = vaultDao.book(userId.value, bookHash)
        val salt = existing?.kdfSalt ?: VaultCrypto.newSalt()
        val key = deriveKey(salt) ?: return VaultUploadReport.NoSecret

        val now = clock()
        val sealedBook = VaultCrypto.seal(key, bookBytes)
        val objectPath = VaultObjectPath.forBook(userId, bookHash)

        val bookResult =
            gateway.putBook(
                userId,
                VaultBookObject(
                    userId = userId,
                    bookHash = bookHash,
                    objectPath = objectPath,
                    kdfSalt = salt,
                    algorithm = VaultCrypto.ALGORITHM,
                    sealed = sealedBook,
                    updatedAt = now,
                ),
            )
        bookResult.exceptionOrNull()?.let { return VaultUploadReport.Failed(it.messageOrType()) }

        val translations = cacheDao.translationsForBook(bookHash)
        val translationObjects = translations.map { it.toVaultObject(key, now) }
        gateway.putTranslations(userId, translationObjects).exceptionOrNull()?.let {
            return VaultUploadReport.Failed(it.messageOrType())
        }

        val glossaries = cacheDao.glossariesForBook(bookHash)
        val glossaryObjects = glossaries.map { it.toVaultObject(key, now) }
        gateway.putGlossaries(userId, glossaryObjects).exceptionOrNull()?.let {
            return VaultUploadReport.Failed(it.messageOrType())
        }

        vaultDao.upsertBook(
            VaultBookEntity(
                userId = userId.value,
                bookHash = bookHash,
                enabled = true,
                objectPath = objectPath,
                kdfSalt = salt,
                algorithm = VaultCrypto.ALGORITHM,
                sizeBytes = sealedBook.ciphertext.size.toLong(),
                uploadedAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
        vaultDao.upsertTranslations(
            translationObjects.map { row ->
                VaultTranslationEntity(
                    userId = userId.value,
                    bookHash = row.key.bookHash,
                    segmentHash = row.key.segmentHash,
                    model = row.key.model,
                    lang = row.key.lang,
                    promptVersion = row.key.promptVersion,
                    glossaryHash = row.key.glossaryHash,
                    uploadedAt = now,
                )
            },
        )
        vaultDao.upsertGlossaries(
            glossaryObjects.map { row ->
                VaultGlossaryEntity(
                    userId = userId.value,
                    bookHash = row.key.bookHash,
                    model = row.key.model,
                    lang = row.key.lang,
                    promptVersion = row.key.promptVersion,
                    uploadedAt = now,
                )
            },
        )

        Log.i(
            TAG,
            "Vault upload: ${translationObjects.size} translations, " +
                "${glossaryObjects.size} glossaries.",
        )
        return VaultUploadReport.Uploaded(
            translations = translationObjects.size,
            glossaries = glossaryObjects.size,
            ciphertextBytes = sealedBook.ciphertext.size,
        )
    }

    /**
     * Pull this user's [bookHash] back down, decrypt it, and write the translations and
     * glossaries into **this device's** cache under the ordinary six-column key.
     *
     * That last step is the point of the whole feature: once these rows land,
     * [app.berilo.reader.translate.engine.translateBook] and
     * [app.berilo.reader.translate.engine.buildGlossary] resolve them as ordinary cache hits and
     * make no API call, so the same book is never paid for twice. Glossaries are restored
     * alongside translations because `buildGlossary` bills a call *before* `translateBook` is
     * entered (`docs/findings.md`, 2026-07-27) — restoring only translations would still cost one
     * call per book.
     *
     * Reads only this user's namespace. There is no argument by which it could read another's.
     */
    suspend fun restore(bookHash: String): VaultRestoreReport {
        val bookResult = gateway.getBook(userId, bookHash)
        bookResult.exceptionOrNull()?.let { return VaultRestoreReport.Failed(it.messageOrType()) }
        val stored = bookResult.getOrNull() ?: return VaultRestoreReport.NotInVault

        val key = deriveKey(stored.kdfSalt) ?: return VaultRestoreReport.NoSecret
        val bookBytes =
            runCatching { VaultCrypto.open(key, stored.sealed) }
                .getOrElse { return VaultRestoreReport.Failed("could not decrypt: wrong secret") }

        val translationsResult = gateway.getTranslations(userId, bookHash)
        translationsResult.exceptionOrNull()?.let {
            return VaultRestoreReport.Failed(it.messageOrType())
        }
        val glossariesResult = gateway.getGlossaries(userId, bookHash)
        glossariesResult.exceptionOrNull()?.let {
            return VaultRestoreReport.Failed(it.messageOrType())
        }

        val now = clock()
        val translations = translationsResult.getOrDefault(emptyList())
        val restoredTranslations =
            runCatching {
                translations.map { row ->
                    TranslationEntity(
                        bookHash = row.key.bookHash,
                        segmentHash = row.key.segmentHash,
                        model = row.key.model,
                        lang = row.key.lang,
                        promptVersion = row.key.promptVersion,
                        glossaryHash = row.key.glossaryHash,
                        text = VaultCrypto.openText(key, row.sealed),
                        costEur = row.costEur,
                        createdAt = now,
                    )
                }
            }
                .getOrElse { return VaultRestoreReport.Failed("could not decrypt: wrong secret") }
        cacheDao.insertTranslations(restoredTranslations)

        val glossaries = glossariesResult.getOrDefault(emptyList())
        val restoredGlossaries =
            runCatching {
                glossaries.map { row ->
                    GlossaryEntity(
                        bookHash = row.key.bookHash,
                        model = row.key.model,
                        lang = row.key.lang,
                        promptVersion = row.key.promptVersion,
                        termsJson = VaultCrypto.openText(key, row.sealed),
                        createdAt = now,
                    )
                }
            }
                .getOrElse { return VaultRestoreReport.Failed("could not decrypt: wrong secret") }
        restoredGlossaries.forEach { cacheDao.upsertGlossary(it) }

        vaultDao.upsertBook(
            VaultBookEntity(
                userId = userId.value,
                bookHash = bookHash,
                enabled = true,
                objectPath = stored.objectPath,
                kdfSalt = stored.kdfSalt,
                algorithm = stored.algorithm,
                sizeBytes = stored.sealed.ciphertext.size.toLong(),
                uploadedAt = stored.updatedAt,
                updatedAt = now,
                deletedAt = null,
            ),
        )

        Log.i(
            TAG,
            "Vault restore: ${restoredTranslations.size} translations, " +
                "${restoredGlossaries.size} glossaries.",
        )
        return VaultRestoreReport.Restored(
            bookBytes = bookBytes,
            translations = restoredTranslations.size,
            glossaries = restoredGlossaries.size,
        )
    }

    /** Derive the content key, clearing the passphrase copy afterwards. */
    private fun deriveKey(salt: ByteArray?): SecretKey? {
        if (salt == null) return null
        val passphrase = secret.passphrase() ?: return null
        return try {
            VaultCrypto.deriveKey(passphrase, salt)
        } finally {
            passphrase.fill(' ')
        }
    }

    private fun TranslationEntity.toVaultObject(key: SecretKey, now: Long) =
        VaultTranslationObject(
            key =
                VaultTranslationKey(
                    userId = userId,
                    bookHash = bookHash,
                    segmentHash = segmentHash,
                    model = model,
                    lang = lang,
                    promptVersion = promptVersion,
                    glossaryHash = glossaryHash,
                ),
            sealed = VaultCrypto.seal(key, text),
            costEur = costEur,
            updatedAt = now,
        )

    private fun GlossaryEntity.toVaultObject(key: SecretKey, now: Long) =
        VaultGlossaryObject(
            key =
                VaultGlossaryKey(
                    userId = userId,
                    bookHash = bookHash,
                    model = model,
                    lang = lang,
                    promptVersion = promptVersion,
                ),
            sealed = VaultCrypto.seal(key, termsJson),
            updatedAt = now,
        )

    private fun Throwable.messageOrType(): String = message ?: this::class.java.simpleName
}

/** Outcome of a [VaultRepository.upload]. */
sealed interface VaultUploadReport {

    /** The user has not opted this book in. **No gateway call was made** (§8.2(4)). */
    data object NotEnabled : VaultUploadReport

    /** No vault passphrase is set on this device, so nothing could be encrypted. */
    data object NoSecret : VaultUploadReport

    /**
     * The book and its cached work are in the vault.
     *
     * @property translations Encrypted translation rows pushed.
     * @property glossaries Encrypted glossary rows pushed.
     * @property ciphertextBytes Size of the encrypted book object.
     */
    data class Uploaded(
        val translations: Int,
        val glossaries: Int,
        val ciphertextBytes: Int,
    ) : VaultUploadReport

    /** The gateway refused or was unreachable. */
    data class Failed(val message: String) : VaultUploadReport
}

/** Outcome of a [VaultRepository.restore]. */
sealed interface VaultRestoreReport {

    /** This user has no such book in their namespace. */
    data object NotInVault : VaultRestoreReport

    /** No vault passphrase is set on this device, so nothing could be decrypted. */
    data object NoSecret : VaultRestoreReport

    /**
     * The book and its cached work are back on this device, and the translations resolve against
     * the ordinary six-column cache key.
     *
     * @property bookBytes The decrypted book file, for the caller to persist.
     * @property translations Rows written into the local translation cache.
     * @property glossaries Rows written into the local glossary cache.
     */
    class Restored(
        val bookBytes: ByteArray,
        val translations: Int,
        val glossaries: Int,
    ) : VaultRestoreReport

    /** The gateway was unreachable, or the bytes did not authenticate under the derived key. */
    data class Failed(val message: String) : VaultRestoreReport
}
