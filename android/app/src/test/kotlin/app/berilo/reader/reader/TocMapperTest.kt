package app.berilo.reader.reader

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.robolectric.RobolectricTestRunner

/**
 * Flattening a Readium TOC ([Link] tree) to the drawer's depth-tagged list. Runs
 * under Robolectric because Readium parses link hrefs through `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class TocMapperTest {

    private fun links(json: String): List<Link> = Link.fromJSONArray(JSONArray(json.trimIndent()))

    @Test
    fun `nested chapters flatten depth-first in reading order with depth tags`() {
        val toc = links(
            """
            [
              { "href": "/ch1.html", "title": "Chapter 1",
                "children": [
                  { "href": "/ch1.html#s1", "title": "Section 1.1" },
                  { "href": "/ch1.html#s2", "title": "Section 1.2" }
                ] },
              { "href": "/ch2.html", "title": "Chapter 2" }
            ]
            """,
        )

        val chapters = TocMapper.flatten(toc)

        assertEquals(
            listOf("Chapter 1" to 0, "Section 1.1" to 1, "Section 1.2" to 1, "Chapter 2" to 0),
            chapters.map { it.title to it.depth },
        )
    }

    @Test
    fun `an untitled group is skipped but its titled children remain reachable`() {
        val toc = links(
            """
            [
              { "href": "/group.html",
                "children": [ { "href": "/g1.html", "title": "Real Section" } ] }
            ]
            """,
        )

        val chapters = TocMapper.flatten(toc)

        // The group carries no title, so it is not shown; its child stays at the
        // group's depth (0) rather than being pushed a level deeper.
        assertEquals(1, chapters.size)
        assertEquals("Real Section", chapters.first().title)
        assertEquals(0, chapters.first().depth)
    }

    @Test
    fun `href is carried through for navigation`() {
        val toc = links("""[ { "href": "/OEBPS/ch7.html", "title": "Chapter 7" } ]""")

        val chapters = TocMapper.flatten(toc)

        assertEquals(1, chapters.size)
        assertEquals("Chapter 7", chapters.first().title)
        org.junit.Assert.assertTrue(chapters.first().href.contains("ch7.html"))
    }
}
