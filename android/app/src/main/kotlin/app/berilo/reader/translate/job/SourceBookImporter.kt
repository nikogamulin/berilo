package app.berilo.reader.translate.job

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
private const val COULD_NOT_OPEN_MESSAGE = "Could not open the selected file"
private const val DEFAULT_FAILURE_MESSAGE = "Could not read the EPUB"

/**
 * A source-language EPUB the user has handed over for translation.
 *
 * @property id SHA-256 of the file bytes — the same content-hash identity
 *   [app.berilo.reader.store.importer.BookImporter] uses, so re-picking the same file is
 *   recognised rather than re-copied.
 * @property file Where the bytes live in app-private storage.
 * @property displayName Name to show the user, derived from the picked document.
 */
data class SourceBook(
    val id: String,
    val file: File,
    val displayName: String,
)

/** Outcome of one [SourceBookImporter.import] call. */
sealed interface SourceImportOutcome {

    /** The file was copied in for the first time. */
    data class Imported(val source: SourceBook) : SourceImportOutcome

    /** These exact bytes were already staged — the existing copy is reused, nothing re-written. */
    data class AlreadyStaged(val source: SourceBook) : SourceImportOutcome

    /** The file could not be read; nothing was left on disk. */
    data class Failed(val reason: String) : SourceImportOutcome
}

/**
 * Stages a **source-language** EPUB for on-device translation (B7).
 *
 * Deliberately separate from [app.berilo.reader.store.importer.BookImporter], and deliberately
 * writing to a different directory: that importer's job is "this book is ready to read", and it
 * inserts a [app.berilo.reader.store.db.BookEntity] so the library shows it. A source book is
 * *not* ready to read — it is an untranslated input — so putting it in the library would show
 * the user an English book they never asked to shelve, and would make the translated output a
 * confusing near-duplicate. Source files live in their own directory with no DB row; the
 * library only ever gains the **translated** EPUB, imported through the normal path by
 * [BookTranslationJob].
 *
 * Everything else follows `BookImporter`'s discipline, for the same reasons documented there:
 * a stream **factory** rather than an open stream (the import outlives the SAF picker
 * callback, so a caller-side `use` block would close the stream before the first read), SHA-256
 * content dedupe, and a temp file that is deleted on any [IOException] so a failure never
 * leaves a half-written archive behind.
 *
 * @param sourcesDir Directory staged EPUBs are written to (`filesDir/sources`).
 * @param ioDispatcher Dispatcher the file work runs on. Overridable so tests can pass a
 *   `TestDispatcher` and stay on virtual time.
 */
class SourceBookImporter(
    private val sourcesDir: File,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {

    /**
     * Copy the picked document into [sourcesDir], keyed by its content hash.
     *
     * @param openStream Factory that opens the picked document; `null` means it could not be
     *   opened. **Not an already-open stream** — see the class docstring.
     * @param suggestedFileName Display name from the picker, used for the UI label only.
     * @return [SourceImportOutcome.Imported] on a first copy, [SourceImportOutcome.AlreadyStaged]
     *   when these bytes are already present, or [SourceImportOutcome.Failed] with nothing left
     *   on disk.
     */
    suspend fun import(openStream: () -> InputStream?, suggestedFileName: String): SourceImportOutcome =
        withContext(ioDispatcher) {
            sourcesDir.mkdirs()
            val tempFile = File.createTempFile("source-", EPUB_EXTENSION, sourcesDir)
            var finalFile: File? = null
            try {
                // Opened AND closed here, on the IO dispatcher — see the class docstring.
                val hash =
                    (openStream() ?: throw IOException(COULD_NOT_OPEN_MESSAGE)).use { input ->
                        copyAndHash(input, tempFile)
                    }
                val displayName = displayNameFor(suggestedFileName, hash)
                val staged = File(sourcesDir, "$hash$EPUB_EXTENSION")
                if (staged.exists()) {
                    tempFile.delete()
                    return@withContext SourceImportOutcome.AlreadyStaged(
                        SourceBook(id = hash, file = staged, displayName = displayName),
                    )
                }
                finalFile = staged
                moveInto(tempFile, staged)
                SourceImportOutcome.Imported(
                    SourceBook(id = hash, file = staged, displayName = displayName),
                )
            } catch (e: IOException) {
                // Nothing half-staged survives a failure, whether it died before or after the
                // temp-to-final rename.
                tempFile.delete()
                finalFile?.delete()
                SourceImportOutcome.Failed(e.message ?: DEFAULT_FAILURE_MESSAGE)
            }
        }

    private fun displayNameFor(suggestedFileName: String, hash: String): String =
        suggestedFileName.removeSuffix(EPUB_EXTENSION).ifBlank { hash }

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
}
