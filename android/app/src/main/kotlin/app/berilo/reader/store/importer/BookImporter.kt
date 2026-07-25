package app.berilo.reader.store.importer

import app.berilo.reader.store.db.BookDao
import app.berilo.reader.store.db.BookEntity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SHA_256 = "SHA-256"
private const val EPUB_EXTENSION = ".epub"
private const val JPEG_EXTENSION = ".jpg"
private const val COULD_NOT_OPEN_MESSAGE = "Could not open the selected file"

/** Outcome of a single [BookImporter.import] call. */
sealed interface ImportOutcome {
    data class Imported(val bookId: String) : ImportOutcome

    data class Duplicate(val bookId: String) : ImportOutcome

    data class Failed(val reason: String) : ImportOutcome
}

/**
 * Copies an EPUB picked via SAF into app-private storage, hashes it for
 * dedupe, extracts metadata/cover, and persists a [BookEntity].
 *
 * Content-hash dedupe: the SHA-256 of the file bytes is the [BookEntity.id].
 * Importing the same file twice (same bytes) is a no-op — the second import
 * is reported as [ImportOutcome.Duplicate] and its temp copy is discarded,
 * never touching the DB or on-disk library.
 *
 * @param ioDispatcher Dispatcher the file/DB work runs on. Overridable so
 *   tests can pass a `TestDispatcher` and stay on virtual time instead of a
 *   real background thread pool.
 */
class BookImporter(
    private val bookDao: BookDao,
    private val metadataExtractor: BookMetadataExtractor,
    private val booksDir: File,
    private val coversDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {
    suspend fun import(openStream: () -> InputStream?, suggestedFileName: String): ImportOutcome =
        withContext(ioDispatcher) {
            booksDir.mkdirs()
            val tempFile = File.createTempFile("import-", EPUB_EXTENSION, booksDir)
            var finalFile: File? = null
            try {
                // The stream is opened AND closed here, on the IO dispatcher.
                // Callers must hand over a factory rather than an open stream:
                // this import outlives the SAF picker callback, so a caller-side
                // `use` block would close the stream before the first read.
                val hash =
                    (openStream() ?: throw IOException(COULD_NOT_OPEN_MESSAGE)).use { input ->
                        copyAndHash(input, tempFile)
                    }
                if (bookDao.exists(hash)) {
                    tempFile.delete()
                    return@withContext ImportOutcome.Duplicate(hash)
                }

                finalFile = File(booksDir, "$hash$EPUB_EXTENSION")
                moveInto(tempFile, finalFile)

                val fallbackTitle = suggestedFileName.removeSuffix(EPUB_EXTENSION).ifBlank { hash }
                val metadata = metadataExtractor.extract(finalFile, fallbackTitle)
                val coverPath = metadata.coverBytes?.let { writeCover(hash, it) }

                bookDao.insert(
                    BookEntity(
                        id = hash,
                        title = metadata.title,
                        authors = metadata.authors.joinToString(", "),
                        filePath = finalFile.absolutePath,
                        coverPath = coverPath,
                        addedAt = clock(),
                        lastOpenedAt = null,
                        progressionJson = null,
                    ),
                )
                ImportOutcome.Imported(hash)
            } catch (e: IOException) {
                // Nothing half-imported survives a failure: no orphan file
                // without a DB row, whether it died before or after the
                // temp-to-final rename.
                tempFile.delete()
                finalFile?.delete()
                ImportOutcome.Failed(e.message ?: "Could not import EPUB")
            }
        }

    private fun copyAndHash(input: InputStream, destination: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        destination.outputStream().use { out ->
            DigestOutputStream(out, digest).use { digestOut -> input.copyTo(digestOut) }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun moveInto(source: File, destination: File) {
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun writeCover(hash: String, bytes: ByteArray): String {
        coversDir.mkdirs()
        val coverFile = File(coversDir, "$hash$JPEG_EXTENSION")
        coverFile.writeBytes(bytes)
        return coverFile.absolutePath
    }
}
