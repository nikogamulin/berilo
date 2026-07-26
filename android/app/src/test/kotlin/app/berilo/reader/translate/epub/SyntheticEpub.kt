package app.berilo.reader.translate.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds small EPUB archives in-test, so the reader's structural rules can be exercised
 * without `data/`.
 *
 * The real books are copyrighted and gitignored; nothing from them may be copied into the
 * repository. Everything here is written for the test that uses it.
 */
internal class SyntheticEpub(private val opfDirectory: String = "OEBPS") {

    private val members = linkedMapOf<String, ByteArray>()
    private val manifest = mutableListOf<ManifestItem>()
    private val spine = mutableListOf<String>()

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String?,
    )

    /**
     * Add a spine document.
     *
     * @param name File name inside [opfDirectory].
     * @param body Raw markup placed inside `<body>`; pass [rawDocument] instead to control the
     *   whole file, malformed markup included.
     * @param title The document's `<title>`, or `null` for none.
     * @return This builder.
     */
    fun document(name: String, body: String, title: String? = null): SyntheticEpub {
        val head = title?.let { "<head><title>$it</title></head>" }.orEmpty()
        return rawDocument(
            name,
            """<?xml version="1.0" encoding="utf-8"?>
               |<html xmlns="http://www.w3.org/1999/xhtml">$head<body>$body</body></html>
            """.trimMargin().toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Add a spine document verbatim, including bytes that are not well-formed XML.
     *
     * @param name File name inside [opfDirectory].
     * @param bytes The exact file content.
     * @return This builder.
     */
    fun rawDocument(name: String, bytes: ByteArray): SyntheticEpub = apply {
        val id = "doc${spine.size + 1}"
        members[path(name)] = bytes
        manifest.add(ManifestItem(id, name, "application/xhtml+xml", null))
        spine.add(id)
    }

    /**
     * Add a resource that is in the manifest but not the spine (an image, a stylesheet).
     *
     * @param name File name inside [opfDirectory].
     * @param bytes The file content.
     * @param mediaType Declared media type, or `null` to omit the declaration so the reader
     *   has to fall back to the extension.
     * @return This builder.
     */
    fun resource(name: String, bytes: ByteArray, mediaType: String? = "image/png"): SyntheticEpub =
        apply {
            members[path(name)] = bytes
            if (mediaType != null) {
                manifest.add(ManifestItem("res${manifest.size}", name, mediaType, null))
            }
        }

    /**
     * Add an NCX table of contents mapping document names to chapter titles.
     *
     * @param entries `document file name to chapter title`, in TOC order.
     * @return This builder.
     */
    fun ncx(vararg entries: Pair<String, String>): SyntheticEpub = apply {
        val points =
            entries.mapIndexed { index, (href, label) ->
                """<navPoint id="n$index" playOrder="${index + 1}">
                   |<navLabel><text>$label</text></navLabel><content src="$href"/></navPoint>
                """.trimMargin()
            }
        members[path(NCX_NAME)] =
            """<?xml version="1.0" encoding="utf-8"?>
               |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
               |<navMap>${points.joinToString("")}</navMap></ncx>
            """.trimMargin().toByteArray(Charsets.UTF_8)
        manifest.add(ManifestItem("ncx", NCX_NAME, "application/x-dtbncx+xml", null))
    }

    /**
     * Write the archive.
     *
     * @param file Destination `.epub` file.
     * @param title `dc:title`.
     * @param language `dc:language`.
     * @return [file], for chaining into a reader call.
     */
    fun writeTo(file: File, title: String = "Synthetic", language: String = "en"): File {
        val opfPath = path(OPF_NAME)
        members[CONTAINER_PATH] =
            """<?xml version="1.0" encoding="utf-8"?>
               |<container version="1.0"
               | xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
               |<rootfiles><rootfile full-path="$opfPath"
               | media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimMargin().toByteArray(Charsets.UTF_8)
        members[opfPath] = opf(title, language).toByteArray(Charsets.UTF_8)

        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            // Real EPUBs put an uncompressed `mimetype` first; the reader must not depend on
            // it, but writing it keeps these archives honest.
            zip.putNextEntry(ZipEntry(MIMETYPE_NAME))
            zip.write(EPUB_MIMETYPE.toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
            members.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun opf(title: String, language: String): String {
        val items =
            manifest.joinToString("") { item ->
                val properties = item.properties?.let { """ properties="$it"""" }.orEmpty()
                """<item id="${item.id}" href="${item.href}" """ +
                    """media-type="${item.mediaType}"$properties/>"""
            }
        val refs = spine.joinToString("") { """<itemref idref="$it"/>""" }
        val tocAttribute = if (manifest.any { it.id == "ncx" }) """ toc="ncx"""" else ""
        return """<?xml version="1.0" encoding="utf-8"?>
                  |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="i">
                  |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                  |<dc:identifier id="i">urn:uuid:synthetic</dc:identifier>
                  |<dc:title>$title</dc:title><dc:language>$language</dc:language>
                  |</metadata><manifest>$items</manifest>
                  |<spine$tocAttribute>$refs</spine></package>
        """.trimMargin()
    }

    private fun path(name: String): String =
        if (opfDirectory.isEmpty()) name else "$opfDirectory/$name"

    private companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val OPF_NAME = "content.opf"
        const val NCX_NAME = "toc.ncx"
        const val MIMETYPE_NAME = "mimetype"
        const val EPUB_MIMETYPE = "application/epub+zip"
    }
}
