package app.berilo.reader.translate.engine

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.segmentHash
import app.berilo.reader.translate.prompts.BASELINE
import app.berilo.reader.translate.prompts.ExecutionContext
import app.berilo.reader.translate.prompts.REVISE
import app.berilo.reader.translate.prompts.SL_STYLE
import app.berilo.reader.translate.prompts.StyleLanguageError
import app.berilo.reader.translate.prompts.StyleTier
import app.berilo.reader.translate.prompts.resolveStyle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translate engine (B5) — batching, resumability, the retry ladder, the revision pass and
 * the 1:1 segment-integrity guarantee.
 *
 * **No test here makes a live call.** Every client is a scripted in-memory double and every
 * cost assertion reads the fake's billed numbers, so the whole file runs at EUR 0 with no key
 * configured.
 */
class TranslateEngineTest {

    private val lang = "sl"

    // -----------------------------------------------------------------------------------------
    // Batching (Verify 4)
    // -----------------------------------------------------------------------------------------

    /**
     * Asserts the **actual batch shapes**, not merely that batching happened. One book exercises
     * every break condition at once: the size cap, a chapter boundary, an empty segment and a
     * cache hit.
     *
     * **Mutation-proof:** deleting the cached-candidate check from the gather loop makes the
     * third batch `[A14, A15]` instead of `[A14]` — red here, and it would silently re-bill an
     * already-paid segment.
     */
    @Test
    fun `batches cap at ten and break on chapter, empty segment and cache hit`() =
        runTest {
            val book =
                bookOf(
                    *(1..12).map { 0 to "A$it" }.toTypedArray(), // 0..11  chapter 0
                    0 to "   ", // 12     blank
                    0 to "A14", // 13
                    0 to "A15", // 14     pre-cached below
                    1 to "B1", // 15     chapter 1
                    1 to "B2", // 16
                    1 to "B3", // 17
                )
            val cache = InMemoryTranslationCache()
            cache.storeBatch(
                bookHash = app.berilo.reader.translate.model.bookHash(book),
                model = TEST_MODEL,
                lang = lang,
                translations = listOf(SegmentTranslation(segmentHash("A15"), "SL:A15", 0.0)),
                call = CallRecord(CALL_KIND_BATCH, 1, 1, 0.0),
                promptVersion = BASELINE.version,
                glossaryHash = app.berilo.reader.translate.model.glossaryIdentity(null),
            )
            val client = EchoBatchLlmClient()

            val translated =
                translateBook(
                    book = book,
                    client = client,
                    targetLang = lang,
                    cache = cache,
                    model = TEST_MODEL,
                    style = BASELINE,
                )

            assertEquals(
                "batch shapes",
                listOf(
                    (1..10).map { "A$it" },
                    listOf("A11", "A12"),
                    listOf("A14"),
                    listOf("B1", "B2", "B3"),
                ),
                client.batches,
            )
            assertEquals("A15 must not be re-sent", 4, client.callCount)
            assertEquals(book.segments.size, translated.segments.size)
        }

    @Test
    fun `a batch never spans a chapter boundary even when far under the cap`() =
        runTest {
            val book = bookOf(0 to "A1", 0 to "A2", 1 to "B1", 2 to "C1")
            val client = EchoBatchLlmClient()

            translateBook(
                book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE,
            )

            assertEquals(
                listOf(listOf("A1", "A2"), listOf("B1"), listOf("C1")),
                client.batches,
            )
        }

    // -----------------------------------------------------------------------------------------
    // Resumability (Verify 2, 3) — the headline
    // -----------------------------------------------------------------------------------------

    /**
     * **The headline test.** A run is killed mid-book; the resumed run re-bills *zero* already
     * cached segments. Asserted on the fake client's call count, never on a log line.
     *
     * **Mutation-proof:** moving `cache.storeBatch` out of the loop (committing once at the end
     * instead of per batch) makes the second run re-send all 25 segments — 3 calls, not 1.
     */
    @Test
    fun `a killed run resumes without re-billing a single cached segment`() =
        runTest {
            val book = chapterOf(25) // 25 segments -> batches of 10, 10, 5
            val cache = InMemoryTranslationCache()

            // Run 1: two good batches, then the process "dies" on the third.
            val dying =
                object : LlmClient {
                    var callCount = 0
                    private val echo = EchoBatchLlmClient()

                    override suspend fun complete(
                        prompt: String,
                        system: String?,
                        maxTokens: Int?,
                    ): LlmResult {
                        callCount++
                        if (callCount > 2) {
                            throw LlmError("process died", LlmError.Kind.NETWORK)
                        }
                        return echo.complete(prompt, system, maxTokens)
                    }
                }

            assertSuspendThrows<LlmError> {
                translateBook(book, dying, lang, cache, TEST_MODEL, style = BASELINE)
            }
            assertEquals("first run billed 2 batches then died", 3, dying.callCount)
            assertEquals("two batches committed before the kill", 20, cache.size)

            // Run 2: same cache, fresh client. Only the 5 uncached segments may be sent.
            val resumed = EchoBatchLlmClient()
            val translated =
                translateBook(book, resumed, lang, cache, TEST_MODEL, style = BASELINE)

            assertEquals("resume must make exactly one call", 1, resumed.callCount)
            assertEquals(
                "and only for the 5 segments that were never cached",
                listOf((21..25).map { "Sentence $it." }),
                resumed.batches,
            )
            assertEquals(25, translated.segments.size)
            assertTrue(translated.segments.all { it.text.startsWith("SL:") })

            // Run 3: nothing left to do at all.
            val third = EchoBatchLlmClient()
            translateBook(book, third, lang, cache, TEST_MODEL, style = BASELINE)

            assertEquals("a second full run over an unchanged book costs nothing", 0, third.callCount)
        }

    @Test
    fun `a second run reports every segment as cached and zero as translated`() =
        runTest {
            val book = chapterOf(12)
            val cache = InMemoryTranslationCache()
            translateBook(book, EchoBatchLlmClient(), lang, cache, TEST_MODEL, style = BASELINE)

            var last: TranslationStats? = null
            translateBook(
                book, EchoBatchLlmClient(), lang, cache, TEST_MODEL, style = BASELINE,
                onProgress = { last = it },
            )

            val stats = requireNotNull(last)
            assertEquals(12, stats.cachedSegments)
            assertEquals(0, stats.translatedSegments)
            assertEquals(0, stats.apiCalls)
            assertEquals(0.0, stats.costEur, 1e-12)
        }

    /**
     * The per-batch commit is one transaction per batch, in order — the property that bounds
     * process-death loss to a single batch.
     */
    @Test
    fun `each batch is committed in its own transaction before the next one starts`() =
        runTest {
            val book = chapterOf(25)
            val cache = InMemoryTranslationCache()

            translateBook(book, EchoBatchLlmClient(), lang, cache, TEST_MODEL, style = BASELINE)

            assertEquals("one commit per batch", listOf(10, 10, 5), cache.commits.map { it.size })
            assertEquals("one call record per batch", 3, cache.calls.size)
            assertTrue(cache.calls.all { it.kind == CALL_KIND_BATCH })
        }

    @Test
    fun `a batch's cost is apportioned evenly across its segments`() =
        runTest {
            val book = chapterOf(4)
            val cache = InMemoryTranslationCache()

            translateBook(book, EchoBatchLlmClient(), lang, cache, TEST_MODEL, style = BASELINE)

            val commit = cache.commits.single()
            assertEquals(4, commit.size)
            // EchoBatchLlmClient bills 0.001 per call; one call for four segments.
            commit.forEach { assertEquals(0.00025, it.costEur, 1e-12) }
        }

    // -----------------------------------------------------------------------------------------
    // The retry ladder and truncation (Verify 5, 6)
    // -----------------------------------------------------------------------------------------

    /**
     * **The bounced-fix regression test** (`docs/findings.md`, 2026-07-26). A truncated batch
     * reply must degrade — batch, then strict retry, then per-segment — and the book must
     * *complete*. No exception may escape.
     *
     * **Mutation-proof:** removing the `EMPTY_COMPLETION`/`TRUNCATED_COMPLETION` branch from
     * `completeBatchRung` (letting the error propagate, as A4's bounced Python fix did) turns
     * this red with a mid-book abort — exactly the defect the finding records.
     */
    @Test
    fun `a truncated batch degrades to strict, then per-segment, and completes the book`() =
        runTest {
            val book = chapterOf(3)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Failure(LlmError.Kind.TRUNCATED_COMPLETION, billed()),
                        ScriptedReply.Failure(LlmError.Kind.TRUNCATED_COMPLETION, billed()),
                        ScriptedReply.Text("Ena."),
                        ScriptedReply.Text("Dve."),
                        ScriptedReply.Text("Tri."),
                    ),
                )

            val translated =
                translateBook(book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE)

            assertEquals(5, client.callCount)
            assertEquals(
                listOf("Ena.", "Dve.", "Tri."),
                translated.segments.map(Segment::text),
            )
        }

    @Test
    fun `an empty completion degrades exactly like a truncated one`() =
        runTest {
            val book = chapterOf(2)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Failure(LlmError.Kind.EMPTY_COMPLETION, billed()),
                        ScriptedReply.Text(markedReply(listOf("Ena.", "Dve."))),
                    ),
                )

            val translated =
                translateBook(book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE)

            assertEquals(2, client.callCount)
            assertEquals(listOf("Ena.", "Dve."), translated.segments.map(Segment::text))
        }

    /**
     * The per-segment rung has no smaller unit to fall back to, so there the same failure is
     * **loud** — the other half of the finding's rule.
     */
    @Test
    fun `a truncation at the per-segment rung raises loudly, naming the segment`() =
        runTest {
            val book = chapterOf(2)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Failure(LlmError.Kind.TRUNCATED_COMPLETION, billed()),
                        ScriptedReply.Failure(LlmError.Kind.TRUNCATED_COMPLETION, billed()),
                        ScriptedReply.Failure(LlmError.Kind.TRUNCATED_COMPLETION, billed()),
                    ),
                )

            val error =
                assertSuspendThrows<TranslationError> {
                    translateBook(
                        book, client, lang, InMemoryTranslationCache(), TEST_MODEL,
                        style = BASELINE,
                    )
                }

            val message = requireNotNull(error.message)
            assertTrue("names the segment: $message", message.contains(book.segments[0].id))
            assertTrue("names the book: $message", message.contains("Test Book"))
        }

    /**
     * A billed-but-unusable call still costs money. B6 attached the [LlmResult] to those errors
     * precisely so the ladder can fold the cost in; losing it would trade an abort bug for an
     * under-reporting one.
     *
     * **Mutation-proof:** dropping `error.result?.let(results::add)` from `completeBatchRung`
     * turns this red (cost falls to the three per-segment calls only).
     */
    @Test
    fun `truncated and empty calls still contribute their billed cost and tokens`() =
        runTest {
            val book = chapterOf(3)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Failure(
                            LlmError.Kind.TRUNCATED_COMPLETION,
                            billed(inputTokens = 100, outputTokens = 200, costEur = 0.05),
                        ),
                        ScriptedReply.Failure(
                            LlmError.Kind.EMPTY_COMPLETION,
                            billed(inputTokens = 10, outputTokens = 20, costEur = 0.005),
                        ),
                        ScriptedReply.Text("Ena.", inputTokens = 1, outputTokens = 2, costEur = 0.001),
                        ScriptedReply.Text("Dve.", inputTokens = 1, outputTokens = 2, costEur = 0.001),
                        ScriptedReply.Text("Tri.", inputTokens = 1, outputTokens = 2, costEur = 0.001),
                    ),
                )
            var last: TranslationStats? = null

            translateBook(
                book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE,
                onProgress = { last = it },
            )

            val stats = requireNotNull(last)
            assertEquals(0.05 + 0.005 + 0.003, stats.costEur, 1e-12)
            assertEquals(100 + 10 + 3, stats.inputTokens)
            assertEquals(200 + 20 + 6, stats.outputTokens)
            assertEquals(5, stats.apiCalls)
        }

    @Test
    fun `a bad marker mapping retries strictly and succeeds without per-segment fallback`() =
        runTest {
            val book = chapterOf(3)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Text("[[1]] Ena.\n[[2]] Dve."), // only 2 of 3
                        ScriptedReply.Text(markedReply(listOf("Ena.", "Dve.", "Tri."))),
                    ),
                )

            val translated =
                translateBook(book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE)

            assertEquals("strict retry only — no per-segment fallback", 2, client.callCount)
            assertEquals(listOf("Ena.", "Dve.", "Tri."), translated.segments.map(Segment::text))
            assertTrue(
                "the retry must use the strict system prompt",
                client.calls[1].second == BASELINE.strictSystem,
            )
        }

    /**
     * A stray `[[2]]` inside prose must **not** force a retry — anchoring exists to make the
     * change cost-monotone (review finding 14).
     */
    @Test
    fun `a stray marker inside a translation does not force a retry`() =
        runTest {
            val book = chapterOf(2)
            val client =
                ScriptedLlmClient(
                    listOf(ScriptedReply.Text("[[1]] Element [[2]] polja.\n[[2]] Drugi.")),
                )

            val translated =
                translateBook(book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE)

            assertEquals("exactly one call — no retry", 1, client.callCount)
            assertEquals(
                listOf("Element [[2]] polja.", "Drugi."),
                translated.segments.map(Segment::text),
            )
        }

    @Test
    fun `a content-policy refusal without a fallback client fails loudly`() =
        runTest {
            val book = chapterOf(2)
            val client =
                ScriptedLlmClient(listOf(ScriptedReply.Failure(LlmError.Kind.CONTENT_POLICY)))

            val error =
                assertSuspendThrows<TranslationError> {
                    translateBook(
                        book, client, lang, InMemoryTranslationCache(), TEST_MODEL,
                        style = BASELINE,
                    )
                }

            assertTrue(requireNotNull(error.message).contains("content-policy"))
        }

    @Test
    fun `a content-policy refusal is retried against the fallback client`() =
        runTest {
            val book = chapterOf(2)
            val primary =
                ScriptedLlmClient(listOf(ScriptedReply.Failure(LlmError.Kind.CONTENT_POLICY)))
            val fallback = EchoBatchLlmClient(prefix = "FB:")

            val translated =
                translateBook(
                    book, primary, lang, InMemoryTranslationCache(), TEST_MODEL,
                    style = BASELINE, fallbackClient = fallback,
                )

            assertEquals(1, primary.callCount)
            assertEquals(1, fallback.callCount)
            assertTrue(translated.segments.all { it.text.startsWith("FB:") })
        }

    // -----------------------------------------------------------------------------------------
    // Revision pass (Verify 7)
    // -----------------------------------------------------------------------------------------

    /**
     * Integrity over fluency: a revision pass that cannot be applied leaves the un-revised
     * draft in place and is counted, never dropped or half-applied.
     */
    @Test
    fun `a failed revision pass keeps the un-revised draft and counts the failure`() =
        runTest {
            val book = chapterOf(3)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Text(markedReply(listOf("Osnutek 1.", "Osnutek 2.", "Osnutek 3."))),
                        ScriptedReply.Text("[[1]] Samo eden."), // bad mapping
                        ScriptedReply.Text("popolnoma brez oznak"), // bad again
                    ),
                )
            var last: TranslationStats? = null

            val translated =
                translateBook(
                    book, client, lang, InMemoryTranslationCache(), TEST_MODEL,
                    style = REVISE, onProgress = { last = it },
                )

            assertEquals("translate + revise + strict revise", 3, client.callCount)
            assertEquals(
                listOf("Osnutek 1.", "Osnutek 2.", "Osnutek 3."),
                translated.segments.map(Segment::text),
            )
            assertEquals(1, requireNotNull(last).revisionFailures)
        }

    @Test
    fun `a successful revision pass replaces the draft and counts no failure`() =
        runTest {
            val book = chapterOf(2)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Text(markedReply(listOf("Osnutek 1.", "Osnutek 2."))),
                        ScriptedReply.Text(markedReply(listOf("Zlikano 1.", "Zlikano 2."))),
                    ),
                )
            var last: TranslationStats? = null

            val translated =
                translateBook(
                    book, client, lang, InMemoryTranslationCache(), TEST_MODEL,
                    style = REVISE, onProgress = { last = it },
                )

            assertEquals(listOf("Zlikano 1.", "Zlikano 2."), translated.segments.map(Segment::text))
            assertEquals(0, requireNotNull(last).revisionFailures)
        }

    /** A truncated revision degrades too — it has the un-revised draft to fall back to. */
    @Test
    fun `a truncated revision pass keeps the draft and folds in its billed cost`() =
        runTest {
            val book = chapterOf(2)
            val client =
                ScriptedLlmClient(
                    listOf(
                        ScriptedReply.Text(
                            markedReply(listOf("Osnutek 1.", "Osnutek 2.")),
                            costEur = 0.01,
                        ),
                        ScriptedReply.Failure(
                            LlmError.Kind.TRUNCATED_COMPLETION,
                            billed(costEur = 0.002),
                        ),
                        ScriptedReply.Failure(
                            LlmError.Kind.TRUNCATED_COMPLETION,
                            billed(costEur = 0.003),
                        ),
                    ),
                )
            var last: TranslationStats? = null

            val translated =
                translateBook(
                    book, client, lang, InMemoryTranslationCache(), TEST_MODEL,
                    style = REVISE, onProgress = { last = it },
                )

            assertEquals(listOf("Osnutek 1.", "Osnutek 2."), translated.segments.map(Segment::text))
            val stats = requireNotNull(last)
            assertEquals(1, stats.revisionFailures)
            assertEquals(0.015, stats.costEur, 1e-12)
        }

    // -----------------------------------------------------------------------------------------
    // Segment integrity (Verify 8)
    // -----------------------------------------------------------------------------------------

    /**
     * The 1:1 guarantee end to end: same count, order, ids, positions, types and chapter
     * metadata; only `text` differs. Mixes translated, cached, empty and skipped segments so
     * every pass-through path is covered at once.
     */
    @Test
    fun `the returned book preserves every segment's identity and only changes text`() =
        runTest {
            val book =
                bookOf(0 to "A1", 0 to "  ", 0 to "A3", 1 to "B1", 1 to "B2")
            val skipped = setOf(book.segments[4].id)

            val translated =
                translateBook(
                    book, EchoBatchLlmClient(), lang, InMemoryTranslationCache(), TEST_MODEL,
                    style = BASELINE, skipSegmentIds = skipped,
                )

            assertEquals(book.segments.size, translated.segments.size)
            book.segments.zip(translated.segments).forEach { (source, target) ->
                assertEquals(source.id, target.id)
                assertEquals(source.position, target.position)
                assertEquals(source.type, target.type)
                assertEquals(source.chapterIndex, target.chapterIndex)
                assertEquals(source.chapterTitle, target.chapterTitle)
            }
            assertEquals("  ", translated.segments[1].text)
            assertEquals("skipped segments pass through untouched", "B2", translated.segments[4].text)
            assertEquals("SL:A1", translated.segments[0].text)
        }

    @Test
    fun `stats account for every segment exactly once`() =
        runTest {
            val book = bookOf(0 to "A1", 0 to "  ", 0 to "A3", 1 to "B1", 1 to "B2")
            var last: TranslationStats? = null

            translateBook(
                book, EchoBatchLlmClient(), lang, InMemoryTranslationCache(), TEST_MODEL,
                style = BASELINE, skipSegmentIds = setOf(book.segments[4].id),
                onProgress = { last = it },
            )

            val stats = requireNotNull(last)
            assertEquals(5, stats.totalSegments)
            assertEquals(3, stats.translatedSegments)
            assertEquals(1, stats.emptySegments)
            assertEquals(1, stats.skippedSegments)
            assertEquals(0, stats.cachedSegments)
            assertEquals(5, stats.processedSegments)
        }

    @Test
    fun `progress is reported per batch and once at the end`() =
        runTest {
            val book = chapterOf(25)
            val seen = mutableListOf<TranslationStats>()

            translateBook(
                book, EchoBatchLlmClient(), lang, InMemoryTranslationCache(), TEST_MODEL,
                style = BASELINE, onProgress = seen::add,
            )

            assertEquals("3 batches + 1 final", 4, seen.size)
            assertEquals(listOf(10, 20, 25, 25), seen.map(TranslationStats::translatedSegments))
            assertNotEquals(
                "snapshots must be independent, not one mutated object",
                seen[0], seen[1],
            )
            assertEquals(0, seen.last().currentChapterIndex)
        }

    // -----------------------------------------------------------------------------------------
    // Rolling context
    // -----------------------------------------------------------------------------------------

    @Test
    fun `rolling context carries the last two pairs into the next batch`() =
        runTest {
            val book = bookOf(0 to "A1", 0 to "A2", 0 to "A3", 0 to "A4")
            val client = EchoBatchLlmClient()

            translateBook(
                book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE,
                batchSize = 2,
            )

            assertEquals(2, client.callCount)
            val second = client.calls[1]
            assertTrue("carries A1", second.contains("SOURCE: A1"))
            assertTrue("carries A2", second.contains("SOURCE: A2"))
            assertTrue("and their translations", second.contains("TRANSLATION: SL:A2"))
        }

    /**
     * `contextPairs <= 0` means *no* rolling context. The pre-A3 bug appended without trimming,
     * feeding every prior pair of the book into each batch — the exact opposite of the intent,
     * and a guaranteed token-ceiling blow-through on a full book (review finding 10).
     */
    @Test
    fun `contextPairs of zero disables rolling context entirely`() =
        runTest {
            val book = bookOf(0 to "A1", 0 to "A2", 0 to "A3", 0 to "A4")
            val client = EchoBatchLlmClient()

            translateBook(
                book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE,
                batchSize = 2, contextPairs = 0,
            )

            assertTrue(
                "no CONTEXT block may appear in any prompt",
                client.calls.none { it.contains("CONTEXT (already translated") },
            )
        }

    @Test
    fun `context never grows beyond the configured window`() =
        runTest {
            val book = bookOf(*(1..8).map { 0 to "A$it" }.toTypedArray())
            val client = EchoBatchLlmClient()

            translateBook(
                book, client, lang, InMemoryTranslationCache(), TEST_MODEL, style = BASELINE,
                batchSize = 2, contextPairs = 2,
            )

            val last = client.calls.last()
            assertEquals(
                "exactly two context pairs, never a growing tail",
                2,
                Regex("SOURCE: ").findAll(last).count(),
            )
        }

    /** Cached segments feed the context too, so a resumed run's prompts match an unbroken run's. */
    @Test
    fun `cached segments feed the rolling context just like translated ones`() =
        runTest {
            val book = bookOf(0 to "A1", 0 to "A2", 0 to "A3")
            val cache = InMemoryTranslationCache()
            cache.storeBatch(
                bookHash = app.berilo.reader.translate.model.bookHash(book),
                model = TEST_MODEL,
                lang = lang,
                translations = listOf(
                    SegmentTranslation(segmentHash("A1"), "SL:A1", 0.0),
                    SegmentTranslation(segmentHash("A2"), "SL:A2", 0.0),
                ),
                call = CallRecord(CALL_KIND_BATCH, 1, 1, 0.0),
                promptVersion = BASELINE.version,
                glossaryHash = app.berilo.reader.translate.model.glossaryIdentity(null),
            )
            val client = EchoBatchLlmClient()

            translateBook(book, client, lang, cache, TEST_MODEL, style = BASELINE)

            val only = client.calls.single()
            assertTrue("cached A1 is context", only.contains("SOURCE: A1"))
            assertTrue("cached A2 is context", only.contains("SOURCE: A2"))
        }

    // -----------------------------------------------------------------------------------------
    // Cache keying
    // -----------------------------------------------------------------------------------------

    @Test
    fun `changing the style re-translates instead of serving the old text`() =
        runTest {
            val book = chapterOf(2)
            val cache = InMemoryTranslationCache()
            translateBook(book, EchoBatchLlmClient(), lang, cache, TEST_MODEL, style = BASELINE)

            val underNewStyle = EchoBatchLlmClient()
            translateBook(book, underNewStyle, lang, cache, TEST_MODEL, style = SL_STYLE)

            assertEquals("a different prompt version must miss the cache", 1, underNewStyle.callCount)
        }

    @Test
    fun `changing the glossary re-translates instead of serving the old text`() =
        runTest {
            val book = chapterOf(2)
            val cache = InMemoryTranslationCache()
            translateBook(book, EchoBatchLlmClient(), lang, cache, TEST_MODEL, style = BASELINE)

            val withGlossary = EchoBatchLlmClient()
            translateBook(
                book, withGlossary, lang, cache, TEST_MODEL, style = BASELINE,
                glossary = app.berilo.reader.translate.model.Glossary(mapOf("Kaplan" to "Kaplan")),
            )

            assertEquals("a different glossary must miss the cache", 1, withGlossary.callCount)
        }

    // -----------------------------------------------------------------------------------------
    // Style resolution and execution context (Verify 9)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `DEVICE resolves the single-pass economy style by default`() {
        val style = resolveStyle(targetLang = "sl", context = ExecutionContext.DEVICE)

        assertSame(BASELINE, style)
        assertEquals("economy is single-pass", null, style.reviseSystem)
    }

    @Test
    fun `a QUALITY tier override on DEVICE resolves the two-pass style`() {
        val style =
            resolveStyle(
                targetLang = "sl",
                context = ExecutionContext.DEVICE,
                tier = StyleTier.QUALITY,
            )

        assertSame(REVISE, style)
        assertTrue("quality adds the editor pass", style.reviseSystem != null)
    }

    /**
     * The tier override must never be reached by *lying about the context*. Asserted as a
     * property: `DEVICE` and `WORKSTATION` disagree on the default, and the override reaches
     * the quality style while leaving `DEVICE` itself intact.
     */
    @Test
    fun `the execution context is never falsified to reach the other tier`() {
        val deviceDefault = resolveStyle("sl", context = ExecutionContext.DEVICE)
        val workstationDefault = resolveStyle("sl", context = ExecutionContext.WORKSTATION)
        val deviceOverridden =
            resolveStyle("sl", context = ExecutionContext.DEVICE, tier = StyleTier.QUALITY)

        assertNotEquals(
            "the two contexts must genuinely differ, or the override is meaningless",
            deviceDefault, workstationDefault,
        )
        assertEquals(workstationDefault, deviceOverridden)
        assertSame("DEVICE's own default is unchanged by the override existing", BASELINE, deviceDefault)
    }

    @Test
    fun `a style that contradicts the target language is refused before any call`() =
        runTest {
            val client = EchoBatchLlmClient()

            assertSuspendThrows<StyleLanguageError> {
                translateBook(
                    chapterOf(2), client, "de", InMemoryTranslationCache(), TEST_MODEL,
                    style = REVISE,
                )
            }
            assertEquals("the refusal must precede every billed call", 0, client.callCount)
        }

    // -----------------------------------------------------------------------------------------
    // Back matter
    // -----------------------------------------------------------------------------------------

    @Test
    fun `back-matter chapter titles are detected and their segments are skippable`() {
            assertTrue(isBackMatterTitle("Index"))
            assertTrue(isBackMatterTitle("  ACKNOWLEDGEMENTS  "))
            assertTrue(isBackMatterTitle("Select Bibliography"))
            assertTrue(!isBackMatterTitle("Chapter One"))
            assertTrue(!isBackMatterTitle(null))
    }
}
