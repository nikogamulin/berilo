package app.berilo.reader.dictionary

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

class DictionaryServiceTest {

    private fun fixedResult(text: String = "DEFINITION: banka") =
        LlmResult(text = text, inputTokens = 10, outputTokens = 8, costEur = 0.0001, model = "gpt-5-mini")

    @Test
    fun `prompt includes the word, sentence, and target language`() =
        runTest {
            val client = RecordingLlmClient(fixedResult())
            val service = DictionaryService(createClient = { client })

            service.define(
                LlmSettings(model = "gpt-5-mini", targetLang = "sl"),
                word = "bank",
                sentence = "She sat by the river bank.",
            )

            val prompt = client.lastPrompt.orEmpty()
            assertTrue(prompt.contains("bank"))
            assertTrue(prompt.contains("She sat by the river bank."))
            assertTrue(prompt.contains("sl"))
        }

    @Test
    fun `bounds the completion with a named maxTokens constant`() =
        runTest {
            val client = RecordingLlmClient(fixedResult())
            val service = DictionaryService(createClient = { client })

            service.define(LlmSettings(), word = "bank", sentence = "A sentence.")

            assertTrue((client.lastMaxTokens ?: 0) > 0)
        }

    @Test
    fun `routes to dictionaryModel when set, falling back to the main model when null`() =
        runTest {
            val client = RecordingLlmClient(fixedResult())
            var seenModel: String? = null
            val service =
                DictionaryService(
                    createClient = { settings -> seenModel = settings.model; client },
                )

            service.define(
                LlmSettings(model = "gpt-5-mini", dictionaryModel = "claude-haiku-4-5"),
                word = "bank",
                sentence = "A sentence.",
            )
            assertEquals("claude-haiku-4-5", seenModel)

            service.define(LlmSettings(model = "gpt-5-mini", dictionaryModel = null), word = "bank", sentence = "A sentence.")
            assertEquals("gpt-5-mini", seenModel)
        }

    @Test
    fun `parses the response into a DictionaryDefinition and surfaces cost and model`() =
        runTest {
            val client = RecordingLlmClient(fixedResult("DEFINITION: banka\nBASE FORM: banka"))
            val service = DictionaryService(createClient = { client })

            val result = service.define(LlmSettings(), word = "bank", sentence = "A sentence.")

            assertEquals("banka", result.definition.definition)
            assertEquals("banka", result.definition.baseForm)
            assertEquals(0.0001, result.costEur, 1e-9)
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
            val service = DictionaryService(createClient = { failing })

            var thrownKind: LlmError.Kind? = null
            try {
                service.define(LlmSettings(), word = "bank", sentence = "A sentence.")
            } catch (e: LlmError) {
                thrownKind = e.kind
            }

            assertEquals(LlmError.Kind.NETWORK, thrownKind)
        }
}
