package app.berilo.reader.translate.epub

import android.util.Log
import app.berilo.reader.translate.model.Book
import app.berilo.reader.translate.model.ImageResource
import app.berilo.reader.translate.model.Segment
import app.berilo.reader.translate.model.SegmentType
import app.berilo.reader.translate.model.makeSegmentId
import app.berilo.reader.translate.model.pythonCollapseWhitespace
import app.berilo.reader.translate.model.pythonStrip
import java.io.File
import java.util.zip.ZipFile
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads an EPUB into a [Book] with the *same* segment identity the Python CLI produces.
 *
 * This is a port of `translator/berilo/normalize/epub.py`, and "port" is meant strictly. The
 * segment id is `sha1("{chapterIndex}:{position}:{text}")` and `book_hash` is a sha1 over the
 * ordered ids, so any difference in how a chapter index is assigned, how a block's text is
 * whitespace-collapsed, or which spine documents count at all changes every id from that point
 * on — and the tablet then shares no translation-cache row with the workstation, re-billing a
 * book that was already paid for. `EpubReaderIdentityTest` holds that line against the same
 * fixtures B1a generated from the real books.
 *
 * Two Python behaviours are reproduced deliberately rather than fixed, because fixing either
 * here would move `book_hash` and desynchronize the two implementations:
 *
 * - `<br/>` unwraps with no separating space, so `a<br/>b` becomes `ab` (filed as **A9**).
 * - A chapter split across several XHTML files takes one `chapterIndex` per file (**A8**).
 *
 * @property parser The XHTML parser; injectable so a test can prove the repair pass is
 *   unreachable for well-formed markup.
 */
class EpubReader internal constructor(private val parser: XhtmlParser) {

    /** Reads EPUBs with the production strict-then-repair parser. */
    constructor() : this(XhtmlParser())

    /**
     * Parse an EPUB file into a [Book].
     *
     * Resolves the OPF spine for document order and metadata, uses `toc.ncx` (or the EPUB3 nav
     * document) for chapter titles, then walks each spine document for block-level content. A
     * spine document that yields no non-empty segments (a cover page, an image-only plate, the
     * nav document itself) is skipped and **consumes no chapter slot**.
     *
     * Images are carried as book-level [ImageResource]s anchored to the segment they follow —
     * never as segments, which would shift every later segment id. A source file referenced at
     * least [RECURRING_IMAGE_MIN_REFERENCES] times is furniture (a logo, a chapter ornament)
     * and is carried once; anything referenced fewer times is a figure and keeps every
     * placement.
     *
     * @param file The `.epub` source file.
     * @return The normalized book, with segments in document order.
     * @throws EpubParseException If the container, the OPF, or any spine document is missing
     *   or malformed beyond recovery. Spine documents are never skipped.
     * @throws java.util.zip.ZipException If [file] is not a valid zip archive.
     */
    fun read(file: File): Book = ZipFile(file).use { archive -> Archive(archive).toBook(file) }

    /** One open EPUB archive, and every step of the normalization that reads from it. */
    private inner class Archive(private val zip: ZipFile) {

        /** Entry names in central-directory order, matching Python's `namelist()`. */
        private val names: List<String> =
            zip.entries().asSequence().map { it.name }.toList()

        fun toBook(file: File): Book {
            val opfPath = findOpfPath()
            val opfRoot = parse(readMember(opfPath), opfPath)
            val opfDirectory = PosixPaths.directoryName(opfPath)

            val metadata = readMetadata(opfRoot)
            val manifest = readManifest(opfRoot, opfDirectory)
            val mediaTypes = readManifestMediaTypes(opfRoot, opfDirectory)
            val spineHrefs = readSpine(opfRoot, manifest)
            val chapterTitles = readTocTitles(opfRoot, opfDirectory)

            val documents = parseSpineDocuments(spineHrefs)
            // A source file referenced this many times is furniture and is carried once; a
            // figure reused two or three times keeps every placement.
            val referenceCounts =
                documents.flatMap { it.imageRefs }.groupingBy(ImageRef::resolved).eachCount()

            val segments = mutableListOf<Segment>()
            val images = mutableListOf<ImageResource>()
            // Images referenced by a document that yields no segments (a cover page) have no
            // chapter of their own; they lead the next chapter that does get one.
            var pendingImages = mutableListOf<ImageRef>()
            val carriedFurniture = mutableSetOf<String>()
            var chapterIndex = 0
            var position = 0
            val tocResolved = chapterTitles.isNotEmpty()
            var previousTitle: String? = null

            for (document in documents) {
                val documentImages = mutableListOf<ImageRef>()
                for (reference in document.imageRefs) {
                    if ((referenceCounts[reference.resolved] ?: 0) >= RECURRING_IMAGE_MIN_REFERENCES) {
                        // Furniture: one resource per source file, so a logo repeated on every
                        // chapter is not packaged N times.
                        if (!carriedFurniture.add(reference.resolved)) continue
                    }
                    documentImages.add(reference)
                }

                if (document.blocks.isEmpty()) {
                    pendingImages.addAll(documentImages)
                    continue
                }

                val chapterTitle =
                    resolveChapterTitle(
                        document = document,
                        tocTitle = chapterTitles[document.href],
                        tocResolved = tocResolved,
                        previousTitle = previousTitle,
                        bookTitle = metadata.title,
                    )
                previousTitle = chapterTitle

                val chapterSegments =
                    document.blocks.map { block ->
                        Segment(
                            id = makeSegmentId(chapterIndex, position, block.text),
                            type = block.type,
                            text = block.text,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            position = position,
                            headingLevel = block.headingLevel,
                        ).also { position += 1 }
                    }
                segments.addAll(chapterSegments)

                // Images from earlier segment-less documents lead this chapter.
                val anchored = pendingImages.map { it.copy(anchorIndex = LEADING_ANCHOR) }
                pendingImages = mutableListOf()
                for (reference in anchored + documentImages) {
                    val anchorSegmentId =
                        if (reference.anchorIndex == LEADING_ANCHOR) {
                            null
                        } else {
                            chapterSegments[reference.anchorIndex].id
                        }
                    loadImage(
                        reference = reference,
                        mediaTypes = mediaTypes,
                        imageId = imageId(images.size + 1),
                        chapterIndex = chapterIndex,
                        anchorSegmentId = anchorSegmentId,
                    )?.let(images::add)
                }
                chapterIndex += 1
            }

            // Trailing segment-less documents (a back-cover plate) have no chapter of their
            // own: hang their images off the last segment of the book.
            if (pendingImages.isNotEmpty() && segments.isNotEmpty()) {
                val last = segments.last()
                for (reference in pendingImages) {
                    loadImage(
                        reference = reference,
                        mediaTypes = mediaTypes,
                        imageId = imageId(images.size + 1),
                        chapterIndex = last.chapterIndex,
                        anchorSegmentId = last.id,
                    )?.let(images::add)
                }
            }

            warnOnTitleCollapse(segments, metadata.title)

            return Book(
                title = metadata.title,
                authors = metadata.authors,
                language = metadata.language,
                sourcePath = file.path,
                sourceFormat = SOURCE_FORMAT,
                segments = segments,
                images = images,
            )
        }

        // --- archive access ---------------------------------------------------------------

        /**
         * Read one archive member.
         *
         * @param name Zip-internal path.
         * @return The member's bytes.
         * @throws EpubParseException If the archive has no such member.
         */
        private fun readMember(name: String, absentReason: String = ABSENT): ByteArray {
            val entry = zip.getEntry(name) ?: throw EpubParseException(name, absentReason)
            return zip.getInputStream(entry).use { it.readBytes() }
        }

        private fun parse(data: ByteArray, document: String): Element = parser.parse(data, document)

        /**
         * Resolve a manifest href to an entry that actually exists in the archive.
         *
         * Manifests can name a document that is absent under that name (repackaged books
         * rename the file but not the manifest entry). The lookup widens in three steps: exact
         * path, same basename anywhere in the archive, then — if [suffix] is given and exactly
         * one entry has it — that unique entry.
         *
         * @param href Zip-internal path as declared by the OPF manifest.
         * @param suffix Optional lowercase filename suffix used for the unique-match lookup.
         * @return The resolved path, or `null` when no candidate matches.
         */
        private fun locateMember(href: String, suffix: String? = null): String? {
            if (href in names) return href
            val basename = PosixPaths.baseName(href).lowercase()
            names.firstOrNull { PosixPaths.baseName(it).lowercase() == basename }?.let { return it }
            if (suffix != null) {
                val bySuffix = names.filter { it.lowercase().endsWith(suffix) }
                if (bySuffix.size == 1) return bySuffix.single()
            }
            return null
        }

        // --- package document -------------------------------------------------------------

        /**
         * Locate the OPF package document via `META-INF/container.xml`.
         *
         * @return The zip-internal path to the OPF package document.
         * @throws IllegalArgumentException If the container declares no OPF rootfile.
         */
        private fun findOpfPath(): String =
            parse(readMember(CONTAINER_PATH), CONTAINER_PATH)
                .selfAndDescendants()
                .filter { it.localTagName == "rootfile" }
                .firstNotNullOfOrNull { it.attributeOrNull("full-path") }
                ?: throw IllegalArgumentException("No OPF rootfile declared in $CONTAINER_PATH")

        private fun readMetadata(opfRoot: Element): EpubMetadata {
            var title = ""
            val authors = mutableListOf<String>()
            var language = ""
            for (element in opfRoot.selfAndDescendants()) {
                val text = element.leadingText.pythonStrip()
                when (element.localTagName) {
                    "title" -> if (title.isEmpty()) title = text
                    "creator" -> if (text.isNotEmpty()) authors.add(text)
                    "language" -> if (language.isEmpty()) language = text
                }
            }
            return EpubMetadata(title, authors, language)
        }

        /** Map manifest item `id` to its zip-internal path, resolved against [opfDirectory]. */
        private fun readManifest(opfRoot: Element, opfDirectory: String): Map<String, String> =
            buildMap {
                for (element in opfRoot.selfAndDescendants()) {
                    if (element.localTagName != "item") continue
                    val id = element.attributeOrNull("id") ?: continue
                    val href = element.attributeOrNull("href") ?: continue
                    put(id, PosixPaths.resolve(opfDirectory, href))
                }
            }

        /**
         * Map every manifest item's path to its declared media type.
         *
         * The manifest's own declaration is more reliable than guessing from an extension.
         */
        private fun readManifestMediaTypes(
            opfRoot: Element,
            opfDirectory: String,
        ): Map<String, String> = buildMap {
            for (element in opfRoot.selfAndDescendants()) {
                if (element.localTagName != "item") continue
                val href = element.attributeOrNull("href") ?: continue
                val mediaType = element.attributeOrNull("media-type") ?: continue
                put(PosixPaths.resolve(opfDirectory, href), mediaType)
            }
        }

        /** Spine document paths in document order, resolved through the manifest. */
        private fun readSpine(opfRoot: Element, manifest: Map<String, String>): List<String> {
            val spine =
                opfRoot.selfAndDescendants().firstOrNull { it.localTagName == "spine" }
                    ?: return emptyList()
            return spine.childElements
                .filter { it.localTagName == "itemref" }
                .mapNotNull { manifest[it.attributeOrNull("idref") ?: ""] }
        }

        // --- chapter titles ---------------------------------------------------------------

        /**
         * Build a `{document path: chapter title}` map from `toc.ncx` or the EPUB3 nav document.
         *
         * Falls through to the nav document whenever `toc.ncx` is missing, unparseable, *or*
         * yields no titles at all — an empty NCX is as useless as an absent one.
         */
        private fun readTocTitles(opfRoot: Element, opfDirectory: String): Map<String, String> {
            val items = opfRoot.selfAndDescendants().filter { it.localTagName == "item" }

            items.firstOrNull { it.attributeOrNull("media-type") == NCX_MEDIA_TYPE }
                ?.attributeOrNull("href")
                ?.let { href ->
                    val titles =
                        titlesFromMember(
                            PosixPaths.resolve(opfDirectory, href),
                            ::parseNcxTitles,
                            label = "toc.ncx",
                            suffix = NCX_SUFFIX,
                        )
                    if (titles.isNotEmpty()) return titles
                }

            items.firstOrNull { NAV_PROPERTY in (it.getAttribute("properties")).splitOnWhitespace() }
                ?.attributeOrNull("href")
                ?.let { href ->
                    val titles =
                        titlesFromMember(
                            PosixPaths.resolve(opfDirectory, href),
                            ::parseNavTitles,
                            label = "nav document",
                        )
                    if (titles.isNotEmpty()) return titles
                }

            return emptyMap()
        }

        /**
         * Parse chapter titles out of one TOC document, tolerating a renamed file.
         *
         * A TOC document that cannot be parsed even leniently is a *degradation*, not data
         * loss — no segment, heading or image lives in it, and chapter titles fall back to the
         * ladder in [resolveChapterTitle]. It is therefore the one place that warns and
         * continues rather than throwing, and its downstream consequence is reported by
         * [warnOnTitleCollapse]. A spine document, which does carry content, is never treated
         * this way.
         */
        private fun titlesFromMember(
            href: String,
            parse: (ByteArray, String, String) -> Map<String, String>,
            label: String,
            suffix: String? = null,
        ): Map<String, String> {
            val located = locateMember(href, suffix)
            if (located == null) {
                Log.w(LOG_TAG, "$label declared at $href is absent from the archive")
                return emptyMap()
            }
            if (located != href) {
                Log.w(LOG_TAG, "$label declared at $href resolved to $located in the archive")
            }
            return try {
                parse(readMember(located), PosixPaths.directoryName(located), located)
            } catch (exception: EpubParseException) {
                Log.w(LOG_TAG, "Could not parse $label at $located: ${exception.message}")
                emptyMap()
            }
        }

        /** Extract `{document path: chapter title}` from a `toc.ncx` document. */
        private fun parseNcxTitles(
            data: ByteArray,
            directory: String,
            document: String,
        ): Map<String, String> = buildMap {
            for (navPoint in parse(data, document).selfAndDescendants()) {
                if (navPoint.localTagName != "navpoint") continue
                var label: String? = null
                var source: String? = null
                for (child in navPoint.childElements) {
                    when (child.localTagName) {
                        "navlabel" ->
                            child.childElements
                                .firstOrNull { it.localTagName == "text" }
                                ?.leadingText
                                ?.takeIf(String::isNotEmpty)
                                ?.let { label = it.pythonStrip() }
                        "content" -> source = child.attributeOrNull("src")
                    }
                }
                val resolvedLabel = label
                val resolvedSource = source
                if (!resolvedLabel.isNullOrEmpty() && resolvedSource != null) {
                    putIfAbsent(
                        PosixPaths.resolve(directory, resolvedSource.substringBefore(FRAGMENT)),
                        resolvedLabel,
                    )
                }
            }
        }

        /** Extract `{document path: chapter title}` from an EPUB3 nav document's toc. */
        private fun parseNavTitles(
            data: ByteArray,
            directory: String,
            document: String,
        ): Map<String, String> {
            val tocNav =
                parse(data, document).selfAndDescendants().firstOrNull { element ->
                    element.localTagName == "nav" &&
                        NAV_TOC_TYPE in
                        element.attributeByLocalName("type").orEmpty().splitOnWhitespace()
                } ?: return emptyMap()

            return buildMap {
                for (anchor in tocNav.selfAndDescendants()) {
                    if (anchor.localTagName != "a") continue
                    val href = anchor.attributeOrNull("href") ?: continue
                    val text = anchor.allText.pythonStrip()
                    if (text.isNotEmpty()) {
                        putIfAbsent(
                            PosixPaths.resolve(directory, href.substringBefore(FRAGMENT)),
                            text,
                        )
                    }
                }
            }
        }

        /**
         * Resolve one document's chapter title.
         *
         * The ladder: the document's own TOC entry; else the previous document's title (TOC
         * entries mark chapter *starts*, and the spine is reading order, so an entry-less
         * document continues the chapter the previous one opened); else, when no TOC resolved
         * at all, the document's `<title>`, its first heading, and finally the book title. A
         * `<title>` equal to the book title carries no chapter information (Calibre stamps
         * every split document with it), so the in-document heading outranks it.
         */
        private fun resolveChapterTitle(
            document: ParsedDocument,
            tocTitle: String?,
            tocResolved: Boolean,
            previousTitle: String?,
            bookTitle: String,
        ): String {
            if (!tocTitle.isNullOrEmpty()) return tocTitle
            if (tocResolved && !previousTitle.isNullOrEmpty()) return previousTitle
            val documentTitle = fallbackTitle(document.root)
            val leadingHeading =
                document.blocks.firstOrNull { it.type == SegmentType.HEADING }?.text
            return (if (sameTitle(documentTitle, bookTitle)) null else documentTitle)
                ?: leadingHeading
                ?: documentTitle
                ?: UNTITLED
        }

        // --- spine documents --------------------------------------------------------------

        /**
         * Parse every spine document, losing none of them silently.
         *
         * @throws EpubParseException If a spine document is absent from the archive or cannot
         *   be parsed even under the leniency rule. Both were silent skips before A1: a lost
         *   spine document takes every paragraph, heading and image in it with it.
         */
        private fun parseSpineDocuments(spineHrefs: List<String>): List<ParsedDocument> {
            val parsed = mutableListOf<ParsedDocument>()
            for (href in spineHrefs) {
                val root = parse(readMember(href, SPINE_ABSENT), href)
                val body = root.selfAndDescendants().firstOrNull { it.localTagName == "body" }
                if (body == null) {
                    Log.w(LOG_TAG, "Spine document $href has no <body>; it contributes nothing")
                    continue
                }
                val directory = PosixPaths.directoryName(href)
                val blocks = mutableListOf<Block>()
                val imageRefs = mutableListOf<ImageRef>()
                for (node in iterateBlocks(body)) {
                    if (node.blockType == null) {
                        val imageHref = imageHref(node.element)
                        if (imageHref == null || imageHref.startsWith(DATA_URI_PREFIX)) continue
                        val resolved =
                            locateMember(
                                PosixPaths.resolve(directory, imageHref.substringBefore(FRAGMENT)),
                            )
                        if (resolved == null) {
                            Log.w(LOG_TAG, "Image $imageHref referenced by $href not found")
                            continue
                        }
                        imageRefs.add(
                            ImageRef(
                                anchorIndex = blocks.size - 1,
                                resolved = resolved,
                                alt = node.element.attributeOrNull("alt"),
                            ),
                        )
                        continue
                    }
                    val text = blockText(node.element)
                    if (text.isNotEmpty()) {
                        blocks.add(Block(node.blockType, node.headingLevel, text))
                    }
                }
                parsed.add(ParsedDocument(href, root, blocks, imageRefs))
            }
            return parsed
        }

        // --- images -----------------------------------------------------------------------

        /**
         * Read one referenced image out of the archive.
         *
         * @return The loaded resource, or `null` when the file cannot be read or is empty.
         */
        private fun loadImage(
            reference: ImageRef,
            mediaTypes: Map<String, String>,
            imageId: String,
            chapterIndex: Int,
            anchorSegmentId: String?,
        ): ImageResource? {
            val entry = zip.getEntry(reference.resolved)
            if (entry == null) {
                Log.w(LOG_TAG, "Image ${reference.resolved} is absent from the archive; skipping")
                return null
            }
            val data = zip.getInputStream(entry).use { it.readBytes() }
            if (data.isEmpty()) {
                Log.w(LOG_TAG, "Image ${reference.resolved} is empty; skipping")
                return null
            }
            val mediaType =
                mediaTypes[reference.resolved]
                    ?: IMAGE_MEDIA_TYPES[PosixPaths.extension(reference.resolved).lowercase()]
                    ?: DEFAULT_IMAGE_MEDIA_TYPE
            return ImageResource(
                id = imageId,
                mediaType = mediaType,
                data = data,
                sourceHref = reference.resolved,
                chapterIndex = chapterIndex,
                anchorSegmentId = anchorSegmentId,
                alt = reference.alt,
            )
        }
    }

    private companion object {

        /**
         * A source file referenced at least this many times is furniture (a logo, a chapter
         * ornament) and is carried once; anything referenced fewer times is a figure and is
         * carried at every placement.
         */
        const val RECURRING_IMAGE_MIN_REFERENCES = 3

        const val CONTAINER_PATH = "META-INF/container.xml"
        const val SOURCE_FORMAT = "epub"
        const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
        const val NCX_SUFFIX = ".ncx"
        const val NAV_PROPERTY = "nav"
        const val NAV_TOC_TYPE = "toc"
        const val FRAGMENT = "#"
        const val DATA_URI_PREFIX = "data:"
        const val UNTITLED = "Untitled"
        const val LOG_TAG = "EpubReader"
        const val ABSENT = "absent from the archive"
        const val SPINE_ABSENT = "declared in the spine but absent from the archive"

        /** Anchor index meaning "nothing precedes this image in its chapter". */
        const val LEADING_ANCHOR = -1

        /** Width of the zero-padded per-book image id (`img0001`). */
        const val IMAGE_ID_DIGITS = 4

        /** A wholly-bold paragraph longer than this is emphasized prose, not a heading. */
        const val HEADING_LIKE_MAX_CHARS = 120

        /**
         * Heading level given to a retyped heading-like paragraph. Fixed rather than inferred
         * so the type round-trips through the writer's `<h2>` unchanged.
         */
        const val RETYPED_HEADING_LEVEL = 2

        /**
         * Emit a loud error when this share of a book's segments collapses onto one chapter
         * title: that means chapter titles never resolved, and every downstream consumer that
         * keys on them is silently inert.
         */
        const val TITLE_COLLAPSE_WARN_SHARE = 0.5

        /**
         * Inline elements whose semantics are preserved as HTML in [Segment.text]; every other
         * inline or structural tag is unwrapped to its bare text content.
         */
        val EMPHASIS_TAGS = setOf("em", "strong", "i", "b", "sub", "sup")

        val HEADING_LEVELS: Map<String, Int> = (1..6).associateBy { "h$it" }

        /**
         * Block-level elements that become their own [Segment]. A matched element is not
         * descended into further: its full text, including nested inline markup, is captured
         * in one pass by [blockText].
         */
        val BLOCK_TAG_TYPES: Map<String, SegmentType> =
            buildMap {
                put("p", SegmentType.PARAGRAPH)
                put("li", SegmentType.LIST_ITEM)
                put("blockquote", SegmentType.BLOCKQUOTE)
                put("figcaption", SegmentType.CAPTION)
                put("caption", SegmentType.CAPTION)
                HEADING_LEVELS.keys.forEach { put(it, SegmentType.HEADING) }
            }

        /** Never walked for content: EPUB navigation is structure, not book prose. */
        val SKIP_TAGS = setOf("nav")

        /** `<img src>` and the SVG `<image xlink:href>` wrapper most EPUB covers use. */
        val IMAGE_TAGS = setOf("img", "image")

        /**
         * Bold markup, as a tag or a class token. Calibre MOBI→EPUB conversions emit no
         * `<h1>`-`<h6>` at all: every heading is a `<p>` whose text sits inside a
         * `<span class="bold">`. Such a paragraph is structurally a heading, so it is retyped
         * — never dropped, the 1:1 source↔target mapping must hold.
         */
        val BOLD_TAGS = setOf("b", "strong")

        val BOLD_CLASS_TOKENS = setOf("bold", "strong")

        /** Fallback media types for a file the OPF manifest declares no type for. */
        val IMAGE_MEDIA_TYPES =
            mapOf(
                ".jpg" to "image/jpeg",
                ".jpeg" to "image/jpeg",
                ".png" to "image/png",
                ".gif" to "image/gif",
                ".svg" to "image/svg+xml",
                ".webp" to "image/webp",
                ".tif" to "image/tiff",
                ".tiff" to "image/tiff",
                ".bmp" to "image/bmp",
            )

        const val DEFAULT_IMAGE_MEDIA_TYPE = "application/octet-stream"
    }

    // --- pure helpers, shared by the archive walk and its tests ---------------------------

    /** Package metadata of an EPUB: `dc:title`, every `dc:creator`, `dc:language`. */
    private data class EpubMetadata(
        val title: String,
        val authors: List<String>,
        val language: String,
    )

    /** One non-empty block of a spine document, before it is given a position. */
    private data class Block(val type: SegmentType, val headingLevel: Int?, val text: String)

    /**
     * One image reference in a spine document.
     *
     * @property anchorIndex Index of the block the image follows; [LEADING_ANCHOR] when
     *   nothing precedes it in this document.
     * @property resolved Zip-internal path the reference resolved to.
     * @property alt Alternative text from the source, if any.
     */
    private data class ImageRef(val anchorIndex: Int, val resolved: String, val alt: String?)

    /** One spine document after parsing, before chapters are assigned. */
    private data class ParsedDocument(
        val href: String,
        val root: Element,
        val blocks: List<Block>,
        val imageRefs: List<ImageRef>,
    )

    /**
     * One document-order item yielded by [iterateBlocks].
     *
     * @property element The source XHTML element.
     * @property blockType Segment type for a block-level element, or `null` when [element] is
     *   an image reference rather than translatable text.
     * @property headingLevel Heading level (1-6) for headings, else `null`.
     */
    private data class BlockNode(
        val element: Element,
        val blockType: SegmentType?,
        val headingLevel: Int? = null,
    )

    /**
     * Yield one node per block-level element or image reference, in document order.
     *
     * Does not descend into a matched block's own children for further *blocks* — its text is
     * fully captured by [blockText] — but does scan them for images, which commonly sit inside
     * a wrapper `<p>`; nor does it descend into [SKIP_TAGS].
     */
    private fun iterateBlocks(element: Element): List<BlockNode> {
        val nodes = mutableListOf<BlockNode>()
        for (child in element.childElements) {
            val tag = child.localTagName
            if (tag in SKIP_TAGS) continue
            if (tag in IMAGE_TAGS) {
                nodes.add(BlockNode(child, null))
                continue
            }
            val mappedType = BLOCK_TAG_TYPES[tag]
            if (mappedType != null) {
                var blockType = mappedType
                var headingLevel = HEADING_LEVELS[tag]
                if (tag == "p") {
                    // Round-trip class-tagged types emitted by the writer so eval alignment
                    // fingerprints survive PDF→EPUB rebuilds.
                    val cssClass = child.getAttribute("class").splitOnWhitespace()
                    when {
                        "caption" in cssClass -> blockType = SegmentType.CAPTION
                        "other" in cssClass -> blockType = SegmentType.OTHER
                        isHeadingLike(child) -> {
                            blockType = SegmentType.HEADING
                            headingLevel = RETYPED_HEADING_LEVEL
                        }
                    }
                }
                nodes.add(BlockNode(child, blockType, headingLevel))
                child.selfAndDescendants()
                    .filter { it !== child && it.localTagName in IMAGE_TAGS }
                    .forEach { nodes.add(BlockNode(it, null)) }
                continue
            }
            nodes.addAll(iterateBlocks(child))
        }
        return nodes
    }

    /**
     * Serialize an element's mixed content, whitespace un-normalized.
     *
     * Emphasis tags are kept as HTML; every other child element is unwrapped to its own
     * serialized text — including `<br/>`, which therefore contributes no separating space
     * (Python does the same; filed as **A9**, not fixed here).
     */
    private fun serializeInline(element: Element): String = buildString {
        for (node in element.visibleChildren) {
            if (node.nodeType != Node.ELEMENT_NODE) {
                append(node.nodeValue.orEmpty())
                continue
            }
            val child = node as Element
            val tag = child.localTagName
            val inner = serializeInline(child)
            if (tag in EMPHASIS_TAGS) append("<$tag>").append(inner).append("</$tag>")
            else append(inner)
        }
    }

    /** Serialize a block element to its normalized, whitespace-collapsed text. */
    private fun blockText(element: Element): String =
        serializeInline(element).pythonCollapseWhitespace()

    /** True if [element] renders its content bold, by tag or by class token. */
    private fun isBoldMarked(element: Element): Boolean =
        element.localTagName in BOLD_TAGS ||
            element.getAttribute("class").lowercase().splitOnWhitespace()
                .any { it in BOLD_CLASS_TOKENS }

    /** [element]'s visible text that lies OUTSIDE any bold-marked subtree. */
    private fun unmarkedText(element: Element): String =
        buildString {
            for (node in element.visibleChildren) {
                if (node.nodeType != Node.ELEMENT_NODE) {
                    append(node.nodeValue.orEmpty())
                    continue
                }
                val child = node as Element
                if (!isBoldMarked(child)) append(unmarkedText(child))
            }
        }.pythonCollapseWhitespace()

    /**
     * True if a `<p>` is structurally a heading: short and wholly bold.
     *
     * "Wholly bold" means every character of visible text sits inside a bold tag or a
     * bold-classed element — an emphasized phrase inside a sentence leaves text outside the
     * marked subtree and so does not qualify.
     */
    private fun isHeadingLike(element: Element): Boolean {
        if (element.selfAndDescendants().none { it !== element && isBoldMarked(it) }) return false
        if (unmarkedText(element).isNotEmpty()) return false
        val text = blockText(element)
        // Python's len() counts code points, not UTF-16 units: an astral character must not
        // count double against the 120-character cut.
        val length = text.codePointCount(0, text.length)
        return length in 1..HEADING_LIKE_MAX_CHARS
    }

    /** Fall back to an XHTML document's `<title>` when no TOC entry matches it. */
    private fun fallbackTitle(root: Element): String? =
        root.selfAndDescendants()
            .filter { it.localTagName == "title" }
            .firstNotNullOfOrNull { it.leadingText.pythonStrip().takeIf(String::isNotEmpty) }

    /**
     * Case- and whitespace-insensitive title equality.
     *
     * Python folds with `str.casefold()`, which this cannot reach on the JVM; `lowercase()`
     * differs only for full-fold expansions (`ß` → `ss`, ligatures). It is safe to differ:
     * this predicate chooses a chapter *title*, and no title reaches [makeSegmentId], so the
     * worst case is a differently-labelled chapter, never a different `book_hash`.
     */
    private fun sameTitle(left: String?, right: String?): Boolean {
        if (left.isNullOrEmpty() || right.isNullOrEmpty()) return false
        return left.pythonCollapseWhitespace().lowercase() ==
            right.pythonCollapseWhitespace().lowercase()
    }

    /** An image element's source href (`src`, or the SVG `xlink:href`). */
    private fun imageHref(element: Element): String? =
        element.attributeOrNull("src") ?: element.attributeByLocalName("href")

    /** The stable per-book resource id of the [ordinal]-th image, 1-based. */
    private fun imageId(ordinal: Int): String = "img" + ordinal.toString().padStart(IMAGE_ID_DIGITS, '0')

    /**
     * Log loudly when chapter titles have collapsed onto a single value.
     *
     * A book whose TOC never resolved gives every spine document the same chapter title. That
     * is invisible in the output but silently disables every consumer keyed on chapter titles,
     * so it is reported with the measured share.
     */
    private fun warnOnTitleCollapse(segments: List<Segment>, bookTitle: String) {
        if (segments.isEmpty()) return
        val (topTitle, topCount) =
            segments.groupingBy(Segment::chapterTitle).eachCount().maxByOrNull { it.value }
                ?: return
        val share = topCount.toDouble() / segments.size
        if (share < TITLE_COLLAPSE_WARN_SHARE) return
        Log.e(
            LOG_TAG,
            "Chapter titles did not resolve: " +
                String.format(java.util.Locale.ROOT, "%.1f", share * 100) + "% of segments " +
                "($topCount/${segments.size}) carry a single chapter title" +
                (if (sameTitle(topTitle, bookTitle)) " (the book title)" else "") +
                ". Front/back-matter folding and prose sampling key on chapter titles and " +
                "will be unreliable for this book — check that the OPF manifest names a " +
                "toc.ncx or nav document that exists in the archive.",
        )
    }
}

/**
 * Split on Python-whitespace, as `str.split()` with no argument does.
 *
 * Kotlin's `split(" ")` and `Regex("\\s+")` both differ from it — the first only on one
 * character, the second on the JVM's six-character ASCII `\s`. Class-token and `epub:type`
 * matching go through here so a token separated by a non-breaking space is still a token.
 *
 * @return The non-empty whitespace-separated tokens.
 */
private fun String.splitOnWhitespace(): List<String> =
    pythonCollapseWhitespace().split(' ').filter(String::isNotEmpty)
