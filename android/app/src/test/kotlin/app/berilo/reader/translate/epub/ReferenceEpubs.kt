package app.berilo.reader.translate.epub

import java.io.File
import org.junit.Assume

/**
 * Locates EPUBs built by the **Python** CLI from the real example books, for the real-book half
 * of the byte-identity gate.
 *
 * The synthetic vector ([AssembleVectors]) proves agreement on every rule; this proves it on
 * 2000-segment books with real images, where a rule the synthetic corpus does not exercise
 * would show up. Those archives contain copyrighted book text, so — exactly like `data/` — they
 * are never committed, never copied into a worktree, and the tests **skip** without them.
 *
 * Build the references from the main checkout, then point the suite at them:
 * ```
 * PYTHONPATH=translator python3 -c "
 * from berilo.assemble import build_epub
 * from berilo.normalize.epub import normalize_epub
 * build_epub(normalize_epub('data/examples/<book>.epub'), '/tmp/refs/<slug>.reference.epub')"
 *
 * ./gradlew test -Dberilo.examples.dir=... -Dberilo.reference.epubs.dir=/tmp/refs
 * ```
 */
object ReferenceEpubs {

    /** System property (and `BERILO_REFERENCE_EPUBS_DIR` environment variable) naming the directory. */
    const val DIRECTORY_PROPERTY = "berilo.reference.epubs.dir"

    /** Filename suffix of a reference archive: `<slug>.reference.epub`. */
    private const val SUFFIX = ".reference.epub"

    private val directory: File?
        get() =
            (System.getProperty(DIRECTORY_PROPERTY) ?: System.getenv("BERILO_REFERENCE_EPUBS_DIR"))
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)

    /**
     * Skip the calling test unless a reference archive exists for every example book.
     *
     * Partial coverage is deliberately not enough: the gate is the whole corpus, and one book
     * agreeing is not the invariant.
     */
    fun assumeAvailable() {
        val root = directory
        Assume.assumeTrue(
            "Skipping: -D$DIRECTORY_PROPERTY=<dir> is not set to a directory. The reference " +
                "archives hold copyrighted book text, so they are never committed and this " +
                "half of the byte-identity gate cannot run here.",
            root != null,
        )
        val missing = ExampleBooks.SOURCE_PREFIXES.keys.filter { !referenceOf(root!!, it).isFile }
        Assume.assumeTrue("Skipping: $root holds no reference EPUB for $missing.", missing.isEmpty())
    }

    /**
     * The Python-built reference archive for one example book.
     *
     * @param slug Fixture slug, e.g. `sandworm`. Only call after [assumeAvailable].
     */
    fun referenceOf(slug: String): File = referenceOf(requireNotNull(directory), slug)

    private fun referenceOf(root: File, slug: String) = File(root, "$slug$SUFFIX")
}
