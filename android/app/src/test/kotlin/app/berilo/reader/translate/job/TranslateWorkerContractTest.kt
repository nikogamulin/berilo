package app.berilo.reader.translate.job

import androidx.work.Data
import app.berilo.reader.translate.engine.TranslationStats
import app.berilo.reader.translate.prompts.StyleTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three pure functions [TranslateWorker.doWork] is composed from.
 *
 * `doWork` itself is a five-line delegation, and running it would need
 * `androidx.work:work-testing` — a dependency this story may not add. What *can* drift without
 * anyone noticing is the boundary it crosses: the request that goes into WorkManager's `Data`
 * and comes back out, the progress snapshot that does the same, and the outcome-to-verdict
 * mapping. Those are asserted here; the run they wrap is asserted in
 * `BookTranslationJobResumeTest` and `TranslateEndToEndTest`.
 */
class TranslateWorkerContractTest {

    private val request =
        TranslationRequest(
            sourceId = "abc123",
            sourceFilePath = "/data/user/0/app.berilo.reader/files/sources/abc123.epub",
            displayName = "A Quiet Library",
            targetLang = "sl",
            model = "gpt-5-mini",
            tier = StyleTier.QUALITY,
        )

    /**
     * The confirmed request survives the WorkManager boundary intact.
     *
     * **Mutation-proof:** drop any single `putString` from [requestData] and the round trip
     * returns `null` (the field is required), failing here. The tier matters most: a tier lost
     * in transit and silently defaulted would either bill double or deliver a quality the user
     * did not choose.
     */
    @Test
    fun `a confirmed request round-trips through WorkManager Data`() {
        assertEquals(request, translationRequestFrom(requestData(request)))
    }

    /**
     * An unreadable job fails rather than guessing.
     *
     * There is no safe default here. Defaulting the tier to ECONOMY starts a run the user never
     * confirmed at that price; defaulting to QUALITY bills double. Both are worse than failing.
     */
    @Test
    fun `a request missing any field, or naming an unknown tier, is refused`() {
        for (key in listOf(KEY_SOURCE_ID, KEY_SOURCE_PATH, KEY_DISPLAY_NAME, KEY_TARGET_LANG, KEY_MODEL, KEY_TIER)) {
            val without =
                Data.Builder()
                    .putAll(requestData(request).keyValueMap.filterKeys { it != key })
                    .build()
            assertNull("missing $key must refuse the job", translationRequestFrom(without))
        }

        val badTier =
            Data.Builder()
                .putAll(requestData(request).keyValueMap)
                .putString(KEY_TIER, "LUXURY")
                .build()
        assertNull("an unknown tier must refuse the job", translationRequestFrom(badTier))
    }

    /** A progress snapshot survives the boundary, including "no chapter yet". */
    @Test
    fun `a progress snapshot round-trips, and a null chapter stays null`() {
        val stats =
            TranslationStats(
                totalSegments = 1294,
                translatedSegments = 300,
                cachedSegments = 12,
                apiCalls = 41,
                costEur = 0.3721,
                currentChapterIndex = 7,
                currentChapterTitle = "Herodotus and His Successors",
            )

        val recovered = progressFrom(progressData(stats))!!
        assertEquals(stats.toProgress(), recovered)
        assertEquals(1294, recovered.totalSegments)
        assertEquals(312, recovered.processedSegments)
        assertEquals(7, recovered.chapterIndex)
        assertEquals("Herodotus and His Successors", recovered.chapterTitle)
        assertEquals(41, recovered.apiCalls)
        assertEquals(0.3721, recovered.costEur, 1e-9)

        val early = progressFrom(progressData(TranslationStats(totalSegments = 10)))!!
        assertNull("no chapter reported yet", early.chapterIndex)
        assertNull("and no title, rather than an empty string", early.chapterTitle)
    }

    /** Before the first `setProgress`, WorkManager hands back empty `Data`. */
    @Test
    fun `empty progress data is no snapshot, not a zeroed one`() {
        assertNull(progressFrom(Data.EMPTY))
    }

    /**
     * A retryable failure is a wait, not a restart.
     *
     * **Mutation-proof:** map `Failed(retryable = true)` to `FAILURE` and this fails — and in
     * production a network blip would abandon a part-paid book instead of resuming it, which is
     * precisely what the per-batch cache commit exists to prevent.
     */
    @Test
    fun `outcomes map to the verdict that preserves paid work`() {
        assertEquals(
            WorkerVerdict.SUCCESS,
            verdictFor(TranslationJobOutcome.Completed("book", TranslationStats(1), alreadyInLibrary = false)),
        )
        assertEquals(
            WorkerVerdict.RETRY,
            verdictFor(TranslationJobOutcome.Failed("offline", retryable = true)),
        )
        assertEquals(
            WorkerVerdict.FAILURE,
            verdictFor(TranslationJobOutcome.Failed("no API key", retryable = false)),
        )
    }
}
