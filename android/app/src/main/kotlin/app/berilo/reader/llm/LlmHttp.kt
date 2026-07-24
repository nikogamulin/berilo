package app.berilo.reader.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Shared HTTP plumbing for [OpenAiClient]/[AnthropicClient]: timeouts, a single retry on
 * 429/5xx, and error-response mapping that never echoes response-body text into an
 * [LlmError] message (the only way to guarantee no provider payload can carry key
 * material back out through a thrown exception).
 */

internal val LLM_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private const val REQUEST_TIMEOUT_SECONDS = 30L
private const val RETRY_BACKOFF_MS = 1_000L
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

internal val llmJson: Json = Json { ignoreUnknownKeys = true }

internal val defaultLlmHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}

/**
 * Executes [request], retrying once after a fixed backoff if the first attempt returns a
 * 429 or 5xx. Returns the first response that is either successful or not retryable (the
 * caller maps the final response's status via [requireSuccessful]).
 *
 * @throws LlmError With [LlmError.Kind.NETWORK] if the underlying HTTP call throws
 *   (connection failure, timeout) on either attempt.
 */
internal suspend fun executeWithRetry(
    httpClient: OkHttpClient,
    request: Request,
    retryBackoffMs: Long = RETRY_BACKOFF_MS,
): Response {
    val first = executeOnce(httpClient, request)
    if (!isRetryable(first)) return first
    first.close()
    delay(retryBackoffMs)
    return executeOnce(httpClient, request)
}

private fun executeOnce(httpClient: OkHttpClient, request: Request): Response =
    try {
        httpClient.newCall(request).execute()
    } catch (e: IOException) {
        throw LlmError("Network error contacting the LLM provider — check your connection.", LlmError.Kind.NETWORK, e)
    }

private fun isRetryable(response: Response): Boolean =
    response.code == HTTP_TOO_MANY_REQUESTS || response.code >= HTTP_SERVER_ERROR_FLOOR

/**
 * Maps a non-2xx [response] to a fixed, actionable [LlmError] and closes the response body
 * without reading it. Returns [response] unchanged (still open) when successful.
 */
internal fun requireSuccessful(response: Response, providerLabel: String): Response {
    if (response.isSuccessful) return response
    val code = response.code
    response.close()
    throw when (code) {
        HTTP_UNAUTHORIZED -> LlmError("Invalid API key — check Settings", LlmError.Kind.AUTH)
        HTTP_TOO_MANY_REQUESTS -> LlmError("$providerLabel rate limit exceeded — try again shortly.", LlmError.Kind.RATE_LIMIT)
        in HTTP_SERVER_ERROR_FLOOR..599 -> LlmError("$providerLabel is temporarily unavailable — try again shortly.", LlmError.Kind.PROVIDER)
        else -> LlmError("$providerLabel request failed (HTTP $code).", LlmError.Kind.PROVIDER)
    }
}
