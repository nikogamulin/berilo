package app.berilo.reader.annotations

import app.berilo.reader.store.db.TranslationCacheDao
import app.berilo.reader.store.db.TranslationEntity

/**
 * Shortest selection worth attempting a containment match on.
 *
 * An exact match is unambiguous at any length, but "in" or "the sea" occurs in hundreds of
 * paragraphs, and the first row a containment scan happens to return for such a fragment would
 * be provenance in name only. Twenty-four characters is roughly a clause — long enough that a
 * collision across two unrelated segments is a curiosity rather than the expected case. Below
 * it the flag is stored with no provenance, which is the honest outcome.
 */
private const val MIN_CONTAINMENT_LENGTH = 24

/** SQL `LIKE` wildcards, plus the escape character itself. */
private const val LIKE_SPECIAL_CHARACTERS = "\\%_"

/** Escape character declared by the `ESCAPE` clause in [TranslationCacheDao.findTranslationMatching]. */
private const val LIKE_ESCAPE_CHARACTER = '\\'

/**
 * Builds a `LIKE` pattern matching any text that contains [text] literally.
 *
 * Internal rather than private so [TranslationProvenanceResolver]'s escaping can be asserted
 * directly — a `%` in a flagged passage is rare enough that a query-level test would not
 * reliably notice it going wrong.
 *
 * @param text Literal text to search for.
 * @return A pattern for use with `LIKE ... ESCAPE '\'`.
 */
internal fun likeContainsPattern(text: String): String =
    buildString(text.length + 2) {
        append('%')
        for (character in text) {
            if (character in LIKE_SPECIAL_CHARACTERS) append(LIKE_ESCAPE_CHARACTER)
            append(character)
        }
        append('%')
    }

/**
 * Recovers which cached translation produced a flagged passage.
 *
 * An interface for the same reason `TranslationCache` is one: the only production implementation
 * reads Room, and a repository test that wants to assert "the flag stored regardless" should not
 * have to stand up a translation cache to say so.
 */
interface ProvenanceResolver {
    /**
     * @param selectedText Translated text as the reader selected it.
     * @return The matched translation-cache key, or null when nothing matched.
     */
    suspend fun resolve(selectedText: String): TranslationProvenance?
}

/** A resolver that never matches — the honest stand-in wherever provenance is out of scope. */
object NoProvenance : ProvenanceResolver {
    override suspend fun resolve(selectedText: String): TranslationProvenance? = null
}

/**
 * Recovers which cached translation produced a flagged passage (B9).
 *
 * There is no key to join on. The library stores a translated book under the sha256 of its
 * EPUB; the translation cache stores segments under `bookHash`, a hash of the *source* book's
 * segment ids, and the two are computed from different documents (`docs/findings.md`: segment
 * ids necessarily change across a translate round trip). So the only available link is the
 * translated text itself, which is byte-identical in both places — the assembler writes exactly
 * what the cache stored.
 *
 * Two steps, in order:
 * 1. **Exact.** The user selected a whole segment; its trimmed text equals a cached row's.
 * 2. **Containment.** The far commoner case — a sentence or clause inside a paragraph — matched
 *    against the segment that contains it, guarded by [MIN_CONTAINMENT_LENGTH].
 *
 * Neither step can succeed for a selection spanning inline markup boundaries in a way that
 * changes the rendered characters, or for a book translated by the CLI on another machine whose
 * cache rows never reached this device. Both are ordinary misses: [resolve] returns null and
 * the flag is stored without provenance.
 *
 * @param dao The translation cache DAO.
 */
class TranslationProvenanceResolver(private val dao: TranslationCacheDao) : ProvenanceResolver {

    override suspend fun resolve(selectedText: String): TranslationProvenance? {
        val needle = selectedText.trim()
        if (needle.isEmpty()) return null
        dao.findTranslationByExactText(needle)?.let { return it.toProvenance() }
        if (needle.length < MIN_CONTAINMENT_LENGTH) return null
        return dao.findTranslationMatching(likeContainsPattern(needle))?.toProvenance()
    }
}

private fun TranslationEntity.toProvenance(): TranslationProvenance =
    TranslationProvenance(
        bookHash = bookHash,
        segmentHash = segmentHash,
        model = model,
        lang = lang,
        promptVersion = promptVersion,
        glossaryHash = glossaryHash,
    )
