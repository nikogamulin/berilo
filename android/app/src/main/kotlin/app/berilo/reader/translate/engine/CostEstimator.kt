package app.berilo.reader.translate.engine

import app.berilo.reader.llm.costEur
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.pythonStrip
import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.TranslationStyle
import app.berilo.reader.translate.prompts.ensureSupports

/**
 * The no-API dry-run cost estimator, ported from `translate.py`'s `estimate_cost`.
 *
 * B7 gates **all** spending on this (CLAUDE.md §4): the estimate screen shows chapters,
 * segments, estimated EUR and the resolved model + style before any paid call is possible.
 * It makes no API calls itself.
 */

/** Rough characters-per-token ratio for English prose (dry-run heuristic only). */
const val CHARS_PER_TOKEN: Int = 4

/**
 * Estimated target/source output-length ratio.
 *
 * **Flagged, ported as-is (A3):** 1.1 is a *Slovenian* assumption — Slovenian runs slightly
 * longer than English on average — yet it is applied to every target language. Changing it
 * silently would make on-device estimates disagree with the CLI's, so it stays until the
 * translator changes too.
 */
const val TARGET_EXPANSION: Double = 1.1

/** Fixed prompt scaffolding tokens per batch call (system instruction, glossary, context, `[[n]]`). */
const val PROMPT_OVERHEAD_TOKENS_PER_BATCH: Int = 400

// Evidence (docs/findings.md, 2026-07-24): the `doctor` one-sentence smoke on gpt-5-mini billed
// 479 output tokens for a ~15-token *visible* translation (479 / 15 ~= 32x). The model bills
// hidden reasoning tokens as output. That overhead is dominated by a roughly FIXED per-CALL
// reasoning budget rather than scaling with output length, so applying the raw 32x multiplier to
// a long batch would wildly overestimate. The surcharge is therefore modelled as a fixed number
// of output tokens added *per API call*: (479 - 15) = 464.
private const val REASONING_EVIDENCE_OUTPUT_TOKENS = 479
private const val REASONING_EVIDENCE_VISIBLE_TOKENS = 15

/** Reasoning-token surcharge added per API call for reasoning-billing models. */
const val REASONING_TOKENS_PER_CALL: Int =
    REASONING_EVIDENCE_OUTPUT_TOKENS - REASONING_EVIDENCE_VISIBLE_TOKENS

/** Model-name prefixes that bill hidden reasoning tokens as output. */
val REASONING_BILLING_MODEL_PREFIXES: List<String> = listOf("gpt-5", "o1", "o3", "o4")

/** Estimated output tokens for one book-context memo call (<= 90 words). */
const val BOOK_CONTEXT_OUTPUT_TOKENS: Int = 200

/** Estimated output tokens for one glossary-extraction call. */
private const val GLOSSARY_OUTPUT_TOKENS = 300

/** Return whether [model] bills hidden reasoning tokens as output. */
private fun isReasoningModel(model: String): Boolean =
    REASONING_BILLING_MODEL_PREFIXES.any(model::startsWith)

/** Number of batch calls needed for [segmentCount] segments. */
private fun batchesFor(segmentCount: Int, batchSize: Int): Int =
    if (segmentCount <= 0) 0 else (segmentCount + batchSize - 1) / batchSize

/**
 * Per-chapter dry-run estimate.
 *
 * @property index Chapter index.
 * @property title Chapter title, if known.
 * @property segments Segments that would be translated in this chapter.
 * @property inputTokens Estimated input tokens for the chapter.
 * @property outputTokens Estimated output tokens for the chapter.
 * @property costEur Estimated EUR cost for the chapter.
 */
data class ChapterEstimate(
    val index: Int,
    val title: String?,
    val segments: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val costEur: Double,
)

/**
 * Whole-book dry-run estimate.
 *
 * @property model Model the estimate is priced for.
 * @property targetLang Target language code.
 * @property promptVersion Translation style version the estimate is priced for.
 * @property totalSegments Total segments in the book.
 * @property translatableSegments Segments that would be sent to the API.
 * @property skippedSegments Segments passed through untranslated (back matter).
 * @property emptySegments Empty/whitespace segments passed through unchanged.
 * @property batches Estimated number of batch API calls.
 * @property reasoningTokens Reasoning-token surcharge included in [outputTokens].
 * @property inputTokens Estimated total input tokens.
 * @property outputTokens Estimated total output tokens (incl. reasoning surcharge).
 * @property costEur Estimated total EUR cost.
 * @property chapters Per-chapter breakdown (translatable chapters only).
 * @property revisionCalls Extra editor-pass calls the style requires (one per batch for a
 *   revising style, zero otherwise).
 * @property bookContextCalls Extra per-book style-memo calls (0 or 1).
 */
data class CostEstimate(
    val model: String,
    val targetLang: String,
    val promptVersion: String,
    val totalSegments: Int,
    val translatableSegments: Int,
    val skippedSegments: Int,
    val emptySegments: Int,
    val batches: Int,
    val reasoningTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val costEur: Double,
    val chapters: List<ChapterEstimate> = emptyList(),
    val revisionCalls: Int = 0,
    val bookContextCalls: Int = 0,
)

/** Mutable per-chapter accumulator used while grouping (mirrors Python's dict-of-dicts). */
private class ChapterTally(val title: String?) {
    var segments: Int = 0
    var chars: Int = 0
}

/**
 * Estimate the cost of translating [book] without making any API calls.
 *
 * Token counts use a chars/4 heuristic plus fixed per-batch prompt overhead. For
 * reasoning-billing models the estimate adds [REASONING_TOKENS_PER_CALL] output tokens per API
 * call, because those models bill hidden reasoning tokens as output and would otherwise be
 * badly underestimated.
 *
 * The estimate is priced for the style it is asked about: a revising style adds one editor call
 * per batch (roughly doubling the bill), and a book-context style adds one memo call.
 *
 * @param book The source book.
 * @param model Model identifier to price against (must be in the pricing table).
 * @param targetLang Target language code.
 * @param batchSize Segments per batch (drives the batch count).
 * @param skipSegmentIds Segment ids excluded from translation (back matter).
 * @param glossary Whether a glossary-extraction call is included in the estimate.
 * @param style Translation style being priced (drives the extra passes).
 * @return The [CostEstimate], including a per-chapter breakdown.
 * @throws app.berilo.reader.translate.prompts.StyleLanguageError If [style] is written for a
 *   language other than [targetLang]. The dry run refuses for the same reason the paid run
 *   does: quoting a price for a contradictory pair invites the user to approve it.
 */
fun estimateCost(
    book: Book,
    model: String,
    targetLang: String,
    batchSize: Int = DEFAULT_BATCH_SIZE,
    skipSegmentIds: Set<String> = emptySet(),
    glossary: Boolean = true,
    style: TranslationStyle = BASELINE,
): CostEstimate {
    ensureSupports(style, targetLang)
    val reasoning = isReasoningModel(model)
    val revising = style.reviseSystem != null

    // Group translatable segments by chapter, preserving first-seen order.
    val chapters = LinkedHashMap<Int, ChapterTally>()
    var empty = 0
    var skipped = 0
    for (segment in book.segments) {
        val stripped = segment.text.pythonStrip()
        if (stripped.isEmpty()) {
            empty++
            continue
        }
        if (segment.id in skipSegmentIds) {
            skipped++
            continue
        }
        val tally = chapters.getOrPut(segment.chapterIndex) { ChapterTally(segment.chapterTitle) }
        tally.segments++
        // Code points, not UTF-16 units: Python's `len()` counts code points, and an estimate
        // that disagreed with the CLI's on an astral-heavy book would quote a different price
        // for the same job.
        tally.chars += stripped.codePointCount(0, stripped.length)
    }

    val chapterEstimates = mutableListOf<ChapterEstimate>()
    var totalBatches = 0
    var totalReasoning = 0
    var totalRevisionCalls = 0
    for (chapterIndex in chapters.keys.sorted()) {
        val tally = chapters.getValue(chapterIndex)
        val batches = batchesFor(tally.segments, batchSize)
        totalBatches += batches

        val sourceTokens = tally.chars / CHARS_PER_TOKEN
        val targetTokens = (sourceTokens * TARGET_EXPANSION).toInt()
        var inputTokens = sourceTokens + batches * PROMPT_OVERHEAD_TOKENS_PER_BATCH
        var outputTokens = targetTokens
        var calls = batches

        if (revising) {
            // The editor pass re-reads the source and the draft, and rewrites the draft:
            // input ~= source + draft, output ~= draft.
            inputTokens += sourceTokens + targetTokens + batches * PROMPT_OVERHEAD_TOKENS_PER_BATCH
            outputTokens += targetTokens
            calls += batches
            totalRevisionCalls += batches
        }

        val reasoningTokens = if (reasoning) calls * REASONING_TOKENS_PER_CALL else 0
        outputTokens += reasoningTokens
        totalReasoning += reasoningTokens

        chapterEstimates.add(
            ChapterEstimate(
                index = chapterIndex,
                title = tally.title,
                segments = tally.segments,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costEur = costEur(model, inputTokens, outputTokens),
            ),
        )
    }

    // Glossary extraction: one call over a sampled excerpt.
    var extraInput = 0
    var extraOutput = 0
    if (glossary && chapterEstimates.isNotEmpty()) {
        extraInput += DEFAULT_MAX_SAMPLE_CHARS / CHARS_PER_TOKEN
        extraOutput += GLOSSARY_OUTPUT_TOKENS + if (reasoning) REASONING_TOKENS_PER_CALL else 0
        if (reasoning) totalReasoning += REASONING_TOKENS_PER_CALL
    }

    // Book-context memo: one call over the book's opening excerpt.
    var bookContextCalls = 0
    if (style.bookContextSystem != null && chapterEstimates.isNotEmpty()) {
        bookContextCalls = 1
        extraInput += BOOK_CONTEXT_EXCERPT_CHARS / CHARS_PER_TOKEN
        extraOutput += BOOK_CONTEXT_OUTPUT_TOKENS + if (reasoning) REASONING_TOKENS_PER_CALL else 0
        if (reasoning) totalReasoning += REASONING_TOKENS_PER_CALL
    }

    val inputTokens = chapterEstimates.sumOf(ChapterEstimate::inputTokens) + extraInput
    val outputTokens = chapterEstimates.sumOf(ChapterEstimate::outputTokens) + extraOutput

    return CostEstimate(
        model = model,
        targetLang = targetLang,
        promptVersion = style.version,
        totalSegments = book.segments.size,
        translatableSegments = chapterEstimates.sumOf(ChapterEstimate::segments),
        skippedSegments = skipped,
        emptySegments = empty,
        batches = totalBatches,
        reasoningTokens = totalReasoning,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        costEur = costEur(model, inputTokens, outputTokens),
        chapters = chapterEstimates,
        revisionCalls = totalRevisionCalls,
        bookContextCalls = bookContextCalls,
    )
}
