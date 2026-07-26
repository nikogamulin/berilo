package app.berilo.reader.translate.job

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.translate.engine.TranslationStats

/**
 * Counts and prices **every** call a run makes, at the one place they all pass through.
 *
 * Why this exists rather than trusting [TranslationStats]: the engine's counters cover the calls
 * the engine itself makes, and a book translation makes calls the engine never sees.
 * `buildGlossary` bills one extraction call *before* `translateBook` is entered, so a run
 * reporting only the engine's numbers understates its own cost by a whole call — and CLAUDE.md
 * §4 makes the reported figure a promise, not a diagnostic. Metering at the [LlmClient] boundary
 * makes the reported cost exactly "what this client was billed", which stays true for any pass
 * added later without anyone remembering to add it here.
 *
 * A call that **raises** is still counted when the provider already billed it: `LlmError`
 * carries the billed [LlmResult] for the empty/truncated-completion kinds precisely so the cost
 * is not lost. That is review finding 5, which the CLI gets wrong today.
 *
 * Not thread-safe, and does not need to be: `translateBook` drives one call at a time.
 *
 * @param delegate The real client.
 */
internal class MeteredLlmClient(private val delegate: LlmClient) : LlmClient {

    /** Calls entered, including ones that raised after being billed. */
    var calls: Int = 0
        private set

    var inputTokens: Int = 0
        private set

    var outputTokens: Int = 0
        private set

    var costEur: Double = 0.0
        private set

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult =
        try {
            delegate.complete(prompt, system, maxTokens).also(::record)
        } catch (e: LlmError) {
            e.result?.let(::record)
            throw e
        }

    private fun record(result: LlmResult) {
        calls++
        inputTokens += result.inputTokens
        outputTokens += result.outputTokens
        costEur += result.costEur
    }

    /**
     * Replace [stats]'s billing columns with what was actually billed.
     *
     * The segment counters are the engine's — it is the only thing that knows them — while the
     * money is this meter's.
     */
    fun applyTo(stats: TranslationStats): TranslationStats =
        stats.copy(
            apiCalls = calls,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costEur = costEur,
        )
}
