package app.berilo.reader.store.importer

import java.io.File

/**
 * Metadata read out of an EPUB during import.
 *
 * @property language BCP-47 primary subtag from the EPUB's `dc:language` (e.g. `"sl"`), or
 *   null if it declared none. S3.2 ([OPEN-2] in `docs/sync_api.md`): this is the language the
 *   book is *in*, which for translator output is the target language. A source language is
 *   deliberately not guessed here — an EPUB carries no record of what it was translated from,
 *   and inventing one would put a fabricated value on the public language-pair badge.
 */
data class ExtractedMetadata(
    val title: String,
    val authors: List<String>,
    val coverBytes: ByteArray?,
    val language: String? = null,
)

/**
 * Reads title/authors/cover out of an EPUB file.
 *
 * Kept as an interface so import bookkeeping ([app.berilo.reader.store.importer.BookImporter])
 * is unit-testable on the host JVM with a fake, independent of the real
 * Readium Streamer + Android runtime the production implementation needs.
 */
interface BookMetadataExtractor {
    suspend fun extract(file: File, fallbackTitle: String): ExtractedMetadata
}

/**
 * Builds the display title and author list the library shows, given raw
 * values read from EPUB metadata. Pure function: no Readium/Android types,
 * so it is unit-testable directly.
 */
object BookMetadataMapper {
    private const val UNKNOWN_AUTHOR = "Unknown"

    fun mapTitle(rawTitle: String?, fallbackFileName: String): String =
        rawTitle?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackFileName

    fun mapAuthors(rawAuthors: List<String>): List<String> {
        val cleaned = rawAuthors.map { it.trim() }.filter { it.isNotEmpty() }
        return cleaned.ifEmpty { listOf(UNKNOWN_AUTHOR) }
    }

    /**
     * Reduces EPUB `dc:language` values to a single BCP-47 primary subtag, or null.
     *
     * `dc:language` is repeatable and regularly regional (`sl-SI`, `en-GB`); the sync contract
     * constrains `source_lang`/`target_lang` to `^[a-z]{2,3}$` (`docs/sync_api.md` §2), so a
     * regional tag would be rejected server-side. The first declared language wins, since EPUB
     * orders them by prominence.
     */
    fun mapLanguage(rawLanguages: List<String>): String? =
        rawLanguages
            .asSequence()
            .map { it.trim().substringBefore('-').lowercase(java.util.Locale.ROOT) }
            .firstOrNull { it.length in 2..3 && it.all(Char::isLetter) }
}
