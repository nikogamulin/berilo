package app.berilo.reader.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_PROVIDER_LABEL = "Anthropic"
private const val ANTHROPIC_API_VERSION = "2023-06-01"

/** The Anthropic messages API requires `max_tokens` on every request; this default is
 * generous enough for a dictionary/interpretation/translation-smoke response (mirrors
 * `translator/berilo/providers/anthropic.py`'s `DEFAULT_MAX_TOKENS`). */
private const val ANTHROPIC_DEFAULT_MAX_TOKENS = 1024

/**
 * [LlmClient] backed by the Anthropic messages API.
 *
 * @param apiKey The user's own Anthropic API key. Held only as a constructor
 *   parameter used to build the `x-api-key` header; never stored in a
 *   logged/`toString()`-visible field.
 * @param model Model identifier to use for completions.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val httpClient: OkHttpClient = defaultLlmHttpClient,
    private val json: Json = llmJson,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = ANTHROPIC_BASE_URL,
) : LlmClient {

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult =
        withContext(ioDispatcher) {
            val requestBody =
                AnthropicRequest(
                    model = model,
                    messages = listOf(AnthropicMessage(role = "user", content = prompt)),
                    maxTokens = maxTokens ?: ANTHROPIC_DEFAULT_MAX_TOKENS,
                    system = system,
                )
            val bodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
            val request =
                Request.Builder()
                    .url(baseUrl)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_API_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody(LLM_JSON_MEDIA_TYPE))
                    .build()

            val response = requireSuccessful(executeWithRetry(httpClient, request), ANTHROPIC_PROVIDER_LABEL)
            parseAnthropicResponse(response)
        }

    private fun parseAnthropicResponse(response: okhttp3.Response): LlmResult =
        response.use {
            val bodyText =
                it.body?.string()
                    ?: throw LlmError("$ANTHROPIC_PROVIDER_LABEL response had no body.", LlmError.Kind.PARSE)
            val parsed =
                try {
                    json.decodeFromString(AnthropicResponse.serializer(), bodyText)
                } catch (e: SerializationException) {
                    throw LlmError("Could not parse $ANTHROPIC_PROVIDER_LABEL response.", LlmError.Kind.PARSE, e)
                }
            val text = parsed.content.filter { block -> block.type == "text" }.joinToString(separator = "") { block -> block.text }
            if (text.isEmpty()) {
                throw LlmError("$ANTHROPIC_PROVIDER_LABEL response had no completion.", LlmError.Kind.PARSE)
            }
            val usage =
                parsed.usage ?: throw LlmError("$ANTHROPIC_PROVIDER_LABEL response had no usage data.", LlmError.Kind.PARSE)
            LlmResult(
                text = text,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costEur = costEur(model, usage.inputTokens, usage.outputTokens),
                model = model,
            )
        }
}

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicContentBlock(val type: String = "", val text: String = "")

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)
