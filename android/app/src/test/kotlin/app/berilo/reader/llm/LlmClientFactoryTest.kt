package app.berilo.reader.llm

import app.berilo.reader.settings.LlmSettings
import okhttp3.mockwebserver.MockWebServer
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

    @Test
    fun `unpriced gpt- model fails before any HTTP request is ever possible`() {
        // review finding 6: without the pre-flight, an OpenAiClient billed by a real
        // request could still be constructed here; a MockWebServer that receives no
        // request proves the guard fires before any client capable of a network call
        // is ever returned.
        val server = MockWebServer()
        server.start()
        try {
            val error =
                try {
                    createLlmClient(LlmSettings(openaiKey = "test-openai-key", model = "gpt-4.1"))
                    fail("expected LlmError")
                    null
                } catch (e: LlmError) {
                    e
                }
            assertEquals(LlmError.Kind.PROVIDER, error!!.kind)
            assertTrue(error.message.orEmpty().contains("gpt-4.1"))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unpriced claude- model fails before any HTTP request is ever possible`() {
        val server = MockWebServer()
        server.start()
        try {
            val error =
                try {
                    createLlmClient(LlmSettings(anthropicKey = "test-anthropic-key", model = "claude-3-made-up"))
                    fail("expected LlmError")
                    null
                } catch (e: LlmError) {
                    e
                }
            assertEquals(LlmError.Kind.PROVIDER, error!!.kind)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
