package app.berilo.reader.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.db.TranslationEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **These tests are load-bearing, not hygiene.** Each one pins a line from `docs/sync_api.md` §8
 * that the research note (`docs/research/2026-07-27-personal-copy-cloud-sync.md` §3.2) identifies
 * as the difference between private copying under *Austro-Mechana* (C-433/20) and communication
 * to the public under *Tom Kabinet* (C-263/18) / *VCAST* (C-265/16). A change that makes one of
 * these fail is a change to the service's legal posture, not a refactor — the fix is to restore
 * the property, never to relax the test.
 *
 * Every test names the §8 clause it defends, so the next reader can see what breaking it costs.
 */
@RunWith(RobolectricTestRunner::class)
class VaultIsolationTest {

    private lateinit var alicePhone: AppDatabase
    private lateinit var bobPhone: AppDatabase
    private lateinit var gateway: FakeVaultGateway

    private val alice = UserId("user_alice")
    private val bob = UserId("user_bob")

    /** The collision the whole story exists to prevent: both users own the same ISBN. */
    private val sharedBookHash = "book-hash-same-isbn"

    @Before
    fun setUp() {
        alicePhone = inMemoryDatabase()
        bobPhone = inMemoryDatabase()
        gateway = FakeVaultGateway()
    }

    @After
    fun tearDown() {
        alicePhone.close()
        bobPhone.close()
    }

    // --- §8.2(1): per-user isolation lives in the primary key -----------------------------

    /**
     * §8.2(1). Asserts the *schema*, not the rows: `PRAGMA table_info` per column, because a
     * row-counting test cannot see a key change and one such defect already shipped
     * (`docs/findings.md`, 2026-07-27, `MIGRATION_5_6`/`calls.id`).
     *
     * Remove `userId` from `VaultTranslationEntity`'s `primaryKeys` and this fails immediately.
     */
    @Test
    fun `userId is the first primary-key column of vault_translations - sync_api 8_2_1`() {
        val pkOrder = primaryKeyColumns(alicePhone, "vault_translations")

        assertEquals(
            "userId must be the FIRST primary-key column of vault_translations. sync_api.md " +
                "§8.2(1) requires per-user isolation to live in the key — not a WHERE clause, " +
                "not an RLS policy alone — so a refactor that drops a predicate cannot merge " +
                "two users' translated text.",
            listOf(
                "userId",
                "bookHash",
                "segmentHash",
                "model",
                "lang",
                "promptVersion",
                "glossaryHash",
            ),
            pkOrder,
        )
    }

    /** §8.2(1), the same rule for the other two vault tables. */
    @Test
    fun `userId leads the primary key of every vault table - sync_api 8_2_1`() {
        assertEquals(
            listOf("userId", "bookHash"),
            primaryKeyColumns(alicePhone, "vault_books"),
        )
        assertEquals(
            listOf("userId", "bookHash", "model", "lang", "promptVersion"),
            primaryKeyColumns(alicePhone, "vault_glossaries"),
        )
    }

    // --- §8.3(1): never share a translation row across users ------------------------------

    /**
     * §8.3(1). Two users push the same `book_hash`; both rows must survive.
     *
     * Mutation proof: drop `userId` from `VaultTranslationKey` (or from the entity's primary key)
     * and Bob's push collapses onto Alice's row — the stored count falls to 1 and this fails.
     */
    @Test
    fun `two users pushing the same book_hash keep separate rows - no cross-user dedup - sync_api 8_3_1`() =
        runTest {
            seedAndUpload(alicePhone, alice, "Alice's paid translation.")
            seedAndUpload(bobPhone, bob, "Bob's paid translation.")

            assertEquals(
                "Both users' rows must be stored. One row would mean the second user was " +
                    "deduplicated onto the first's translated text — sync_api.md §8.3(1): " +
                    "'Cross-user deduplication is this same defect wearing a cost-optimisation " +
                    "hat. No exception, however large the storage saving.'",
                2,
                gateway.storedTranslationCount,
            )
        }

    /**
     * §8.3(1), the read half: Bob's restore must return Bob's text and must never contain
     * Alice's, even though the two rows agree on all six content-addressed columns.
     */
    @Test
    fun `user B restore returns only B rows and never A text - sync_api 8_3_1`() =
        runTest {
            seedAndUpload(alicePhone, alice, ALICE_TEXT)
            seedAndUpload(bobPhone, bob, BOB_TEXT)

            // A fresh device for Bob, holding nothing.
            val bobTablet = inMemoryDatabase()
            try {
                val report = repositoryFor(bob, bobTablet).restore(sharedBookHash)
                assertTrue("Bob's restore should succeed", report is VaultRestoreReport.Restored)

                val restored =
                    bobTablet.translationCacheDao().translationsForBook(sharedBookHash)
                assertEquals(1, restored.size)
                assertEquals(
                    "Bob must get Bob's text back",
                    BOB_TEXT,
                    restored.single().text,
                )
                assertFalse(
                    "Alice's translated text must never reach Bob's device. Serving it would " +
                        "be reproduction plus communication to the public (InfoSoc Art. 3(1); " +
                        "Tom Kabinet C-263/18), not private copying — sync_api.md §8.3(1).",
                    restored.any { it.text.contains(ALICE_TEXT) },
                )
            } finally {
                bobTablet.close()
            }
        }

    // --- §8.3(2): no content-addressed object path keyed on book hash alone ----------------

    /**
     * §8.3(2). Every written object path must be namespaced by user. Two users owning the same
     * book must never land on the same object.
     */
    @Test
    fun `object paths are namespaced by user never by book hash alone - sync_api 8_3_2`() =
        runTest {
            seedAndUpload(alicePhone, alice, ALICE_TEXT)
            seedAndUpload(bobPhone, bob, BOB_TEXT)

            assertEquals(2, gateway.writtenPaths.size)
            assertEquals(
                "two users, two distinct object paths",
                2,
                gateway.writtenPaths.toSet().size,
            )
            assertTrue(
                "every path must contain its owner: $gateway.writtenPaths",
                gateway.writtenPaths.any { it.contains(alice.value) } &&
                    gateway.writtenPaths.any { it.contains(bob.value) },
            )
            gateway.writtenPaths.forEach { path ->
                assertFalse(
                    "a path must never be the book hash alone (sync_api.md §8.3(2))",
                    path == "vault/$sharedBookHash.enc" || path == sharedBookHash,
                )
            }
            assertEquals(
                "vault/${alice.value}/$sharedBookHash.enc",
                VaultObjectPath.forBook(alice, sharedBookHash),
            )
        }

    // --- §8.3(3): no sharing surface ------------------------------------------------------

    /**
     * §8.3(3). The gateway must expose no way to publish, link or grant access to a vault object:
     * "A sharing path converts storage into making-available."
     *
     * Asserted over the interface's own method names, so adding `fun shareBook(...)` fails here
     * before it can ever be called.
     */
    @Test
    fun `the vault gateway exposes no sharing surface - sync_api 8_3_3`() {
        val forbidden = listOf("share", "public", "link", "grant", "invite", "send")
        val offenders =
            VaultGateway::class.java.declaredMethods
                .map { it.name }
                .filter { name -> forbidden.any { name.lowercase().contains(it) } }

        assertEquals(
            "VaultGateway must expose no sharing surface — no links, no public objects, no " +
                "send-to-a-friend (sync_api.md §8.3(3)). Offending methods: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    // --- §8.2(2): the key is never transmitted, the ciphertext is opaque -------------------

    /**
     * §8.2(2). Nothing the gateway holds may contain the plaintext, the passphrase or the
     * derived key. This is what keeps the provider a conduit rather than a party with editorial
     * control (DSA Art. 6(2)).
     */
    @Test
    fun `nothing stored server-side contains plaintext or the user secret - sync_api 8_2_2`() =
        runTest {
            seedAndUpload(alicePhone, alice, ALICE_TEXT)

            val haystack = gateway.allStoredBytes()
            listOf(ALICE_TEXT, PASSPHRASE, BOOK_PLAINTEXT).forEach { needle ->
                assertFalse(
                    "'$needle' must not appear in anything the server stores — the server " +
                        "holds ciphertext it cannot read (sync_api.md §8.2(2))",
                    haystack.any { bytes -> bytes.containsSubsequence(needle.toByteArray()) },
                )
            }
        }

    /** §8.2(2). The passphrase never reaches the vault's device bookkeeping either. */
    @Test
    fun `the vault secret is never persisted alongside the ciphertext - sync_api 8_2_2`() =
        runTest {
            seedAndUpload(alicePhone, alice, ALICE_TEXT)

            val record = alicePhone.vaultDao().book(alice.value, sharedBookHash)
            assertNotNull(record)
            assertNotNull("the non-secret salt is kept, so a second device can derive", record!!.kdfSalt)
            assertFalse(
                "the salt must not be the passphrase",
                record.kdfSalt!!.containsSubsequence(PASSPHRASE.toByteArray()),
            )
        }

    // --- §8.2(3)/(4): user-initiated only -------------------------------------------------

    /**
     * §8.2(3) and §8.2(4). A book nobody opted in is never uploaded, and the gateway is never
     * called speculatively — the server has no way to learn the book exists.
     */
    @Test
    fun `an opted-out book reaches the gateway zero times - sync_api 8_2_3 and 8_2_4`() =
        runTest {
            seedCache(alicePhone, ALICE_TEXT)
            val repository = repositoryFor(alice, alicePhone)

            assertFalse("off by default", repository.isEnabled(sharedBookHash))
            val report = repository.upload(sharedBookHash, BOOK_PLAINTEXT.toByteArray())

            assertEquals(VaultUploadReport.NotEnabled, report)
            assertEquals(
                "the gateway must not be called at all for an opted-out book",
                0,
                gateway.callCount,
            )
            assertNull(
                "and nothing may be recorded as uploaded",
                alicePhone.vaultDao().book(alice.value, sharedBookHash),
            )
        }

    // --- helpers ---------------------------------------------------------------------------

    private fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        )
            .build()

    private fun repositoryFor(userId: UserId, database: AppDatabase) =
        VaultRepository(
            userId = userId,
            gateway = gateway,
            vaultDao = database.vaultDao(),
            cacheDao = database.translationCacheDao(),
            secret = FakeVaultSecret(PASSPHRASE),
            clock = { 1_700_000_000_000L },
        )

    /** Put one paid translation in [database]'s local cache under the shared six-column key. */
    private suspend fun seedCache(database: AppDatabase, text: String) {
        database.translationCacheDao()
            .insertTranslations(
                listOf(
                    TranslationEntity(
                        bookHash = sharedBookHash,
                        segmentHash = "segment-hash-identical-paragraph",
                        model = "gpt-5-mini",
                        lang = "sl",
                        promptVersion = "baseline_v1",
                        glossaryHash = "glossary-hash",
                        text = text,
                        costEur = 0.002,
                        createdAt = 1L,
                    ),
                ),
            )
    }

    private suspend fun seedAndUpload(database: AppDatabase, userId: UserId, text: String) {
        seedCache(database, text)
        val repository = repositoryFor(userId, database)
        repository.setEnabled(sharedBookHash, enabled = true)
        val report = repository.upload(sharedBookHash, BOOK_PLAINTEXT.toByteArray())
        assertTrue("upload should succeed, was $report", report is VaultUploadReport.Uploaded)
    }

    /** The 1-based primary-key column order SQLite reports for [table]. */
    private fun primaryKeyColumns(database: AppDatabase, table: String): List<String> {
        val ordered = sortedMapOf<Int, String>()
        database.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val position = cursor.getInt(pkIndex)
                if (position > 0) ordered[position] = cursor.getString(nameIndex)
            }
        }
        return ordered.values.toList()
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        const val ALICE_TEXT = "Alice's paid translation."
        const val BOB_TEXT = "Bob's paid translation."
        const val BOOK_PLAINTEXT = "Chapter One. The book's own words."
    }
}

/** Naive byte-subsequence search, for asserting a plaintext is absent from stored bytes. */
internal fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (start in 0..size - needle.size) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return true
    }
    return false
}
