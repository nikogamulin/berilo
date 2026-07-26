package app.berilo.reader.translate.epub

import android.util.Log
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.ImageResource
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.SegmentType
import java.io.BufferedOutputStream
import java.io.File
import java.util.UUID

/**
 * Renders a translated [Book] to a reader-ready EPUB 3 file, byte-identically to the CLI.
 *
 * This is a port of `translator/berilo/assemble.py`, and — as with [EpubReader] — "port" is
 * meant strictly: for the same [Book] this writer's output must be **byte-for-byte** what
 * `build_epub` produces on the workstation. `build_epub` is already deterministic by
 * construction (fixed `dcterms:modified`, a UUID5 `dc:identifier` derived from book metadata,
 * every zip timestamp pinned to the DOS epoch, a fixed entry order), so byte-identity costs
 * nothing to hold and is the strongest available drift detector: one differing byte means the
 * two implementations diverged, and we would rather learn that from a digest than from a book
 * that renders differently on the tablet than on the laptop.
 *
 * Two rendering decisions look like defects and are neither. Both exist because
 * `normalize_epub` — [EpubReader] on this side — reads this output back, and the round trip
 * must be **type-stable** or a rebuilt book stops aligning with the already-translated one:
 *
 * - CAPTION and OTHER segments carry `class="caption"` / `class="other"` on a plain `<p>`.
 *   Those class tags are how the reader recovers the type; drop them and every CAPTION comes
 *   back as a PARAGRAPH.
 * - An image is wrapped in `<div class="figure">`, never in `<figure>`/`<figcaption>`. The
 *   reader maps `figcaption` to a CAPTION segment, so a real `<figure>` would **mint a new
 *   segment on every rebuild**, shifting every later `position`, every later segment id and
 *   therefore the whole `book_hash`.
 *
 * @see DeterministicZip for why `java.util.zip.ZipOutputStream` cannot be used here.
 */
class EpubWriter {

    /**
     * Render [book] to an EPUB 3 file.
     *
     * @param book The translated book. Its segments' text is the content that ends up in the
     *   output; chapter, nav and spine order follow `book.segments` document order.
     * @param output Destination `.epub` file; parent directories are created.
     * @param bilingual When true, every rendered block is followed by its aligned source
     *   segment as `<p class="source">`. Requires [sourceBook].
     * @param sourceBook The untranslated source book, required when [bilingual] is true. Its
     *   segments must carry identical ids in identical order.
     * @return [output].
     * @throws IllegalArgumentException If [bilingual] is true without a [sourceBook], or the
     *   two books' segments do not align 1:1.
     */
    fun write(
        book: Book,
        output: File,
        bilingual: Boolean = false,
        sourceBook: Book? = null,
    ): File {
        val sourceById: Map<String, Segment>? =
            if (bilingual) {
                requireNotNull(sourceBook) { "bilingual=true requires sourceBook" }
                validateBilingualAlignment(book, sourceBook)
                sourceBook.segments.associateBy(Segment::id)
            } else {
                null
            }

        val chapters = groupChapters(book)
        val chapterHrefs =
            chapters.withIndex().associate { (index, chapter) ->
                chapter.index to "chap_${pad4(index + 1)}.xhtml"
            }
        val imageHrefs = imageHrefs(book)
        val imagesById = book.images.associateBy(ImageResource::id)

        val members = mutableListOf<DeterministicZip.Member>()
        members += DeterministicZip.Member(MIMETYPE_NAME, MIMETYPE, DeterministicZip.STORED)
        members += deflated("META-INF/container.xml", CONTAINER_XML)
        members +=
            deflated(
                "$OEBPS/content.opf",
                contentOpf(book, chapters, chapterHrefs, imageHrefs),
            )
        members += deflated("$OEBPS/nav.xhtml", navXhtml(book, chapters, chapterHrefs))
        members += deflated("$OEBPS/stylesheet.css", STYLESHEET_CSS)
        chapters.forEachIndexed { index, chapter ->
            val label = chapterLabel(chapter.title, index + 1)
            val body =
                renderChapterBody(
                    segments = chapter.segments,
                    sourceById = sourceById,
                    images = imagesById,
                    imageAnchors = groupImages(book.images, chapter.index),
                    imageHrefs = imageHrefs,
                )
            members +=
                deflated(
                    "$OEBPS/${chapterHrefs.getValue(chapter.index)}",
                    chapterXhtml(book.language, label, body),
                )
        }
        // Image entries last, in document order: the fixed entry order is what makes
        // re-assembly byte-identical.
        book.images.forEach { image ->
            val method =
                if (image.mediaType in STORED_IMAGE_TYPES) {
                    DeterministicZip.STORED
                } else {
                    DeterministicZip.DEFLATED
                }
            members +=
                DeterministicZip.Member(
                    "$OEBPS/${imageHrefs.getValue(image.id)}",
                    image.data,
                    method,
                )
        }

        output.parentFile?.mkdirs()
        BufferedOutputStream(output.outputStream()).use { stream ->
            DeterministicZip.write(stream, members)
        }
        return output
    }

    // --- structure ------------------------------------------------------------------------

    /** One chapter's segments, in the order the chapter first appears in the book. */
    private data class Chapter(val index: Int, val title: String?, val segments: List<Segment>)

    /**
     * Group `book.segments` into chapters, preserving document order.
     *
     * The chapter's title is its **first** segment's `chapterTitle`, exactly as
     * `_group_chapters` takes `chapters[index][0].chapter_title`.
     */
    private fun groupChapters(book: Book): List<Chapter> {
        val grouped = linkedMapOf<Int, MutableList<Segment>>()
        book.segments.forEach { segment ->
            grouped.getOrPut(segment.chapterIndex) { mutableListOf() }.add(segment)
        }
        return grouped.map { (index, segments) ->
            Chapter(index, segments.first().chapterTitle, segments)
        }
    }

    /**
     * Map each image resource id to its OPF-relative href.
     *
     * Hrefs come from document-order position, never from the source filename, so two source
     * images sharing a basename cannot collide and the output stays stable across runs.
     */
    private fun imageHrefs(book: Book): Map<String, String> =
        book.images.withIndex().associate { (position, image) ->
            val extension = IMAGE_EXTENSIONS[image.mediaType] ?: DEFAULT_IMAGE_EXTENSION
            image.id to "$IMAGE_DIR/img_${pad4(position + 1)}$extension"
        }

    /** Group one chapter's images by the segment id they follow (`null` = chapter-leading). */
    private fun groupImages(
        images: List<ImageResource>,
        chapterIndex: Int,
    ): Map<String?, List<String>> {
        val grouped = linkedMapOf<String?, MutableList<String>>()
        images.filter { it.chapterIndex == chapterIndex }.forEach { image ->
            grouped.getOrPut(image.anchorSegmentId) { mutableListOf() }.add(image.id)
        }
        return grouped
    }

    /** Return [title], or a `"Chapter N"` fallback when it is missing **or empty**. */
    private fun chapterLabel(title: String?, position: Int): String =
        if (title.isNullOrEmpty()) "Chapter $position" else title

    /**
     * Verify [book] and [sourceBook] carry identical segment ids in identical order.
     *
     * Segment integrity is canon (CLAUDE.md §2): a bilingual EPUB pairs every translated
     * segment with the exact source segment it was translated from, never a positional guess.
     */
    private fun validateBilingualAlignment(book: Book, sourceBook: Book) {
        require(book.segments.size == sourceBook.segments.size) {
            "Bilingual segment count mismatch: translated book has ${book.segments.size} " +
                "segments, sourceBook has ${sourceBook.segments.size}"
        }
        book.segments.forEachIndexed { index, target ->
            val source = sourceBook.segments[index]
            require(target.id == source.id) {
                "Bilingual segment id mismatch at position $index: translated segment " +
                    "id='${target.id}' vs sourceBook segment id='${source.id}'"
            }
        }
    }

    // --- body rendering -------------------------------------------------------------------

    /**
     * Render one chapter's XHTML `<body>` inner markup.
     *
     * Consecutive [SegmentType.LIST_ITEM]s collapse into a single `<ul>`. Images anchored to
     * this chapter are emitted after the segment they follow, at the top for a `null` anchor,
     * and after the **whole** `<ul>` for a list-item anchor (a `div` may not sit between list
     * items). An image whose anchor segment is not rendered in this chapter is emitted at the
     * END of the chapter with a warning rather than dropped: a misplaced image is a defect, a
     * vanished one is data loss (review finding 15).
     */
    private fun renderChapterBody(
        segments: List<Segment>,
        sourceById: Map<String, Segment>?,
        images: Map<String, ImageResource>,
        imageAnchors: Map<String?, List<String>>,
        imageHrefs: Map<String, String>,
    ): String {
        val parts = StringBuilder()
        val pendingList = mutableListOf<Segment>()
        val emitted = mutableSetOf<String>()
        var firstHeadingRendered = false

        fun sourceParagraph(segment: Segment) {
            val source = sourceById?.get(segment.id) ?: return
            parts.append("<p class=\"source\">").append(renderInline(source.text)).append("</p>")
        }

        fun renderImages(imageIds: List<String>) {
            imageIds.forEach { imageId ->
                emitted.add(imageId)
                parts.append(renderImage(images.getValue(imageId), imageHrefs.getValue(imageId)))
            }
        }

        fun anchoredImages(anchor: String?) = renderImages(imageAnchors[anchor].orEmpty())

        fun flushList() {
            if (pendingList.isEmpty()) return
            parts.append("<ul>")
            pendingList.forEach { item ->
                parts.append("<li>").append(renderInline(item.text))
                sourceParagraph(item)
                parts.append("</li>")
            }
            parts.append("</ul>")
            pendingList.forEach { anchoredImages(it.id) }
            pendingList.clear()
        }

        anchoredImages(null)

        segments.forEach { segment ->
            if (segment.type == SegmentType.LIST_ITEM) {
                pendingList.add(segment)
                return@forEach
            }
            flushList()

            val inline = renderInline(segment.text)
            when (segment.type) {
                SegmentType.HEADING -> {
                    val requested = segment.headingLevel ?: if (firstHeadingRendered) 2 else 1
                    val level = requested.coerceIn(HEADING_MIN_LEVEL, HEADING_MAX_LEVEL)
                    firstHeadingRendered = true
                    parts.append("<h$level>").append(inline).append("</h$level>")
                }
                SegmentType.BLOCKQUOTE ->
                    parts.append("<blockquote><p>").append(inline).append("</p></blockquote>")
                // Class-tagged so EpubReader round-trips the type: eval alignment fingerprints
                // on segment types, so a CAPTION that comes back as a PARAGRAPH breaks it.
                SegmentType.CAPTION ->
                    parts.append("<p class=\"caption\">").append(inline).append("</p>")
                SegmentType.OTHER ->
                    parts.append("<p class=\"other\">").append(inline).append("</p>")
                SegmentType.PARAGRAPH, SegmentType.LIST_ITEM ->
                    parts.append("<p>").append(inline).append("</p>")
            }

            sourceParagraph(segment)
            anchoredImages(segment.id)
        }

        flushList()
        orphanedImages(imageAnchors, emitted)?.let(::renderImages)
        return parts.toString()
    }

    /**
     * This chapter's images whose anchor segment was never rendered, or `null` when there are
     * none. Warned about, then emitted — never dropped.
     */
    private fun orphanedImages(
        imageAnchors: Map<String?, List<String>>,
        emitted: Set<String>,
    ): List<String>? {
        val orphans = imageAnchors.values.flatten().filterNot(emitted::contains)
        if (orphans.isEmpty()) return null
        Log.w(
            LOG_TAG,
            "Images ${orphans.joinToString(", ")} are anchored to segments absent from their " +
                "own chapter; emitting them at the end of the chapter rather than dropping them",
        )
        return orphans
    }

    /**
     * Render one image resource as a self-contained XHTML figure block.
     *
     * The wrapper is a plain `div`, never `<figure>`/`<figcaption>` — see the class docs.
     */
    private fun renderImage(image: ImageResource, href: String): String {
        val alt = escapeText(image.alt.orEmpty()).replace("\"", "&quot;")
        return "<div class=\"figure\"><img src=\"$href\" alt=\"$alt\"/></div>"
    }

    /** XML-escape `&` and `<` in plain text content — and deliberately nothing else. */
    private fun escapeText(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;")

    /**
     * Render a segment's inline HTML subset as XML-safe XHTML.
     *
     * Literal text is escaped (`&`, `<`); the whitelisted emphasis tags are passed through,
     * lower-cased. If the tags are unbalanced, mismatched or left unclosed, the **whole**
     * segment falls back to fully-escaped plain text — losing emphasis markup beats risking
     * invalid XHTML.
     */
    private fun renderInline(text: String): String {
        val tags = INLINE_TAG_REGEX.findAll(text).map(MatchResult::value).toList()
        if (tags.isEmpty()) return escapeText(text)

        val literals = INLINE_TAG_REGEX.split(text)
        val stack = ArrayDeque<String>()
        val rendered = StringBuilder()
        literals.forEachIndexed { index, literal ->
            rendered.append(escapeText(literal))
            if (index >= tags.size) return@forEachIndexed
            val tag = tags[index]
            val name = tag.trim('<', '/', '>').lowercase()
            if (tag.startsWith("</")) {
                if (stack.isEmpty() || stack.last() != name) return escapeText(text)
                stack.removeLast()
                rendered.append("</").append(name).append(">")
            } else {
                stack.addLast(name)
                rendered.append("<").append(name).append(">")
            }
        }
        if (stack.isNotEmpty()) return escapeText(text)
        return rendered.toString()
    }

    // --- documents ------------------------------------------------------------------------

    /** Render a full chapter XHTML5 document. */
    private fun chapterXhtml(language: String, title: String, bodyInner: String): ByteArray =
        (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                "xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\">\n" +
                "<head>\n" +
                "<title>${escapeText(title)}</title>\n" +
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"stylesheet.css\"/>\n" +
                "</head>\n" +
                "<body>\n$bodyInner\n</body>\n" +
                "</html>\n"
            ).toByteArray(Charsets.UTF_8)

    /** Render the EPUB3 `nav.xhtml` table of contents. */
    private fun navXhtml(
        book: Book,
        chapters: List<Chapter>,
        chapterHrefs: Map<Int, String>,
    ): ByteArray {
        val entries =
            chapters.withIndex().joinToString("") { (index, chapter) ->
                val label = chapterLabel(chapter.title, index + 1)
                "<li><a href=\"${chapterHrefs.getValue(chapter.index)}\">" +
                    "${escapeText(label)}</a></li>"
            }
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                "xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"${book.language}\">\n" +
                "<head>\n" +
                "<title>${escapeText(book.title)}</title>\n" +
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"stylesheet.css\"/>\n" +
                "</head>\n" +
                "<body>\n" +
                "<nav epub:type=\"toc\" id=\"toc\">\n" +
                "<h1>Contents</h1>\n" +
                "<ol>$entries</ol>\n" +
                "</nav>\n" +
                "</body>\n" +
                "</html>\n"
            ).toByteArray(Charsets.UTF_8)
    }

    /** Render `content.opf`: metadata, manifest and spine. */
    private fun contentOpf(
        book: Book,
        chapters: List<Chapter>,
        chapterHrefs: Map<Int, String>,
        imageHrefs: Map<String, String>,
    ): ByteArray {
        val language = book.language
        val creators =
            book.authors.joinToString("") { "<dc:creator>${escapeText(it)}</dc:creator>" }

        val manifest = StringBuilder()
        manifest.append(
            "<item id=\"nav\" href=\"nav.xhtml\" " +
                "media-type=\"application/xhtml+xml\" properties=\"nav\"/>",
        )
        manifest.append("<item id=\"css\" href=\"stylesheet.css\" media-type=\"text/css\"/>")
        val spine = StringBuilder()
        chapters.forEachIndexed { index, chapter ->
            val itemId = "chap${pad4(index + 1)}"
            manifest.append(
                "<item id=\"$itemId\" href=\"${chapterHrefs.getValue(chapter.index)}\" " +
                    "media-type=\"application/xhtml+xml\"/>",
            )
            spine.append("<itemref idref=\"$itemId\"/>")
        }
        // Images are manifest resources only: they carry no reading order, so never a spine
        // entry.
        book.images.forEachIndexed { position, image ->
            val href = imageHrefs[image.id] ?: return@forEachIndexed
            manifest.append(
                "<item id=\"img${pad4(position + 1)}\" href=\"$href\" " +
                    "media-type=\"${image.mediaType}\"/>",
            )
        }

        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" " +
                "unique-identifier=\"bookid\" xml:lang=\"$language\">\n" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:dcterms=\"http://purl.org/dc/terms/\">\n" +
                "<dc:identifier id=\"bookid\">${bookIdentifier(book)}</dc:identifier>\n" +
                "<dc:title>${escapeText("[${language.uppercase()}] ${book.title}")}</dc:title>\n" +
                creators +
                "<dc:language>${escapeText(language)}</dc:language>\n" +
                "<meta property=\"dcterms:modified\">$FIXED_MODIFIED</meta>\n" +
                "</metadata>\n" +
                "<manifest>$manifest</manifest>\n" +
                "<spine>$spine</spine>\n" +
                "</package>\n"
            ).toByteArray(Charsets.UTF_8)
    }

    /** Derive the stable `urn:uuid:` identifier for [book] from its metadata. */
    private fun bookIdentifier(book: Book): String {
        val seed = "berilo:${book.sourcePath}:${book.title}:${book.language}"
        return "urn:uuid:${uuid5(ID_NAMESPACE, seed)}"
    }

    // --- helpers --------------------------------------------------------------------------

    private fun deflated(name: String, data: ByteArray) =
        DeterministicZip.Member(name, data, DeterministicZip.DEFLATED)

    /** Zero-pad to four digits, locale-independently — Python's `f"{n:04d}"`. */
    private fun pad4(value: Int): String = value.toString().padStart(4, '0')

    internal companion object {

        const val LOG_TAG = "EpubWriter"

        /**
         * Namespace UUID for the per-book `dc:identifier`. An arbitrary fixed constant, looked
         * up nowhere — only its stability matters.
         */
        val ID_NAMESPACE: UUID = UUID.fromString("2c1e7b0a-2f3e-4a9b-8a3b-6a5b0c9d7e1f")

        /**
         * Fixed `dcterms:modified`: real wall-clock time would break byte-identical
         * re-assembly of the same book.
         */
        const val FIXED_MODIFIED = "2000-01-01T00:00:00Z"

        const val MIMETYPE_NAME = "mimetype"
        const val OEBPS = "OEBPS"

        /** Zip-internal directory holding carried-over image resources. */
        const val IMAGE_DIR = "images"

        const val HEADING_MIN_LEVEL = 1
        const val HEADING_MAX_LEVEL = 3

        val MIMETYPE: ByteArray = "application/epub+zip".toByteArray(Charsets.US_ASCII)

        /**
         * Inline emphasis subset the normalizer and translator produce (`EpubReader`'s
         * `EMPHASIS_TAGS`); anything else in `Segment.text` is literal content to escape, not
         * markup.
         */
        val INLINE_TAG_REGEX = Regex("</?(?:em|strong|i|b|sub|sup)>", RegexOption.IGNORE_CASE)

        /**
         * File extension per media type. The bytes are copied through unmodified so the
         * extension is cosmetic — but it must be deterministic, and an unknown type gets a
         * neutral fallback rather than being dropped.
         */
        val IMAGE_EXTENSIONS =
            mapOf(
                "image/jpeg" to ".jpg",
                "image/png" to ".png",
                "image/gif" to ".gif",
                "image/svg+xml" to ".svg",
                "image/webp" to ".webp",
                "image/tiff" to ".tif",
                "image/bmp" to ".bmp",
            )

        const val DEFAULT_IMAGE_EXTENSION = ".img"

        /**
         * Image formats that are already compressed: re-deflating them costs time and gains
         * nothing, so they are stored.
         */
        val STORED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        val CONTAINER_XML: ByteArray =
            (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<container version=\"1.0\" " +
                    "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                    "<rootfiles>\n" +
                    "<rootfile full-path=\"OEBPS/content.opf\" " +
                    "media-type=\"application/oebps-package+xml\"/>\n" +
                    "</rootfiles>\n" +
                    "</container>\n"
                ).toByteArray(Charsets.US_ASCII)

        val STYLESHEET_CSS: ByteArray =
            (
                "body { font-family: serif; }\n" +
                    "p.caption { font-style: italic; font-size: 0.9em; }\n" +
                    "p.source { color: #666666; font-size: 0.85em; " +
                    "margin-top: 0; margin-bottom: 1em; }\n" +
                    "div.figure { margin: 1em 0; text-align: center; }\n" +
                    "div.figure img { max-width: 100%; height: auto; }\n"
                ).toByteArray(Charsets.US_ASCII)
    }
}
