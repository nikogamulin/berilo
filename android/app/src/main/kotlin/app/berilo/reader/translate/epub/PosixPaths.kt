package app.berilo.reader.translate.epub

/**
 * The four `posixpath` functions the EPUB normalizer resolves zip-internal paths with.
 *
 * `java.io.File` and `java.nio.file.Path` are not substitutes: they are platform-separator
 * aware, they collapse differently, and `File("a/../..").normalize()` is not
 * `posixpath.normpath("a/../..")`. Zip entry names are always `/`-separated and are matched
 * against the archive's own name list, so the resolution has to be the same string algebra
 * Python uses — a manifest href that resolves to a different string finds no archive member,
 * loses its document, and shifts every later segment id.
 *
 * Hrefs are deliberately **not** percent-decoded, mirroring `normalize/epub.py`.
 */
internal object PosixPaths {

    private const val SEPARATOR = '/'
    private const val SEPARATOR_STRING = "/"
    private const val CURRENT = "."
    private const val PARENT = ".."

    /**
     * Join a base directory and a relative reference, as `posixpath.join`.
     *
     * @param base The base directory; may be empty.
     * @param href The reference; an absolute one replaces [base] entirely.
     * @return The joined path, un-normalized.
     */
    fun join(base: String, href: String): String =
        when {
            href.startsWith(SEPARATOR) -> href
            base.isEmpty() || base.endsWith(SEPARATOR) -> base + href
            else -> base + SEPARATOR + href
        }

    /**
     * Lexically normalize a path, as `posixpath.normpath`.
     *
     * Removes empty and `.` components and resolves `..` without touching the filesystem.
     * Leading slashes are preserved the way POSIX prescribes: exactly two stay two, three or
     * more collapse to one.
     *
     * @param path The path to normalize.
     * @return The normalized path; `.` for an empty input.
     */
    fun normalize(path: String): String {
        if (path.isEmpty()) return CURRENT
        val leadingSlashes =
            when {
                !path.startsWith(SEPARATOR) -> 0
                path.startsWith("//") && !path.startsWith("///") -> 2
                else -> 1
            }
        val resolved = ArrayDeque<String>()
        for (component in path.split(SEPARATOR)) {
            if (component.isEmpty() || component == CURRENT) continue
            if (component != PARENT ||
                (leadingSlashes == 0 && resolved.isEmpty()) ||
                resolved.lastOrNull() == PARENT
            ) {
                resolved.addLast(component)
            } else if (resolved.isNotEmpty()) {
                resolved.removeLast()
            }
        }
        val joined = SEPARATOR_STRING.repeat(leadingSlashes) + resolved.joinToString(SEPARATOR_STRING)
        return joined.ifEmpty { CURRENT }
    }

    /**
     * Resolve a relative href against a base directory, as `_resolve` in `normalize/epub.py`.
     *
     * @param baseDir Directory the href is relative to.
     * @param href The reference.
     * @return The normalized zip-internal path.
     */
    fun resolve(baseDir: String, href: String): String = normalize(join(baseDir, href))

    /**
     * Return the directory part of a path, as `posixpath.dirname`.
     *
     * @param path The path.
     * @return Everything before the last separator, without a trailing separator (unless the
     *   head is nothing but separators); the empty string when there is none.
     */
    fun directoryName(path: String): String {
        val head = path.substring(0, path.lastIndexOf(SEPARATOR) + 1)
        return if (head.isNotEmpty() && head.any { it != SEPARATOR }) {
            head.trimEnd(SEPARATOR)
        } else {
            head
        }
    }

    /**
     * Return the final component of a path, as `posixpath.basename`.
     *
     * @param path The path.
     * @return Everything after the last separator.
     */
    fun baseName(path: String): String = path.substring(path.lastIndexOf(SEPARATOR) + 1)

    /**
     * Return a path's extension including the dot, as `posixpath.splitext(path)[1]`.
     *
     * Leading dots of the final component are not extensions, so `.gitignore` has none.
     *
     * @param path The path.
     * @return The extension including its dot, or the empty string.
     */
    fun extension(path: String): String {
        val separatorIndex = path.lastIndexOf(SEPARATOR)
        val dotIndex = path.lastIndexOf('.')
        if (dotIndex > separatorIndex) {
            for (index in separatorIndex + 1 until dotIndex) {
                if (path[index] != '.') return path.substring(dotIndex)
            }
        }
        return ""
    }
}
