package app.berilo.reader.translate.engine

import android.util.Log
import app.berilo.reader.llm.LlmClient
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.Glossary
import app.berilo.reader.translate.model.pythonStrip
import app.berilo.reader.translate.model.sha1Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The per-book glossary pass, ported from `translator/berilo/glossary.py`.
 *
 * One LLM call samples the source text and extracts proper names, place names and recurring
 * domain terms into a `source term -> fixed target rendering` map, injected into every batch
 * prompt so terminology stays consistent across chunk boundaries.
 *
 * The pass is memoized under [glossaryPromptVersion], which is **derived** from the extraction
 * prompt and the sampling parameters rather than declared by hand: without that, improving the
 * extraction prompt to fix a mistranslated name would hit the unchanged key and return the old
 * terms at zero cost (CLAUDE.md §9).
 */

private const val TAG = "GlossaryBuilder"

/** How many chapters (evenly sampled across the book) to feed the extractor. */
const val DEFAULT_SAMPLE_CHAPTERS: Int = 6

/** Cap on source characters sent to the single extraction call, keeping it cheap on any book. */
const val DEFAULT_MAX_SAMPLE_CHARS: Int = 12_000

/** Upper bound on distinct glossary terms retained, keeping every batch prompt small. */
const val MAX_GLOSSARY_TERMS: Int = 80

/**
 * System prompt for the extraction call. Byte-identical to `glossary.py`'s `_GLOSSARY_SYSTEM`;
 * `GlossaryPromptVersionTest` pins the resulting version against the value Python derives.
 */
internal const val GLOSSARY_SYSTEM: String =
    "You build a translation glossary for a book. You are given sample " +
        "passages of the source text. Identify proper names (people, places, " +
        "organizations) and recurring domain-specific terms that MUST be rendered " +
        "consistently throughout the translation. For each, give the canonical " +
        "BASE form of the fixed rendering in the target language (do not inflect or " +
        "decline it). Personal and place names usually keep their original spelling " +
        "in Slovenian. Reply with ONLY a JSON object mapping each source term to its " +
        "fixed target rendering. No prose, no code fences."

/**
 * User-message template for the extraction call, with Python's `str.format` placeholders left
 * verbatim. It is a *named constant* because it participates in [glossaryPromptVersion]: every
 * string that reaches the model must be inside the version, or a prompt edit becomes a silent
 * no-op on the next run.
 */
internal const val GLOSSARY_PROMPT_TEMPLATE: String =
    "Target language: {target_lang}.\n\nSource passages:\n{sample}"

/** Prefix on every derived version, so the value is legible in a database dump. */
private const val GLOSSARY_VERSION_PREFIX = "glossary_"

/** Hex characters of the payload digest kept in the version string. */
private const val GLOSSARY_VERSION_DIGEST_CHARS = 12

/** Greedy `{...}` scan, mirroring Python's `re.compile(r"\{.*\}", re.DOTALL)`. */
private val JSON_OBJECT_RE = Regex("""\{.*}""", RegexOption.DOT_MATCHES_ALL)

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Return the cache identity of the glossary-extraction pass.
 *
 * Derived from every input that shapes the extracted terms — the system prompt, the
 * user-message template, and the sampling parameters actually used — so editing any of them
 * changes the key automatically. A hand-maintained version string is exactly what was forgotten
 * the first time this defect appeared (CLAUDE.md §9).
 *
 * @return A short, stable version string such as `"glossary_3512dce61808"`.
 */
fun glossaryPromptVersion(
    sampleChapters: Int = DEFAULT_SAMPLE_CHAPTERS,
    maxSampleChars: Int = DEFAULT_MAX_SAMPLE_CHARS,
    maxTerms: Int = MAX_GLOSSARY_TERMS,
): String {
    val payload =
        listOf(
            GLOSSARY_SYSTEM,
            GLOSSARY_PROMPT_TEMPLATE,
            "sample_chapters=$sampleChapters",
            "max_sample_chars=$maxSampleChars",
            "max_terms=$maxTerms",
        ).joinToString("\n")
    return GLOSSARY_VERSION_PREFIX + sha1Hex(payload).take(GLOSSARY_VERSION_DIGEST_CHARS)
}

/** Identity of the glossary pass under its default sampling parameters. */
val GLOSSARY_PROMPT_VERSION: String = glossaryPromptVersion()

/**
 * Take the first [maxCodePoints] Unicode **code points**, never splitting a surrogate pair.
 *
 * Python slices strings by code point; Kotlin's [String.take] slices by UTF-16 unit. On a book
 * containing astral characters a naive `take` would both cut at a different place than the CLI
 * does and be able to leave a lone surrogate in the prompt.
 */
private fun String.takeCodePoints(maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    if (length <= maxCodePoints) return this // fast path: no astral chars can be present
    val count = codePointCount(0, length)
    if (count <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}

/** Concatenate source text from chapters evenly sampled across the book, capped at [maxChars]. */
private fun sampleSourceText(book: Book, sampleChapters: Int, maxChars: Int): String {
    val chapterIndices = book.segments.map { it.chapterIndex }.distinct().sorted()
    if (chapterIndices.isEmpty()) return ""
    val chosen =
        if (chapterIndices.size <= sampleChapters) {
            chapterIndices.toSet()
        } else {
            val step = chapterIndices.size.toDouble() / sampleChapters
            (0 until sampleChapters).mapTo(mutableSetOf()) { chapterIndices[(it * step).toInt()] }
        }

    val parts = mutableListOf<String>()
    var used = 0
    for (segment in book.segments) {
        if (segment.chapterIndex !in chosen) continue
        val text = segment.text.pythonStrip()
        if (text.isEmpty()) continue
        parts.add(text)
        used += text.codePointCount(0, text.length)
        if (used >= maxChars) break
    }
    return parts.joinToString("\n").takeCodePoints(maxChars)
}

/**
 * Parse the extractor's JSON reply into a term map, tolerating stray prose.
 *
 * Mirrors `_parse_glossary_json`, including its quirk that the [MAX_GLOSSARY_TERMS] check runs
 * after each entry whether or not that entry was kept.
 *
 * @param raw The raw model reply.
 * @return The parsed map; empty if nothing parseable.
 */
internal fun parseGlossaryJson(raw: String): Map<String, String> {
    val match = JSON_OBJECT_RE.find(raw)
    if (match == null) {
        Log.w(TAG, "Glossary extraction returned no JSON object; using empty glossary.")
        return emptyMap()
    }
    val loaded =
        try {
            lenientJson.parseToJsonElement(match.value) as? JsonObject
        } catch (invalid: kotlinx.serialization.SerializationException) {
            Log.w(TAG, "Glossary extraction JSON did not parse; using empty glossary.")
            return emptyMap()
        } ?: return emptyMap()

    val terms = LinkedHashMap<String, String>()
    for ((source, target) in loaded) {
        // Python stringifies non-string values (`str(1)` -> "1"); JsonPrimitive.content does the
        // same for scalars. A nested object/array renders as JSON here where Python would render
        // a Python repr — a divergence with no practical reach, since the prompt demands a flat
        // string map and either form is discarded as a useless "term" downstream.
        val targetText = (target as? JsonPrimitive)?.content ?: target.toString()
        val sourceText = source.pythonStrip()
        if (sourceText.isNotEmpty() && targetText.pythonStrip().isNotEmpty()) {
            terms[sourceText] = targetText.pythonStrip()
        }
        if (terms.size >= MAX_GLOSSARY_TERMS) break
    }
    return terms
}

/**
 * Build (or load from cache) the fixed-term glossary for [book].
 *
 * Makes **at most one** LLM call. If a glossary for
 * `(book, model, lang, glossaryPromptVersion)` is already cached, no call is made — so a
 * resumed run never re-bills the extraction.
 *
 * @param book The source book to extract terms from.
 * @param client LLM client used for the single extraction call.
 * @param targetLang Target language code (e.g. `"sl"`).
 * @param model Model identifier for cache keying.
 * @param cache Optional cache for memoization.
 * @param sampleChapters Number of chapters to sample for extraction.
 * @param maxSampleChars Character cap on the extraction prompt's source text.
 * @return The resolved [Glossary] (possibly empty).
 */
suspend fun buildGlossary(
    book: Book,
    client: LlmClient,
    targetLang: String,
    model: String,
    cache: TranslationCache? = null,
    sampleChapters: Int = DEFAULT_SAMPLE_CHAPTERS,
    maxSampleChars: Int = DEFAULT_MAX_SAMPLE_CHARS,
): Glossary {
    val bhash = app.berilo.reader.translate.model.bookHash(book)
    val promptVersion = glossaryPromptVersion(sampleChapters, maxSampleChars)

    cache?.getGlossary(bhash, model, targetLang, promptVersion)?.let { cached ->
        Log.i(TAG, "Glossary cache hit (${cached.size} terms, $promptVersion).")
        return Glossary(terms = cached)
    }

    val sample = sampleSourceText(book, sampleChapters, maxSampleChars)
    if (sample.pythonStrip().isEmpty()) return Glossary()

    val prompt =
        GLOSSARY_PROMPT_TEMPLATE
            .replace("{target_lang}", targetLang)
            .replace("{sample}", sample)
    val result = client.complete(prompt = prompt, system = GLOSSARY_SYSTEM)
    val terms = parseGlossaryJson(result.text)
    Log.i(TAG, "Glossary extracted: ${terms.size} terms.")

    cache?.storeGlossary(
        bookHash = bhash,
        model = model,
        lang = targetLang,
        terms = terms,
        call = CallRecord(
            kind = CALL_KIND_GLOSSARY,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            costEur = result.costEur,
        ),
        promptVersion = promptVersion,
    )
    return Glossary(terms = terms)
}
