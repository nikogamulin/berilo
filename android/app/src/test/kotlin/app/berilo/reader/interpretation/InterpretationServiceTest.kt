package app.berilo.reader.interpretation

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.settings.LlmSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LlmClient] test double that records every call and returns a fixed [LlmResult]. */
private class RecordingLlmClient(private val result: LlmResult) : LlmClient {
    var lastPrompt: String? = null
        private set
    var lastSystem: String? = null
        private set
    var lastMaxTokens: Int? = null
        private set
    var callCount = 0
        private set

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult {
        callCount++
        lastPrompt = prompt
        lastSystem = system
        lastMaxTokens = maxTokens
        return result
    }
}

class InterpretationServiceTest {

    private fun fixedResult(text: String = "Passage means the fall of an empire.") =
        LlmResult(text = text, inputTokens = 40, outputTokens = 120, costEur = 0.0021, model = "gpt-5-mini")

    @Test
    fun `prompt includes the passage, book title, and target language`() =
        runTest {
            val client = RecordingLlmClient(fixedResult())
            val service = InterpretationService(createClient = { client })

            service.interpret(
                LlmSettings(model = "gpt-5-mini", targetLang = "sl"),
                passage = "The old king watched the city burn from the highest tower.",
                bookTitle = "The Last Reign",
            )

            val prompt = client.lastPrompt.orEmpty()
            assertTrue(prompt.contains("The old king watched the city burn from the highest tower."))
            assertTrue(prompt.contains("The Last Reign"))
            assertTrue(prompt.contains("sl"))
        }

    @Test
    fun `bounds the completion with a named maxTokens constant`() =
        runTest {
            val client = RecordingLlmClient(fixedResult())
            val service = InterpretationService(createClient = { client })

            service.interpret(LlmSettings(), passage = "A passage.", bookTitle = "A Book")

            assertTrue((client.lastMaxTokens ?: 0) > 0)
        }

    @Test
    fun `routes to interpretationModel when set, falling back to the main model when null`() =
        runTest {
            val client = RecordingLlmClient(fixedResult())
            var seenModel: String? = null
            val service =
                InterpretationService(
                    createClient = { settings -> seenModel = settings.model; client },
                )

            service.interpret(
                LlmSettings(model = "gpt-5-mini", interpretationModel = "claude-haiku-4-5"),
                passage = "A passage.",
                bookTitle = "A Book",
            )
            assertEquals("claude-haiku-4-5", seenModel)

            service.interpret(
                LlmSettings(model = "gpt-5-mini", interpretationModel = null),
                passage = "A passage.",
                bookTitle = "A Book",
            )
            assertEquals("gpt-5-mini", seenModel)
        }

    @Test
    fun `surfaces the trimmed completion text, cost, and model`() =
        runTest {
            val client = RecordingLlmClient(fixedResult("  Passage means the fall of an empire.  \n"))
            val service = InterpretationService(createClient = { client })

            val result = service.interpret(LlmSettings(), passage = "A passage.", bookTitle = "A Book")

            assertEquals("Passage means the fall of an empire.", result.text)
            assertEquals(0.0021, result.costEur, 1e-9)
            assertEquals("gpt-5-mini", result.model)
        }

    @Test
    fun `propagates LlmError from the client unchanged`() =
        runTest {
            val failing =
                object : LlmClient {
                    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult =
                        throw LlmError("You're offline.", LlmError.Kind.NETWORK)
                }
            val service = InterpretationService(createClient = { failing })

            var thrownKind: LlmError.Kind? = null
            try {
                service.interpret(LlmSettings(), passage = "A passage.", bookTitle = "A Book")
            } catch (e: LlmError) {
                thrownKind = e.kind
            }

            assertEquals(LlmError.Kind.NETWORK, thrownKind)
        }
}
