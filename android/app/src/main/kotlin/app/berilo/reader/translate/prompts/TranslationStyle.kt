package app.berilo.reader.translate.prompts

import app.berilo.reader.translate.model.pythonStrip
import java.security.MessageDigest

/**
 * One named, versioned translation prompt contract, ported byte-exactly from
 * `translator/berilo/prompts.py`.
 *
 * Translation quality is a property of the *prompt*: every style carries an explicit [version]
 * string that the translation cache keys on, so a prompt change correctly *misses* the cache
 * instead of silently serving text produced by different wording. [promptDigest] pins that text
 * per style — `PromptTextTest` compares it against the same digest `tests/test_prompts.py` pins,
 * so editing a style without bumping its version fails loudly on both sides.
 *
 * **Styles are bound to target languages** (review finding 4; A3). A style whose prompts name a
 * specific language declares it in [targetLangs]; [resolveStyle] picks the right style for a
 * target language and execution context, and [ensureSupports] refuses an explicit style that
 * contradicts the target instead of billing a two-pass run whose editor pass argues with its own
 * translation pass. [targetLangs] never reaches the model, so it is deliberately outside
 * [promptDigest] and [version]: binding an existing style to a language does not change its cache
 * identity.
 *
 * @property name Registry key, e.g. `"sl_style_v1"`.
 * @property version Version string written into the translation cache key. Bump it whenever any
 *   prompt text below changes, or cached translations produced by the old text are served under
 *   the new style.
 * @property description One-line summary of the hypothesis this style encodes.
 * @property batchSystem System prompt for the numbered batch completion.
 * @property strictSystem System prompt for the strict retry after a 1:1 mismatch.
 * @property singleSystem System prompt for the per-segment fallback path, so fallback segments
 *   obey the same contract as batched ones.
 * @property bookContextSystem System prompt for a once-per-book style memo injected into every
 *   batch prompt, or `null` for no such pass.
 * @property reviseSystem System prompt for a second native-editor pass over each translated
 *   batch, or `null` for no revision pass.
 * @property targetLangs Primary language subtags this style's prompts are written for, or `null`
 *   when the prompts name no language and the style therefore covers any target. **Not part of
 *   the style's identity** — see the class docstring.
 */
data class TranslationStyle(
    val name: String,
    val version: String,
    val description: String,
    val batchSystem: String,
    val strictSystem: String,
    val singleSystem: String,
    val bookContextSystem: String? = null,
    val reviseSystem: String? = null,
    val targetLangs: List<String>? = null,
) {
    /**
     * Return whether this style's prompts are written for [targetLang].
     *
     * @param targetLang Target language tag as the caller supplied it; reduced by [normalizeLang]
     *   before matching.
     * @return `true` for a language-agnostic style, or when the tag's primary subtag is one of
     *   [targetLangs].
     */
    fun supports(targetLang: String): Boolean {
        val declared = targetLangs ?: return true
        return normalizeLang(targetLang) in declared
    }

    /** Strict retry prompt for the revision pass, or `null` if this style has no such pass. */
    val reviseStrictSystem: String?
        get() = reviseSystem?.let { it + STRICT_MARKER_CLAUSE }

    /**
     * Short digest over every prompt string this style carries.
     *
     * Pinned per style against `tests/test_prompts.py`'s pinned digests in `PromptTextTest`: a
     * text edit without a version bump changes the digest and fails that comparison.
     */
    val promptDigest: String
        get() {
            val payload =
                listOf(
                    batchSystem,
                    strictSystem,
                    singleSystem,
                    bookContextSystem ?: "",
                    reviseSystem ?: "",
                ).joinToString(DIGEST_SEPARATOR)
            return sha1Hex(payload).take(PROMPT_DIGEST_LENGTH)
        }
}

/** Separator between the five prompt strings hashed into [TranslationStyle.promptDigest]. */
private const val DIGEST_SEPARATOR = "\n\u0000\n"

/** Number of leading hex characters of the sha1 digest kept as [TranslationStyle.promptDigest]. */
private const val PROMPT_DIGEST_LENGTH = 16

private const val SHA1 = "SHA-1"

/** Hex-encode the sha1 digest of a string's UTF-8 bytes, mirroring `Identity.kt`'s helper. */
private fun sha1Hex(payload: String): String {
    val digest = MessageDigest.getInstance(SHA1).digest(payload.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        digest.forEach { byte -> append("%02x".format(byte)) }
    }
}

/**
 * A translation style was asked to run against a language it does not cover.
 *
 * Raised instead of letting a Slovenian-specific system prompt drive a run whose target is, say,
 * `"de"` — contradictory instructions billed at the two-pass rate (review finding 4).
 */
class StyleLanguageError(message: String) : IllegalArgumentException(message)

/** Separators that introduce a region/script subtag in a BCP-47-ish language tag. */
private val LANG_SUBTAG_SEPARATORS = listOf("-", "_")

/**
 * Reduce a target-language tag to the primary subtag used for matching.
 *
 * The rule, in full — this app exposes target language as a free-text field, so it is stated
 * here rather than spread across callers, and it must match `berilo.prompts.normalize_lang`
 * exactly: strip surrounding whitespace, lowercase, then keep everything before the first `-` or
 * `_`. Nothing else is interpreted — no ISO 639-2 → 639-1 mapping, no language-name lookup. A tag
 * that does not reduce to a declared code simply matches no language-bound style and resolves to
 * the language-agnostic one, which is a correct translation contract rather than a failure.
 *
 * Uses [pythonStrip], not [String.trim], for the same reason `Identity.kt` does: Python's
 * `str.strip()` removes one code point (U+0085 NEXT LINE) that Kotlin's whitespace predicates do
 * not, and a language tag is exactly the kind of value that could arrive with stray whitespace
 * from OPF metadata.
 *
 * @param targetLang Target language tag as the caller supplied it.
 * @return The lowercase primary subtag, e.g. `"sl"` for `" sl-SI "`.
 */
fun normalizeLang(targetLang: String): String {
    var tag = targetLang.pythonStrip().lowercase()
    for (separator in LANG_SUBTAG_SEPARATORS) {
        tag = tag.substringBefore(separator)
    }
    return tag
}
