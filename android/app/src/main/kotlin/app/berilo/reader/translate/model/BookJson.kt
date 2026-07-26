package app.berilo.reader.translate.model

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON serialization for [Book], matching `Book.to_json()` / `Book.from_json()` in
 * `translator/berilo/models.py` field for field.
 *
 * The payload is the interchange shape between the CLI and the app, so the surrogate DTOs below
 * pin the Python spelling of every key (`source_path`, `heading_level`, ...) rather than letting
 * Kotlin's camelCase leak into the wire. `images` and `heading_level` post-date the original
 * payload (S1.14), so both are optional on the way in — a book serialized before then still
 * loads.
 */
private val BOOK_JSON =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

@Serializable
private data class SegmentDto(
    val id: String,
    val type: SegmentType,
    val text: String,
    @SerialName("chapter_index") val chapterIndex: Int,
    @SerialName("chapter_title") val chapterTitle: String?,
    val position: Int,
    @SerialName("heading_level") val headingLevel: Int? = null,
)

@Serializable
private data class ImageResourceDto(
    val id: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("data") val dataBase64: String,
    @SerialName("source_href") val sourceHref: String,
    @SerialName("chapter_index") val chapterIndex: Int,
    @SerialName("anchor_segment_id") val anchorSegmentId: String? = null,
    val alt: String? = null,
)

@Serializable
private data class BookDto(
    val title: String,
    val authors: List<String>,
    val language: String,
    @SerialName("source_path") val sourcePath: String,
    @SerialName("source_format") val sourceFormat: String,
    val segments: List<SegmentDto> = emptyList(),
    val images: List<ImageResourceDto> = emptyList(),
)

private fun Segment.toDto() =
    SegmentDto(
        id = id,
        type = type,
        text = text,
        chapterIndex = chapterIndex,
        chapterTitle = chapterTitle,
        position = position,
        headingLevel = headingLevel,
    )

private fun SegmentDto.toModel() =
    Segment(
        id = id,
        type = type,
        text = text,
        chapterIndex = chapterIndex,
        chapterTitle = chapterTitle,
        position = position,
        headingLevel = headingLevel,
    )

private fun ImageResource.toDto() =
    ImageResourceDto(
        id = id,
        mediaType = mediaType,
        dataBase64 = Base64.getEncoder().encodeToString(data),
        sourceHref = sourceHref,
        chapterIndex = chapterIndex,
        anchorSegmentId = anchorSegmentId,
        alt = alt,
    )

private fun ImageResourceDto.toModel() =
    ImageResource(
        id = id,
        mediaType = mediaType,
        data = Base64.getDecoder().decode(dataBase64),
        sourceHref = sourceHref,
        chapterIndex = chapterIndex,
        anchorSegmentId = anchorSegmentId,
        alt = alt,
    )

/**
 * Serialize this book (and all its segments and images) to JSON.
 *
 * @return JSON text in the same shape `Book.to_json()` produces: image bytes base64-encoded,
 *   segment types written as their wire value.
 */
fun Book.toJson(): String =
    BOOK_JSON.encodeToString(
        BookDto.serializer(),
        BookDto(
            title = title,
            authors = authors,
            language = language,
            sourcePath = sourcePath,
            sourceFormat = sourceFormat,
            segments = segments.map(Segment::toDto),
            images = images.map(ImageResource::toDto),
        ),
    )

/**
 * Deserialize a book previously produced by [Book.toJson] or Python's `Book.to_json()`.
 *
 * @param json JSON text.
 * @return The reconstructed book.
 * @throws kotlinx.serialization.SerializationException If a required field is missing or a
 *   segment carries an unknown `type`.
 */
fun bookFromJson(json: String): Book {
    val dto = BOOK_JSON.decodeFromString(BookDto.serializer(), json)
    return Book(
        title = dto.title,
        authors = dto.authors,
        language = dto.language,
        sourcePath = dto.sourcePath,
        sourceFormat = dto.sourceFormat,
        segments = dto.segments.map(SegmentDto::toModel),
        images = dto.images.map(ImageResourceDto::toModel),
    )
}
