package app.berilo.reader.vault

/**
 * The account that owns a vault row (S3.7).
 *
 * A [value class][JvmInline] rather than a bare `String` for one reason: every other identifier
 * in the vault — `bookHash`, `segmentHash`, `model`, `lang` — is also a `String`, so a plain
 * `String` parameter list lets an argument slip one position and silently key a row under a book
 * hash. `UserId` cannot be passed where a `bookHash` is expected, or vice versa, and the compiler
 * says so. `docs/sync_api.md` §8.2(1) requires per-user isolation to live in the *key*; this type
 * is the first of the three layers that put it there (the other two are the composite primary
 * keys in `store/db/VaultEntities.kt` and the user-scoped [VaultRepository]).
 *
 * The value is the Clerk user id (`user_...`) carried on
 * [app.berilo.reader.sync.auth.AccountState.SignedIn.userId] — the same id every synced row is
 * owned by server-side (`docs/sync_api.md` §2).
 */
@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "UserId must not be blank: an empty owner would collapse every user into one " +
                "namespace, which is exactly what docs/sync_api.md §8.3(1) forbids."
        }
    }
}

/**
 * Where one user's encrypted book object lives, per the `docs/sync_api.md` §8.5 contract sketch:
 * `vault/{user_id}/{book_hash}.enc`.
 *
 * This object is the **only** way the app builds a vault object path. §8.3(2) forbids
 * content-addressed storage keyed on the book hash alone — two users who own the same ISBN must
 * not land on the same object — so there is deliberately no `forBook(bookHash)` overload to
 * reach for. The user segment comes first so that a prefix listing can never span two accounts.
 */
object VaultObjectPath {

    /** Root prefix for every vault object; nothing else is ever written under it. */
    const val ROOT: String = "vault"

    /** Extension marking the payload as opaque ciphertext rather than a readable book. */
    const val CIPHERTEXT_EXTENSION: String = ".enc"

    /** The object path for [bookHash] within [userId]'s namespace. */
    fun forBook(userId: UserId, bookHash: String): String {
        require(bookHash.isNotBlank()) { "bookHash must not be blank" }
        return "$ROOT/${userId.value}/$bookHash$CIPHERTEXT_EXTENSION"
    }

    /** The prefix under which all of [userId]'s objects live, and no other user's ever do. */
    fun prefixFor(userId: UserId): String = "$ROOT/${userId.value}/"
}

/**
 * The identity of one cached segment translation *within one account*.
 *
 * This is the on-device translation cache's six-column key (`bookHash`, `segmentHash`, `model`,
 * `lang`, `promptVersion`, `glossaryHash`) with [userId] prepended. The six columns alone are
 * correct on a single machine and dangerous the moment rows are hosted: two users importing the
 * same ISBN collide on `bookHash` and the second would be served the first's translated text
 * (`docs/sync_api.md` §8.3(1)).
 *
 * [userId] is the first constructor parameter and has no default, so no call site can construct
 * a vault translation key without naming an owner.
 */
data class VaultTranslationKey(
    val userId: UserId,
    val bookHash: String,
    val segmentHash: String,
    val model: String,
    val lang: String,
    val promptVersion: String,
    val glossaryHash: String,
)

/**
 * The identity of one cached per-book glossary *within one account*.
 *
 * Mirrors [app.berilo.reader.store.db.GlossaryEntity]'s four-column key with [userId] prepended,
 * for the same reason as [VaultTranslationKey]. The glossary matters to the vault's whole point:
 * `buildGlossary` bills one call *before* `translateBook` is entered, so a restore that carried
 * translations but not the glossary would still cost a call per book (`docs/findings.md`,
 * 2026-07-27).
 */
data class VaultGlossaryKey(
    val userId: UserId,
    val bookHash: String,
    val model: String,
    val lang: String,
    val promptVersion: String,
)
