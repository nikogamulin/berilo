package app.berilo.reader.translate.epub

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **The cross-language gate for the writer.** For the same `Book`, this writer's archive must
 * be byte-identical to `berilo.assemble.build_epub`'s.
 *
 * Byte-identity is not perfectionism. `build_epub` is deterministic by construction, so the two
 * implementations *can* agree exactly — which makes a whole-archive sha256 the cheapest and
 * most sensitive drift detector available: a moved heading level, a lost class attribute, a
 * different compression choice and a wall-clock timestamp all surface as the same one failing
 * assertion, and none of them can hide.
 *
 * The per-entry and per-document assertions run **first** so a failure names what moved rather
 * than only reporting that two digests differ.
 */
class EpubWriterByteIdentityTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `every python case reproduces byte for byte`() {
        val cases = AssembleVectors.cases
        assertTrue("expected at least four vector cases, got ${cases.size}", cases.size >= 4)
        cases.forEach { case ->
            val output = write(case)

            // 1. Rendering: which document, if any, differs.
            ZipFile(output).use { archive ->
                case.documents.forEach { (name, expected) ->
                    val entry =
                        requireNotNull(archive.getEntry(name)) { "${case.name}: missing $name" }
                    val actual =
                        archive.getInputStream(entry).use { it.readBytes() }
                            .toString(Charsets.UTF_8)
                    assertEquals("${case.name}: $name", expected, actual)
                }
            }

            // 2. Archive shape: order, compression method, CRC-32 and both sizes.
            assertEquals(
                "${case.name}: entry records",
                case.entries.joinToString("\n") {
                    "${it.name} ${it.method} ${it.crc32} ${it.size}/${it.compressedSize}"
                },
                entryRecords(output).joinToString("\n"),
            )

            // 3. The bytes themselves.
            assertEquals("${case.name}: archive size", case.size.toLong(), output.length())
            assertEquals("${case.name}: sha256", case.sha256, sha256(output))
        }
    }

    @Test
    fun `entry order is mimetype, container, opf, nav, stylesheet, chapters, images`() {
        val output = write(AssembleVectors.case("images"))
        assertEquals(
            listOf(
                "mimetype",
                "META-INF/container.xml",
                "OEBPS/content.opf",
                "OEBPS/nav.xhtml",
                "OEBPS/stylesheet.css",
                "OEBPS/chap_0001.xhtml",
                "OEBPS/chap_0002.xhtml",
                "OEBPS/images/img_0001.png",
                "OEBPS/images/img_0002.jpg",
                "OEBPS/images/img_0003.svg",
                "OEBPS/images/img_0004.webp",
                "OEBPS/images/img_0005.img",
            ),
            entryNames(output),
        )
    }

    @Test
    fun `mimetype is the first entry and is stored uncompressed`() {
        val output = write(AssembleVectors.case("synthetic"))
        ZipFile(output).use { archive ->
            val first = archive.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals("stored", java.util.zip.ZipEntry.STORED.toLong(), first.method.toLong())
            assertEquals(first.size, first.compressedSize)
            assertEquals(
                "application/epub+zip",
                archive.getInputStream(first).use { it.readBytes() }.toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `stored image entries carry a correct CRC-32 and size`() {
        // Java's ZipOutputStream would have thrown without these; a hand-written header can
        // instead emit a *wrong* one, which no reader notices until it verifies the CRC.
        val case = AssembleVectors.case("images")
        val output = write(case)
        val expected = case.entries.filter { it.method == "stored" }.associateBy { it.name }
        assertTrue("expected stored image entries in the vector", expected.size >= 3)
        ZipFile(output).use { archive ->
            expected.forEach { (name, record) ->
                val entry = requireNotNull(archive.getEntry(name))
                assertEquals("$name method", 0L, entry.method.toLong())
                assertEquals("$name crc", record.crc32, "%08x".format(entry.crc))
                assertEquals("$name size", record.size.toLong(), entry.size)
                assertEquals("$name compressed size", record.size.toLong(), entry.compressedSize)
                // Reading through ZipFile verifies the CRC and throws if it disagrees.
                assertEquals(
                    "$name payload length",
                    record.size,
                    archive.getInputStream(entry).use { it.readBytes() }.size,
                )
            }
        }
    }

    @Test
    fun `already-compressed image types are stored and everything else is deflated`() {
        val output = write(AssembleVectors.case("images"))
        ZipFile(output).use { archive ->
            val methods =
                archive.entries().asSequence()
                    .filter { it.name.startsWith("OEBPS/images/") }
                    .associate { it.name to it.method }
            assertEquals(
                mapOf(
                    "OEBPS/images/img_0001.png" to 0, // image/png
                    "OEBPS/images/img_0002.jpg" to 0, // image/jpeg
                    "OEBPS/images/img_0003.svg" to 8, // image/svg+xml — text, deflate it
                    "OEBPS/images/img_0004.webp" to 0, // image/webp
                    "OEBPS/images/img_0005.img" to 8, // unknown media type
                ),
                methods,
            )
        }
    }

    @Test
    fun `every entry timestamp is pinned to the DOS epoch`() {
        // A wall-clock timestamp is the classic way a "deterministic" writer stops being one:
        // every other assertion still passes and only the digest moves, once a day.
        val output = write(AssembleVectors.case("synthetic"))
        ZipFile(output).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                // DOS date 1980-01-01 00:00, as ZipEntry decodes it into the default zone.
                val time = java.util.Calendar.getInstance()
                time.timeInMillis = entry.time
                assertEquals("${entry.name} year", 1980, time.get(java.util.Calendar.YEAR))
                assertEquals(
                    "${entry.name} day of year",
                    1,
                    time.get(java.util.Calendar.DAY_OF_YEAR),
                )
            }
        }
    }

    @Test
    fun `writing the same book twice produces identical bytes`() {
        val case = AssembleVectors.case("synthetic")
        assertEquals(sha256(write(case, "first")), sha256(write(case, "second")))
    }

    // --- helpers ------------------------------------------------------------------------

    private fun write(case: AssembleVectors.Case, suffix: String = "out"): File {
        val output = File(temporaryFolder.newFolder(), "${case.name}.$suffix.epub")
        return EpubWriter().write(case.book(), output, case.bilingual, case.sourceBook())
    }

    private fun entryNames(file: File): List<String> =
        ZipFile(file).use { archive -> archive.entries().asSequence().map { it.name }.toList() }

    private fun entryRecords(file: File): List<String> =
        ZipFile(file).use { archive ->
            archive.entries().asSequence()
                .map { entry ->
                    val method = if (entry.method == 0) "stored" else "deflated"
                    "${entry.name} $method ${"%08x".format(entry.crc)} " +
                        "${entry.size}/${entry.compressedSize}"
                }
                .toList()
        }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
