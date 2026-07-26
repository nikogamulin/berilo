package app.berilo.reader.translate.engine

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.SegmentType
import app.berilo.reader.translate.model.makeSegmentId

/**
 * Shared test doubles and builders for the translate engine (B5).
 *
 * Public helper file rather than the per-file `private class` convention the older LLM tests
 * use: the engine's tests need *one* scripted client whose call log every assertion reads, and
 * six copies of it would drift. Follows the `Fake*Dao` pattern already established in
 * `store/importer/FakeBookDao.kt`.
 *
 * **No test here may reach a provider.** CI runs with no key configured; every client below is
 * a pure in-memory script.
 */

/** One scripted reply: either text to return, or a failure to raise. */
sealed interface ScriptedReply {

    /** Return [text], billed at [inputTokens]/[outputTokens]/[costEur]. */
    data class Text(
        val text: String,
        val inputTokens: Int = 10,
        val outputTokens: Int = 20,
        val costEur: Double = 0.001,
    ) : ScriptedReply

    /**
     * Raise [LlmError] of [kind], optionally carrying an already-billed [result].
     *
     * The `result` is the whole point of B6's design: a truncated call was billed even though
     * its text is unusable, and the ladder must fold that cost in rather than lose it.
     */
    data class Failure(
        val kind: LlmError.Kind,
        val result: LlmResult? = null,
        val message: String = "scripted ${kind.name}",
    ) : ScriptedReply
}

/** Build a billed [LlmResult] for a scripted failure's already-spent tokens. */
fun billed(inputTokens: Int = 7, outputTokens: Int = 13, costEur: Double = 0.0005): LlmResult =
    LlmResult(
        text = "",
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        costEur = costEur,
        model = TEST_MODEL,
    )

/** Model identifier every engine test keys the cache on. Priced, so `costEur` never throws. */
const val TEST_MODEL: String = "gpt-5-mini"

/**
 * An [LlmClient] that replays a fixed script and records every call.
 *
 * [callCount] is what the resumability and cache-hit tests assert on — never a log line, which
 * could be emitted without a call ever being billed.
 *
 * @param replies Replies to serve in order. When exhausted, [onExhausted] decides what happens.
 * @param onExhausted Reply served once [replies] runs out; `null` makes exhaustion an error, so
 *   a test that expects N calls fails loudly on the N+1st instead of silently succeeding.
 */
class ScriptedLlmClient(
    private val replies: List<ScriptedReply>,
    private val onExhausted: ((Int) -> ScriptedReply)? = null,
) : LlmClient {

    /** Every `(prompt, system)` pair this client was called with, in order. */
    val calls: MutableList<Pair<String, String?>> = mutableListOf()

    /** Number of times [complete] was entered — including calls that raised. */
    val callCount: Int get() = calls.size

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult {
        val index = calls.size
        calls.add(prompt to system)
        val reply =
            replies.getOrNull(index)
                ?: onExhausted?.invoke(index)
                ?: error("ScriptedLlmClient exhausted: unexpected call #${index + 1}")
        return when (reply) {
            is ScriptedReply.Text ->
                LlmResult(
                    text = reply.text,
                    inputTokens = reply.inputTokens,
                    outputTokens = reply.outputTokens,
                    costEur = reply.costEur,
                    model = TEST_MODEL,
                )
            is ScriptedReply.Failure ->
                throw LlmError(reply.message, reply.kind, result = reply.result)
        }
    }
}

/**
 * Serve a well-formed batch reply for whatever batch is asked for, forever.
 *
 * Reads the `[[n]]` markers back out of the prompt so the reply always matches the batch's real
 * size — which is what makes the batch-shape assertions meaningful: the client cannot
 * accidentally impose a shape of its own.
 */
class EchoBatchLlmClient(private val prefix: String = "SL:") : LlmClient {

    /** Prompts seen, in order — one entry per API call. */
    val calls: MutableList<String> = mutableListOf()

    /** The `[[n]]`-numbered source texts of each call, i.e. the actual batch shapes sent. */
    val batches: List<List<String>>
        get() = calls.map { prompt -> sourcesOf(prompt) }

    val callCount: Int get() = calls.size

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult {
        calls.add(prompt)
        val sources = sourcesOf(prompt)
        val body =
            sources.mapIndexed { i, source -> "[[${i + 1}]] $prefix$source" }.joinToString("\n")
        return LlmResult(
            text = body,
            inputTokens = 10,
            outputTokens = 20,
            costEur = 0.001,
            model = TEST_MODEL,
        )
    }

    private companion object {
        /** Only the numbered source block at the end of the prompt carries the batch. */
        private val MARKER = Regex("""^\[\[(\d+)]] (.*)$""", RegexOption.MULTILINE)

        fun sourcesOf(prompt: String): List<String> {
            val block = prompt.substringAfterLast("followed by its translation:\n\n")
            return MARKER.findAll(block).map { it.groupValues[2] }.toList()
        }
    }
}

/**
 * In-memory [TranslationCache] with the same six-column key as the Room table.
 *
 * Keeps the engine's tests off the Robolectric runtime; `RoomTranslationCacheTest` separately
 * proves the real DAO honours the same contract.
 */
class InMemoryTranslationCache : TranslationCache {

    private data class Key(
        val bookHash: String,
        val segmentHash: String,
        val model: String,
        val lang: String,
        val promptVersion: String,
        val glossaryHash: String,
    )

    private val translations = mutableMapOf<Key, String>()
    private val glossaries = mutableMapOf<List<String>, Map<String, String>>()
    private val bookContexts = mutableMapOf<List<String>, String>()

    /** Every [storeBatch] invocation's stored translations, in order — one entry per commit. */
    val commits: MutableList<List<SegmentTranslation>> = mutableListOf()

    /** Every [CallRecord] written, in order. */
    val calls: MutableList<CallRecord> = mutableListOf()

    /** Total rows currently cached. */
    val size: Int get() = translations.size

    override suspend fun getTranslation(
        bookHash: String,
        segmentHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        glossaryHash: String,
    ): String? =
        translations[Key(bookHash, segmentHash, model, lang, promptVersion, glossaryHash)]

    override suspend fun storeBatch(
        bookHash: String,
        model: String,
        lang: String,
        translations: List<SegmentTranslation>,
        call: CallRecord,
        promptVersion: String,
        glossaryHash: String,
    ) {
        translations.forEach { translation ->
            val key =
                Key(bookHash, translation.segmentHash, model, lang, promptVersion, glossaryHash)
            // INSERT OR IGNORE semantics, matching the DAO and `cache.py`.
            this.translations.putIfAbsent(key, translation.text)
        }
        commits.add(translations)
        calls.add(call)
    }

    override suspend fun getGlossary(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
    ): Map<String, String>? = glossaries[listOf(bookHash, model, lang, promptVersion)]

    override suspend fun storeGlossary(
        bookHash: String,
        model: String,
        lang: String,
        terms: Map<String, String>,
        call: CallRecord?,
        promptVersion: String,
    ) {
        glossaries[listOf(bookHash, model, lang, promptVersion)] = terms
        call?.let(calls::add)
    }

    override suspend fun getBookContext(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
    ): String? = bookContexts[listOf(bookHash, model, lang, promptVersion)]

    override suspend fun storeBookContext(
        bookHash: String,
        model: String,
        lang: String,
        promptVersion: String,
        memo: String,
        call: CallRecord?,
    ) {
        bookContexts[listOf(bookHash, model, lang, promptVersion)] = memo
        call?.let(calls::add)
    }
}

/**
 * Build a synthetic [Book] from `(chapterIndex, text)` pairs, numbering positions book-globally
 * and deriving each id exactly as `normalize_epub` does.
 *
 * @param entries `(chapterIndex, text)` in document order.
 * @param title Book title.
 */
fun bookOf(
    vararg entries: Pair<Int, String>,
    title: String = "Test Book",
): Book =
    Book(
        title = title,
        authors = listOf("A. Author"),
        language = "en",
        sourcePath = "test.epub",
        sourceFormat = "epub",
        segments = entries.mapIndexed { position, (chapterIndex, text) ->
            Segment(
                id = makeSegmentId(chapterIndex, position, text),
                type = SegmentType.PARAGRAPH,
                text = text,
                chapterIndex = chapterIndex,
                chapterTitle = "Chapter $chapterIndex",
                position = position,
            )
        },
    )

/** A one-chapter book of [count] distinct paragraphs. */
fun chapterOf(count: Int, chapterIndex: Int = 0, prefix: String = "Sentence"): Book =
    bookOf(*(1..count).map { chapterIndex to "$prefix $it." }.toTypedArray())

/**
 * Assert [block] throws [T], returning it for further assertions.
 *
 * JUnit's `assertThrows` cannot host a suspending call, and nesting `runTest` inside `runTest`
 * is an error, so suspending failure paths need this instead.
 */
suspend inline fun <reified T : Throwable> assertSuspendThrows(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (throwable: Throwable) {
        if (throwable is T) return throwable
        throw AssertionError(
            "expected ${T::class.simpleName}, got ${throwable::class.simpleName}",
            throwable,
        )
    }
    throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
}

/** Render a well-formed `[[n]]`-marked reply for [texts]. */
fun markedReply(texts: List<String>): String =
    texts.mapIndexed { i, text -> "[[${i + 1}]] $text" }.joinToString("\n")
