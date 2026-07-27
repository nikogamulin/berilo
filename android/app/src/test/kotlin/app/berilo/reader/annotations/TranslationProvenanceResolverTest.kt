package app.berilo.reader.annotations

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.db.TranslationCacheDao
import app.berilo.reader.store.db.TranslationEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B9's provenance recovery, against the real translation-cache tables.
 *
 * The property under test is asymmetric on purpose: a match must recover the *exact* six-column
 * cache key, and a miss must be a plain `null` rather than a guess — a flag carrying the wrong
 * model and prompt would send a future re-examination at the wrong run.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationProvenanceResolverTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TranslationCacheDao
    private lateinit var resolver: TranslationProvenanceResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.translationCacheDao()
        resolver = TranslationProvenanceResolver(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun cache(
        text: String,
        segmentHash: String = "seg-1",
        promptVersion: String = "revise_v1",
        createdAt: Long = 1_000L,
    ) {
        dao.insertTranslations(
            listOf(
                TranslationEntity(
                    bookHash = "book-hash-1",
                    segmentHash = segmentHash,
                    model = "gpt-5-mini",
                    lang = "sl",
                    promptVersion = promptVersion,
                    glossaryHash = "glossary-hash-1",
                    text = text,
                    costEur = 0.001,
                    createdAt = createdAt,
                ),
            ),
        )
    }

    @Test
    fun `an exactly matching segment recovers the whole cache key`() =
        runTest {
            cache("Geografija je usoda, ki je nihče ne izbere.")

            val provenance = resolver.resolve("Geografija je usoda, ki je nihče ne izbere.")

            assertEquals(
                TranslationProvenance(
                    bookHash = "book-hash-1",
                    segmentHash = "seg-1",
                    model = "gpt-5-mini",
                    lang = "sl",
                    promptVersion = "revise_v1",
                    glossaryHash = "glossary-hash-1",
                ),
                provenance,
            )
        }

    @Test
    fun `surrounding whitespace in the selection does not defeat the exact match`() =
        runTest {
            cache("Geografija je usoda, ki je nihče ne izbere.")

            // Readium hands back the rendered selection; a trailing newline from the source
            // markup is an artifact of the DOM, not a different passage.
            assertEquals(
                "seg-1",
                resolver.resolve("  Geografija je usoda, ki je nihče ne izbere.\n")?.segmentHash,
            )
        }

    @Test
    fun `a sentence selected from inside a paragraph matches the containing segment`() =
        runTest {
            cache(
                "Geografija je usoda, ki je nihče ne izbere. Reke in gorovja so starejši od " +
                    "vsake meje, ki jo je narisal človek.",
            )

            // The commonest real gesture: the reader selects one bad sentence, not the whole
            // paragraph. Without containment matching every such flag would be provenance-free.
            assertEquals(
                "seg-1",
                resolver.resolve("Reke in gorovja so starejši od vsake meje")?.segmentHash,
            )
        }

    @Test
    fun `a short fragment is left unmatched rather than matched at random`() =
        runTest {
            cache("Geografija je usoda, ki je nihče ne izbere.")

            // "je usoda" appears in the cached row, so a containment scan WOULD return it —
            // and would return some arbitrary row in a real book with thousands of segments.
            assertNull(resolver.resolve("je usoda"))
        }

    @Test
    fun `a passage from a book whose cache never reached this device stores no provenance`() =
        runTest {
            cache("Geografija je usoda, ki je nihče ne izbere.")

            assertNull(resolver.resolve("Povsem drugo besedilo, ki ga ni v predpomnilniku."))
        }

    @Test
    fun `an empty selection resolves to null without querying`() =
        runTest {
            cache("Geografija je usoda, ki je nihče ne izbere.")

            assertNull(resolver.resolve("   "))
        }

    @Test
    fun `the newest row wins when the same text was translated twice`() =
        runTest {
            cache("Isto besedilo v obeh tekih.", segmentHash = "seg-old", promptVersion = "baseline_v1", createdAt = 1L)
            cache("Isto besedilo v obeh tekih.", segmentHash = "seg-new", promptVersion = "revise_v1", createdAt = 2L)

            // A segment re-translated under a newer prompt is what the reader is looking at, so
            // that is the run the flag must name.
            assertEquals("revise_v1", resolver.resolve("Isto besedilo v obeh tekih.")?.promptVersion)
        }

    @Test
    fun `SQL wildcards in the selected text are matched literally, not as wildcards`() =
        runTest {
            cache("Rast je znašala 40% v enem samem letu, kar je izjemno veliko.")
            cache("Povsem nepovezan odstavek brez odstotkov.", segmentHash = "seg-2", createdAt = 2_000L)

            // Unescaped, "100%_zanesljivo" would be a LIKE pattern rather than a literal and
            // would match the newest row in the table — silently attaching another segment's
            // model and prompt to this flag.
            assertNull(resolver.resolve("100%_zanesljivo ni v predpomnilniku"))
            assertEquals("seg-1", resolver.resolve("znašala 40% v enem samem letu")?.segmentHash)
        }

    @Test
    fun `likeContainsPattern escapes every LIKE metacharacter`() {
        assertEquals("%100\\%\\_x\\\\y%", likeContainsPattern("100%_x\\y"))
    }
}
