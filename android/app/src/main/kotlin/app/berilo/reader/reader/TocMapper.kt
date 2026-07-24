package app.berilo.reader.reader

import org.readium.r2.shared.publication.Link

/**
 * One entry in the reader's table-of-contents drawer.
 *
 * @property title Chapter/section title shown in the drawer.
 * @property href Readium href (as a string) to navigate to on tap.
 * @property depth Nesting level, 0 for top-level chapters; used to indent
 *   nested sections.
 */
data class TocChapter(
    val title: String,
    val href: String,
    val depth: Int,
)

/**
 * Flattens a Readium publication's nested table of contents ([Link] tree) into
 * the depth-tagged, display-order list the drawer renders.
 *
 * Entries with a blank title are skipped (some EPUBs carry structural, unlabeled
 * `<nav>` nodes), but their children are still walked so a labeled section under
 * an unlabeled group is not lost — preserving reachability of every chapter.
 */
object TocMapper {

    /** Depth-first flatten of [links] into ordered [TocChapter]s. */
    fun flatten(links: List<Link>): List<TocChapter> {
        val out = mutableListOf<TocChapter>()
        appendLevel(links, depth = 0, out = out)
        return out
    }

    private fun appendLevel(links: List<Link>, depth: Int, out: MutableList<TocChapter>) {
        for (link in links) {
            val title = link.title?.trim()
            if (!title.isNullOrEmpty()) {
                out += TocChapter(title = title, href = link.href.toString(), depth = depth)
            }
            val childDepth = if (title.isNullOrEmpty()) depth else depth + 1
            if (link.children.isNotEmpty()) {
                appendLevel(link.children, childDepth, out)
            }
        }
    }
}
