package app.berilo.reader.translate.job

import androidx.work.Data
import app.berilo.reader.translate.engine.TranslationStats

/**
 * What the progress screen shows: position in the book, and what it has cost so far.
 *
 * A deliberately narrower type than [TranslationStats]. The engine's snapshot is rich, but the
 * WorkManager boundary can only carry primitives, so reconstructing a full [TranslationStats]
 * from progress `Data` would mean inventing values for the fields that did not survive the trip
 * — a struct that looks authoritative while half of it is made up. This carries exactly the six
 * numbers that cross the boundary and nothing else.
 *
 * @property totalSegments Segments in the book.
 * @property processedSegments Segments accounted for so far (translated, cached, skipped, empty).
 * @property chapterIndex Chapter the most recent batch belonged to, or `null` before the first.
 * @property chapterTitle That chapter's title, if the source names one.
 * @property apiCalls API calls this run has made.
 * @property costEur EUR this run has actually spent.
 */
data class TranslationProgress(
    val totalSegments: Int,
    val processedSegments: Int,
    val chapterIndex: Int? = null,
    val chapterTitle: String? = null,
    val apiCalls: Int = 0,
    val costEur: Double = 0.0,
) {
    /**
     * Completed share in `0f..1f`.
     *
     * Clamped, and zero for an empty book rather than `NaN`: a `Float.NaN` reaching a
     * determinate progress indicator renders as a full or empty bar depending on the platform,
     * which is a silently wrong number in the one place the user is watching for a real one.
     */
    val fraction: Float
        get() = if (totalSegments <= 0) 0f else (processedSegments.toFloat() / totalSegments).coerceIn(0f, 1f)
}

/** Narrow an engine snapshot to what the UI and the WorkManager boundary carry. */
fun TranslationStats.toProgress(): TranslationProgress =
    TranslationProgress(
        totalSegments = totalSegments,
        processedSegments = processedSegments,
        chapterIndex = currentChapterIndex,
        chapterTitle = currentChapterTitle,
        apiCalls = apiCalls,
        costEur = costEur,
    )

/**
 * Rebuild a [TranslationProgress] from a worker's progress `Data`.
 *
 * @return The progress, or `null` when [data] carries no snapshot yet (WorkManager hands back
 *   empty `Data` between enqueue and the first `setProgress`).
 */
fun progressFrom(data: Data): TranslationProgress? {
    if (!data.keyValueMap.containsKey(KEY_PROGRESS_TOTAL)) return null
    val chapterIndex = data.getInt(KEY_PROGRESS_CHAPTER_INDEX, NO_CHAPTER)
    return TranslationProgress(
        totalSegments = data.getInt(KEY_PROGRESS_TOTAL, 0),
        processedSegments = data.getInt(KEY_PROGRESS_PROCESSED, 0),
        chapterIndex = chapterIndex.takeIf { it != NO_CHAPTER },
        chapterTitle = data.getString(KEY_PROGRESS_CHAPTER_TITLE)?.ifBlank { null },
        apiCalls = data.getInt(KEY_PROGRESS_API_CALLS, 0),
        costEur = data.getDouble(KEY_PROGRESS_COST_EUR, 0.0),
    )
}
