package app.berilo.reader.store.importer

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private const val COVER_JPEG_QUALITY = 90

/**
 * Reads title/authors/cover out of an EPUB via the Readium Streamer.
 *
 * Needs a real Android [Context] and the Readium runtime, so it is exercised
 * by device/instrumented testing (S2.1 Verify residual), not the JVM suite —
 * [BookImporter]'s bookkeeping is unit-tested against [FakeMetadataExtractor]
 * (test sources) instead.
 */
class ReadiumMetadataExtractor(context: Context) : BookMetadataExtractor {

    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationOpener =
        PublicationOpener(
            DefaultPublicationParser(
                context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null,
            ),
        )

    override suspend fun extract(file: File, fallbackTitle: String): ExtractedMetadata =
        withContext(Dispatchers.IO) {
            val asset =
                assetRetriever.retrieve(file).getOrElse { error ->
                    throw IOException("Could not open EPUB asset: $error")
                }
            val publication =
                publicationOpener.open(asset, allowUserInteraction = false).getOrElse { error ->
                    throw IOException("Could not parse EPUB: $error")
                }
            try {
                val title = BookMetadataMapper.mapTitle(publication.metadata.title, fallbackTitle)
                val authors = BookMetadataMapper.mapAuthors(publication.metadata.authors.map { it.name })
                val coverBytes = publication.cover()?.let(::encodeCoverJpeg)
                ExtractedMetadata(
                    title = title,
                    authors = authors,
                    coverBytes = coverBytes,
                    language = BookMetadataMapper.mapLanguage(publication.metadata.languages),
                )
            } finally {
                publication.close()
            }
        }

    private fun encodeCoverJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, stream)
            stream.toByteArray()
        }
}
