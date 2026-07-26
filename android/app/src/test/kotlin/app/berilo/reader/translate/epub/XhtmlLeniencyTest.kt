package app.berilo.reader.translate.epub

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The leniency rule: recover what HTML allows, fail loudly on the rest, and — the part that
 * actually protects `book_hash` — never touch a well-formed document at all.
 *
 * Why the last one needs a *mechanism* assertion rather than an output comparison: R0-R3 are
 * near-idempotent, so forcing every document through recovery changes nothing observable
 * (`docs/findings.md`, 2026-07-26). A test that compares repaired output to strict output
 * passes under exactly the mutation it exists to catch. So the repair pass is injected, and
 * the injected one fails if it is ever called.
 */
class XhtmlLeniencyTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a well-formed book never reaches the repair pass`() {
        val exploding =
            XhtmlParser(
                repair = { fail("repairXhtml ran for a well-formed document"); error("unreachable") },
            )
        val file =
            SyntheticEpub()
                .document("ch1.xhtml", "<h1>One</h1><p>Body with an <em>emphasis</em>.</p>")
                .document("ch2.xhtml", "<h1>Two</h1><p>More body.</p><img src=\"a.png\"/>")
                .resource("a.png", ONE_PIXEL_PNG)
                .ncx("ch1.xhtml" to "One", "ch2.xhtml" to "Two")
                .writeTo(folder.newFile("wellformed.epub"))

        // The container, the OPF, the NCX and both spine documents all go through this parser.
        val book = EpubReader(exploding).read(file)

        assertEquals(4, book.segments.size)
    }

    @Test
    fun `a bare nbsp, an unclosed br and a stray ampersand are recovered with content intact`() {
        val recovered = mutableListOf<Pair<String, List<String>>>()
        val parser = XhtmlParser(onRecovered = { document, applied -> recovered += document to applied })
        val malformed =
            ("""<?xml version="1.0" encoding="utf-8"?>""" +
                """<html xmlns="http://www.w3.org/1999/xhtml"><body>""" +
                """<h1>Broken &amp; recovered</h1>""" +
                """<p>Bare&nbsp;entity, AT&T, and a<br>line break.</p>""" +
                """</body></html>""")
                .toByteArray(Charsets.UTF_8)

        val file =
            SyntheticEpub()
                .rawDocument("ch1.xhtml", malformed)
                .writeTo(folder.newFile("malformed.epub"))
        val book = EpubReader(parser).read(file)

        assertEquals(
            listOf("Broken & recovered", "Bare entity, AT&T, and aline break."),
            book.segments.map { it.text },
        )
        assertEquals(listOf("OEBPS/ch1.xhtml"), recovered.map { it.first })
        assertEquals(
            listOf(
                "R0 dropped the XML declaration",
                "R1 numericized undeclared named entities",
                "R2 escaped bare ampersands",
                "R3 self-closed void elements",
            ),
            recovered.single().second,
        )
    }

    @Test
    fun `an unrecoverable document raises an error naming it`() {
        val file =
            SyntheticEpub()
                .document("ch1.xhtml", "<h1>Fine</h1>")
                .rawDocument("broken.xhtml", "<html><body><p>mis<em>nested</p></em></body>".toByteArray())
                .writeTo(folder.newFile("unrecoverable.epub"))

        val thrown =
            try {
                EpubReader().read(file)
                fail("expected EpubParseException")
                error("unreachable")
            } catch (exception: EpubParseException) {
                exception
            }

        assertEquals("OEBPS/broken.xhtml", thrown.document)
        // The message, not a log line: this reaches the user as "chapter X could not be read".
        assertTrue(thrown.message.orEmpty(), "OEBPS/broken.xhtml" in thrown.message.orEmpty())
        assertTrue(thrown.message.orEmpty(), "strict parse failed" in thrown.message.orEmpty())
        assertTrue(thrown.message.orEmpty(), "recovery failed" in thrown.message.orEmpty())
    }

    @Test
    fun `a spine document missing from the archive raises rather than silently vanishing`() {
        val file =
            SyntheticEpub()
                .document("ch1.xhtml", "<h1>Fine</h1>")
                .document("ch2.xhtml", "<h1>Also fine</h1>")
                .writeTo(folder.newFile("gap.epub"))
        val stripped = folder.newFile("stripped.epub")
        removeMember(file, stripped, "OEBPS/ch2.xhtml")

        val thrown =
            try {
                EpubReader().read(stripped)
                fail("expected EpubParseException")
                error("unreachable")
            } catch (exception: EpubParseException) {
                exception
            }
        assertEquals("OEBPS/ch2.xhtml", thrown.document)
        assertEquals("declared in the spine but absent from the archive", thrown.reason)
    }

    @Test
    fun `each repair matches Python byte for byte`() {
        PythonReference.REPAIRS.forEach { case ->
            val repaired = repairXhtml(case.raw.toByteArray(Charsets.ISO_8859_1))
            assertEquals(
                case.raw,
                case.repaired,
                // ISO-8859-1 round trip only for the case that is deliberately not UTF-8; the
                // rest are ASCII either way, so decoding as UTF-8 is faithful.
                String(repaired.bytes, Charsets.UTF_8),
            )
            assertEquals(case.raw, case.applied, repaired.applied)
        }
    }

    @Test
    fun `R3 does not end a tag at a literal greater-than inside an attribute value`() {
        // The defect A1 found by reaching for a mechanism assertion: a `[^<>]*` attribute scan
        // self-closes mid-attribute and silently mangles the alt text.
        val repaired = repairXhtml("""<img src="a.png" alt="3 > 2">""".toByteArray())
        assertEquals("""<img src="a.png" alt="3 > 2"/>""", String(repaired.bytes, Charsets.UTF_8))
    }

    @Test
    fun `a name that is not an HTML5 entity is left for R2 rather than invented`() {
        val repaired = repairXhtml("<p>&notanentity; stays</p>".toByteArray())
        assertEquals("<p>&notanentity; stays</p>", String(repaired.bytes, Charsets.UTF_8))
        assertTrue(repaired.applied.isEmpty())
    }

    @Test
    fun `bytes that are not valid UTF-8 decode as ISO-8859-1 instead of failing`() {
        val repaired = repairXhtml(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<p>x</p>".toByteArray())
        assertEquals("ÿþ<p>x</p>", String(repaired.bytes, Charsets.UTF_8))
    }

    @Test
    fun `the parser does not fetch the DTD a DOCTYPE names`() {
        // If it did, XHTML's DTD would declare `nbsp` and the document would parse strictly —
        // where Python's expat rejects it. The two sides would then recover different sets of
        // documents. Proven by the absence of a recovery notice being a failure, not a pass.
        var recoveries = 0
        val parser = XhtmlParser(onRecovered = { _, _ -> recoveries += 1 })
        val doctype =
            ("""<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" """ +
                """"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">""" +
                """<html xmlns="http://www.w3.org/1999/xhtml"><body>""" +
                """<p>Bare&nbsp;entity</p></body></html>""")
                .toByteArray(Charsets.UTF_8)

        val root = parser.parse(doctype, "doctype.xhtml")

        assertNotNull(root)
        assertEquals("the entity had to be repaired, so no DTD was consulted", 1, recoveries)
    }

    @Test
    fun `an EPUB with no container is refused`() {
        val empty = folder.newFile("empty.epub")
        java.util.zip.ZipOutputStream(empty.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
        }
        val thrown =
            try {
                EpubReader().read(empty)
                fail("expected EpubParseException")
                error("unreachable")
            } catch (exception: EpubParseException) {
                exception
            }
        assertEquals("META-INF/container.xml", thrown.document)
        assertFalse(thrown.message.isNullOrEmpty())
    }

    /** Copy an archive, dropping one member — a book whose manifest outlives its file. */
    private fun removeMember(source: File, destination: File, member: String) {
        java.util.zip.ZipFile(source).use { zip ->
            java.util.zip.ZipOutputStream(destination.outputStream()).use { out ->
                zip.entries().asSequence().filter { it.name != member }.forEach { entry ->
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    private companion object {
        /** A 1x1 PNG, so a synthetic book carries real image bytes. */
        val ONE_PIXEL_PNG: ByteArray =
            java.util.Base64.getDecoder()
                .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" +
                        "AAAABJRU5ErkJggg==",
                )
    }
}
