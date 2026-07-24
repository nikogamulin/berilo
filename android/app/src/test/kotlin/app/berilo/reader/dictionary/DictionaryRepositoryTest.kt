package app.berilo.reader.dictionary

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.settings.LlmSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counts every [LlmClient.complete] call — the cache-hit test asserts this stays at zero. */
private class CountingLlmClient(private val text: String = "DEFINITION: banka") : LlmClient {
    var callCount = 0
        private set

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult {
        callCount++
        return LlmResult(text = text, inputTokens = 5, outputTokens = 5, costEur = 0.0002, model = "gpt-5-mini")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryRepositoryTest {

    private val settings = LlmSettings(model = "gpt-5-mini", targetLang = "sl")

    private fun repository(client: LlmClient, dao: FakeDictionaryDao = FakeDictionaryDao()) =
        DictionaryRepository(
            dao = dao,
            service = DictionaryService(createClient = { client }),
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_000L },
        )

    @Test
    fun `cache miss calls the LLM once and writes an entry`() =
        runTest {
            val client = CountingLlmClient()
            val dao = FakeDictionaryDao()

            val result = repository(client, dao).lookup(settings, "Bank", "She sat by the river bank.")

            assertEquals(1, client.callCount)
            assertEquals(1, dao.count())
            assertFalse(result.fromCache)
            assertEquals("banka", result.definition.definition)
        }

    @Test
    fun `cache hit makes zero LlmClient calls`() =
        runTest {
            val client = CountingLlmClient()
            val dao = FakeDictionaryDao()
            val repo = repository(client, dao)

            repo.lookup(settings, "Bank", "She sat by the river bank.")
            assertEquals(1, client.callCount)

            val second = repo.lookup(settings, "Bank", "She sat by the river bank.")

            assertEquals("cache hit must not call the LLM again", 1, client.callCount)
            assertTrue(second.fromCache)
        }

    @Test
    fun `word lookup is case-insensitive for cache purposes`() =
        runTest {
            val client = CountingLlmClient()
            val repo = repository(client)

            repo.lookup(settings, "Bank", "A sentence about a bank.")
            val second = repo.lookup(settings, "bank", "A sentence about a bank.")

            assertEquals(1, client.callCount)
            assertTrue(second.fromCache)
        }

    @Test
    fun `a different sentence context is a separate cache entry (disambiguation)`() =
        runTest {
            val client = CountingLlmClient()
            val repo = repository(client)

            repo.lookup(settings, "bank", "She sat by the river bank.")
            repo.lookup(settings, "bank", "He deposited money at the bank.")

            assertEquals(2, client.callCount)
        }

    @Test
    fun `a different target language is a separate cache entry`() =
        runTest {
            val client = CountingLlmClient()
            val dao = FakeDictionaryDao()
            val repo = repository(client, dao)

            repo.lookup(settings.copy(targetLang = "sl"), "bank", "A sentence.")
            repo.lookup(settings.copy(targetLang = "de"), "bank", "A sentence.")

            assertEquals(2, client.callCount)
            assertEquals(2, dao.count())
        }
}
