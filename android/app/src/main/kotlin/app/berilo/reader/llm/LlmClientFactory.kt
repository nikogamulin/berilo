package app.berilo.reader.llm

import app.berilo.reader.settings.LlmSettings

private const val OPENAI_MODEL_PREFIX = "gpt-"
private const val ANTHROPIC_MODEL_PREFIX = "claude-"

/**
 * Builds an [LlmClient] for `settings.model`, routing `gpt-*` to [OpenAiClient] and
 * `claude-*` to [AnthropicClient].
 *
 * @throws LlmError With [LlmError.Kind.AUTH] if the routed provider has no API key
 *   configured, or [LlmError.Kind.PROVIDER] if the model prefix matches neither vendor.
 */
fun createLlmClient(settings: LlmSettings): LlmClient {
    val model = settings.model
    return when {
        model.startsWith(OPENAI_MODEL_PREFIX) ->
            OpenAiClient(
                apiKey = settings.openaiKey
                    ?: throw LlmError("No OpenAI API key configured — add one in Settings.", LlmError.Kind.AUTH),
                model = model,
            )
        model.startsWith(ANTHROPIC_MODEL_PREFIX) ->
            AnthropicClient(
                apiKey = settings.anthropicKey
                    ?: throw LlmError("No Anthropic API key configured — add one in Settings.", LlmError.Kind.AUTH),
                model = model,
            )
        else -> throw LlmError("Unsupported model '$model' — pick one in Settings.", LlmError.Kind.PROVIDER)
    }
}
