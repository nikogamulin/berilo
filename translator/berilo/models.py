"""Core shared data model for the Berilo translator pipeline.

Every pipeline stage (normalize, translate, verify, assemble) reads and
writes these types. ``Segment`` is the unit of translation; ``Book`` is the
ordered collection of segments plus metadata. Segment IDs are stable content
hashes so re-running a stage (or resuming a partial translation) can match
segments across runs without relying on list position alone.
"""

from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any


class SegmentType(str, Enum):
    """Structural role of a :class:`Segment` within its source document."""

    HEADING = "heading"
    PARAGRAPH = "paragraph"
    LIST_ITEM = "list_item"
    BLOCKQUOTE = "blockquote"
    CAPTION = "caption"
    OTHER = "other"


def make_segment_id(text: str, chapter_index: int, position: int) -> str:
    """Derive a stable content-hash ID for a segment.

    The ID is a sha1 hash of the normalized text plus its chapter index and
    document position, so identical text in different locations still gets
    distinct, reproducible IDs across pipeline runs.

    Args:
        text: The segment's normalized text content.
        chapter_index: Zero-based index of the chapter containing the segment.
        position: Zero-based document-order position of the segment.

    Returns:
        A 40-character hexadecimal sha1 digest.
    """
    normalized = text.strip()
    payload = f"{chapter_index}:{position}:{normalized}".encode()
    return hashlib.sha1(payload).hexdigest()


@dataclass(frozen=True)
class Segment:
    """A single translatable unit of a book (a paragraph, heading, etc.).

    Attributes:
        id: Stable content hash, see :func:`make_segment_id`.
        type: Structural role of this segment.
        text: Segment text with an inline HTML subset preserved
            (``em``, ``strong``, ``i``, ``b``, ``sub``, ``sup``).
        chapter_index: Zero-based index of the containing chapter.
        chapter_title: Title of the containing chapter, if known.
        position: Zero-based document-order position of the segment.
        heading_level: Heading level (1-6) if ``type`` is ``HEADING``,
            otherwise ``None``.
    """

    id: str
    type: SegmentType
    text: str
    chapter_index: int
    chapter_title: str | None
    position: int
    heading_level: int | None = None


@dataclass(frozen=True)
class ImageResource:
    """A book-level image carried from source to output, with its anchor.

    Images are **resources, not segments**: they are never translated and
    never occupy a document position. Inserting them into
    :attr:`Book.segments` would shift every later segment's ``position``,
    change every later segment id (see :func:`make_segment_id`) and therefore
    change the book's cache identity, invalidating every cached translation.
    Anchoring them separately keeps segment ids — and the translation cache —
    untouched.

    Attributes:
        id: Stable per-book resource id (``"img0001"``), assigned in
            document order by the normalizer.
        media_type: IANA media type of ``data`` (``"image/jpeg"``, ...).
        data: The raw, unmodified image bytes.
        source_href: Where the image came from in the source (zip-internal
            path for EPUB, ``"page<N>-<M>"`` for PDF) — for diagnostics only.
        chapter_index: Zero-based index of the chapter the image belongs to,
            matching :attr:`Segment.chapter_index`.
        anchor_segment_id: Id of the segment this image FOLLOWS, or ``None``
            when the image leads its chapter (nothing precedes it).
        alt: Alternative text, if the source supplied any.
    """

    id: str
    media_type: str
    data: bytes
    source_href: str
    chapter_index: int
    anchor_segment_id: str | None = None
    alt: str | None = None


@dataclass
class Book:
    """An ordered collection of segments plus book-level metadata.

    Attributes:
        title: Book title.
        authors: List of author names, in credited order.
        language: Source language of the book (BCP-47 or free-form tag).
        source_path: Path to the original source file.
        source_format: Source file format (``"epub"``, ``"pdf"``, ``"mobi"``).
        segments: Ordered list of segments in document order.
        images: Book-level image resources, in document order. Deliberately
            NOT part of ``segments`` — see :class:`ImageResource`.
    """

    title: str
    authors: list[str]
    language: str
    source_path: str
    source_format: str
    segments: list[Segment] = field(default_factory=list)
    images: list[ImageResource] = field(default_factory=list)

    @property
    def chapter_count(self) -> int:
        """Number of distinct chapters represented in ``segments``."""
        return len({segment.chapter_index for segment in self.segments})

    def to_json(self) -> str:
        """Serialize this book (and all segments) to a JSON string."""
        payload: dict[str, Any] = {
            "title": self.title,
            "authors": self.authors,
            "language": self.language,
            "source_path": self.source_path,
            "source_format": self.source_format,
            "segments": [
                {**asdict(segment), "type": segment.type.value} for segment in self.segments
            ],
            "images": [
                {**asdict(image), "data": base64.b64encode(image.data).decode("ascii")}
                for image in self.images
            ],
        }
        return json.dumps(payload, ensure_ascii=False, indent=2)

    @classmethod
    def from_json(cls, data: str) -> Book:
        """Deserialize a book (and all segments) from a JSON string.

        Args:
            data: JSON string previously produced by :meth:`to_json`.

        Returns:
            The reconstructed :class:`Book`.
        """
        payload = json.loads(data)
        segments = [
            Segment(
                id=segment["id"],
                type=SegmentType(segment["type"]),
                text=segment["text"],
                chapter_index=segment["chapter_index"],
                chapter_title=segment["chapter_title"],
                position=segment["position"],
                heading_level=segment.get("heading_level"),
            )
            for segment in payload["segments"]
        ]
        # ``images`` post-dates the original payload shape: books serialized
        # before S1.14 simply have no image resources.
        images = [
            ImageResource(
                id=image["id"],
                media_type=image["media_type"],
                data=base64.b64decode(image["data"]),
                source_href=image["source_href"],
                chapter_index=image["chapter_index"],
                anchor_segment_id=image.get("anchor_segment_id"),
                alt=image.get("alt"),
            )
            for image in payload.get("images", [])
        ]
        return cls(
            title=payload["title"],
            authors=payload["authors"],
            language=payload["language"],
            source_path=payload["source_path"],
            source_format=payload["source_format"],
            segments=segments,
            images=images,
        )
