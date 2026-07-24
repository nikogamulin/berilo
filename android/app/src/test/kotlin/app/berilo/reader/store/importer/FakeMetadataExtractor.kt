package app.berilo.reader.store.importer

import java.io.File
import java.io.IOException

/** Deterministic stand-in for [ReadiumMetadataExtractor], which needs the real Readium+Android runtime. */
class FakeMetadataExtractor : BookMetadataExtractor {
    var shouldFail: Boolean = false
    var authors: List<String> = listOf("Fake Author")
    var coverBytes: ByteArray? = null

    override suspend fun extract(file: File, fallbackTitle: String): ExtractedMetadata {
        if (shouldFail) throw IOException("simulated extraction failure")
        return ExtractedMetadata(title = fallbackTitle, authors = authors, coverBytes = coverBytes)
    }
}
