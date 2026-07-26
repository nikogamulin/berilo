package app.berilo.reader.translate.job

import app.berilo.reader.translate.engine.CostEstimate
import app.berilo.reader.translate.prompts.StyleTier
import app.berilo.reader.translate.prompts.normalizeLang

/**
 * One tier's priced offer: the style [StyleTier] resolves to, and what it would cost.
 *
 * Both tiers are quoted together so the "higher quality, ~2x cost" choice is made against two
 * real EUR figures rather than an adjective (Niko, 2026-07-26).
 *
 * @property tier The tier this offer prices.
 * @property styleName Registry name of the style [tier] resolved to, e.g. `"baseline_v1"`.
 * @property styleVersion That style's cache-keying version string.
 * @property revising Whether the style adds the native-editor second pass.
 * @property estimate The dry-run estimate, produced with no API call.
 */
data class TierOffer(
    val tier: StyleTier,
    val styleName: String,
    val styleVersion: String,
    val revising: Boolean,
    val estimate: CostEstimate,
) {
    /** Estimated EUR for this tier. */
    val costEur: Double get() = estimate.costEur

    /** Estimated API calls for this tier: batches, plus editor and memo passes. */
    val apiCalls: Int
        get() = estimate.batches + estimate.revisionCalls + estimate.bookContextCalls
}

/**
 * The dry run: everything the user must see **before** a paid run becomes possible.
 *
 * CLAUDE.md §4 — "costs are visible and gated". Building one of these makes zero API calls;
 * [TranslationPlanner] is a pure function of the EPUB plus the pricing table. Nothing on this
 * object can start a run: the only path to spending is
 * [app.berilo.reader.ui.translate.TranslateViewModel.confirmAndTranslate].
 *
 * @property source The staged source EPUB.
 * @property bookTitle Title read from the EPUB.
 * @property sourceLang The book's own language tag, **raw as the OPF declared it** — `en-US`,
 *   `eng`, `EN-GB` all occur in the corpus. Never compare it without [normalizeLang].
 * @property targetLang Target language the user has configured.
 * @property model Model the estimate is priced against.
 * @property chapterCount Chapters the book normalizes to.
 * @property totalSegments Every segment, including the ones that will not be sent.
 * @property economy The single-pass offer — the device default.
 * @property quality The two-pass offer.
 * @property alreadyTargetLanguage Whether the book already declares the target language.
 */
data class TranslationPlan(
    val source: SourceBook,
    val bookTitle: String,
    val sourceLang: String,
    val targetLang: String,
    val model: String,
    val chapterCount: Int,
    val totalSegments: Int,
    val economy: TierOffer,
    val quality: TierOffer,
    val alreadyTargetLanguage: Boolean,
) {
    /** The offer for [tier]. */
    fun offer(tier: StyleTier): TierOffer = if (tier == StyleTier.QUALITY) quality else economy
}

/**
 * Whether [sourceLang] and [targetLang] name the same language.
 *
 * **Both sides go through [normalizeLang]** (B1a). `Book.language` is raw OPF soup across the
 * corpus — `en-US`, `en`, `eng`, `EN-GB` — so a raw string comparison would miss `EN-GB` against
 * `en`. Equally, `normalizeLang` performs no ISO 639-2 to 639-1 mapping, so `eng` does **not**
 * reduce to `en` and Sandworm is correctly reported as *not* already-Slovenian rather than
 * spuriously flagged. That asymmetry is why the result is surfaced as a notice the user can
 * translate straight past, never a refusal.
 *
 * @param sourceLang The book's own declared language tag.
 * @param targetLang The configured target language tag.
 */
fun isSameLanguage(sourceLang: String, targetLang: String): Boolean =
    normalizeLang(sourceLang) == normalizeLang(targetLang)
