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

            val response = executeWithRetry(httpClient, request)
            checkContentPolicyRefusal(response, OPENAI_PROVIDER_LABEL, ::isOpenAiContentPolicyRefusal)
            parseOpenAiResponse(requireSuccessful(response, OPENAI_PROVIDER_LABEL))
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
            val choice =
                parsed.choices.firstOrNull()
                    ?: throw LlmError("$OPENAI_PROVIDER_LABEL response had no completion.", LlmError.Kind.PARSE)
            val text = choice.message.content ?: ""
            val usage = parsed.usage ?: throw LlmError("$OPENAI_PROVIDER_LABEL response had no usage data.", LlmError.Kind.PARSE)
            // Computed before the truncation/emptiness checks below so a caller that
            // degrades instead of propagating (see LlmError.Kind.EMPTY_COMPLETION /
            // TRUNCATED_COMPLETION) can still fold this call's real, billed cost into
            // its accounting via the exception's `result` property.
            val billedResult =
                LlmResult(
                    text = text,
                    inputTokens = usage.promptTokens,
                    outputTokens = usage.completionTokens,
                    costEur = costEur(model, usage.promptTokens, usage.completionTokens),
                    model = model,
                )
            if (choice.finishReason == "length") {
                throw LlmError(
                    "$OPENAI_PROVIDER_LABEL truncated the completion for model $model " +
                        "(finish_reason='length', ${usage.completionTokens} output tokens billed); the " +
                        "response is incomplete. Increase max_tokens or reduce the batch size.",
                    LlmError.Kind.TRUNCATED_COMPLETION,
                    result = billedResult,
                )
            }
            if (text.isEmpty()) {
                throw LlmError(
                    "$OPENAI_PROVIDER_LABEL returned no completion text for model $model " +
                        "(${usage.completionTokens} output tokens billed).",
                    LlmError.Kind.EMPTY_COMPLETION,
                    result = billedResult,
                )
            }
            billedResult
        }
}

/** Detects an OpenAI content-policy refusal from an HTTP 400 error body: either the
 * `invalid_prompt` error code or "usage policy" wording (mirrors
 * `translator/berilo/providers/openai.py`'s `BadRequestError` check). Used only to pick
 * which fixed message to raise — the body text itself never reaches the thrown error. */
private fun isOpenAiContentPolicyRefusal(body: String): Boolean =
    body.contains("invalid_prompt") || body.contains("usage policy")

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
private data class OpenAiChoice(
    val message: OpenAiResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class OpenAiResponseMessage(val content: String? = null)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)
