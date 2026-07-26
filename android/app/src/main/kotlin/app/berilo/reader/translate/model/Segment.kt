package app.berilo.reader.translate.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The translator's shared data model, ported from `translator/berilo/models.py`.
 *
 * Every field set here mirrors the Python dataclasses exactly. That is not tidiness: segment
 * identity is derived from [Segment.chapterIndex], [Segment.position] and [Segment.text] (see
 * `Identity.kt`), so a field this port gets wrong is a book that costs money to translate twice.
 */

/** Structural role of a [Segment] within its source document. */
@Serializable
enum class SegmentType(val value: String) {
    @SerialName("heading")
    HEADING("heading"),

    @SerialName("paragraph")
    PARAGRAPH("paragraph"),

    @SerialName("list_item")
    LIST_ITEM("list_item"),

    @SerialName("blockquote")
    BLOCKQUOTE("blockquote"),

    @SerialName("caption")
    CAPTION("caption"),

    @SerialName("other")
    OTHER("other"),
    ;

    companion object {
        private val BY_VALUE = entries.associateBy(SegmentType::value)

        /**
         * Resolve a serialized type name.
         *
         * @param value The wire name, e.g. `list_item`.
         * @return The matching type.
         * @throws IllegalArgumentException If no type carries that name — mirroring Python's
         *   `SegmentType(value)`, which raises rather than defaulting.
         */
        fun ofValue(value: String): SegmentType =
            BY_VALUE[value] ?: throw IllegalArgumentException("Unknown segment type: $value")
    }
}

/**
 * A single translatable unit of a book (a paragraph, heading, ...).
 *
 * @property id Stable content hash — see `makeSegmentId`.
 * @property type Structural role of this segment.
 * @property text Segment text with an inline HTML subset preserved (`em`, `strong`, `i`, `b`,
 *   `sub`, `sup`).
 * @property chapterIndex Zero-based index of the containing chapter.
 * @property chapterTitle Title of the containing chapter, if known.
 * @property position Zero-based position in document order. **Book-global, not per-chapter**:
 *   it keeps running across chapter boundaries, exactly as `normalize_epub` numbers it.
 * @property headingLevel Heading level (1-6) when [type] is [SegmentType.HEADING], else `null`.
 */
data class Segment(
    val id: String,
    val type: SegmentType,
    val text: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val position: Int,
    val headingLevel: Int? = null,
)

/**
 * A book-level image carried from source to output, with its anchor.
 *
 * Images are **resources, not segments**: they are never translated and never occupy a document
 * position. Putting them in [Book.segments] would shift every later segment's [Segment.position],
 * change every later segment id and therefore change the book's cache identity, invalidating
 * every cached translation.
 *
 * @property id Stable per-book resource id (`img0001`), assigned in document order.
 * @property mediaType IANA media type of [data].
 * @property data The raw, unmodified image bytes.
 * @property sourceHref Where the image came from in the source — diagnostics only.
 * @property chapterIndex Zero-based index of the chapter the image belongs to.
 * @property anchorSegmentId Id of the segment this image FOLLOWS, or `null` when the image leads
 *   its chapter.
 * @property alt Alternative text, if the source supplied any.
 */
class ImageResource(
    val id: String,
    val mediaType: String,
    val data: ByteArray,
    val sourceHref: String,
    val chapterIndex: Int,
    val anchorSegmentId: String? = null,
    val alt: String? = null,
) {
    /** Value equality including the bytes — a data class would compare [data] by reference. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageResource) return false
        return id == other.id &&
            mediaType == other.mediaType &&
            data.contentEquals(other.data) &&
            sourceHref == other.sourceHref &&
            chapterIndex == other.chapterIndex &&
            anchorSegmentId == other.anchorSegmentId &&
            alt == other.alt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + sourceHref.hashCode()
        result = 31 * result + chapterIndex
        result = 31 * result + (anchorSegmentId?.hashCode() ?: 0)
        result = 31 * result + (alt?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ImageResource(id=$id, mediaType=$mediaType, bytes=${data.size}, " +
            "sourceHref=$sourceHref, chapterIndex=$chapterIndex, " +
            "anchorSegmentId=$anchorSegmentId, alt=$alt)"
}

/**
 * An ordered collection of segments plus book-level metadata.
 *
 * @property title Book title.
 * @property authors Author names, in credited order.
 * @property language Source language of the book (BCP-47 or free-form tag).
 * @property sourcePath Path to the original source file.
 * @property sourceFormat Source file format (`epub`, `pdf`, `mobi`).
 * @property segments Ordered list of segments in document order.
 * @property images Book-level image resources, in document order. Deliberately NOT part of
 *   [segments] — see [ImageResource].
 */
data class Book(
    val title: String,
    val authors: List<String>,
    val language: String,
    val sourcePath: String,
    val sourceFormat: String,
    val segments: List<Segment> = emptyList(),
    val images: List<ImageResource> = emptyList(),
) {
    /** Number of distinct chapters represented in [segments]. */
    val chapterCount: Int
        get() = segments.mapTo(mutableSetOf()) { it.chapterIndex }.size
}
