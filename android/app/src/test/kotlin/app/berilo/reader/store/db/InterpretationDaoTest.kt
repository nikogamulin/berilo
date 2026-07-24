package app.berilo.reader.store.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO tests against a real (in-memory) Room database — same Robolectric pattern as
 * [DictionaryDaoTest]. Also exercises the S2.5 version-3 schema (`interpretation_entries`
 * alongside `books` and `dictionary_entries`), which is the offline signal that the
 * `fallbackToDestructiveMigration()` bump didn't break the existing tables.
 */
@RunWith(RobolectricTestRunner::class)
class InterpretationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: InterpretationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.interpretationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `find on an empty cache is a miss`() =
        runTest {
            assertNull(dao.find("hash-1", "sl", "gpt-5-mini"))
        }

    @Test
    fun `upsert then find round-trips every field`() =
        runTest {
            val entry = entry()

            dao.upsert(entry)
            val found = dao.find(entry.passageHash, entry.lang, entry.model)

            assertEquals(entry, found)
        }

    @Test
    fun `find requires an exact match on all three key fields`() =
        runTest {
            dao.upsert(entry())

            assertNull(dao.find("hash-other", "sl", "gpt-5-mini")) // wrong passage hash
            assertNull(dao.find("hash-2", "de", "gpt-5-mini")) // wrong lang
            assertNull(dao.find("hash-2", "sl", "claude-haiku-4-5")) // wrong model
        }

    @Test
    fun `upsert replaces an existing entry for the same key rather than duplicating`() =
        runTest {
            dao.upsert(entry())
            dao.upsert(entry().copy(text = "posodobljena razlaga odlomka"))

            val found = dao.find("hash-2", "sl", "gpt-5-mini")

            assertEquals("posodobljena razlaga odlomka", found?.text)
        }

    private fun entry() =
        InterpretationEntryEntity(
            passageHash = "hash-2",
            lang = "sl",
            model = "gpt-5-mini",
            text = "Odlomek nakazuje prihajajočo izdajo.",
            costEur = 0.0021,
            createdAt = 1_000L,
        )
}
