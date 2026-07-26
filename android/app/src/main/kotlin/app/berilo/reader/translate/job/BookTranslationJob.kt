package app.berilo.reader.translate.job

import android.util.Log
import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.createLlmClient
import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.store.importer.BookImporter
import app.berilo.reader.store.importer.ImportOutcome
import app.berilo.reader.translate.engine.ProgressCallback
import app.berilo.reader.translate.engine.TranslationCache
import app.berilo.reader.translate.engine.TranslationError
import app.berilo.reader.translate.engine.TranslationStats
import app.berilo.reader.translate.engine.backMatterSegmentIds
import app.berilo.reader.translate.engine.buildGlossary
import app.berilo.reader.translate.engine.translateBook
import app.berilo.reader.translate.epub.EpubParseException
import app.berilo.reader.translate.epub.EpubReader
import app.berilo.reader.translate.epub.EpubWriter
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.prompts.StyleTier
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "BookTranslationJob"
private const val EPUB_EXTENSION = ".epub"
private const val OUTPUT_PREFIX = "translated-"

/**
 * What to translate, and how — the confirmed shape of a paid run.
 *
 * Flat and primitive-typed on purpose: [TranslateWorker] round-trips it through WorkManager's
 * `Data`, which carries only primitives.
 *
 * @property sourceId Content hash of the staged source EPUB.
 * @property sourceFilePath Absolute path of the staged source EPUB.
 * @property displayName Name shown to the user while the job runs.
 * @property targetLang Target language tag.
 * @property model Model to bill against.
 * @property tier Style tier the user confirmed. **A tier, never an execution context** — see
 *   [TranslationPlanner.styleFor].
 */
data class TranslationRequest(
    val sourceId: String,
    val sourceFilePath: String,
    val displayName: String,
    val targetLang: String,
    val model: String,
    val tier: StyleTier,
)

/** Terminal outcome of one [BookTranslationJob.run]. */
sealed interface TranslationJobOutcome {

    /**
     * The translated EPUB was written and is in the library.
     *
     * @property bookId Library id of the translated book.
     * @property stats **This run's** billing, not the book's lifetime cost: a resumed run
     *   reports only what it newly spent, because everything committed earlier came back from
     *   the cache for free.
     * @property alreadyInLibrary The identical translated bytes were already shelved.
     */
    data class Completed(
        val bookId: String,
        val stats: TranslationStats,
        val alreadyInLibrary: Boolean,
    ) : TranslationJobOutcome

    /**
     * The run stopped.
     *
     * @property reason User-facing message.
     * @property retryable Whether waiting and running again could succeed. Everything already
     *   committed to the cache stays committed either way, so a retry resumes rather than
     *   restarts.
     * @property stats Billing up to the failure, when the engine got far enough to report any.
     */
    data class Failed(
        val reason: String,
        val retryable: Boolean,
        val stats: TranslationStats? = null,
    ) : TranslationJobOutcome
}

/**
 * The paid run: source EPUB in, translated EPUB in the library out (B7).
 *
 * This is the only place in the app that can bill a whole-book translation, and it is reached
 * from exactly one caller — [TranslateWorker], which is enqueued from exactly one method,
 * [app.berilo.reader.ui.translate.TranslateViewModel.confirmAndTranslate]. That chain is the
 * confirmation gate CLAUDE.md §4 requires; `TranslateViewModelSpendGateTest` asserts it by
 * exercising every other public entry point and proving the client is never touched.
 *
 * **Resumability is inherited, not re-implemented.**
 * [app.berilo.reader.translate.engine.translateBook] commits each batch to [cache] in one
 * transaction as soon as it returns, so process death costs at most one batch. Running this job
 * again after a kill re-reads the same book, hits the cache for every committed segment, and
 * bills only the remainder — which is why the worker retries instead of surfacing a dead end.
 *
 * @param cache Translation cache — the resumability substrate.
 * @param bookImporter The library's normal importer; the translated EPUB goes in through the
 *   same door as a CLI-produced one, so it opens in the reader like any other book.
 * @param workDir Scratch directory the EPUB is assembled in before import.
 * @param epubReader Source reader.
 * @param epubWriter Output writer.
 * @param clientFactory Builds the [LlmClient] for a run. Defaults to
 *   [createLlmClient] so the pricing pre-flight always runs; injectable so every test drives a
 *   scripted double and the suite bills nothing.
 * @param ioDispatcher Dispatcher the whole job runs on.
 */
class BookTranslationJob(
    private val cache: TranslationCache,
    private val bookImporter: BookImporter,
    private val workDir: File,
    private val epubReader: EpubReader = EpubReader(),
    private val epubWriter: EpubWriter = EpubWriter(),
    private val clientFactory: (LlmSettings) -> LlmClient = ::createLlmClient,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {

    /**
     * Translate the requested book and shelve the result.
     *
     * @param request The confirmed run.
     * @param settings Settings supplying the API keys the run authenticates with.
     * @param onProgress Invoked with a fresh [TranslationStats] snapshot after each batch.
     * @return [TranslationJobOutcome.Completed] with this run's billing, or
     *   [TranslationJobOutcome.Failed].
     */
    suspend fun run(
        request: TranslationRequest,
        settings: LlmSettings,
        onProgress: ProgressCallback = {},
    ): TranslationJobOutcome =
        withContext(ioDispatcher) {
            var latest: TranslationStats? = null
            val record: ProgressCallback = { stats ->
                latest = stats
                onProgress(stats)
            }
            try {
                // The engine reports billing through the callback, not the return value, so the
                // last snapshot is this run's actual EUR — including the fallback and revision
                // spend the CLI drops (review finding 5).
                when (val outcome = translate(request, settings, record)) {
                    is TranslationJobOutcome.Completed ->
                        outcome.copy(stats = latest ?: outcome.stats)
                    else -> outcome
                }
            } catch (e: LlmError) {
                Log.w(TAG, "Translation stopped on a provider error (${e.kind}).", e)
                TranslationJobOutcome.Failed(
                    reason = e.message ?: "The translation provider refused the request.",
                    retryable = e.kind.isTransient,
                    stats = latest,
                )
            } catch (e: TranslationError) {
                // Segment integrity failed loudly (CLAUDE.md §2). Retrying rebuilds the same
                // prompt and fails the same way, so this is a dead end, not a wait.
                Log.e(TAG, "Translation stopped on a segment-integrity failure.", e)
                TranslationJobOutcome.Failed(
                    reason = e.message ?: "A segment could not be translated.",
                    retryable = false,
                    stats = latest,
                )
            } catch (e: EpubParseException) {
                Log.e(TAG, "Source EPUB could not be read.", e)
                TranslationJobOutcome.Failed(
                    reason = "Could not read ${request.displayName}: ${e.reason}",
                    retryable = false,
                    stats = latest,
                )
            } catch (e: IOException) {
                Log.w(TAG, "Translation stopped on an IO error.", e)
                TranslationJobOutcome.Failed(
                    reason = e.message ?: "Could not write the translated EPUB.",
                    retryable = true,
                    stats = latest,
                )
            }
        }

    private suspend fun translate(
        request: TranslationRequest,
        settings: LlmSettings,
        onProgress: ProgressCallback,
    ): TranslationJobOutcome {
        val sourceFile = File(request.sourceFilePath)
        if (!sourceFile.exists()) {
            return TranslationJobOutcome.Failed(
                reason = "The source file for ${request.displayName} is gone — import it again.",
                retryable = false,
            )
        }

        val book = epubReader.read(sourceFile)
        val style = TranslationPlanner.styleFor(request.targetLang, request.tier)
        // Metered: the reported EUR must be every call this run made, glossary extraction and
        // billed failures included — see MeteredLlmClient.
        val client = MeteredLlmClient(clientFactory(settings.copy(model = request.model)))
        val reporting: ProgressCallback = { stats -> onProgress(client.applyTo(stats)) }
        Log.i(
            TAG,
            "Translating '${book.title}' (${book.segments.size} segments, " +
                "${book.chapterCount} chapters) to ${request.targetLang} " +
                "with ${request.model} / ${style.name}.",
        )

        val glossary =
            buildGlossary(
                book = book,
                client = client,
                targetLang = request.targetLang,
                model = request.model,
                cache = cache,
            )

        val translated =
            translateBook(
                book = book,
                client = client,
                targetLang = request.targetLang,
                cache = cache,
                model = request.model,
                glossary = glossary.takeIf { !it.isEmpty() },
                skipSegmentIds = backMatterSegmentIds(book),
                onProgress = reporting,
                style = style,
            )

        // The output declares the language it is actually written in, so the library, the reader
        // and any later re-import all read the target rather than the source tag.
        return shelve(
            translated.copy(language = request.targetLang),
            request,
            client.applyTo(TranslationStats(totalSegments = book.segments.size)),
        )
    }

    /**
     * Write the translated book and hand it to the library importer.
     *
     * The scratch file is deleted whether or not the import succeeded — [BookImporter] copies
     * the bytes into its own directory, so leaving this one behind would double every
     * translated book's disk footprint.
     *
     * @param floor Billing to report if no progress snapshot was produced. A book served
     *   entirely from cache commits no batch and therefore fires no callback, so without this
     *   it would report zero segments instead of "all of them, for nothing".
     */
    private suspend fun shelve(
        translated: Book,
        request: TranslationRequest,
        floor: TranslationStats,
    ): TranslationJobOutcome {
        workDir.mkdirs()
        val output = File(workDir, "$OUTPUT_PREFIX${request.sourceId}$EPUB_EXTENSION")
        try {
            epubWriter.write(translated, output)
            val name = "${request.displayName} (${request.targetLang})$EPUB_EXTENSION"
            return when (val outcome = bookImporter.import({ output.inputStream() }, name)) {
                is ImportOutcome.Imported ->
                    TranslationJobOutcome.Completed(outcome.bookId, floor, alreadyInLibrary = false)
                is ImportOutcome.Duplicate ->
                    TranslationJobOutcome.Completed(outcome.bookId, floor, alreadyInLibrary = true)
                is ImportOutcome.Failed ->
                    TranslationJobOutcome.Failed(reason = outcome.reason, retryable = true)
            }
        } finally {
            // BookImporter copies the bytes into its own directory, so keeping this scratch
            // copy would double every translated book's footprint on a tablet.
            output.delete()
        }
    }
}

/** Provider failures worth waiting out rather than surfacing as a dead end. */
private val LlmError.Kind.isTransient: Boolean
    get() = this == LlmError.Kind.NETWORK || this == LlmError.Kind.RATE_LIMIT
