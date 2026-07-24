package app.berilo.reader.annotations

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Authority declared for the `<provider>` in `AndroidManifest.xml`; must match exactly. */
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

/** Subdirectory of `cacheDir` the exported files are written to (matches
 * `res/xml/file_paths.xml`'s `cache-path`). */
private const val EXPORT_SUBDIR = "exports"

/**
 * Writes a notebook's Markdown export to `cacheDir` and builds the `ACTION_SEND` share-sheet
 * intent for it (S2.6) — a `text/markdown` body via `EXTRA_TEXT` plus the same content
 * streamed as a `.md` file via [FileProvider], so Obsidian's "Import file" and share-target
 * pickers that read the stream both work. Device-only glue (file I/O, `Context`, `Intent`),
 * exercised on the Boox device per the story's Verify line — kept thin, no branching logic
 * beyond what [MarkdownExporter]'s pure output requires.
 */
object MarkdownShareExporter {

    /**
     * Writes [markdown] to a `.md` file named after [bookTitle] under `cacheDir/exports/` and
     * returns a chooser [Intent] for sharing it.
     */
    fun buildShareIntent(context: Context, bookTitle: String, markdown: String): Intent {
        val exportsDir = File(context.cacheDir, EXPORT_SUBDIR).apply { mkdirs() }
        val file = File(exportsDir, "${sanitizeFileName(bookTitle)}.md")
        file.writeText(markdown)

        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX, file)

        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_TEXT, markdown)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(sendIntent, null)
    }

    /** Strips characters that are unsafe in a filename, so an untitled/odd book title never
     * breaks the `cacheDir` write. No local absolute paths leak into the export itself — this
     * only shapes the file *name*. */
    private fun sanitizeFileName(title: String): String {
        val cleaned = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
        return cleaned.ifEmpty { "notebook" }
    }
}
