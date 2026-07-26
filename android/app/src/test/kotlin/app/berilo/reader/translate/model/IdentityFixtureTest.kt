package app.berilo.reader.translate.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-language gate: this port must agree with the CLI on every real book's identity.
 *
 * The Kotlin side cannot read an EPUB yet (that is B2), so today's gate is
 * "[bookHash] recomputed from the fixture's ordered ids reproduces the fixture's `book_hash`",
 * plus a full text → id → `book_hash` round trip on the synthetic corpus, which is the one book
 * whose text may be committed. When B2's reader lands, its output goes straight into
 * [IdentityFixtures.assertMatchesFixture] against these same files.
 */
class IdentityFixtureTest {

    /** Values pinned by `docs/plans/2026-07-26-ondevice-translation.md` §5, measured at €0. */
    private data class Expected(
        val slug: String,
        val bookHash: String,
        val segments: Int,
        val chapters: Int,
        val images: Int,
    )

    private val expected =
        listOf(
            Expected("new-rules-of-war", "2db2bd1ca6782089f32f2af99bc2b69cbbaf259a", 2309, 27, 4),
            Expected("revenge-of-geography", "f30cd8f30a17f696909f446a121bd8b0eb20b91c", 1294, 47, 17),
            Expected("sandworm", "3f76c96f58a59016bc86bc11616b040f12038ef2", 1813, 61, 3),
            Expected("ember-spark", "14425e03f36e50dfbcd2c6631fbebfd90f7d5dbf", 1231, 35, 61),
        )

    @Test
    fun `every example fixture carries the book hash the plan measured`() {
        assertEquals(IdentityFixtures.EXAMPLE_SLUGS, expected.map(Expected::slug))
        expected.forEach { row ->
            val fixture = IdentityFixtures.loadIdentity(row.slug)
            assertEquals("${row.slug} book_hash", row.bookHash, fixture.bookHash)
            assertEquals("${row.slug} segments", row.segments, fixture.segmentCount)
            assertEquals("${row.slug} chapters", row.chapters, fixture.chapterCount)
            assertEquals("${row.slug} images", row.images, fixture.imageCount)
        }
    }

    @Test
    fun `bookHash over each fixture's ordered ids reproduces its book hash`() {
        IdentityFixtures.EXAMPLE_SLUGS.forEach { slug ->
            val fixture = IdentityFixtures.loadIdentity(slug)
            assertEquals(slug, fixture.bookHash, bookHash(fixture.segments.map(FixtureSegment::id)))
        }
    }

    @Test
    fun `every example fixture is internally consistent`() {
        IdentityFixtures.EXAMPLE_SLUGS.forEach { slug ->
            val fixture = IdentityFixtures.loadIdentity(slug)
            assertEquals(
                "$slug fixture_version",
                IdentityFixtures.EXPECTED_FIXTURE_VERSION,
                fixture.fixtureVersion,
            )
            assertEquals("$slug slug", slug, fixture.source.slug)
            assertEquals("$slug rows", fixture.segmentCount, fixture.segments.size)
            assertEquals("$slug images", fixture.imageCount, fixture.images.size)
            assertEquals(
                "$slug chapters",
                fixture.chapterCount,
                fixture.segments.mapTo(mutableSetOf(), FixtureSegment::chapterIndex).size,
            )
            // position is book-global: 0..n-1 across chapter boundaries, never restarting.
            assertEquals(
                "$slug positions",
                List(fixture.segmentCount) { it },
                fixture.segments.map(FixtureSegment::position),
            )
            fixture.segments.forEach { segment ->
                assertEquals("$slug id length", SHA1_HEX_LENGTH, segment.id.length)
                assertEquals("$slug hash length", SHA1_HEX_LENGTH, segment.segmentHash.length)
                if (segment.type != SegmentType.HEADING) {
                    assertEquals("$slug non-heading level", null, segment.headingLevel)
                }
            }
        }
    }

    @Test
    fun `the synthetic corpus round-trips from text to book hash`() {
        val book = bookFromJson(IdentityFixtures.readText("synthetic.book.json"))
        IdentityFixtures.assertMatchesFixture(book, IdentityFixtures.loadIdentity("synthetic"))
    }

    @Test
    fun `the synthetic corpus exercises the identity edge cases`() {
        val book = bookFromJson(IdentityFixtures.readText("synthetic.book.json"))
        assertTrue("chapters", book.chapterCount >= 3)
        assertTrue(
            "a segment padded with whitespace trim() would keep",
            book.segments.any { it.text.trim() != it.text.pythonStrip() },
        )
        assertTrue(
            "a segment padded with whitespace both strips remove",
            book.segments.any { it.text != it.text.trim() },
        )
        assertTrue(
            "a segment with an internal newline",
            book.segments.any { it.text.contains('\n') },
        )
        assertTrue("images", book.images.size >= 2)
    }

    @Test
    fun `no committed identity fixture contains book text`() {
        // data/ is gitignored because the example books are copyrighted; these files are
        // committed. Any key that could carry prose is a licence problem, not a style one.
        val forbidden = setOf("text", "title", "chapter_title", "authors", "alt", "source_path")
        (IdentityFixtures.EXAMPLE_SLUGS + "synthetic").forEach { slug ->
            val root =
                Json.parseToJsonElement(IdentityFixtures.readText("$slug.identity.json")).jsonObject
            assertKeysAbsent("$slug.identity.json", root, forbidden)
        }
    }

    private fun assertKeysAbsent(where: String, root: JsonObject, forbidden: Set<String>) {
        fun walk(node: Any?) {
            when (node) {
                is JsonObject -> {
                    node.keys.forEach { key ->
                        assertFalse("$where must not carry '$key'", key in forbidden)
                    }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
    }
}
