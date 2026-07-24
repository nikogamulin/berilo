package app.berilo.reader.llm

/**
 * Per-model USD pricing table and USD to EUR cost conversion.
 *
 * Mirrors `translator/berilo/providers/pricing.py` — keep both in sync when
 * either changes. Pricing as of 2026-07, verify before relying on absolute
 * costs.
 */

/** USD to EUR conversion rate. Pricing as of 2026-07, verify before relying on absolute costs. */
private const val EUR_PER_USD = 0.92

/** Tokens per pricing unit (vendor list prices are per-million-token). */
private const val TOKENS_PER_PRICING_UNIT = 1_000_000.0

/** model -> (USD per million input tokens, USD per million output tokens). Pricing as of 2026-07. */
private val PRICING_USD_PER_MILLION_TOKENS: Map<String, Pair<Double, Double>> =
    mapOf(
        "gpt-5-mini" to (0.25 to 2.00),
        "gpt-5" to (1.25 to 10.00),
        "gpt-5-nano" to (0.05 to 0.40),
        "claude-sonnet-4-5" to (3.00 to 15.00),
        "claude-haiku-4-5" to (1.00 to 5.00),
        "claude-opus-4-1" to (15.00 to 75.00),
    )

/**
 * Computes the EUR cost of a completion call.
 *
 * @param model Model identifier as billed (must be a key in the pricing table above).
 * @param inputTokens Number of input (prompt) tokens billed.
 * @param outputTokens Number of output (completion) tokens billed.
 * @return Cost of the call in EUR.
 * @throws LlmError With [LlmError.Kind.PARSE] if `model` has no known pricing entry — an
 *   unrecognized model in a real response means something is wrong with the
 *   response parse, not a genuine new model to price silently.
 */
internal fun costEur(model: String, inputTokens: Int, outputTokens: Int): Double {
    val pricing =
        PRICING_USD_PER_MILLION_TOKENS[model]
            ?: throw LlmError("Unknown model in provider response: '$model'.", LlmError.Kind.PARSE)
    val (inputUsdPerMillion, outputUsdPerMillion) = pricing
    val usdCost =
        (inputTokens / TOKENS_PER_PRICING_UNIT) * inputUsdPerMillion +
            (outputTokens / TOKENS_PER_PRICING_UNIT) * outputUsdPerMillion
    return usdCost * EUR_PER_USD
}
