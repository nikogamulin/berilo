package app.berilo.reader.translate.prompts

/**
 * Versioned translation-style registry and language-bound resolution table, ported byte-exactly
 * from `translator/berilo/prompts.py` (review finding 4; A3; plan
 * `docs/plans/2026-07-26-ondevice-translation.md` §3.3 + §6).
 *
 * Translation quality is a property of the *prompt*. Every style carries an explicit
 * [TranslationStyle.version] string that the translation cache keys on, so switching styles
 * correctly *misses* the cache instead of silently serving text produced by different wording.
 *
 * **The prompt strings below must stay byte-identical to Python's.** `PromptTextTest` asserts
 * that against committed vectors generated from the real `prompts.py` module — not by reading
 * this file, by diffing it — and pins [TranslationStyle.promptDigest] per style against the same
 * digests `tests/test_prompts.py` pins. A stray whitespace difference here means the tablet and
 * the workstation produce different translations under the same version string.
 *
 * **Styles are bound to target languages** (review finding 4). [resolveStyle] picks the style for
 * a `(target language, execution context)` pair, honouring an explicitly requested style only
 * after [ensureSupports] — a mismatch is a loud, actionable refusal, never a silent contradiction.
 * Execution context sets the *default* tier only ([ExecutionContext.WORKSTATION] →
 * [StyleTier.QUALITY], [ExecutionContext.DEVICE] → [StyleTier.ECONOMY]); the app's "higher
 * quality, ~2x cost" toggle overrides the tier explicitly and must never falsify the context to
 * reach the other style.
 */

// --------------------------------------------------------------------------------------------
// Baseline prompt text (pre-registry, must not drift — see PromptTextTest).
// --------------------------------------------------------------------------------------------

private const val BASELINE_BATCH_SYSTEM =
    "You are a professional literary translator. Translate the MEANING, not the words: preserve register and tone, render idioms natively, and keep terminology consistent with the glossary. Preserve any inline HTML tags exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each source segment is prefixed with a marker like [[1]]. Return EVERY segment, each prefixed with its EXACT same marker, in the SAME order, and translate nothing else. Do not merge, split, add, or drop segments. Output only the marked translations."

private const val BASELINE_SINGLE_SYSTEM =
    "You are a professional literary translator. Translate the MEANING, not the words, preserving register, idioms, and any inline HTML tags exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Reply with ONLY the translation, no markers, no commentary."

/**
 * Appended to a batch system prompt when the previous attempt did not return a 1:1 marker
 * mapping. Kept separate so every style gets the same strict retry.
 */
const val STRICT_MARKER_CLAUSE: String =
    " CRITICAL: the previous attempt did not return exactly one [[n]] marker per source segment. Return EXACTLY the same markers you were given — no more, no fewer — each on its own line followed by that segment's translation."

// --------------------------------------------------------------------------------------------
// Slovenian style contract (docs/plans/2026-07-25-fluency-uplift.md §4.1).
// --------------------------------------------------------------------------------------------

private const val SL_CONTRACT =
    "SLOVENIAN STYLE CONTRACT — the result must read as prose written in Slovenian, not prose translated into it:\n- Do not calque English word order. Reorder to natural Slovenian information structure (known before new) and keep the clitic cluster (se, si, ga, je, bi, mu) in second position.\n- Prefer verbs to nominalizations: 'ko so preiskali' rather than 'po izvedbi preiskave'.\n- Drop subject pronouns that the verb ending already carries; keep one only for contrast or emphasis.\n- Avoid passive calques. NEVER render the English agentive 'by' as 's strani'; use an active clause, a reflexive passive ('se je zgodilo'), or an impersonal third-person plural.\n- Use the dual wherever exactly two entities are meant (dva/dve, sta, jima, njiju).\n- Write full šumniki (č, š, ž) and correct case endings; decline personal names, place names and borrowed terms as Slovenian requires.\n- Render idioms with their Slovenian equivalents, never word for word; where no equivalent exists, state the sense plainly.\n- Keep the source's paragraph breaks and roughly its sentence rhythm, but split an English sentence when Slovenian syntax would otherwise strain."

private const val BOOK_CONTEXT_SYSTEM =
    "You brief a literary translator before they begin a book. From the title, author and opening excerpt, write ONE paragraph of at most 90 words that states the genre, the register (formal, colloquial, academic, journalistic), the narrator's voice and stance, and the two or three stylistic habits the translator must reproduce (sentence length, irony, technical density, use of jargon). Write it as instructions to the translator, in English. No preamble, no bullet points, no quoting of the excerpt."

private const val REVISE_SYSTEM_TEXT =
    "You are a native Slovenian editor revising a draft translation. Fix fluency only: calqued English word order, nominalizations that should be verbs, redundant subject pronouns, 's strani' and other passive calques, missing dual, missing or wrong šumniki, wrong case endings, and idioms rendered literally. You must NOT change the meaning, add or remove information, or alter names, numbers, or terminology fixed by the glossary. Preserve inline HTML tags exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each segment is prefixed with a marker like [[1]] followed by its SOURCE and its DRAFT. Return EVERY segment, each prefixed with its EXACT same marker, in the SAME order, containing only the revised translation. Do not merge, split, add, or drop segments. If a draft already reads naturally, return it unchanged. Output only the marked segments."

private val REVISE_SYSTEM: String = REVISE_SYSTEM_TEXT + "\n\n" + SL_CONTRACT

// --------------------------------------------------------------------------------------------
// Language-agnostic editor pass (review finding 4): the same two-pass structure as
// REVISE_SYSTEM with every Slovenian-specific rule replaced by its language-neutral statement, so
// a target other than Slovenian gets the measured benefit of a second pass without contradictory
// instructions.
// --------------------------------------------------------------------------------------------

private const val GENERIC_REVISE_SYSTEM =
    "You are a native editor of the target language revising a draft translation. Fix fluency only: word order calqued from the source, nominalizations that the target language would express as verbs, pronouns and articles the target language's morphology makes redundant, passive constructions the target language would render actively, wrong inflection, agreement or case, missing or wrong diacritics, and idioms rendered word for word instead of by their native equivalent. The result must read as prose written in the target language, not prose translated into it. You must NOT change the meaning, add or remove information, or alter names, numbers, or terminology fixed by the glossary. Preserve inline HTML tags exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each segment is prefixed with a marker like [[1]] followed by its SOURCE and its DRAFT. Return EVERY segment, each prefixed with its EXACT same marker, in the SAME order, containing only the revised translation. Do not merge, split, add, or drop segments. If a draft already reads naturally, return it unchanged. Output only the marked segments."

private val SL_BATCH: String = BASELINE_BATCH_SYSTEM + "\n\n" + SL_CONTRACT
private val SL_STRICT: String = SL_BATCH + STRICT_MARKER_CLAUSE
private val SL_SINGLE: String = BASELINE_SINGLE_SYSTEM + "\n\n" + SL_CONTRACT

/** Primary subtag of the only language any shipped style is written for. */
const val SLOVENIAN: String = "sl"

/**
 * The pre-registry prompts, byte-identical. Default for every entry point, so existing caches
 * stay valid and today's output is unchanged.
 */
val BASELINE: TranslationStyle =
    TranslationStyle(
        name = "baseline_v1",
        version = "baseline_v1",
        description = "Pre-registry prompts: generic literary-translation instruction.",
        batchSystem = BASELINE_BATCH_SYSTEM,
        strictSystem = BASELINE_BATCH_SYSTEM + STRICT_MARKER_CLAUSE,
        singleSystem = BASELINE_SINGLE_SYSTEM,
    )

val SL_STYLE: TranslationStyle =
    TranslationStyle(
        name = "sl_style_v1",
        version = "sl_style_v1",
        description = "Baseline plus an explicit Slovenian style contract.",
        batchSystem = SL_BATCH,
        strictSystem = SL_STRICT,
        singleSystem = SL_SINGLE,
        targetLangs = listOf(SLOVENIAN),
    )

val BOOK_CONTEXT: TranslationStyle =
    TranslationStyle(
        name = "book_context_v1",
        version = "book_context_v1",
        description = "Slovenian contract plus a per-book style memo in every prompt.",
        batchSystem = SL_BATCH,
        strictSystem = SL_STRICT,
        singleSystem = SL_SINGLE,
        bookContextSystem = BOOK_CONTEXT_SYSTEM,
        targetLangs = listOf(SLOVENIAN),
    )

val REVISE: TranslationStyle =
    TranslationStyle(
        name = "revise_v1",
        version = "revise_v1",
        description = "Slovenian contract plus a native-editor revision pass per batch.",
        batchSystem = SL_BATCH,
        strictSystem = SL_STRICT,
        singleSystem = SL_SINGLE,
        reviseSystem = REVISE_SYSTEM,
        targetLangs = listOf(SLOVENIAN),
    )

/**
 * Two-pass structure for every target Berilo has no language contract for. Carries the baseline
 * (language-agnostic) translation prompts plus a language-neutral editor pass, so a non-Slovenian
 * target gets the measured benefit of a second pass without a Slovenian editor arguing with a
 * draft in another language.
 */
val REVISE_GENERIC: TranslationStyle =
    TranslationStyle(
        name = "revise_generic_v1",
        version = "revise_generic_v1",
        description = "Baseline prompts plus a language-neutral native-editor revision pass.",
        batchSystem = BASELINE_BATCH_SYSTEM,
        strictSystem = BASELINE_BATCH_SYSTEM + STRICT_MARKER_CLAUSE,
        singleSystem = BASELINE_SINGLE_SYSTEM,
        reviseSystem = GENERIC_REVISE_SYSTEM,
    )

private val STYLES: LinkedHashMap<String, TranslationStyle> =
    linkedMapOf(
        BASELINE.name to BASELINE,
        SL_STYLE.name to SL_STYLE,
        BOOK_CONTEXT.name to BOOK_CONTEXT,
        REVISE.name to REVISE,
        REVISE_GENERIC.name to REVISE_GENERIC,
    )

/** Version string the cache uses for rows written before the registry existed. */
val BASELINE_VERSION: String = BASELINE.version

// Default style, set by the E2 bake-off on 2026-07-25 (Kaplan, 6 contiguous runs x 10 segments,
// cluster-bootstrapped, seed 42; see loops/build/ledger.jsonl). Against baseline_v1, revise_v1
// won BOTH judged dimensions with CI lower bounds above zero -- T3 fluency +0.48 [+0.17, +0.87]
// and T2 meaning +0.23 [+0.07, +0.45] -- while sl_style_v1 (the same Slovenian contract WITHOUT
// the revision pass) was null on fluency (+0.05 [-0.10, +0.22]) and hinted at a meaning
// regression. The gain therefore comes from the second editing pass, not from the style contract.
/** Name of the default style (used as the workstation default). */
val DEFAULT: TranslationStyle = REVISE

/** Name of [DEFAULT]. */
val DEFAULT_STYLE_NAME: String = DEFAULT.name

// --------------------------------------------------------------------------------------------
// Execution context and tier (plan §3.4 + the §6 decision of 2026-07-26).
// --------------------------------------------------------------------------------------------

/**
 * Where a translation run executes, which sets its *default* style tier.
 *
 * The tablet is a materially different context from the workstation: a full book is a
 * multi-hour job on battery, so the quality tier is an explicit user choice there rather than a
 * silent default. **The Android app is always [DEVICE]** — B7's "higher quality, ~2x cost"
 * toggle is a [StyleTier] override passed alongside `context = DEVICE`, never a context lie.
 */
enum class ExecutionContext {
    WORKSTATION,
    DEVICE,
}

/**
 * How much the caller is willing to spend per segment.
 *
 * [StyleTier.ECONOMY] is one API call per batch; [StyleTier.QUALITY] adds the native-editor pass,
 * roughly doubling calls and cost for a measured Rubric T gain.
 */
enum class StyleTier {
    ECONOMY,
    QUALITY,
}

// --------------------------------------------------------------------------------------------
// Resolution table (plan §3.3 + the §6 decision of 2026-07-26).
// --------------------------------------------------------------------------------------------

/**
 * `(tier, primary language subtag) -> style`. `null` as the language key is the fallback row for
 * every target with no language-specific contract.
 *
 * The ECONOMY row is `baseline_v1` for Slovenian too, and that is a measured choice, not an
 * oversight: the E2 bake-off (2026-07-25, `loops/build/ledger.jsonl`) found `sl_style_v1` -- the
 * same Slovenian contract *without* the second pass -- null on fluency (+0.05 [-0.10, +0.22]) and
 * hinting at a meaning regression against `baseline_v1`. The gain lives in the editor pass, so a
 * single-pass run buys nothing by paying for the contract's extra prompt tokens.
 */
private val STYLE_TABLE: Map<Pair<StyleTier, String?>, TranslationStyle> =
    mapOf(
        (StyleTier.QUALITY to SLOVENIAN) to REVISE,
        (StyleTier.QUALITY to null) to REVISE_GENERIC,
        (StyleTier.ECONOMY to SLOVENIAN) to BASELINE,
        (StyleTier.ECONOMY to null) to BASELINE,
    )

/**
 * The tier each execution context defaults to. Resolved by Niko 2026-07-26: single-pass is the
 * on-device default with the quality tier an explicit, cost-labelled opt-in; the workstation
 * keeps the two-pass default.
 */
private val DEFAULT_TIER: Map<ExecutionContext, StyleTier> =
    mapOf(
        ExecutionContext.WORKSTATION to StyleTier.QUALITY,
        ExecutionContext.DEVICE to StyleTier.ECONOMY,
    )

/** Return the style tier [context] runs at unless the caller overrides it. */
fun defaultTier(context: ExecutionContext): StyleTier = DEFAULT_TIER.getValue(context)

/**
 * Refuse loudly when [style] is not written for [targetLang].
 *
 * @param style The style about to be run.
 * @param targetLang Target language tag the run will translate into.
 * @throws StyleLanguageError If [style] declares target languages and [targetLang] is not one of
 *   them. The message names the style, its declared languages, the requested target, and the
 *   style that *would* have resolved, so the refusal is actionable rather than merely loud.
 */
fun ensureSupports(style: TranslationStyle, targetLang: String) {
    if (style.supports(targetLang)) return
    val declared = style.targetLangs?.joinToString(", ") ?: ""
    val suggestionTier = if (style.reviseSystem != null) StyleTier.QUALITY else StyleTier.ECONOMY
    val suggestion = STYLE_TABLE.getValue(suggestionTier to null)
    throw StyleLanguageError(
        "translation style '${style.name}' is written for $declared only and cannot " +
            "translate into '$targetLang': its prompts would instruct the model in one " +
            "language while the request asks for another. Use style '${suggestion.name}' " +
            "instead, or resolve automatically by leaving the style unset.",
    )
}

/**
 * Resolve the translation style for a target language and execution context.
 *
 * The rule, in full:
 * 1. An explicitly [requested] style is honoured, but only after [ensureSupports] -- a mismatch
 *    is a refusal, never a silent contradiction.
 * 2. Otherwise the tier is [tier] if given, else [defaultTier] for [context] (workstation ->
 *    QUALITY, device -> ECONOMY).
 * 3. The style is the table's row for `(tier, normalizeLang(targetLang))` if that language has a
 *    row, else the table's language-agnostic row.
 *
 * @param targetLang Target language tag (`"sl"`, `"de"`, `"sl-SI"`...).
 * @param requested A style the caller named explicitly, or `null` to resolve.
 * @param context Where the run executes; sets the default tier. **Always [ExecutionContext.DEVICE]
 *   on Android.**
 * @param tier Explicit tier override (the app's "higher quality, ~2x cost" toggle), or `null` to
 *   take the context's default.
 * @return The resolved [TranslationStyle].
 * @throws StyleLanguageError If [requested] does not cover [targetLang].
 */
fun resolveStyle(
    targetLang: String,
    requested: TranslationStyle? = null,
    context: ExecutionContext = ExecutionContext.WORKSTATION,
    tier: StyleTier? = null,
): TranslationStyle {
    if (requested != null) {
        ensureSupports(requested, targetLang)
        return requested
    }
    val effectiveTier = tier ?: defaultTier(context)
    val lang = normalizeLang(targetLang)
    val key = if (STYLE_TABLE.containsKey(effectiveTier to lang)) effectiveTier to lang else effectiveTier to null
    return STYLE_TABLE.getValue(key)
}

/**
 * Look up a translation style by registry name.
 *
 * @param name Registry key, e.g. `"baseline_v1"`.
 * @return The registered [TranslationStyle].
 * @throws IllegalArgumentException If no style with that name is registered (message lists the
 *   available names, so a typo fails loudly rather than falling back to the default and silently
 *   changing quality).
 */
fun getStyle(name: String): TranslationStyle =
    STYLES[name] ?: throw IllegalArgumentException(
        "unknown translation style '$name'; available: ${styleNames()}",
    )

/** Return the registered style names in registration order. */
fun styleNames(): List<String> = STYLES.keys.toList()
