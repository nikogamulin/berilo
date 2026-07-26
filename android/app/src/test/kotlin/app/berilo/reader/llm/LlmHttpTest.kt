package app.berilo.reader.llm

import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Direct tests of [executeWithRetry]'s backoff ladder and [checkContentPolicyRefusal]'s
 * body-peeking, isolated from either provider client's JSON parsing. */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(): Request = Request.Builder().url(server.url("/")).get().build()

    // Bound to this TestScope's own scheduler: an unbound UnconfinedTestDispatcher()
    // would make delay() throw instead of virtual-time-skipping it.
    private fun TestScope.dispatcher() = UnconfinedTestDispatcher(testScheduler)

    @Test
    fun `exponential backoff grows per attempt and stays within base plus jitter bounds`() =
        runTest {
            repeat(1 + MAX_RETRY_ATTEMPTS) { server.enqueue(MockResponse().setResponseCode(429)) }
            val zeroJitter = Random(0) // nextLong(bound) with a fixed seed is still >= 0

            val before = testScheduler.currentTime
            val response =
                kotlinx.coroutines.withContext(dispatcher()) {
                    executeWithRetry(httpClient, request(), retryBackoffMs = 100L, random = zeroJitter)
                }
            val elapsedMs = testScheduler.currentTime - before

            assertEquals(429, response.code)
            response.close()
            assertEquals(1 + MAX_RETRY_ATTEMPTS, server.requestCount)
            // Lower bound: 5 retries of base*2^(attempt-1) with zero jitter = 100+200+400+800+1600 = 3100ms.
            // Upper bound: same schedule with jitter maxed at (almost) one base unit each attempt.
            assertTrue("expected >= 3100ms, was $elapsedMs", elapsedMs >= 3_100L)
            assertTrue("expected < 3600ms, was $elapsedMs", elapsedMs < 3_600L)
        }

    @Test
    fun `retryable response stops retrying once a non-retryable response arrives`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val response =
                kotlinx.coroutines.withContext(dispatcher()) {
                    executeWithRetry(httpClient, request(), retryBackoffMs = 10L)
                }

            assertEquals(200, response.code)
            response.close()
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `Retry-After header is honoured over the computed backoff`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "3"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val before = testScheduler.currentTime
            val response =
                kotlinx.coroutines.withContext(dispatcher()) {
                    // A large retryBackoffMs would make the computed backoff dominate if
                    // Retry-After were ignored — proves the header wins.
                    executeWithRetry(httpClient, request(), retryBackoffMs = 60_000L)
                }
            val elapsedMs = testScheduler.currentTime - before

            assertEquals(200, response.code)
            response.close()
            assertEquals(3_000L, elapsedMs)
        }

    @Test
    fun `checkContentPolicyRefusal peeks the body so the caller can still read it`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error": "usage policy"}"""))

            val response =
                kotlinx.coroutines.withContext(dispatcher()) {
                    executeWithRetry(httpClient, request())
                }

            val error =
                try {
                    checkContentPolicyRefusal(response, "TestProvider") { it.contains("usage policy") }
                    null
                } catch (e: LlmError) {
                    e
                }

            assertEquals(LlmError.Kind.CONTENT_POLICY, error!!.kind)
        }

    @Test
    fun `checkContentPolicyRefusal leaves a non-matching response open and unread`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error": "bad schema"}"""))

            val response =
                kotlinx.coroutines.withContext(dispatcher()) {
                    executeWithRetry(httpClient, request())
                }

            checkContentPolicyRefusal(response, "TestProvider") { it.contains("usage policy") }

            // Still open and the body can still be read exactly once by the caller.
            assertFalse(response.body == null)
            assertEquals("""{"error": "bad schema"}""", response.body!!.string())
            response.close()
        }
}
