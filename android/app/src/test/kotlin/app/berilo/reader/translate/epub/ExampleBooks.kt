package app.berilo.reader.translate.epub

import java.io.File
import org.junit.Assume

/**
 * Locates the real example EPUBs the cross-language identity gate reads.
 *
 * The books are copyrighted and live under the gitignored `data/`, which exists only in the
 * main checkout — never in CI, never in an agent worktree, and never copied into one. So the
 * path is supplied from outside and every test that needs it **skips** (JUnit `Assume` —
 * reported as skipped, never as passed) when it is not, mirroring
 * `ScreenshotHarness.assumeRecording()`.
 *
 * ```
 * ./gradlew test -Dberilo.examples.dir=/abs/path/to/data/examples
 * ```
 *
 * The mapping below is the Kotlin half of `berilo.identity_fixture.EXAMPLE_SOURCES`: only the
 * title prefix is recorded, because the on-disk filenames carry download provenance that has
 * no business in a committed file.
 */
object ExampleBooks {

    /** System property (and `BERILO_EXAMPLES_DIR` environment variable) naming the directory. */
    const val DIRECTORY_PROPERTY = "berilo.examples.dir"

    /** Fixture slug -> the leading part of the example book's filename. */
    val SOURCE_PREFIXES =
        mapOf(
            "new-rules-of-war" to "The New Rules of War",
            "revenge-of-geography" to "The Revenge of Geography",
            "sandworm" to "Sandworm",
            "ember-spark" to "Ember Spark",
        )

    /** Filename suffixes of *translator output* sitting next to the sources — never a source. */
    private val TRANSLATED_SUFFIXES = listOf(".sl.epub", ".si.epub")

    private val directory: File?
        get() =
            (System.getProperty(DIRECTORY_PROPERTY) ?: System.getenv("BERILO_EXAMPLES_DIR"))
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)

    /**
     * Skip the calling test unless every example book is readable.
     *
     * Skipping on a *partial* corpus is deliberate: the gate is the four-book table, and three
     * of four passing is not the invariant this story exists to hold.
     */
    fun assumeAvailable() {
        val root = directory
        Assume.assumeTrue(
            "Skipping: -D$DIRECTORY_PROPERTY=<dir> (or BERILO_EXAMPLES_DIR) is not set to a " +
                "directory. data/ is gitignored — the example books are copyrighted and exist " +
                "only in the main checkout, so this gate cannot run here.",
            root != null,
        )
        val missing = SOURCE_PREFIXES.filterValues { resolve(root!!, it) == null }.keys
        Assume.assumeTrue(
            "Skipping: $root holds no source EPUB for ${missing.joinToString()}.",
            missing.isEmpty(),
        )
    }

    /**
     * Resolve one example book by slug.
     *
     * @param slug Fixture slug, e.g. `sandworm`.
     * @return The source EPUB. Only call after [assumeAvailable].
     */
    fun bookOf(slug: String): File {
        val root = requireNotNull(directory) { "call assumeAvailable() first" }
        val prefix = requireNotNull(SOURCE_PREFIXES[slug]) { "unknown example slug: $slug" }
        return requireNotNull(resolve(root, prefix)) { "no source EPUB for $slug under $root" }
    }

    private fun resolve(root: File, prefix: String): File? =
        root.listFiles()
            ?.filter { file ->
                file.name.startsWith(prefix) &&
                    file.name.endsWith(".epub") &&
                    TRANSLATED_SUFFIXES.none(file.name::endsWith)
            }
            ?.minByOrNull(File::getName)
}
