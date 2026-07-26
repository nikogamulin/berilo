package app.berilo.reader.translate.engine

/**
 * A segment could not be translated after every fallback, or the book's segment count changed.
 *
 * The loud half of the segment-integrity guarantee (CLAUDE.md §2). The message names the book,
 * chapter and segment so the failure is actionable rather than merely noisy.
 */
class TranslationError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Running totals for one [translateBook] invocation, ported from `translate.py`'s
 * `TranslationStats`.
 *
 * **Immutable, unlike Python's mutable dataclass.** The engine keeps its running counters
 * privately and hands a fresh snapshot to each [ProgressCallback] invocation, because B7 renders
 * these on a Compose screen: a mutable object handed to the UI would either be mutated under a
 * recomposition or compare equal to itself and never trigger one.
 *
 * @property totalSegments Total segments in the book.
 * @property translatedSegments Segments newly translated via the API this run.
 * @property cachedSegments Segments served from the cache this run.
 * @property skippedSegments Segments passed through untranslated (back matter).
 * @property emptySegments Empty/whitespace segments passed through unchanged.
 * @property apiCalls Number of API calls made this run.
 * @property revisionFailures Batches whose revision pass could not be applied. The first-pass
 *   translation was kept, so integrity holds and only fluency is lost — surfaced here rather
 *   than corrupting the book.
 * @property inputTokens Total input tokens billed this run.
 * @property outputTokens Total output tokens billed this run.
 * @property costEur Total EUR cost this run.
 * @property currentChapterIndex Chapter index of the most recent batch.
 * @property currentChapterTitle Chapter title of the most recent batch.
 */
data class TranslationStats(
    val totalSegments: Int,
    val translatedSegments: Int = 0,
    val cachedSegments: Int = 0,
    val skippedSegments: Int = 0,
    val emptySegments: Int = 0,
    val apiCalls: Int = 0,
    val revisionFailures: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costEur: Double = 0.0,
    val currentChapterIndex: Int? = null,
    val currentChapterTitle: String? = null,
) {
    /** Segments accounted for so far (translated + cached + skipped + empty). */
    val processedSegments: Int
        get() = translatedSegments + cachedSegments + skippedSegments + emptySegments
}

/** Invoked with a fresh [TranslationStats] snapshot after each batch and once at the end. */
typealias ProgressCallback = (TranslationStats) -> Unit
