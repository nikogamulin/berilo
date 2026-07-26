package app.berilo.reader.llm

import app.berilo.reader.settings.LlmSettings

private const val OPENAI_MODEL_PREFIX = "gpt-"
private const val ANTHROPIC_MODEL_PREFIX = "claude-"

/**
 * Builds an [LlmClient] for `settings.model`, routing `gpt-*` to [OpenAiClient] and
 * `claude-*` to [AnthropicClient].
 *
 * The pricing table is pre-flighted (review finding 6) *before* either client is
 * constructed, so a model with no pricing entry fails fast, for free, and before any
 * HTTP request can ever be made — never after the call has already been billed.
 *
 * @throws LlmError With [LlmError.Kind.PROVIDER] if `settings.model` has no pricing
 *   entry or its prefix matches neither vendor, or [LlmError.Kind.AUTH] if the routed
 *   provider has no API key configured.
 */
fun createLlmClient(settings: LlmSettings): LlmClient {
    val model = settings.model
    return when {
        model.startsWith(OPENAI_MODEL_PREFIX) -> {
            requireKnownModel(model)
            OpenAiClient(
                apiKey = settings.openaiKey
                    ?: throw LlmError("No OpenAI API key configured — add one in Settings.", LlmError.Kind.AUTH),
                model = model,
            )
        }
        model.startsWith(ANTHROPIC_MODEL_PREFIX) -> {
            requireKnownModel(model)
            AnthropicClient(
                apiKey = settings.anthropicKey
                    ?: throw LlmError("No Anthropic API key configured — add one in Settings.", LlmError.Kind.AUTH),
                model = model,
            )
        }
        else -> throw LlmError("Unsupported model '$model' — pick one in Settings.", LlmError.Kind.PROVIDER)
    }
}
