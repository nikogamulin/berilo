package app.berilo.reader.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.translate.engine.EchoBatchLlmClient
import app.berilo.reader.translate.engine.RoomTranslationCache
import app.berilo.reader.translate.engine.ScriptedLlmClient
import app.berilo.reader.translate.engine.ScriptedReply
import app.berilo.reader.translate.engine.TEST_MODEL
import app.berilo.reader.translate.engine.buildGlossary
import app.berilo.reader.translate.engine.chapterOf
import app.berilo.reader.translate.engine.translateBook
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.bookHash
import app.berilo.reader.translate.prompts.BASELINE
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **The headline test for S3.7: don't pay to translate the same book twice.**
 *
 * One user, two devices. The first device pays for a glossary pass and a book's worth of segment
 * translations. Both are encrypted here, pushed to the vault as ciphertext, and pulled onto a
 * *second* device instance — after which translating the same book on that second device makes
 * **zero** API calls, asserted on the fake client's own counter.
 *
 * That is the entire value proposition of `docs/sync_api.md` §8, proven rather than asserted, and
 * it is legitimate precisely because it stays *within one user's account across their own
 * devices* — §8.3(1) draws the line at account boundaries, which `VaultIsolationTest` defends.
 *
 * The glossary is carried deliberately. `buildGlossary` bills one call **before** `translateBook`
 * is ever entered (`docs/findings.md`, 2026-07-27: "Meter at the `LlmClient` boundary, not at the
 * stage that happens to make most of the calls"), so a vault that restored only translations
 * would still cost one call per book and "zero" would be off by exactly one.
 */
@RunWith(RobolectricTestRunner::class)
class VaultRoundTripTest {

    private lateinit var firstDevice: AppDatabase
    private lateinit var secondDevice: AppDatabase
    private lateinit var gateway: FakeVaultGateway

    private val user = UserId("user_niko")

    @Before
    fun setUp() {
        firstDevice = inMemoryDatabase()
        secondDevice = inMemoryDatabase()
        gateway = FakeVaultGateway()
    }

    @After
    fun tearDown() {
        firstDevice.close()
        secondDevice.close()
    }

    @Test
    fun `a book restored from the vault onto a second device translates for zero API calls`() =
        runTest {
            val book = chapterOf(SEGMENT_COUNT)
            val hash = bookHash(book)
            val firstCache = RoomTranslationCache(firstDevice.translationCacheDao())

            // --- Device 1: pay for the glossary pass and the translation ---------------------
            val glossaryClient = ScriptedLlmClient(listOf(ScriptedReply.Text(GLOSSARY_JSON)))
            val glossary = buildGlossary(book, glossaryClient, LANG, TEST_MODEL, firstCache)
            assertEquals("the glossary pass costs one call the first time", 1, glossaryClient.callCount)
            assertEquals(mapOf("Ministry" to "Ministrstvo"), glossary.terms)

            val translateClient = EchoBatchLlmClient()
            val translated =
                translateBook(
                    book,
                    translateClient,
                    LANG,
                    firstCache,
                    TEST_MODEL,
                    glossary = glossary,
                    style = BASELINE,
                )
            assertTrue("device 1 actually paid", translateClient.callCount > 0)
            assertEquals(SEGMENT_COUNT, translated.segments.size)

            // --- Push: opt in, encrypt, upload ------------------------------------------------
            val firstVault = repositoryFor(firstDevice)
            firstVault.setEnabled(hash, enabled = true)
            val upload = firstVault.upload(hash, BOOK_BYTES)

            assertTrue("upload should succeed, was $upload", upload is VaultUploadReport.Uploaded)
            upload as VaultUploadReport.Uploaded
            assertEquals("every translated segment is in the vault", SEGMENT_COUNT, upload.translations)
            assertEquals("and so is the glossary", 1, upload.glossaries)

            // --- Restore onto a second, empty device ------------------------------------------
            assertEquals(
                "the second device starts with nothing cached",
                emptyList<Any>(),
                secondDevice.translationCacheDao().translationsForBook(hash),
            )

            val restore = repositoryFor(secondDevice).restore(hash)
            assertTrue("restore should succeed, was $restore", restore is VaultRestoreReport.Restored)
            restore as VaultRestoreReport.Restored
            assertEquals(SEGMENT_COUNT, restore.translations)
            assertEquals(1, restore.glossaries)
            assertEquals(
                "the book file itself comes back byte-identical",
                BOOK_BYTES.toList(),
                restore.bookBytes.toList(),
            )

            // --- The claim: translating this book again now costs nothing ---------------------
            // No scripted replies and no exhaustion handler, so any call at all fails loudly
            // rather than quietly returning something plausible.
            val secondDeviceClient = ScriptedLlmClient(replies = emptyList())
            val secondCache = RoomTranslationCache(secondDevice.translationCacheDao())

            val restoredGlossary =
                buildGlossary(book, secondDeviceClient, LANG, TEST_MODEL, secondCache)
            assertEquals(
                "the restored glossary must be the one device 1 paid for",
                glossary.terms,
                restoredGlossary.terms,
            )

            val retranslated =
                translateBook(
                    book,
                    secondDeviceClient,
                    LANG,
                    secondCache,
                    TEST_MODEL,
                    glossary = restoredGlossary,
                    style = BASELINE,
                )

            assertEquals(
                "THE HEADLINE ASSERTION: a book restored from the user's own vault must " +
                    "re-translate on a second device for zero API calls. Any number above zero " +
                    "means the user paid twice for the same work, which is the whole thing " +
                    "sync_api.md §8 exists to prevent.",
                0,
                secondDeviceClient.callCount,
            )
            assertEquals(
                "and the text is the text device 1 paid for",
                translated.segments.map { it.text },
                retranslated.segments.map { it.text },
            )
        }

    /**
     * The restored rows resolve under the *existing* six-column cache key — not a vault-specific
     * lookup — which is why the engine needs no knowledge of the vault at all.
     */
    @Test
    fun `restored rows resolve against the existing six-column cache key`() =
        runTest {
            val book = chapterOf(SEGMENT_COUNT)
            val hash = bookHash(book)
            val firstCache = RoomTranslationCache(firstDevice.translationCacheDao())
            val glossary =
                buildGlossary(
                    book,
                    ScriptedLlmClient(listOf(ScriptedReply.Text(GLOSSARY_JSON))),
                    LANG,
                    TEST_MODEL,
                    firstCache,
                )
            translateBook(
                book,
                EchoBatchLlmClient(),
                LANG,
                firstCache,
                TEST_MODEL,
                glossary = glossary,
                style = BASELINE,
            )

            val firstVault = repositoryFor(firstDevice)
            firstVault.setEnabled(hash, enabled = true)
            firstVault.upload(hash, BOOK_BYTES)
            repositoryFor(secondDevice).restore(hash)

            val original = firstDevice.translationCacheDao().translationsForBook(hash).sortedBy { it.segmentHash }
            val restored = secondDevice.translationCacheDao().translationsForBook(hash).sortedBy { it.segmentHash }

            assertEquals(original.size, restored.size)
            original.zip(restored).forEach { (before, after) ->
                assertEquals(before.bookHash, after.bookHash)
                assertEquals(before.segmentHash, after.segmentHash)
                assertEquals(before.model, after.model)
                assertEquals(before.lang, after.lang)
                assertEquals(before.promptVersion, after.promptVersion)
                assertEquals(before.glossaryHash, after.glossaryHash)
                assertEquals(before.text, after.text)
                assertEquals("the original spend is carried, not reset", before.costEur, after.costEur, 0.0)
            }
        }

    /** What the server holds is opaque: none of the book's or translation's words appear in it. */
    @Test
    fun `the bytes handed to the vault contain no plaintext from the source`() =
        runTest {
            val book: Book = chapterOf(SEGMENT_COUNT)
            val hash = bookHash(book)
            val cache = RoomTranslationCache(firstDevice.translationCacheDao())
            val glossary =
                buildGlossary(
                    book,
                    ScriptedLlmClient(listOf(ScriptedReply.Text(GLOSSARY_JSON))),
                    LANG,
                    TEST_MODEL,
                    cache,
                )
            translateBook(
                book,
                EchoBatchLlmClient(),
                LANG,
                cache,
                TEST_MODEL,
                glossary = glossary,
                style = BASELINE,
            )
            val vault = repositoryFor(firstDevice)
            vault.setEnabled(hash, enabled = true)
            vault.upload(hash, BOOK_BYTES)

            val stored = gateway.allStoredBytes()
            val plaintexts =
                book.segments.map { it.text } +
                    listOf("Ministrstvo", "Ministry", PASSPHRASE, String(BOOK_BYTES))

            plaintexts.forEach { needle ->
                assertTrue(
                    "'$needle' must not be readable in anything the vault stores",
                    stored.none { it.containsSubsequence(needle.toByteArray()) },
                )
            }
            assertTrue("something was actually stored", stored.isNotEmpty())
        }

    /** A different secret cannot open the vault — AES-GCM authenticates, so it fails loudly. */
    @Test
    fun `a second device with the wrong secret cannot decrypt and restores nothing`() =
        runTest {
            val book = chapterOf(SEGMENT_COUNT)
            val hash = bookHash(book)
            val cache = RoomTranslationCache(firstDevice.translationCacheDao())
            translateBook(
                book,
                EchoBatchLlmClient(),
                LANG,
                cache,
                TEST_MODEL,
                style = BASELINE,
            )
            val vault = repositoryFor(firstDevice)
            vault.setEnabled(hash, enabled = true)
            vault.upload(hash, BOOK_BYTES)

            val wrongSecretDevice =
                VaultRepository(
                    userId = user,
                    gateway = gateway,
                    vaultDao = secondDevice.vaultDao(),
                    cacheDao = secondDevice.translationCacheDao(),
                    secret = FakeVaultSecret("not the passphrase"),
                    clock = { NOW },
                )

            val report = wrongSecretDevice.restore(hash)
            assertTrue("a wrong secret must fail, not half-restore", report is VaultRestoreReport.Failed)
            assertEquals(
                "and must write nothing into the local cache",
                emptyList<Any>(),
                secondDevice.translationCacheDao().translationsForBook(hash),
            )
            assertNotEquals(0, gateway.callCount)
        }

    // --- helpers ---------------------------------------------------------------------------

    private fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        )
            .build()

    private fun repositoryFor(database: AppDatabase) =
        VaultRepository(
            userId = user,
            gateway = gateway,
            vaultDao = database.vaultDao(),
            cacheDao = database.translationCacheDao(),
            secret = FakeVaultSecret(PASSPHRASE),
            clock = { NOW },
        )

    private companion object {
        const val LANG = "sl"
        const val SEGMENT_COUNT = 6
        const val PASSPHRASE = "a secret only this reader knows"
        const val GLOSSARY_JSON = """{"Ministry": "Ministrstvo"}"""
        const val NOW = 1_700_000_000_000L
        val BOOK_BYTES = "Chapter One. Every word of the user's own book.".toByteArray()
    }
}
