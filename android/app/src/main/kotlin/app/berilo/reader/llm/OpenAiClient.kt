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

private const val OPENAI_BASE_URL = "https://api.openai.com/v1/chat/completions"
private const val OPENAI_PROVIDER_LABEL = "OpenAI"

/** Model families that accept the `reasoning_effort` parameter (mirrors
 * `translator/berilo/providers/openai.py`'s `_REASONING_PREFIXES`). */
private val OPENAI_REASONING_MODEL_PREFIXES = listOf("gpt-5", "o1", "o3", "o4")

/** Reasoning effort sent for reasoning-model calls — cheapest useful setting for
 * short dictionary/interpretation/translation completions. */
private const val OPENAI_REASONING_EFFORT = "low"

/**
 * [LlmClient] backed by the OpenAI chat completions API.
 *
 * @param apiKey The user's own OpenAI API key. Held only as a constructor
 *   parameter used to build the `Authorization` header; never stored in a
 *   logged/`toString()`-visible field.
 * @param model Model identifier to use for completions.
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String,
    private val httpClient: OkHttpClient = defaultLlmHttpClient,
    private val json: Json = llmJson,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = OPENAI_BASE_URL,
) : LlmClient {

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult =
        withContext(ioDispatcher) {
            val messages =
                buildList {
                    system?.let { add(OpenAiMessage(role = "system", content = it)) }
                    add(OpenAiMessage(role = "user", content = prompt))
                }
            val requestBody =
                OpenAiChatRequest(
                    model = model,
                    messages = messages,
                    maxCompletionTokens = maxTokens,
                    reasoningEffort = if (isReasoningModel(model)) OPENAI_REASONING_EFFORT else null,
                )
            val bodyJson = json.encodeToString(OpenAiChatRequest.serializer(), requestBody)
            val request =
                Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody(LLM_JSON_MEDIA_TYPE))
                    .build()

            val response = requireSuccessful(executeWithRetry(httpClient, request), OPENAI_PROVIDER_LABEL)
            parseOpenAiResponse(response)
        }

    private fun parseOpenAiResponse(response: okhttp3.Response): LlmResult =
        response.use {
            val bodyText =
                it.body?.string()
                    ?: throw LlmError("$OPENAI_PROVIDER_LABEL response had no body.", LlmError.Kind.PARSE)
            val parsed =
                try {
                    json.decodeFromString(OpenAiChatResponse.serializer(), bodyText)
                } catch (e: SerializationException) {
                    throw LlmError("Could not parse $OPENAI_PROVIDER_LABEL response.", LlmError.Kind.PARSE, e)
                }
            val text =
                parsed.choices.firstOrNull()?.message?.content
                    ?: throw LlmError("$OPENAI_PROVIDER_LABEL response had no completion.", LlmError.Kind.PARSE)
            val usage = parsed.usage ?: throw LlmError("$OPENAI_PROVIDER_LABEL response had no usage data.", LlmError.Kind.PARSE)
            LlmResult(
                text = text,
                inputTokens = usage.promptTokens,
                outputTokens = usage.completionTokens,
                costEur = costEur(model, usage.promptTokens, usage.completionTokens),
                model = model,
            )
        }
}

private fun isReasoningModel(model: String): Boolean = OPENAI_REASONING_MODEL_PREFIXES.any { model.startsWith(it) }

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
private data class OpenAiChatResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@Serializable
private data class OpenAiChoice(val message: OpenAiResponseMessage)

@Serializable
private data class OpenAiResponseMessage(val content: String? = null)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)
