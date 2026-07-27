package app.berilo.reader.vault

/**
 * In-memory [VaultGateway] standing in for the `berilo-cloud` server half (S3.7).
 *
 * The server lives in a separate private repo (CLAUDE.md §5) and is not implemented here, so
 * this is what every vault test runs against. **No test in this package reaches a network, a
 * backend or a provider**, and none costs anything.
 *
 * Two properties make it useful as evidence rather than merely as a stub:
 *
 * 1. **It stores rows the way a server would** — under composite keys whose first component is
 *    the owner. Storage is a single flat map per kind, deliberately *not* a map-of-maps per
 *    user, so that an implementation which forgot the owner would collide here exactly as it
 *    would collide in Postgres. `VaultIsolationTest` relies on that: if `userId` were dropped
 *    from the key, user B's push would overwrite user A's row and the test would fail.
 * 2. **It counts calls.** [callCount] is what the opt-in test asserts on — "nothing uploaded" has
 *    to mean "the gateway was never called", not "the gateway was called with an empty list".
 */
class FakeVaultGateway : VaultGateway {

    private data class BookKey(val userId: String, val bookHash: String)

    private val books = mutableMapOf<BookKey, VaultBookObject>()
    private val translations = mutableMapOf<VaultTranslationKey, VaultTranslationObject>()
    private val glossaries = mutableMapOf<VaultGlossaryKey, VaultGlossaryObject>()

    /** Every method invocation, in order, as `"methodName:userId"`. */
    val calls: MutableList<String> = mutableListOf()

    /** Number of gateway methods invoked. Zero is the assertion the opt-in test makes. */
    val callCount: Int get() = calls.size

    /** Every object path ever written. Asserted to be user-namespaced, never book-only. */
    val writtenPaths: MutableList<String> = mutableListOf()

    /** Total rows held, across all users — the count that proves no cross-user dedup happened. */
    val storedTranslationCount: Int get() = translations.size

    /** Every stored byte a server could read: ciphertext, nonces and salts, and nothing else. */
    fun allStoredBytes(): List<ByteArray> =
        books.values.flatMap { listOf(it.sealed.ciphertext, it.sealed.nonce, it.kdfSalt) } +
            translations.values.flatMap { listOf(it.sealed.ciphertext, it.sealed.nonce) } +
            glossaries.values.flatMap { listOf(it.sealed.ciphertext, it.sealed.nonce) }

    override suspend fun putBook(userId: UserId, book: VaultBookObject): Result<Unit> {
        calls.add("putBook:${userId.value}")
        writtenPaths.add(book.objectPath)
        books[BookKey(userId.value, book.bookHash)] = book
        return Result.success(Unit)
    }

    override suspend fun getBook(userId: UserId, bookHash: String): Result<VaultBookObject?> {
        calls.add("getBook:${userId.value}")
        return Result.success(books[BookKey(userId.value, bookHash)])
    }

    override suspend fun putTranslations(
        userId: UserId,
        rows: List<VaultTranslationObject>,
    ): Result<Unit> {
        calls.add("putTranslations:${userId.value}")
        rows.forEach { translations[it.key] = it }
        return Result.success(Unit)
    }

    override suspend fun getTranslations(
        userId: UserId,
        bookHash: String,
    ): Result<List<VaultTranslationObject>> {
        calls.add("getTranslations:${userId.value}")
        return Result.success(
            translations.values.filter { it.key.userId == userId && it.key.bookHash == bookHash },
        )
    }

    override suspend fun putGlossaries(
        userId: UserId,
        rows: List<VaultGlossaryObject>,
    ): Result<Unit> {
        calls.add("putGlossaries:${userId.value}")
        rows.forEach { glossaries[it.key] = it }
        return Result.success(Unit)
    }

    override suspend fun getGlossaries(
        userId: UserId,
        bookHash: String,
    ): Result<List<VaultGlossaryObject>> {
        calls.add("getGlossaries:${userId.value}")
        return Result.success(
            glossaries.values.filter { it.key.userId == userId && it.key.bookHash == bookHash },
        )
    }

    override suspend fun deleteBook(userId: UserId, bookHash: String): Result<Unit> {
        calls.add("deleteBook:${userId.value}")
        books.remove(BookKey(userId.value, bookHash))
        translations.keys.removeAll { it.userId == userId && it.bookHash == bookHash }
        glossaries.keys.removeAll { it.userId == userId && it.bookHash == bookHash }
        return Result.success(Unit)
    }
}

/** In-memory [VaultSecret], standing in for the Keystore-backed [StoredVaultSecret]. */
class FakeVaultSecret(private val value: String?) : VaultSecret {
    override fun passphrase(): CharArray? = value?.toCharArray()
}
