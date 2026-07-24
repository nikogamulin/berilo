package app.berilo.reader.llm

import app.berilo.reader.settings.LlmSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmClientFactoryTest {

    @Test
    fun `gpt- models route to OpenAiClient`() {
        val client = createLlmClient(LlmSettings(openaiKey = "test-openai-key", model = "gpt-5-mini"))
        assertTrue(client is OpenAiClient)
    }

    @Test
    fun `claude- models route to AnthropicClient`() {
        val client = createLlmClient(LlmSettings(anthropicKey = "test-anthropic-key", model = "claude-sonnet-4-5"))
        assertTrue(client is AnthropicClient)
    }

    @Test
    fun `missing OpenAI key raises an actionable AUTH error`() {
        val error =
            try {
                createLlmClient(LlmSettings(openaiKey = null, model = "gpt-5-mini"))
                fail("expected LlmError")
                null
            } catch (e: LlmError) {
                e
            }
        assertEquals(LlmError.Kind.AUTH, error!!.kind)
        assertTrue(error.message.orEmpty().contains("Settings"))
    }

    @Test
    fun `missing Anthropic key raises an actionable AUTH error`() {
        val error =
            try {
                createLlmClient(LlmSettings(anthropicKey = null, model = "claude-haiku-4-5"))
                fail("expected LlmError")
                null
            } catch (e: LlmError) {
                e
            }
        assertEquals(LlmError.Kind.AUTH, error!!.kind)
    }

    @Test
    fun `unrecognized model prefix raises PROVIDER`() {
        val error =
            try {
                createLlmClient(LlmSettings(model = "mistral-large"))
                fail("expected LlmError")
                null
            } catch (e: LlmError) {
                e
            }
        assertEquals(LlmError.Kind.PROVIDER, error!!.kind)
    }
}
