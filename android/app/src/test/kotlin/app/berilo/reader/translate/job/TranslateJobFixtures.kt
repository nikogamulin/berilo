package app.berilo.reader.translate.job

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.translate.epub.SyntheticEpub
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Model every job test bills against. Priced, so `costEur` never throws. */
const val JOB_TEST_MODEL: String = "gpt-5-mini"

/** EUR billed per scripted call, so a call-count assertion and a cost assertion agree. */
const val JOB_TEST_COST_PER_CALL: Double = 0.002

/** Marker that identifies a translate-batch prompt (see `TranslateEngine.buildBatchPrompt`). */
private const val BATCH_MARKER = "followed by its translation:\n\n"

private val NUMBERED = Regex("""^\[\[(\d+)]] (.*)$""", RegexOption.MULTILINE)

/**
 * An [LlmClient] that translates by prefixing, and can be told to die mid-run.
 *
 * **Nothing here reaches a provider.** CI runs with no key configured, and every assertion in
 * the job tests reads this object's own counters — never a log line, which could be emitted
 * without a call ever being billed.
 *
 * @param prefix Prepended to each source segment to stand in for a translation, so the shelved
 *   EPUB can be checked for *translated* text rather than merely for having been written.
 * @param dieAfterCalls Call number after which every further call raises a transient
 *   [LlmError] — the stand-in for Android killing the process mid-book. `null` never dies.
 */
class TranslatingLlmClient(
    private val prefix: String = "SL:",
    private val dieAfterCalls: Int? = null,
) : LlmClient {

    /** Every prompt seen, in order — one entry per billed call. */
    val prompts: MutableList<String> = mutableListOf()

    /** Calls entered, including the ones that raised. */
    val callCount: Int get() = prompts.size

    /** Total EUR this client has billed. */
    val billedEur: Double get() = callCount * JOB_TEST_COST_PER_CALL

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult {
        prompts.add(prompt)
        dieAfterCalls?.let { limit ->
            if (prompts.size > limit) {
                throw LlmError("simulated process death", LlmError.Kind.NETWORK)
            }
        }
        return result(replyTo(prompt))
    }

    private fun replyTo(prompt: String): String {
        // A prompt with no numbered block is the glossary extraction (or a style memo); an
        // empty term map keeps the run's cache key stable and the call count predictable.
        if (BATCH_MARKER !in prompt) return "{}"
        val block = prompt.substringAfterLast(BATCH_MARKER)
        val sources = NUMBERED.findAll(block).map { it.groupValues[2] }.toList()
        return sources
            .mapIndexed { index, source -> "[[${index + 1}]] $prefix${source.removePrefix(prefix)}" }
            .joinToString("\n")
    }

    private fun result(text: String) =
        LlmResult(
            text = text,
            inputTokens = 100,
            outputTokens = 50,
            costEur = JOB_TEST_COST_PER_CALL,
            model = JOB_TEST_MODEL,
        )
}

/**
 * A [TranslationRunner] that performs, inline, exactly what [TranslateWorker] performs.
 *
 * The composition is copied deliberately rather than approximated: request in, [BookTranslationJob.run]
 * with the loaded settings and a progress callback, [verdictFor] over the outcome. What this
 * double replaces is only WorkManager's *scheduler* — the enqueue call, the network constraint
 * and the retry backoff. Everything between the confirmation and the shelved book is the
 * production code path.
 *
 * @param job The real translation job.
 * @param settings Settings the run authenticates with.
 */
class InlineTranslationRunner(
    private val job: BookTranslationJob,
    private val settings: LlmSettings = LlmSettings(openaiKey = "fixture-key", model = JOB_TEST_MODEL),
) : TranslationRunner {

    private val state = MutableStateFlow<TranslationRunUpdate>(TranslationRunUpdate.Idle)

    /** Requests handed to [start], in order — the spend-gate tests assert this stays empty. */
    val started: MutableList<TranslationRequest> = mutableListOf()

    /** Verdicts [TranslateWorker] would have returned, in order. */
    internal val verdicts: MutableList<WorkerVerdict> = mutableListOf()

    /** Outcomes of every run, in order. */
    val outcomes: MutableList<TranslationJobOutcome> = mutableListOf()

    override val updates: Flow<TranslationRunUpdate> = state

    /**
     * Runs the job to completion. Suspends, so callers drive it with `advanceUntilIdle()`.
     *
     * Deliberately **not** launched on an internal scope: a test must be able to point at the
     * exact moment the run finished, and a fire-and-forget coroutine would make "did it spend"
     * a race.
     */
    override fun start(request: TranslationRequest) {
        started.add(request)
        state.value = TranslationRunUpdate.Waiting
        pending = request
    }

    override fun cancel() {
        pending = null
        state.value = TranslationRunUpdate.Failed(retryable = false, progress = null)
    }

    private var pending: TranslationRequest? = null

    /** Execute the started run, as the worker would. Call after `start`. */
    suspend fun drain() {
        val request = pending ?: return
        pending = null
        state.value = TranslationRunUpdate.Running(null)
        val outcome =
            job.run(request, settings) { stats ->
                state.value = TranslationRunUpdate.Running(stats.toProgress())
            }
        outcomes.add(outcome)
        val verdict = verdictFor(outcome)
        verdicts.add(verdict)
        state.value =
            when (verdict) {
                WorkerVerdict.SUCCESS ->
                    TranslationRunUpdate.Completed(
                        (state.value as? TranslationRunUpdate.Running)?.progress,
                    )
                WorkerVerdict.RETRY ->
                    TranslationRunUpdate.Failed(
                        retryable = true,
                        progress = (state.value as? TranslationRunUpdate.Running)?.progress,
                    )
                WorkerVerdict.FAILURE ->
                    TranslationRunUpdate.Failed(
                        retryable = false,
                        progress = (state.value as? TranslationRunUpdate.Running)?.progress,
                    )
            }
    }
}

/** A [TranslationRunner] that records starts and never runs anything. */
class RecordingTranslationRunner : TranslationRunner {
    val started: MutableList<TranslationRequest> = mutableListOf()
    var cancelled: Int = 0

    override val updates: Flow<TranslationRunUpdate> = MutableStateFlow(TranslationRunUpdate.Idle)

    override fun start(request: TranslationRequest) {
        started.add(request)
    }

    override fun cancel() {
        cancelled++
    }
}

/**
 * Write a small source-language EPUB.
 *
 * Synthetic, never copied from `data/` — the example books are copyrighted and gitignored, and
 * nothing from them may enter the repository (CLAUDE.md §4).
 *
 * @param file Destination.
 * @param title `dc:title`.
 * @param language `dc:language`, deliberately settable so the raw-OPF-tag cases (`en-US`,
 *   `eng`, `EN-GB`) can be exercised.
 * @param paragraphsPerChapter Body paragraphs in each of the two chapters.
 */
fun writeSourceEpub(
    file: File,
    title: String = "A Quiet Library",
    language: String = "en-US",
    paragraphsPerChapter: Int = 3,
): File {
    fun body(chapter: String, lead: String) =
        "<h1>$chapter</h1>" +
            (1..paragraphsPerChapter).joinToString("") { "<p>$lead paragraph $it.</p>" }
    return SyntheticEpub()
        .document("chap1.xhtml", body("Chapter One", "Opening"), title = "Chapter One")
        .document("chap2.xhtml", body("Chapter Two", "Closing"), title = "Chapter Two")
        .ncx("chap1.xhtml" to "Chapter One", "chap2.xhtml" to "Chapter Two")
        .writeTo(file, title = title, language = language)
}
