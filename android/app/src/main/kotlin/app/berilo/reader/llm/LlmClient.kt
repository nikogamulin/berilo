package app.berilo.reader.llm

/**
 * Provider-agnostic LLM client. Implementations (see [OpenAiClient],
 * [AnthropicClient]) wrap a single vendor's HTTP API behind this interface so
 * the dictionary/interpretation features (S2.4, S2.5) never depend on a
 * specific vendor. The user's own API key is supplied at construction time;
 * it is never logged, never embedded in [LlmResult]/[LlmError], and never
 * appears in a `toString()`.
 */
interface LlmClient {
    /**
     * Requests a single completion from the underlying model.
     *
     * @param prompt The user-turn prompt text.
     * @param system Optional system-prompt text.
     * @param maxTokens Optional cap on completion tokens; `null` omits the
     *   parameter from the request and leaves the provider default.
     * @return The [LlmResult] for this call, with token counts and EUR cost
     *   from the response's reported usage.
     * @throws LlmError If the call fails for any reason (auth, rate limit,
     *   network, provider error, or an unparseable response).
     */
    suspend fun complete(prompt: String, system: String? = null, maxTokens: Int? = null): LlmResult
}

/**
 * Result of a single completion call to an LLM provider.
 *
 * @property text The completion text returned by the model.
 * @property inputTokens Number of input (prompt) tokens billed.
 * @property outputTokens Number of output (completion) tokens billed.
 * @property costEur Estimated cost of this call in EUR.
 * @property model The model identifier that served the request.
 */
data class LlmResult(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costEur: Double,
    val model: String,
)

/**
 * Raised when an [LlmClient] call fails. [message] is always a fixed,
 * actionable string composed by this module — never interpolated from a raw
 * provider response body — so it can never leak API key material even if a
 * provider were to echo request content back in an error payload.
 *
 * @property kind Coarse failure classification the caller (Settings "Test
 *   key", dictionary/interpretation error states) branches on.
 * @property result The billed, unusable-text (or partial-text) completion,
 *   for [Kind.EMPTY_COMPLETION]/[Kind.TRUNCATED_COMPLETION] — `null`
 *   otherwise. A provider call that already reached the API is billed
 *   whether or not its text is usable, so a caller that can degrade to a
 *   smaller unit of work (stricter retry, per-segment fallback) should catch
 *   these kinds and fold this cost into its accounting rather than lose it
 *   silently; only a caller with no smaller unit left should let the error
 *   propagate. Mirrors `translator/berilo/providers/base.py`'s
 *   `EmptyCompletionError`/`TruncatedCompletionError.result`.
 */
class LlmError(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
    val result: LlmResult? = null,
) : Exception(message, cause) {
    enum class Kind {
        AUTH,
        RATE_LIMIT,
        NETWORK,
        PROVIDER,
        PARSE,

        /** A request was refused on content-policy grounds (not transient; never retry
         * against the same provider — route the batch to a fallback). */
        CONTENT_POLICY,

        /** A provider billed a call but returned no completion text (e.g. a reasoning
         * model exhausting its token budget on hidden reasoning). */
        EMPTY_COMPLETION,

        /** A provider cut a completion off before it finished, still billed
         * (OpenAI `finish_reason="length"`, Anthropic `stop_reason="max_tokens"`). */
        TRUNCATED_COMPLETION,
    }
}
