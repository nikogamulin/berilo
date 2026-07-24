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

private const val FAKE_OPENAI_KEY = "test-openai-key-do-not-use"

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiClientTest {

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
    private fun TestScope.client(model: String = "gpt-5-mini") =
        OpenAiClient(
            apiKey = FAKE_OPENAI_KEY,
            model = model,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            baseUrl = server.url("/v1/chat/completions").toString(),
        )

    @Test
    fun `parses usage and computes cost from a successful response`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "choices": [{"message": {"content": "Knjižnica je bila tiha."}}],
                      "usage": {"prompt_tokens": 40, "completion_tokens": 12}
                    }
                    """.trimIndent(),
                ),
            )

            val result = client().complete(prompt = "Translate this.")

            assertEquals("Knjižnica je bila tiha.", result.text)
            assertEquals(40, result.inputTokens)
            assertEquals(12, result.outputTokens)
            assertEquals("gpt-5-mini", result.model)
            // 40/1e6 * 0.25 + 12/1e6 * 2.00 = 0.000034 USD -> * 0.92 EUR
            assertEquals(0.00003128, result.costEur, 1e-9)
        }

    @Test
    fun `sends reasoning_effort low for reasoning models but not for others`() =
        runTest {
            server.enqueue(MockResponse().setBody(successBody()))
            client(model = "gpt-5-mini").complete(prompt = "hi")
            val reasoningRequest = server.takeRequest()
            assertTrue(reasoningRequest.body.readUtf8().contains("\"reasoning_effort\":\"low\""))

            server.enqueue(MockResponse().setBody(successBody()))
            client(model = "claude-haiku-4-5").complete(prompt = "hi")
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
            assertFalse(error.message.orEmpty().contains(FAKE_OPENAI_KEY))
            assertFalse((error.cause?.message).orEmpty().contains(FAKE_OPENAI_KEY))
            assertTrue(error.message.orEmpty().contains("Settings"))
        }

    @Test
    fun `429 retries once then succeeds`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody(successBody()))

            val result = client().complete(prompt = "hi")

            assertEquals("ok", result.text)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `429 twice in a row maps to RATE_LIMIT after the single retry`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(429))

            val error =
                try {
                    client().complete(prompt = "hi")
                    fail("expected LlmError")
                    null
                } catch (e: LlmError) {
                    e
                }

            assertEquals(LlmError.Kind.RATE_LIMIT, error!!.kind)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `unparseable response maps to PARSE`() =
        runTest {
            server.enqueue(MockResponse().setBody("not json"))

            val error =
                try {
                    client().complete(prompt = "hi")
                    fail("expected LlmError")
                    null
                } catch (e: LlmError) {
                    e
                }

            assertEquals(LlmError.Kind.PARSE, error!!.kind)
        }

    private fun successBody() =
        """
        {
          "choices": [{"message": {"content": "ok"}}],
          "usage": {"prompt_tokens": 1, "completion_tokens": 1}
        }
        """.trimIndent()
}
