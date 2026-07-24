package app.berilo.reader.llm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val FAKE_ANTHROPIC_KEY = "test-anthropic-key-do-not-use"

@OptIn(ExperimentalCoroutinesApi::class)
class AnthropicClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // Bound to this TestScope's own scheduler: an unbound UnconfinedTestDispatcher()
    // would make executeWithRetry's delay() throw ("Detected use of different
    // schedulers") instead of virtual-time-skipping it.
    private fun TestScope.client(model: String = "claude-haiku-4-5") =
        AnthropicClient(
            apiKey = FAKE_ANTHROPIC_KEY,
            model = model,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            baseUrl = server.url("/v1/messages").toString(),
        )

    @Test
    fun `parses usage and computes cost from a successful response, joining only text blocks`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "content": [
                        {"type": "text", "text": "Knjižnica je bila tiha."},
                        {"type": "tool_use", "text": "ignored"}
                      ],
                      "usage": {"input_tokens": 40, "output_tokens": 12}
                    }
                    """.trimIndent(),
                ),
            )

            val result = client().complete(prompt = "Translate this.")

            assertEquals("Knjižnica je bila tiha.", result.text)
            assertEquals(40, result.inputTokens)
            assertEquals(12, result.outputTokens)
            assertEquals("claude-haiku-4-5", result.model)
            // 40/1e6 * 1.00 + 12/1e6 * 5.00 = 0.0001 USD -> * 0.92 EUR
            assertEquals(0.000092, result.costEur, 1e-9)
        }

    @Test
    fun `sends x-api-key and anthropic-version headers, never Authorization`() =
        runTest {
            server.enqueue(MockResponse().setBody(successBody()))
            client().complete(prompt = "hi")

            val request = server.takeRequest()
            assertEquals(FAKE_ANTHROPIC_KEY, request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            assertTrue(request.getHeader("Authorization").isNullOrEmpty())
        }

    @Test
    fun `401 maps to AUTH with an actionable message and no key material`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": {"message": "unauthorized"}}"""))

            val error =
                try {
                    client().complete(prompt = "hi")
                    fail("expected LlmError")
                    null
                } catch (e: LlmError) {
                    e
                }

            assertEquals(LlmError.Kind.AUTH, error!!.kind)
            assertFalse(error.message.orEmpty().contains(FAKE_ANTHROPIC_KEY))
            assertTrue(error.message.orEmpty().contains("Settings"))
        }

    @Test
    fun `500 retries once then succeeds`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody(successBody()))

            val result = client().complete(prompt = "hi")

            assertEquals("ok", result.text)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `500 twice in a row maps to PROVIDER after the single retry`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))

            val error =
                try {
                    client().complete(prompt = "hi")
                    fail("expected LlmError")
                    null
                } catch (e: LlmError) {
                    e
                }

            assertEquals(LlmError.Kind.PROVIDER, error!!.kind)
            assertEquals(2, server.requestCount)
        }

    private fun successBody() =
        """
        {
          "content": [{"type": "text", "text": "ok"}],
          "usage": {"input_tokens": 1, "output_tokens": 1}
        }
        """.trimIndent()
}
