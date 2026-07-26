package app.berilo.reader.translate.job

import app.berilo.reader.translate.engine.backMatterSegmentIds
import app.berilo.reader.translate.engine.estimateCost
import app.berilo.reader.translate.epub.EpubReader
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.prompts.ExecutionContext
import app.berilo.reader.translate.prompts.StyleTier
import app.berilo.reader.translate.prompts.TranslationStyle
import app.berilo.reader.translate.prompts.resolveStyle
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the dry-run [TranslationPlan] for a staged source EPUB.
 *
 * **This class cannot spend money.** It holds no [app.berilo.reader.llm.LlmClient] and takes no
 * factory for one — the type system, not a code comment, is what stops the estimate screen from
 * reaching a billed call (CLAUDE.md §4: no implicit start). Everything it needs comes from the
 * EPUB and the local pricing table.
 *
 * @param epubReader Reader used to normalize the source; injectable for tests.
 * @param ioDispatcher Dispatcher the zip/XML work runs on — parsing a full book is far too slow
 *   for the main thread.
 */
class TranslationPlanner(
    private val epubReader: EpubReader = EpubReader(),
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {

    /**
     * Price [source] for both tiers without calling any provider.
     *
     * @param source The staged source EPUB.
     * @param targetLang Target language tag.
     * @param model Model to price against; must be in the pricing table.
     * @return The plan, with an offer for each of [StyleTier.ECONOMY] and [StyleTier.QUALITY].
     * @throws app.berilo.reader.translate.epub.EpubParseException If the EPUB cannot be read.
     * @throws app.berilo.reader.llm.LlmError If [model] has no pricing entry — refused here, for
     *   free, rather than after a run has been billed.
     */
    suspend fun plan(source: SourceBook, targetLang: String, model: String): TranslationPlan =
        withContext(ioDispatcher) {
            val book = epubReader.read(source.file)
            planFor(book, source, targetLang, model)
        }

    /** [plan]'s pure half, over an already-normalized [book]. */
    internal fun planFor(
        book: Book,
        source: SourceBook,
        targetLang: String,
        model: String,
    ): TranslationPlan {
        val skipIds = backMatterSegmentIds(book)
        return TranslationPlan(
            source = source,
            bookTitle = book.title,
            sourceLang = book.language,
            targetLang = targetLang,
            model = model,
            chapterCount = book.chapterCount,
            totalSegments = book.segments.size,
            economy = offerFor(StyleTier.ECONOMY, book, targetLang, model, skipIds),
            quality = offerFor(StyleTier.QUALITY, book, targetLang, model, skipIds),
            alreadyTargetLanguage = isSameLanguage(book.language, targetLang),
        )
    }

    private fun offerFor(
        tier: StyleTier,
        book: Book,
        targetLang: String,
        model: String,
        skipIds: Set<String>,
    ): TierOffer {
        val style = styleFor(targetLang, tier)
        return TierOffer(
            tier = tier,
            styleName = style.name,
            styleVersion = style.version,
            revising = style.reviseSystem != null,
            estimate =
                estimateCost(
                    book = book,
                    model = model,
                    targetLang = targetLang,
                    skipSegmentIds = skipIds,
                    style = style,
                ),
        )
    }

    companion object {
        /**
         * Resolve the style one tier means on this device.
         *
         * `context` is **always** [ExecutionContext.DEVICE] and the quality toggle overrides the
         * *tier* (A3, and the KDoc on
         * [app.berilo.reader.translate.engine.translateBook]). Passing
         * `context = WORKSTATION` would obtain the same two-pass style by lying about where the
         * run happens — the estimate would then be priced for a machine on mains power, and any
         * future rule that keys on context (batch sizes, a retry budget, a cheaper fallback) would
         * silently take the workstation branch on a tablet running off battery. The tier override
         * says "the user chose to pay more"; a context lie says "this is not a tablet", and only
         * one of those is true.
         *
         * @param targetLang Target language tag.
         * @param tier Tier to resolve.
         */
        fun styleFor(targetLang: String, tier: StyleTier): TranslationStyle =
            resolveStyle(
                targetLang = targetLang,
                context = ExecutionContext.DEVICE,
                tier = tier,
            )
    }
}
