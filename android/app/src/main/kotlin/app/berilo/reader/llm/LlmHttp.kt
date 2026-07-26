package app.berilo.reader.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Shared HTTP plumbing for [OpenAiClient]/[AnthropicClient]: timeouts, an exponential
 * backoff retry ladder on 429/5xx (honouring `Retry-After` when a provider sends one),
 * and error-response mapping that never echoes response-body text into an [LlmError]
 * message (the only way to guarantee no provider payload can carry key material back
 * out through a thrown exception).
 */

internal val LLM_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private const val REQUEST_TIMEOUT_SECONDS = 30L

/** Maximum number of retry attempts after the initial call, on a retryable status
 * (429 or 5xx). Mirrors `translator/berilo/providers/__init__.py`'s `MAX_RETRIES`. */
internal const val MAX_RETRY_ATTEMPTS = 5

/** Base delay (ms) for exponential backoff; actual delay per attempt (absent a
 * `Retry-After` header) is `retryBackoffMs * 2^(attempt-1)` plus jitter in
 * `[0, retryBackoffMs)`. Mirrors `providers/__init__.py`'s `BASE_DELAY_S`. */
internal const val RETRY_BACKOFF_MS = 1_000L

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

/** Bytes peeked from an error response body to classify it (e.g. a content-policy
 * refusal) without consuming the stream — the caller (`requireSuccessful` or the
 * per-provider JSON parse) still reads the full body afterwards. */
private const val MAX_ERROR_BODY_PEEK_BYTES = 8_192L

internal val llmJson: Json = Json { ignoreUnknownKeys = true }

internal val defaultLlmHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}

/**
 * Executes [request], retrying with exponential backoff + jitter while the response is
 * retryable (429 or 5xx), up to [maxRetries] attempts after the first call. Honours a
 * `Retry-After` header (delta-seconds form) when the provider sends one, in place of the
 * computed backoff for that attempt. Returns the first response that is either
 * successful or not retryable, or the last response once retries are exhausted (the
 * caller maps the final response's status via [requireSuccessful]).
 *
 * @param retryBackoffMs Base delay in ms for the backoff formula. Injectable so tests
 *   can run it under `UnconfinedTestDispatcher` on virtual time.
 * @param random Source of jitter. Injectable for deterministic tests.
 * @throws LlmError With [LlmError.Kind.NETWORK] if the underlying HTTP call throws
 *   (connection failure, timeout) on any attempt.
 */
internal suspend fun executeWithRetry(
    httpClient: OkHttpClient,
    request: Request,
    retryBackoffMs: Long = RETRY_BACKOFF_MS,
    maxRetries: Int = MAX_RETRY_ATTEMPTS,
    random: Random = Random.Default,
): Response {
    var response = executeOnce(httpClient, request)
    var attempt = 0
    while (isRetryable(response) && attempt < maxRetries) {
        attempt++
        val retryAfterMs = retryAfterDelayMs(response)
        response.close()
        delay(retryAfterMs ?: exponentialBackoffMs(retryBackoffMs, attempt, random))
        response = executeOnce(httpClient, request)
    }
    return response
}

private fun exponentialBackoffMs(baseDelayMs: Long, attempt: Int, random: Random): Long {
    val backoff = baseDelayMs * (1L shl (attempt - 1))
    val jitter = if (baseDelayMs > 0) random.nextLong(baseDelayMs) else 0L
    return backoff + jitter
}

/** Parses a `Retry-After` header's delta-seconds form (the common case for provider
 * rate-limit responses). Returns `null` if absent or not a plain integer (e.g. the
 * rarely-used HTTP-date form), in which case the caller falls back to the computed
 * exponential backoff. */
private fun retryAfterDelayMs(response: Response): Long? {
    val seconds = response.header("Retry-After")?.trim()?.toLongOrNull() ?: return null
    return TimeUnit.SECONDS.toMillis(seconds)
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
 * If [response] is HTTP 400 and its body matches [isContentPolicyRefusal], closes it and
 * throws a fixed-message [LlmError] with [LlmError.Kind.CONTENT_POLICY] (review finding
 * 18) — checked before [requireSuccessful] so a content-policy refusal is distinguished
 * from a generic bad request. Peeks the body (does not consume the stream) so a
 * non-matching 400 still reaches [requireSuccessful]'s generic mapping. The classified
 * body text itself is never placed in the thrown message — only used to decide which
 * fixed message to raise — so a provider payload can never leak through this path.
 */
internal fun checkContentPolicyRefusal(
    response: Response,
    providerLabel: String,
    isContentPolicyRefusal: (String) -> Boolean,
) {
    if (response.code != HTTP_BAD_REQUEST) return
    val bodyText = response.peekBody(MAX_ERROR_BODY_PEEK_BYTES).string()
    if (isContentPolicyRefusal(bodyText)) {
        response.close()
        throw LlmError(
            "$providerLabel flagged the source text for this request; route this batch to a fallback provider.",
            LlmError.Kind.CONTENT_POLICY,
        )
    }
}

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
