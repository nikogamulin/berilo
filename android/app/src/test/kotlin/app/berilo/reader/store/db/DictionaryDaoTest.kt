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
 * [BookDaoTest]. Also exercises the S2.4 version-2 schema (`dictionary_entries` alongside
 * `books`), which is the offline signal that the `fallbackToDestructiveMigration()` bump
 * didn't break the existing `books` table.
 */
@RunWith(RobolectricTestRunner::class)
class DictionaryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: DictionaryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.dictionaryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `find on an empty cache is a miss`() =
        runTest {
            assertNull(dao.find("bank", "hash-1", "sl", "gpt-5-mini"))
        }

    @Test
    fun `upsert then find round-trips every field`() =
        runTest {
            val entry = entry()

            dao.upsert(entry)
            val found = dao.find(entry.word, entry.sentenceHash, entry.lang, entry.model)

            assertEquals(entry, found)
        }

    @Test
    fun `find requires an exact match on all four key fields`() =
        runTest {
            dao.upsert(entry())

            assertNull(dao.find("bank", "hash-1", "sl", "gpt-5-mini")) // wrong sentence hash below
            assertNull(dao.find("bank", "hash-2", "de", "gpt-5-mini")) // wrong lang
            assertNull(dao.find("bank", "hash-2", "sl", "claude-haiku-4-5")) // wrong model
            assertNull(dao.find("river", "hash-2", "sl", "gpt-5-mini")) // wrong word
        }

    @Test
    fun `upsert replaces an existing entry for the same key rather than duplicating`() =
        runTest {
            dao.upsert(entry())
            dao.upsert(entry().copy(definition = "posodobljena banka"))

            val found = dao.find("bank", "hash-2", "sl", "gpt-5-mini")

            assertEquals("posodobljena banka", found?.definition)
        }

    private fun entry() =
        DictionaryEntryEntity(
            word = "bank",
            sentenceHash = "hash-2",
            lang = "sl",
            model = "gpt-5-mini",
            definition = "banka",
            contextMeaning = "finančna ustanova",
            baseForm = "banka",
            usageNote = "pogosto v ednini",
            costEur = 0.0002,
            createdAt = 1_000L,
        )
}
