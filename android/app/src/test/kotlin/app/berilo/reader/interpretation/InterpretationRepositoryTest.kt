package app.berilo.reader.interpretation

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
private class CountingLlmClient(private val text: String = "This passage foreshadows the betrayal.") : LlmClient {
    var callCount = 0
        private set

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult {
        callCount++
        return LlmResult(text = text, inputTokens = 40, outputTokens = 120, costEur = 0.0021, model = "gpt-5-mini")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InterpretationRepositoryTest {

    private val settings = LlmSettings(model = "gpt-5-mini", targetLang = "sl")
    private val passage = "The old king watched the city burn from the highest tower."

    private fun repository(client: LlmClient, dao: FakeInterpretationDao = FakeInterpretationDao()) =
        InterpretationRepository(
            dao = dao,
            service = InterpretationService(createClient = { client }),
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_000L },
        )

    @Test
    fun `cache miss calls the LLM once and writes an entry`() =
        runTest {
            val client = CountingLlmClient()
            val dao = FakeInterpretationDao()

            val result = repository(client, dao).interpret(settings, passage, "The Last Reign")

            assertEquals(1, client.callCount)
            assertEquals(1, dao.count())
            assertFalse(result.fromCache)
            assertEquals("This passage foreshadows the betrayal.", result.text)
        }

    @Test
    fun `cache hit makes zero LlmClient calls`() =
        runTest {
            val client = CountingLlmClient()
            val dao = FakeInterpretationDao()
            val repo = repository(client, dao)

            repo.interpret(settings, passage, "The Last Reign")
            assertEquals(1, client.callCount)

            val second = repo.interpret(settings, passage, "The Last Reign")

            assertEquals("cache hit must not call the LLM again", 1, client.callCount)
            assertTrue(second.fromCache)
        }

    @Test
    fun `a different passage is a separate cache entry`() =
        runTest {
            val client = CountingLlmClient()
            val repo = repository(client)

            repo.interpret(settings, passage, "The Last Reign")
            repo.interpret(settings, "A completely different passage about a river.", "The Last Reign")

            assertEquals(2, client.callCount)
        }

    @Test
    fun `a different target language is a separate cache entry`() =
        runTest {
            val client = CountingLlmClient()
            val dao = FakeInterpretationDao()
            val repo = repository(client, dao)

            repo.interpret(settings.copy(targetLang = "sl"), passage, "The Last Reign")
            repo.interpret(settings.copy(targetLang = "de"), passage, "The Last Reign")

            assertEquals(2, client.callCount)
            assertEquals(2, dao.count())
        }

    @Test
    fun `a different model is a separate cache entry`() =
        runTest {
            val client = CountingLlmClient()
            val dao = FakeInterpretationDao()
            val repo = repository(client, dao)

            repo.interpret(settings.copy(interpretationModel = "gpt-5-mini"), passage, "The Last Reign")
            repo.interpret(settings.copy(interpretationModel = "claude-haiku-4-5"), passage, "The Last Reign")

            assertEquals(2, client.callCount)
            assertEquals(2, dao.count())
        }
}
