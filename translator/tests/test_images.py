"""S1.14: images carried through normalize → translate → assemble.

Images are book-level RESOURCES, never segments. The cost-safety test in this
module (``test_book_hash_is_unchanged_by_adding_images``) is the one that
makes the design mandatory rather than tidy: ``book_hash`` is a sha1 over the
ordered segment ids and every cached translation is keyed on it, so an
implementation that inserted image segments would shift every later segment
position, change every later id, miss every cache row, and force a paid
re-translation of the whole library.
"""

from __future__ import annotations

import shutil
import subprocess
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

import fitz
import pytest

from berilo.assemble import build_epub
from berilo.cache import book_hash
from berilo.eval.rubric_t import align
from berilo.models import Book, ImageResource, Segment, SegmentType
from berilo.normalize.epub import normalize_epub
from berilo.normalize.pdf import (
    FULL_PAGE_IMAGE_AREA_RATIO,
    MIN_IMAGE_DIMENSION_PX,
    RECURRING_IMAGE_MIN_PAGES,
    normalize_pdf,
)

EPUBCHECK = shutil.which("epubcheck")

_BODY_SIZE = 10.0
_LEFT_MARGIN = 72.0


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


def _segment(text: str, position: int, chapter_index: int = 0) -> Segment:
    return Segment(
        id=f"seg{position}",
        type=SegmentType.PARAGRAPH,
        text=text,
        chapter_index=chapter_index,
        chapter_title="Chapter One",
        position=position,
        heading_level=None,
    )


def _book(images: list[ImageResource] | None = None) -> Book:
    return Book(
        title="Sample Book",
        authors=["Jane Doe"],
        language="sl",
        source_path="/tmp/sample.epub",
        source_format="epub",
        segments=[_segment("First paragraph.", 0), _segment("Second paragraph.", 1)],
        images=images or [],
    )


def _image(
    image_id: str,
    data: bytes,
    *,
    anchor: str | None = "seg0",
    chapter_index: int = 0,
    alt: str | None = None,
) -> ImageResource:
    return ImageResource(
        id=image_id,
        media_type="image/png",
        data=data,
        source_href=f"OEBPS/{image_id}.png",
        chapter_index=chapter_index,
        anchor_segment_id=anchor,
        alt=alt,
    )


def _image_entries(epub_path: Path) -> list[str]:
    """Names of the image files packaged inside an assembled EPUB."""
    with zipfile.ZipFile(epub_path) as archive:
        return sorted(name for name in archive.namelist() if name.startswith("OEBPS/images/"))


def _chapter_bodies(epub_path: Path) -> dict[str, str]:
    with zipfile.ZipFile(epub_path) as archive:
        return {
            name: archive.read(name).decode()
            for name in archive.namelist()
            if name.startswith("OEBPS/chap_")
        }


def _source_epub_with_images(epub_builder, png_image) -> Path:
    """A synthetic 2-chapter EPUB carrying three distinct image files."""
    return epub_builder(
        items=[
            {
                "id": "cover",
                "href": "cover.xhtml",
                # Image-only document: no prose, so it yields no chapter.
                "body": '<div><img src="img/cover.png" alt="Cover"/></div>',
                "nav_title": "Cover",
            },
            {
                "id": "c1",
                "href": "c1.xhtml",
                "body": (
                    "<h1>Chapter One</h1>"
                    "<p>The first paragraph of chapter one.</p>"
                    '<p class="image"><img src="img/figure1.png" alt="A figure"/></p>'
                    '<p class="caption">Figure 1: the first figure</p>'
                    "<p>The second paragraph of chapter one.</p>"
                ),
                "nav_title": "Chapter One",
            },
            {
                "id": "c2",
                "href": "c2.xhtml",
                "body": (
                    "<h1>Chapter Two</h1>"
                    "<p>Chapter two opens here.</p>"
                    '<div><img src="img/figure2.png" alt="Another figure"/></div>'
                    # The same file referenced twice must package one resource.
                    '<div><img src="img/figure2.png"/></div>'
                ),
                "nav_title": "Chapter Two",
            },
        ],
        image_items=[
            {
                "id": "imgcover",
                "href": "img/cover.png",
                "media_type": "image/png",
                "data": png_image(200, 300, (10, 10, 10)),
            },
            {
                "id": "imgfig1",
                "href": "img/figure1.png",
                "media_type": "image/png",
                "data": png_image(120, 90, (200, 30, 30)),
            },
            {
                "id": "imgfig2",
                "href": "img/figure2.png",
                "media_type": "image/png",
                "data": png_image(140, 100, (30, 200, 30)),
            },
        ],
    )


# --------------------------------------------------------------------------- #
# Cost safety: images must not touch the cache identity of a book
# --------------------------------------------------------------------------- #


def test_book_hash_is_unchanged_by_adding_images(png_image) -> None:
    """Adding images must not re-key a single cached translation.

    ``book_hash`` covers the ordered segment ids and the translation cache PK
    is ``(book_hash, segment_hash, model, lang, prompt_version)``. If images
    were segments, every later segment's ``position`` — and therefore its id —
    would shift, and every already-paid-for translation in the library would
    have to be bought again.
    """
    before = _book()
    hash_before = book_hash(before)

    after = _book(
        images=[
            _image("img0001", png_image(120, 90), anchor=None),
            _image("img0002", png_image(140, 100), anchor="seg1"),
        ]
    )

    assert book_hash(after) == hash_before
    assert [segment.id for segment in after.segments] == [segment.id for segment in before.segments]


def test_images_round_trip_through_book_json(png_image) -> None:
    data = png_image(120, 90)
    book = _book(images=[_image("img0001", data, anchor="seg1", alt="A figure")])

    restored = Book.from_json(book.to_json())

    assert restored.images == book.images
    assert restored.images[0].data == data
    assert book_hash(restored) == book_hash(book)


def test_book_json_without_images_key_still_loads() -> None:
    """Books serialized before S1.14 have no ``images`` key."""
    payload = _book().to_json().replace('"images": []', '"unused": []')

    restored = Book.from_json(payload)

    assert restored.images == []


# --------------------------------------------------------------------------- #
# Assembler
# --------------------------------------------------------------------------- #


def test_images_are_packaged_and_declared_in_the_manifest(tmp_path: Path, png_image) -> None:
    data = png_image(120, 90)
    book = _book(images=[_image("img0001", data, anchor="seg0")])

    output = build_epub(book, tmp_path / "out.epub")

    assert _image_entries(output) == ["OEBPS/images/img_0001.png"]
    with zipfile.ZipFile(output) as archive:
        assert archive.read("OEBPS/images/img_0001.png") == data
        opf = archive.read("OEBPS/content.opf").decode()
    assert '<item id="img0001" href="images/img_0001.png" media-type="image/png"/>' in opf
    # Resources carry no reading order.
    assert 'idref="img0001"' not in opf


def test_image_is_rendered_after_its_anchor_segment(tmp_path: Path, png_image) -> None:
    book = _book(images=[_image("img0001", png_image(120, 90), anchor="seg0", alt="A figure")])

    output = build_epub(book, tmp_path / "out.epub")

    body = next(iter(_chapter_bodies(output).values()))
    first = body.index("First paragraph.")
    image = body.index("images/img_0001.png")
    second = body.index("Second paragraph.")
    assert first < image < second
    assert 'alt="A figure"' in body


def test_leading_image_is_rendered_before_the_first_segment(tmp_path: Path, png_image) -> None:
    book = _book(images=[_image("img0001", png_image(120, 90), anchor=None)])

    output = build_epub(book, tmp_path / "out.epub")

    body = next(iter(_chapter_bodies(output).values()))
    assert body.index("images/img_0001.png") < body.index("First paragraph.")


def test_build_epub_with_images_is_byte_identical_across_runs(tmp_path: Path, png_image) -> None:
    images = [
        _image("img0001", png_image(120, 90), anchor="seg0"),
        _image("img0002", png_image(140, 100), anchor="seg1"),
    ]
    first = build_epub(_book(images=images), tmp_path / "first.epub")
    second = build_epub(_book(images=images), tmp_path / "second.epub")

    assert first.read_bytes() == second.read_bytes()


def test_bilingual_render_keeps_images_and_source_paragraphs(tmp_path: Path, png_image) -> None:
    translated = _book(images=[_image("img0001", png_image(120, 90), anchor="seg0")])
    source = _book(images=list(translated.images))
    source.segments = [_segment("Prvi odstavek.", 0), _segment("Drugi odstavek.", 1)]

    output = build_epub(translated, tmp_path / "bilingual.epub", bilingual=True, source_book=source)

    body = next(iter(_chapter_bodies(output).values()))
    assert '<p class="source">Prvi odstavek.</p>' in body
    assert body.index('<p class="source">Prvi odstavek.</p>') < body.index("images/img_0001.png")


def test_image_anchored_to_a_list_item_is_emitted_after_the_list(tmp_path: Path, png_image) -> None:
    """A ``div`` may not sit between ``<li>`` elements — the list stays valid."""
    book = _book()
    book.segments = [
        Segment(
            id="li0",
            type=SegmentType.LIST_ITEM,
            text="First item",
            chapter_index=0,
            chapter_title="Chapter One",
            position=0,
        ),
        Segment(
            id="li1",
            type=SegmentType.LIST_ITEM,
            text="Second item",
            chapter_index=0,
            chapter_title="Chapter One",
            position=1,
        ),
    ]
    book.images = [_image("img0001", png_image(120, 90), anchor="li0")]

    output = build_epub(book, tmp_path / "out.epub")

    body = next(iter(_chapter_bodies(output).values()))
    assert body.index("</ul>") < body.index("images/img_0001.png")
    ET.fromstring(body)  # the chapter document stays well-formed XHTML


# --------------------------------------------------------------------------- #
# EPUB normalize → assemble round trip
# --------------------------------------------------------------------------- #


def test_epub_image_count_survives_normalize_then_assemble(
    tmp_path: Path, epub_builder, png_image
) -> None:
    """The S1.14 defect, pinned: sources had images, outputs had none."""
    source_path = _source_epub_with_images(epub_builder, png_image)
    with zipfile.ZipFile(source_path) as archive:
        source_images = [name for name in archive.namelist() if name.startswith("img/")]

    book = normalize_epub(source_path)
    output = build_epub(book, tmp_path / "out.epub")

    assert len(source_images) == 3
    assert len(book.images) == 3
    assert len(_image_entries(output)) == len(source_images)


def test_epub_image_bytes_are_carried_through_unmodified(
    tmp_path: Path, epub_builder, png_image
) -> None:
    source_path = _source_epub_with_images(epub_builder, png_image)

    book = normalize_epub(source_path)
    output = build_epub(book, tmp_path / "out.epub")

    with zipfile.ZipFile(source_path) as source, zipfile.ZipFile(output) as built:
        source_bytes = {source.read(f"img/{name}.png") for name in ("cover", "figure1", "figure2")}
        built_bytes = {built.read(name) for name in _image_entries(output)}
    assert built_bytes == source_bytes


def test_repeated_reference_to_one_file_packages_one_resource(epub_builder, png_image) -> None:
    book = normalize_epub(_source_epub_with_images(epub_builder, png_image))

    hrefs = [image.source_href for image in book.images]
    assert len(hrefs) == len(set(hrefs))


def test_epub_image_anchors_to_the_segment_it_follows(epub_builder, png_image) -> None:
    book = normalize_epub(_source_epub_with_images(epub_builder, png_image))
    by_id = {segment.id: segment for segment in book.segments}

    figure1 = next(image for image in book.images if image.source_href.endswith("figure1.png"))

    assert by_id[figure1.anchor_segment_id].text == "The first paragraph of chapter one."


def test_cover_page_image_leads_the_next_chapter(epub_builder, png_image) -> None:
    """A document with no prose yields no chapter, so its image leads the next."""
    book = normalize_epub(_source_epub_with_images(epub_builder, png_image))

    cover = next(image for image in book.images if image.source_href.endswith("cover.png"))

    assert cover.anchor_segment_id is None
    assert cover.chapter_index == book.segments[0].chapter_index


def test_images_do_not_add_segments(epub_builder, png_image) -> None:
    """The whole cost argument: image references must mint no segments."""
    with_images = normalize_epub(_source_epub_with_images(epub_builder, png_image))
    without_images = normalize_epub(
        epub_builder(
            items=[
                {
                    "id": "c1",
                    "href": "c1.xhtml",
                    "body": (
                        "<h1>Chapter One</h1>"
                        "<p>The first paragraph of chapter one.</p>"
                        '<p class="caption">Figure 1: the first figure</p>'
                        "<p>The second paragraph of chapter one.</p>"
                    ),
                    "nav_title": "Chapter One",
                },
                {
                    "id": "c2",
                    "href": "c2.xhtml",
                    "body": "<h1>Chapter Two</h1><p>Chapter two opens here.</p>",
                    "nav_title": "Chapter Two",
                },
            ]
        )
    )

    assert [segment.text for segment in with_images.segments] == [
        segment.text for segment in without_images.segments
    ]
    assert book_hash(with_images) == book_hash(without_images)


def test_assembled_epub_with_images_still_aligns_on_re_normalization(
    tmp_path: Path, epub_builder, png_image
) -> None:
    """``rubric_t.align`` must survive the rebuilt book: no new/dropped segments."""
    book = normalize_epub(_source_epub_with_images(epub_builder, png_image))
    rebuilt = normalize_epub(build_epub(book, tmp_path / "out.epub"))

    alignment = align(book, rebuilt)

    assert alignment.source_count == alignment.target_count == len(book.segments)
    assert len(alignment.translated_pairs) == len(book.segments)


@pytest.mark.skipif(EPUBCHECK is None, reason="epubcheck not installed")
def test_epubcheck_passes_with_images(tmp_path: Path, epub_builder, png_image) -> None:
    book = normalize_epub(_source_epub_with_images(epub_builder, png_image))
    output = build_epub(book, tmp_path / "out.epub")

    result = subprocess.run([EPUBCHECK, str(output)], capture_output=True, text=True, timeout=60)

    assert result.returncode == 0, result.stdout + result.stderr


# --------------------------------------------------------------------------- #
# PDF extraction
# --------------------------------------------------------------------------- #


_LINE_LEADING = 16.0
_INDENT_X = 90.0

_PageSpec = tuple[list[tuple[float, float, str]], list[tuple[fitz.Rect, bytes]]]


def _paragraph(start_y: float, lines: list[str]) -> list[tuple[float, float, str]]:
    """Lay a paragraph out as indented-first-line, regularly leaded body lines."""
    return [
        (_INDENT_X if index == 0 else _LEFT_MARGIN, start_y + index * _LINE_LEADING, text)
        for index, text in enumerate(lines)
    ]


# Four lines each: enough body lines at the column margin that the modal-x0
# margin estimate lands on it and the indented first lines read as indents.
_INTRO = [
    "The paragraph that introduces the figure",
    "shown just below it on this page, running",
    "long enough for the reflow heuristics to",
    "see an ordinary body paragraph here.",
]
_FOLLOW = [
    "The paragraph that follows the figure",
    "and closes out the page cleanly, again",
    "long enough that the column margin is",
    "estimated from real body lines.",
]


def _pdf_with_images(tmp_path: Path, pages: list[_PageSpec], name: str = "images.pdf") -> Path:
    """Build a synthetic PDF from ``[(text lines, images)]`` page specs.

    Args:
        tmp_path: Directory to write into.
        pages: One entry per page: ``([(x, y, text)], [(rect, png bytes)])``.
        name: Output filename.

    Returns:
        The written PDF path.
    """
    doc = fitz.open()
    for lines, images in pages:
        page = doc.new_page()
        for x, y, text in lines:
            page.insert_text((x, y), text, fontsize=_BODY_SIZE)
        for rect, data in images:
            page.insert_image(rect, stream=data)
    doc.set_metadata({"title": "Illustrated Book", "author": "A. Author"})
    path = tmp_path / name
    doc.save(str(path))
    doc.close()
    return path


def _figure_page(png_image, *, caption: str | None = None) -> _PageSpec:
    """One page: a paragraph, a figure, an optional caption, a closing paragraph."""
    lines = _paragraph(100.0, _INTRO)
    if caption is not None:
        lines += [(_INDENT_X, 316.0, caption)]
    lines += _paragraph(360.0, _FOLLOW)
    return lines, [(fitz.Rect(72, 150, 272, 300), png_image(120, 90))]


def _image_dimensions(data: bytes) -> tuple[int, int]:
    pixmap = fitz.Pixmap(data)
    return pixmap.width, pixmap.height


def test_pdf_figure_is_extracted_and_anchored_to_the_preceding_paragraph(
    tmp_path: Path, png_image
) -> None:
    book = normalize_pdf(_pdf_with_images(tmp_path, [_figure_page(png_image)]))

    assert len(book.images) == 1
    anchor = next(s for s in book.segments if s.id == book.images[0].anchor_segment_id)
    assert anchor.text.startswith("The paragraph that introduces")
    assert book.images[0].media_type == "image/png"


def test_pdf_images_do_not_change_segments_or_book_hash(tmp_path: Path, png_image) -> None:
    lines, images = _figure_page(png_image)
    with_image = normalize_pdf(_pdf_with_images(tmp_path, [(lines, images)], name="with.pdf"))
    without_image = normalize_pdf(_pdf_with_images(tmp_path, [(lines, [])], name="without.pdf"))

    assert [segment.id for segment in with_image.segments] == [
        segment.id for segment in without_image.segments
    ]
    assert book_hash(with_image) == book_hash(without_image)


def test_pdf_tiny_decorative_image_is_skipped(tmp_path: Path, png_image) -> None:
    tiny = MIN_IMAGE_DIMENSION_PX - 1
    lines, _ = _figure_page(png_image)
    path = _pdf_with_images(
        tmp_path, [(lines, [(fitz.Rect(72, 150, 100, 178), png_image(tiny, tiny))])]
    )

    assert normalize_pdf(path).images == []


def test_pdf_full_page_scan_is_skipped(tmp_path: Path, png_image) -> None:
    """OCR-sourced PDFs hide the whole page bitmap behind the text layer."""
    lines, _ = _figure_page(png_image)
    doc = fitz.open()
    page = doc.new_page()
    page.insert_image(page.rect, stream=png_image(400, 560))
    for x, y, text in lines:
        page.insert_text((x, y), text, fontsize=_BODY_SIZE)
    path = tmp_path / "scan.pdf"
    doc.save(str(path))
    doc.close()

    book = normalize_pdf(path)

    assert book.segments  # the text layer still normalizes
    assert book.images == []
    assert FULL_PAGE_IMAGE_AREA_RATIO < 1.0


def test_pdf_recurring_logo_is_dropped_as_furniture(tmp_path: Path, png_image) -> None:
    logo = png_image(80, 80, (7, 7, 7))
    figure = png_image(120, 90, (240, 10, 10))
    pages: list[_PageSpec] = [
        (
            _paragraph(100.0, _INTRO) + _paragraph(360.0, _FOLLOW),
            [(fitz.Rect(72, 150, 152, 230), logo)],
        )
        for _ in range(RECURRING_IMAGE_MIN_PAGES)
    ]
    pages[0][1].append((fitz.Rect(300, 150, 500, 300), figure))

    book = normalize_pdf(_pdf_with_images(tmp_path, pages))

    assert len(book.images) == 1
    assert _image_dimensions(book.images[0].data) == _image_dimensions(figure)


def test_pdf_identical_image_repeated_twice_is_packaged_once(tmp_path: Path, png_image) -> None:
    figure = png_image(120, 90)
    pages: list[_PageSpec] = [
        (
            _paragraph(100.0, _INTRO) + _paragraph(360.0, _FOLLOW),
            [(fitz.Rect(72, 150, 272, 300), figure)],
        )
        for _ in range(2)
    ]

    book = normalize_pdf(_pdf_with_images(tmp_path, pages))

    assert len(book.images) == 1


def test_pdf_caption_is_re_attached_below_its_image(tmp_path: Path, png_image) -> None:
    """A caption directly under an image must follow that image in the output."""
    path = _pdf_with_images(tmp_path, [_figure_page(png_image, caption="(National Diet Library)")])

    book = normalize_pdf(path)
    positions = {segment.id: segment.position for segment in book.segments}
    caption = next(s for s in book.segments if s.type is SegmentType.CAPTION)

    assert len(book.images) == 1
    anchor_id = book.images[0].anchor_segment_id
    assert anchor_id is not None
    assert positions[anchor_id] == caption.position - 1


def test_pdf_caption_printed_above_its_plate_still_follows_the_image(
    tmp_path: Path, png_image
) -> None:
    """The case the geometric anchor alone gets wrong.

    When the figure line is printed ABOVE the plate, the last block starting
    at or above the image IS the caption — so a purely geometric anchor would
    render the image after its own caption. Re-attachment moves the image in
    front of it.
    """
    lines = (
        _paragraph(100.0, _INTRO)
        + [(_INDENT_X, 190.0, "(National Diet Library)")]
        + _paragraph(400.0, _FOLLOW)
    )
    path = _pdf_with_images(
        tmp_path, [(lines, [(fitz.Rect(72, 200, 272, 350), png_image(120, 90))])]
    )

    book = normalize_pdf(path)
    positions = {segment.id: segment.position for segment in book.segments}
    caption = next(s for s in book.segments if s.type is SegmentType.CAPTION)

    assert len(book.images) == 1
    assert positions[book.images[0].anchor_segment_id] == caption.position - 1


def test_pdf_ambiguous_captions_leave_the_image_at_its_geometric_anchor(
    tmp_path: Path, png_image
) -> None:
    """Two captions near one image is ambiguous — neither gets re-attached."""
    lines = (
        _paragraph(100.0, _INTRO)
        + [
            (_INDENT_X, 316.0, "(National Diet Library)"),
            (_INDENT_X, 332.0, "(Library of Congress)"),
        ]
        + _paragraph(380.0, _FOLLOW)
    )
    path = _pdf_with_images(
        tmp_path, [(lines, [(fitz.Rect(72, 150, 272, 300), png_image(120, 90))])]
    )

    book = normalize_pdf(path)
    anchor = next(s for s in book.segments if s.id == book.images[0].anchor_segment_id)

    assert anchor.text.startswith("The paragraph that introduces")


def test_pdf_images_reach_the_assembled_epub(tmp_path: Path, png_image) -> None:
    book = normalize_pdf(_pdf_with_images(tmp_path, [_figure_page(png_image)]))
    output = build_epub(book, tmp_path / "out.epub")

    assert len(_image_entries(output)) == len(book.images) == 1
