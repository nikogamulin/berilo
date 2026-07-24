package app.berilo.reader.store.importer

import java.io.File

/** Metadata read out of an EPUB during import. */
data class ExtractedMetadata(
    val title: String,
    val authors: List<String>,
    val coverBytes: ByteArray?,
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
}
