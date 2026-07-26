package app.berilo.reader.translate.engine

import android.util.Log
import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.Glossary
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.bookHash
import app.berilo.reader.translate.model.glossaryIdentity
import app.berilo.reader.translate.model.pythonStrip
import app.berilo.reader.translate.model.segmentHash
import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.TranslationStyle
import app.berilo.reader.translate.prompts.ensureSupports

/**
 * Batched, glossary-guided, resumable book translation — the Kotlin port of
 * `translator/berilo/translate.py`, and the reason milestone m4 exists.
 *
 * The stage upholds the product's two non-negotiable guarantees (CLAUDE.md §2):
 *
 * * **Segment integrity.** The returned [Book] has exactly the same segments as the input —
 *   same count, order, ids, positions and types. Only [Segment.text] changes. A segment is
 *   never silently dropped; one that survives neither a stricter retry nor a per-segment
 *   fallback raises [TranslationError] naming the book, chapter and segment.
 * * **Resumability.** Each batch's translations are committed to the cache in one transaction
 *   immediately after the batch returns, so a killed run re-bills nothing. On a tablet this is
 *   not a nicety: a full book is a multi-hour job that Android may kill at any moment, and the
 *   cost of process death must be at most one batch.
 *
 * ### The retry ladder, and why truncation must not be loud here
 *
 * `docs/findings.md` (2026-07-26) records that making empty/truncated completions *loud* in
 * Python removed the recovery that had been handling them: the raise landed outside every
 * handler in the ladder, so a survivable truncation became a mid-book abort — and, because the
 * cache commits per batch, a resume rebuilt the same oversized prompt and aborted again.
 *
 * Per-segment fallback is precisely the right remedy for a truncation, because one segment per
 * call is a far smaller prompt. So [LlmError.Kind.EMPTY_COMPLETION] and
 * [LlmError.Kind.TRUNCATED_COMPLETION] **degrade at every rung that has a smaller unit to fall
 * back to** — the batch attempt, the revision pass and the book-context memo — and stay loud
 * only in [translateSingle], which has none. B6 deliberately gave those errors an
 * [LlmError.result] carrying the already-billed tokens, so the cost is folded into the run's
 * accounting rather than lost: an abort bug is not fixed by introducing an under-reporting one.
 */

private const val TAG = "TranslateEngine"

/**
 * Consecutive paragraphs grouped into one completion. Kept in the 8-15 range so a batch is large
 * enough to amortize prompt overhead but small enough that a single mismatch retries cheaply.
 */
const val DEFAULT_BATCH_SIZE: Int = 10

/** Previous source/target pairs prepended to each batch as rolling context. */
const val DEFAULT_CONTEXT_PAIRS: Int = 2

/** Source characters sampled from the book's opening to derive the per-book style memo. */
const val BOOK_CONTEXT_EXCERPT_CHARS: Int = 6_000

/** Lowercased substrings that mark a chapter title as back matter (back matter is ~47% of
 * segments — `docs/findings.md`). Used only when the caller opts in; default OFF for safety. */
private val BACK_MATTER_TITLE_PATTERNS =
    listOf("index", "endnotes", "notes", "bibliography", "acknowledg", "about the author")

/**
 * Return whether [title] names a back-matter chapter.
 *
 * @param title Chapter title, possibly `null`.
 */
fun isBackMatterTitle(title: String?): Boolean {
    if (title.isNullOrEmpty()) return false
    val lowered = title.pythonStrip().lowercase()
    return BACK_MATTER_TITLE_PATTERNS.any { it in lowered }
}

/**
 * Return the ids of segments sitting in back-matter chapters.
 *
 * @param book The source book.
 */
fun backMatterSegmentIds(book: Book): Set<String> =
    book.segments.filter { isBackMatterTitle(it.chapterTitle) }.mapTo(mutableSetOf()) { it.id }

/** Whether this failure has a usable-text problem that a smaller unit of work could fix. */
private val LlmError.isDegradable: Boolean
    get() = kind == LlmError.Kind.EMPTY_COMPLETION || kind == LlmError.Kind.TRUNCATED_COMPLETION

// ---------------------------------------------------------------------------------------------
// Prompt assembly.
// ---------------------------------------------------------------------------------------------

/** Render segments as a numbered `[[n]] text` block for the prompt. */
private fun numberedSourceBlock(segments: List<Segment>): String =
    segments.mapIndexed { i, seg -> "[[${i + 1}]] ${seg.text}" }.joinToString("\n\n")

/** Render rolling context as a do-not-retranslate block (empty when there is none). */
private fun contextBlock(contextPairs: List<Pair<String, String>>): String {
    if (contextPairs.isEmpty()) return ""
    val lines = contextPairs.joinToString("\n\n") { (source, target) ->
        "SOURCE: $source\nTRANSLATION: $target"
    }
    return "CONTEXT (already translated — for style/terminology continuity only; " +
        "DO NOT retranslate or include these in your reply):\n" + lines
}

/** Render the per-book style memo as a prompt block (empty when absent). */
private fun bookContextBlock(bookContext: String?): String =
    if (bookContext.isNullOrEmpty()) {
        ""
    } else {
        "BOOK STYLE MEMO (apply to every segment):\n" + bookContext.pythonStrip()
    }

/** Assemble the user prompt for one batch completion. */
private fun buildBatchPrompt(
    segments: List<Segment>,
    glossary: Glossary?,
    contextPairs: List<Pair<String, String>>,
    targetLang: String,
    bookContext: String?,
): String {
    val blocks = mutableListOf("Translate into: $targetLang.")
    bookContextBlock(bookContext).takeIf(String::isNotEmpty)?.let(blocks::add)
    if (glossary != null && !glossary.isEmpty()) blocks.add(glossary.toPromptBlock())
    contextBlock(contextPairs).takeIf(String::isNotEmpty)?.let(blocks::add)
    blocks.add(
        "Translate each of the following numbered segments. Reply with each " +
            "segment's marker followed by its translation:",
    )
    blocks.add(numberedSourceBlock(segments))
    return blocks.joinToString("\n\n")
}

/** Assemble the editor-pass prompt: one marker per segment, SOURCE + DRAFT. */
private fun buildRevisePrompt(
    segments: List<Segment>,
    drafts: List<String>,
    glossary: Glossary?,
    targetLang: String,
    bookContext: String?,
): String {
    val blocks = mutableListOf("Target language: $targetLang.")
    bookContextBlock(bookContext).takeIf(String::isNotEmpty)?.let(blocks::add)
    if (glossary != null && !glossary.isEmpty()) blocks.add(glossary.toPromptBlock())
    blocks.add(
        "Revise each numbered draft below. Reply with each segment's marker " +
            "followed by the revised translation only:",
    )
    blocks.add(
        segments.zip(drafts).mapIndexed { i, (segment, draft) ->
            "[[${i + 1}]]\nSOURCE: ${segment.text}\nDRAFT: $draft"
        }.joinToString("\n\n"),
    )
    return blocks.joinToString("\n\n")
}

// ---------------------------------------------------------------------------------------------
// The batch ladder.
// ---------------------------------------------------------------------------------------------

/**
 * One batch's translations plus the accounting the caller needs.
 *
 * @property translations Translations aligned 1:1 with the batch's segments.
 * @property results Every completion result billed (token/cost accounting), including the
 *   unusable ones recovered from degradable failures.
 * @property revisionFailed Whether a declared revision pass could not be applied.
 */
private data class BatchOutcome(
    val translations: List<String>,
    val results: List<LlmResult>,
    val revisionFailed: Boolean = false,
)

/**
 * Call [LlmClient.complete] for one batch rung, degrading like a bad marker mapping.
 *
 * An empty or truncated response is a batch-level failure of exactly the same shape as a 1:1
 * mismatch: this rung didn't work, but a stricter retry or a smaller (per-segment) prompt is
 * precisely the remedy. The call was billed even though its text is unusable, so the result
 * carried on the error is recorded here rather than silently lost.
 *
 * @return The [LlmResult], or `null` if this rung failed and the caller should move on.
 */
private suspend fun completeBatchRung(
    client: LlmClient,
    prompt: String,
    system: String?,
    results: MutableList<LlmResult>,
    segmentCount: Int,
): LlmResult? {
    val result =
        try {
            client.complete(prompt = prompt, system = system)
        } catch (error: LlmError) {
            if (!error.isDegradable) throw error
            Log.w(
                TAG,
                "Batch of $segmentCount segments returned no usable text (${error.message}); " +
                    "trying the next rung of the retry ladder.",
            )
            error.result?.let(results::add)
            return null
        }
    results.add(result)
    return result
}

/**
 * Translate one segment on its own — the per-segment fallback path.
 *
 * Uses the *style's* single-segment prompt and the same per-book memo as the batch path, so a
 * batch that degrades here keeps the style's quality contract instead of silently reverting to
 * baseline.
 *
 * This is the last rung of the ladder — there is no smaller unit to degrade to — so an empty or
 * truncated response is a **loud** failure here, unlike at the batch level.
 *
 * @throws TranslationError If the model returns an empty translation, or the provider reports
 *   the call was empty/truncated.
 */
private suspend fun translateSingle(
    segment: Segment,
    client: LlmClient,
    glossary: Glossary?,
    targetLang: String,
    bookTitle: String,
    style: TranslationStyle,
    bookContext: String?,
): Pair<String, LlmResult> {
    val blocks = mutableListOf("Translate into $targetLang.")
    bookContextBlock(bookContext).takeIf(String::isNotEmpty)?.let(blocks::add)
    if (glossary != null && !glossary.isEmpty()) blocks.add(glossary.toPromptBlock())
    blocks.add(segment.text)

    val result =
        try {
            client.complete(prompt = blocks.joinToString("\n\n"), system = style.singleSystem)
        } catch (error: LlmError) {
            if (!error.isDegradable) throw error
            throw TranslationError(
                "Empty translation for segment ${segment.id} " +
                    "(chapter ${segment.chapterIndex} ${segment.chapterTitle}) " +
                    "in book '$bookTitle': ${error.message}",
                error,
            )
        }
    val text = result.text.pythonStrip()
    if (text.isEmpty()) {
        throw TranslationError(
            "Empty translation for segment ${segment.id} " +
                "(chapter ${segment.chapterIndex} ${segment.chapterTitle}) in book '$bookTitle'.",
        )
    }
    return text to result
}

/** Run the batch -> strict retry -> per-segment ladder against one client. */
private suspend fun translateBatchAttempts(
    segments: List<Segment>,
    client: LlmClient,
    glossary: Glossary?,
    contextPairs: List<Pair<String, String>>,
    targetLang: String,
    bookTitle: String,
    style: TranslationStyle,
    bookContext: String?,
): Pair<List<String>, MutableList<LlmResult>> {
    val results = mutableListOf<LlmResult>()
    val prompt = buildBatchPrompt(segments, glossary, contextPairs, targetLang, bookContext)

    val first = completeBatchRung(client, prompt, style.batchSystem, results, segments.size)
    if (first != null) {
        try {
            return parseNumberedResponse(first.text, segments.size) to results
        } catch (mismatch: MarkerMappingException) {
            Log.w(
                TAG,
                "Batch of ${segments.size} segments returned a bad mapping " +
                    "(${mismatch.message}); retrying strictly.",
            )
        }
    }

    val second = completeBatchRung(client, prompt, style.strictSystem, results, segments.size)
    if (second != null) {
        try {
            return parseNumberedResponse(second.text, segments.size) to results
        } catch (mismatch: MarkerMappingException) {
            Log.w(
                TAG,
                "Strict retry still bad (${mismatch.message}); " +
                    "falling back to per-segment translation.",
            )
        }
    }

    val translations = mutableListOf<String>()
    for (segment in segments) {
        val (text, result) =
            translateSingle(segment, client, glossary, targetLang, bookTitle, style, bookContext)
        translations.add(text)
        results.add(result)
    }
    return translations to results
}

/**
 * Run the native-editor revision pass over one already-translated batch.
 *
 * The pass is quality-only: it must never change the segment mapping. A reply that does not map
 * 1:1 is retried strictly once and then abandoned — the caller keeps the first-pass draft, so
 * segment integrity holds and the loss of fluency surfaces as
 * [TranslationStats.revisionFailures] rather than corrupting the book.
 *
 * @return `(revisedTranslationsOrNull, billedResults)`; `null` means the pass could not be
 *   applied.
 */
private suspend fun reviseBatch(
    segments: List<Segment>,
    drafts: List<String>,
    client: LlmClient,
    glossary: Glossary?,
    targetLang: String,
    style: TranslationStyle,
    bookContext: String?,
): Pair<List<String>?, List<LlmResult>> {
    val prompt = buildRevisePrompt(segments, drafts, glossary, targetLang, bookContext)
    val results = mutableListOf<LlmResult>()
    val systems = listOfNotNull(style.reviseSystem, style.reviseStrictSystem)

    systems.forEachIndexed { index, system ->
        val attempt = index + 1
        val result =
            try {
                client.complete(prompt = prompt, system = system)
            } catch (error: LlmError) {
                when {
                    error.kind == LlmError.Kind.CONTENT_POLICY -> {
                        Log.w(
                            TAG,
                            "Revision pass refused on content-policy grounds for a batch of " +
                                "${segments.size} segments; keeping the un-revised translation.",
                        )
                        return null to results
                    }
                    error.isDegradable -> {
                        Log.w(TAG, "Revision pass attempt $attempt returned no usable text.")
                        error.result?.let(results::add)
                        null
                    }
                    else -> throw error
                }
            }
        if (result != null) {
            results.add(result)
            try {
                return parseNumberedResponse(result.text, segments.size) to results
            } catch (mismatch: MarkerMappingException) {
                Log.w(TAG, "Revision pass attempt $attempt returned a bad mapping.")
            }
        }
    }

    Log.w(
        TAG,
        "Revision pass failed for a batch of ${segments.size} segments; " +
            "keeping the un-revised translation.",
    )
    return null to results
}

/**
 * Translate one batch: attempt, strict retry, per-segment fallback, then the optional revision.
 *
 * If the provider refuses the batch on content-policy grounds (a history book quoting
 * propaganda, say), the whole batch is retried once against [fallbackClient] when one is
 * configured; without a fallback the refusal is loud.
 */
private suspend fun translateBatch(
    segments: List<Segment>,
    client: LlmClient,
    glossary: Glossary?,
    contextPairs: List<Pair<String, String>>,
    targetLang: String,
    bookTitle: String,
    style: TranslationStyle,
    bookContext: String?,
    fallbackClient: LlmClient?,
): BatchOutcome {
    var effectiveClient = client
    val attempted =
        try {
            translateBatchAttempts(
                segments, client, glossary, contextPairs, targetLang, bookTitle, style, bookContext,
            )
        } catch (error: LlmError) {
            if (error.kind != LlmError.Kind.CONTENT_POLICY) throw error
            if (fallbackClient == null) {
                throw TranslationError(
                    "Provider refused a batch of ${segments.size} segments in '$bookTitle' on " +
                        "content-policy grounds and no fallback provider is configured " +
                        "(add an Anthropic key in Settings): ${error.message}",
                    error,
                )
            }
            Log.w(
                TAG,
                "Content policy refusal for a batch of ${segments.size} segments; " +
                    "retrying via fallback model.",
            )
            effectiveClient = fallbackClient
            translateBatchAttempts(
                segments, fallbackClient, glossary, contextPairs, targetLang, bookTitle, style,
                bookContext,
            )
        }
    val (translations, results) = attempted

    if (style.reviseSystem == null) {
        return BatchOutcome(translations = translations, results = results)
    }

    val (revised, reviseResults) =
        reviseBatch(
            segments, translations, effectiveClient, glossary, targetLang, style, bookContext,
        )
    results.addAll(reviseResults)
    return BatchOutcome(
        translations = revised ?: translations,
        results = results,
        revisionFailed = revised == null,
    )
}

// ---------------------------------------------------------------------------------------------
// Per-book style memo.
// ---------------------------------------------------------------------------------------------

/** Concatenate the book's opening non-empty segments, capped at [maxChars]. */
private fun bookExcerpt(book: Book, maxChars: Int): String {
    val parts = mutableListOf<String>()
    var used = 0
    for (segment in book.segments) {
        val text = segment.text.pythonStrip()
        if (text.isEmpty()) continue
        parts.add(text)
        used += text.length
        if (used >= maxChars) break
    }
    return parts.joinToString("\n").take(maxChars)
}

/**
 * Derive (or load) the one-paragraph per-book style memo for [style].
 *
 * Makes at most one LLM call, memoized in [cache]. Styles without a
 * [TranslationStyle.bookContextSystem] return `(null, null)` without calling anything — which is
 * every style [app.berilo.reader.translate.prompts.resolveStyle] can return on this platform.
 *
 * A degradable failure here degrades to "no memo" rather than aborting: the memo is a
 * best-effort quality input, not a correctness requirement the way a segment translation is.
 * The billed result is still returned so its cost is accounted for.
 *
 * @return `(memoOrNull, completionResultOrNull)`; the result is `null` on a cache hit or when
 *   the style needs no memo.
 */
suspend fun buildBookContext(
    book: Book,
    client: LlmClient,
    style: TranslationStyle,
    targetLang: String,
    model: String,
    cache: TranslationCache?,
    excerptChars: Int = BOOK_CONTEXT_EXCERPT_CHARS,
): Pair<String?, LlmResult?> {
    val system = style.bookContextSystem ?: return null to null

    val bhash = bookHash(book)
    cache?.getBookContext(bhash, model, targetLang, style.version)?.let { cached ->
        Log.i(TAG, "Book-context memo cache hit (${cached.length} chars).")
        return cached to null
    }

    val excerpt = bookExcerpt(book, excerptChars)
    if (excerpt.pythonStrip().isEmpty()) return null to null

    val prompt =
        "Title: ${book.title}\n" +
            "Author(s): ${book.authors.joinToString(", ").ifEmpty { "unknown" }}\n" +
            "Target language: $targetLang\n\n" +
            "Opening excerpt:\n$excerpt"

    var result: LlmResult? = null
    var memo: String
    try {
        result = client.complete(prompt = prompt, system = system)
        memo = result.text.pythonStrip()
    } catch (error: LlmError) {
        if (!error.isDegradable) throw error
        Log.w(TAG, "Book-context call returned no usable text (${error.message}).")
        result = error.result
        memo = ""
    }

    if (memo.isEmpty()) {
        Log.w(TAG, "Book-context memo came back empty; translating without it.")
        // Cache the empty result too: otherwise a killed-and-resumed run repeats this
        // derivation call on every resume, contradicting the "never re-bills the memo call"
        // guarantee (finding 20).
        if (cache != null && result != null) {
            cache.storeBookContext(
                bhash, model, targetLang, style.version, memo, result.toCallRecord(CALL_KIND_BOOK_CONTEXT),
            )
        }
        return null to result
    }

    Log.i(TAG, "Book-context memo derived (${memo.length} chars).")
    if (cache != null && result != null) {
        cache.storeBookContext(
            bhash, model, targetLang, style.version, memo, result.toCallRecord(CALL_KIND_BOOK_CONTEXT),
        )
    }
    return memo to result
}

/** Wrap one completion's billed usage as a [CallRecord] of [kind]. */
private fun LlmResult.toCallRecord(kind: String) =
    CallRecord(kind = kind, inputTokens = inputTokens, outputTokens = outputTokens, costEur = costEur)

/** Sum a batch's completion results into one accounting record. */
private fun List<LlmResult>.aggregate(kind: String) =
    CallRecord(
        kind = kind,
        inputTokens = sumOf(LlmResult::inputTokens),
        outputTokens = sumOf(LlmResult::outputTokens),
        costEur = sumOf(LlmResult::costEur),
    )

// ---------------------------------------------------------------------------------------------
// The entry point.
// ---------------------------------------------------------------------------------------------

/**
 * Translate every eligible segment of [book] into [targetLang].
 *
 * Segments already in [cache] are reused and never re-sent. Segments whose id is in
 * [skipSegmentIds] — and empty/whitespace segments — pass through untranslated so the returned
 * book keeps a 1:1 mapping with the input. Each translated batch is committed to the cache
 * immediately, so a killed run resumes without re-billing.
 *
 * @param book The normalized source book.
 * @param client LLM client for translation calls.
 * @param targetLang Target language code (e.g. `"sl"`).
 * @param cache Translation cache for resumability and accounting.
 * @param model Model identifier that keys the cache. Passed explicitly because Kotlin's
 *   [LlmClient] — unlike Python's `LLMClient` — does not expose the model it was built for.
 * @param glossary Optional glossary injected into every batch prompt. Its identity participates
 *   in the cache key, so translating the same book under different terms re-translates instead
 *   of serving text produced under the old ones.
 * @param batchSize Maximum consecutive segments per completion.
 * @param contextPairs Number of previous source/target pairs used as rolling context. `<= 0`
 *   disables rolling context entirely.
 * @param skipSegmentIds Segment ids to pass through untranslated.
 * @param onProgress Optional callback invoked with a fresh [TranslationStats] snapshot after
 *   each batch and once at the end.
 * @param fallbackClient Optional second-provider client, used only for batches the primary
 *   provider refuses on content-policy grounds.
 * @param style Translation style (prompt set + extra passes). Its version participates in the
 *   cache key. Resolve it with
 *   [app.berilo.reader.translate.prompts.resolveStyle]`(targetLang, context = DEVICE, tier = ...)`
 *   — the app is **always** `DEVICE`; B7's quality toggle overrides the *tier*, never the
 *   context.
 * @return A new [Book] with the same segments and translated text.
 * @throws TranslationError If a segment cannot be translated after retry and per-segment
 *   fallback, or if the segment count changed.
 * @throws app.berilo.reader.translate.prompts.StyleLanguageError If [style] is written for a
 *   language other than [targetLang] — the gate every entry point passes through, so a
 *   contradictory pair can never reach a billed call.
 */
suspend fun translateBook(
    book: Book,
    client: LlmClient,
    targetLang: String,
    cache: TranslationCache,
    model: String,
    glossary: Glossary? = null,
    batchSize: Int = DEFAULT_BATCH_SIZE,
    contextPairs: Int = DEFAULT_CONTEXT_PAIRS,
    skipSegmentIds: Set<String> = emptySet(),
    onProgress: ProgressCallback? = null,
    fallbackClient: LlmClient? = null,
    style: TranslationStyle = BASELINE,
): Book {
    ensureSupports(style, targetLang)
    val bhash = bookHash(book)
    val promptVersion = style.version
    // The glossary is prompt input like the style is, so it keys the cache too: a changed
    // glossary must re-translate, never re-serve (CLAUDE.md §9).
    val ghash = glossaryIdentity(glossary)
    var stats = TranslationStats(totalSegments = book.segments.size)

    val (bookContext, contextResult) =
        buildBookContext(book, client, style, targetLang, model, cache)
    if (contextResult != null) {
        stats = stats.copy(
            apiCalls = stats.apiCalls + 1,
            inputTokens = stats.inputTokens + contextResult.inputTokens,
            outputTokens = stats.outputTokens + contextResult.outputTokens,
            costEur = stats.costEur + contextResult.costEur,
        )
    }

    val segments = book.segments
    val output = mutableListOf<Segment>()
    val recentPairs = mutableListOf<Pair<String, String>>()

    // `contextPairs <= 0` means "no rolling context": remember nothing. Appending and skipping
    // the trim (the pre-A3 behaviour) fed *every* prior pair of the book into each batch — the
    // exact opposite of the intent, and a guaranteed blow-through of the per-batch token
    // ceiling on a full book (review finding 10).
    fun remember(source: String, target: String) {
        if (contextPairs <= 0) return
        recentPairs.add(source to target)
        while (recentPairs.size > contextPairs) recentPairs.removeAt(0)
    }

    var index = 0
    while (index < segments.size) {
        val segment = segments[index]

        if (segment.text.pythonStrip().isEmpty()) {
            output.add(segment)
            stats = stats.copy(emptySegments = stats.emptySegments + 1)
            index++
            continue
        }

        if (segment.id in skipSegmentIds) {
            output.add(segment)
            stats = stats.copy(skippedSegments = stats.skippedSegments + 1)
            index++
            continue
        }

        val cached =
            cache.getTranslation(
                bhash, segmentHash(segment.text), model, targetLang, promptVersion, ghash,
            )
        if (cached != null) {
            output.add(segment.copy(text = cached))
            // Cached segments feed the rolling context exactly like translated ones do, so a
            // resumed run's prompts are identical to an uninterrupted run's.
            remember(segment.text, cached)
            stats = stats.copy(cachedSegments = stats.cachedSegments + 1)
            index++
            continue
        }

        // Gather a batch of consecutive, same-chapter, uncached, translatable segments starting
        // at the current one. Any violation ends the batch, so batch size is dynamic.
        val batch = mutableListOf(segment)
        var cursor = index + 1
        while (cursor < segments.size && batch.size < batchSize) {
            val candidate = segments[cursor]
            if (candidate.chapterIndex != segment.chapterIndex) break
            if (candidate.id in skipSegmentIds || candidate.text.pythonStrip().isEmpty()) break
            val candidateCached =
                cache.getTranslation(
                    bhash, segmentHash(candidate.text), model, targetLang, promptVersion, ghash,
                )
            if (candidateCached != null) break
            batch.add(candidate)
            cursor++
        }

        val outcome =
            translateBatch(
                segments = batch,
                client = client,
                glossary = glossary,
                contextPairs = recentPairs.toList(),
                targetLang = targetLang,
                bookTitle = book.title,
                style = style,
                bookContext = bookContext,
                fallbackClient = fallbackClient,
            )

        // Persist immediately (kill-safety) — one transaction per batch. This line is the
        // resumability guarantee; moving it out of the loop turns a multi-hour tablet job into
        // an all-or-nothing one.
        val call = outcome.results.aggregate(CALL_KIND_BATCH)
        val perSegmentCost = if (batch.isEmpty()) 0.0 else call.costEur / batch.size
        cache.storeBatch(
            bookHash = bhash,
            model = model,
            lang = targetLang,
            translations = batch.zip(outcome.translations).map { (seg, text) ->
                SegmentTranslation(
                    segmentHash = segmentHash(seg.text),
                    text = text,
                    costEur = perSegmentCost,
                )
            },
            call = call,
            promptVersion = promptVersion,
            glossaryHash = ghash,
        )

        batch.zip(outcome.translations).forEach { (seg, text) ->
            output.add(seg.copy(text = text))
            remember(seg.text, text)
        }

        stats = stats.copy(
            translatedSegments = stats.translatedSegments + batch.size,
            apiCalls = stats.apiCalls + outcome.results.size,
            revisionFailures = stats.revisionFailures + if (outcome.revisionFailed) 1 else 0,
            inputTokens = stats.inputTokens + call.inputTokens,
            outputTokens = stats.outputTokens + call.outputTokens,
            costEur = stats.costEur + call.costEur,
            currentChapterIndex = segment.chapterIndex,
            currentChapterTitle = segment.chapterTitle,
        )
        onProgress?.invoke(stats)

        index = cursor
    }

    if (output.size != book.segments.size) { // defensive: integrity invariant
        throw TranslationError(
            "Segment count changed during translation: " +
                "${book.segments.size} in, ${output.size} out.",
        )
    }

    onProgress?.invoke(stats)
    return book.copy(segments = output.toList())
}
