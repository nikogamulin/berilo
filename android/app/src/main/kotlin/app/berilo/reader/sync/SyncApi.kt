package app.berilo.reader.sync

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * How a sync call ended. Modelled explicitly rather than as an exception because the caller
 * reacts differently to each: [Offline] is normal on an e-reader and must not be surfaced as an
 * error, [Unauthorized] means stop and ask the user to sign in again, [RateLimited] carries the
 * server's own backoff, and [Failed] is worth reporting.
 */
sealed interface SyncCallResult<out T> {
    data class Success<T>(val value: T) : SyncCallResult<T>

    /** No usable network. Expected, non-fatal: the queue simply stays put. */
    data object Offline : SyncCallResult<Nothing>

    /** No Clerk session, or the server rejected the token. */
    data object Unauthorized : SyncCallResult<Nothing>

    /** HTTP 429; [retryAfterSeconds] is the server's `Retry-After` when it sent one. */
    data class RateLimited(val retryAfterSeconds: Long?) : SyncCallResult<Nothing>

    data class Failed(val code: String, val message: String) : SyncCallResult<Nothing>
}

/**
 * Thin HTTP client for `/sync/pull` and `/sync/push` (`docs/sync_api.md` §3).
 *
 * Holds no sync policy — no cursors, no batching decisions, no conflict handling. It turns a
 * request object into an HTTP call and an HTTP response into a [SyncCallResult], which is what
 * makes it straightforward to point at a `MockWebServer` in tests.
 *
 * @param baseUrl The API root, e.g. `https://berilo.app/api/v1`, trailing slash optional.
 * @param tokenProvider Supplies a fresh Clerk session JWT per request. Called per call rather
 *   than cached here: Clerk's tokens are short-lived and its SDK already caches and refreshes
 *   them, so holding one would only add a way to send an expired one.
 */
class SyncApi(
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun pull(request: PullRequest): SyncCallResult<PullResponse> =
        post("sync/pull", json.encodeToString(PullRequest.serializer(), request)) { body ->
            json.decodeFromString(PullResponse.serializer(), body)
        }

    suspend fun push(entities: Map<String, List<JsonObject>>): SyncCallResult<PushResponse> =
        post("sync/push", json.encodeToString(PushRequest.serializer(), PushRequest(entities))) {
            body ->
            json.decodeFromString(PushResponse.serializer(), body)
        }

    private suspend fun <T> post(
        path: String,
        payload: String,
        parse: (String) -> T,
    ): SyncCallResult<T> =
        withContext(ioDispatcher) {
            val token =
                tokenProvider() ?: return@withContext SyncCallResult.Unauthorized
            val request =
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/$path")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful ->
                            try {
                                SyncCallResult.Success(parse(body))
                            } catch (error: Exception) {
                                SyncCallResult.Failed(
                                    "malformed_response",
                                    error.message ?: "Unreadable response from the sync service.",
                                )
                            }
                        response.code == HTTP_UNAUTHORIZED -> SyncCallResult.Unauthorized
                        response.code == HTTP_RATE_LIMITED ->
                            SyncCallResult.RateLimited(
                                response.header("Retry-After")?.toLongOrNull(),
                            )
                        else -> failure(response.code, body)
                    }
                }
            } catch (_: IOException) {
                // No network, DNS failure, timeout: normal on an e-reader that spends most of
                // its life offline. The caller leaves its watermarks untouched and retries.
                SyncCallResult.Offline
            }
        }

    /** Decodes the contract's error envelope, falling back to the raw status when it is absent. */
    private fun failure(code: Int, body: String): SyncCallResult<Nothing> =
        try {
            val envelope = json.decodeFromString(ErrorEnvelope.serializer(), body)
            SyncCallResult.Failed(envelope.error.code, envelope.error.message)
        } catch (_: Exception) {
            SyncCallResult.Failed("http_$code", "The sync service returned HTTP $code.")
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_RATE_LIMITED = 429
    }
}
