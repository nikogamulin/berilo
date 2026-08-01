"""Tests for the LAN book server (S1.15).

Every test is offline and self-contained: books are synthetic EPUBs from
``epub_builder`` (see ``conftest.py``), the server binds ``127.0.0.1`` on an
ephemeral port, and no test touches ``data/`` or the network beyond loopback.
"""

from __future__ import annotations

import ipaddress
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections.abc import Iterator
from pathlib import Path

import pytest
from click.testing import CliRunner

from berilo.cli import cli
from berilo.serve.catalog import CatalogEntry, scan_catalog
from berilo.serve.page import format_size, render_catalog_page
from berilo.serve.server import (
    _ROUTE_PROBE_ADDRESS,
    EPUB_MEDIA_TYPE,
    BookServer,
    generate_token,
    parse_ip_addresses,
)

CHAPTER = {"id": "c1", "href": "c1.xhtml", "nav_title": "One", "body": "<p>Hello</p>"}

TOKEN = "0123456789abcdef0123456789abcdef"


@pytest.fixture
def books_dir(tmp_path: Path, epub_builder) -> Path:
    """A directory holding two readable EPUBs, a PDF, and a corrupt EPUB."""
    library = tmp_path / "library"
    library.mkdir()
    for filename, title, authors in (
        ("a.epub", "The Revenge of Geography", ["Robert D. Kaplan"]),
        ("b.epub", "Maščevanje geografije", ["Robert D. Kaplan"]),
    ):
        source = epub_builder(items=[CHAPTER], title=title, authors=authors, language="sl")
        (library / filename).write_bytes(source.read_bytes())
    (library / "notes.pdf").write_bytes(b"%PDF-1.4 not a book")
    (library / "broken.epub").write_bytes(b"this is not a zip archive")
    return library


@pytest.fixture
def server(books_dir: Path) -> Iterator[BookServer]:
    """A running server on loopback with a fixed token."""
    with BookServer(books_dir, host="127.0.0.1", port=0, token=TOKEN) as running:
        yield running


def get(server: BookServer, path: str, token: str | None = TOKEN) -> tuple[int, bytes, dict]:
    """Issue a GET against *server*, returning ``(status, body, headers)``.

    A 4xx response is reported like any other rather than raised, so tests can
    assert on rejection status codes directly.
    """
    query = f"?t={urllib.parse.quote(token)}" if token is not None else ""
    url = f"http://127.0.0.1:{server.port}{path}{query}"
    try:
        with urllib.request.urlopen(url, timeout=5) as response:  # noqa: S310 - loopback only
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers)


# --- catalog -------------------------------------------------------------


def test_scan_reads_title_and_authors_from_metadata(books_dir: Path) -> None:
    """Entries are labelled from EPUB metadata, not from the filename."""
    entries = scan_catalog(books_dir)

    titles = [entry.title for entry in entries]
    assert "The Revenge of Geography" in titles
    assert "Maščevanje geografije" in titles
    kaplan = next(entry for entry in entries if entry.title == "The Revenge of Geography")
    assert kaplan.authors == ["Robert D. Kaplan"]
    assert kaplan.language == "sl"
    assert kaplan.size_bytes > 0


def test_scan_ignores_non_epub_files(books_dir: Path) -> None:
    """A PDF sitting in the same directory is never listed."""
    assert all(entry.path.suffix == ".epub" for entry in scan_catalog(books_dir))
    assert all("notes" not in entry.title for entry in scan_catalog(books_dir))


def test_corrupt_epub_falls_back_to_its_filename(books_dir: Path) -> None:
    """An unparseable EPUB is still offered, labelled by filename."""
    entries = scan_catalog(books_dir)

    assert [entry.title for entry in entries].count("broken") == 1
    assert len(entries) == 3


def test_same_title_twice_is_disambiguated(tmp_path: Path, epub_builder) -> None:
    """Two builds of one book are tellable apart on the page and on download."""
    library = tmp_path / "variants"
    library.mkdir()
    source = epub_builder(items=[CHAPTER], title="Maščevanje geografije", language="sl")
    for filename in ("Kaplan.baseline.sl.epub", "Kaplan.revise.sl.epub"):
        (library / filename).write_bytes(source.read_bytes())

    entries = scan_catalog(library)

    assert {entry.distinguisher for entry in entries} == {
        "Kaplan.baseline.sl",
        "Kaplan.revise.sl",
    }
    assert len({entry.download_name for entry in entries}) == 2
    html = render_catalog_page(entries, TOKEN)
    assert "Kaplan.baseline.sl" in html and "Kaplan.revise.sl" in html


def test_unique_titles_carry_no_distinguisher(books_dir: Path) -> None:
    """The filename is shown only when the title alone is ambiguous."""
    assert all(entry.distinguisher == "" for entry in scan_catalog(books_dir))


def test_ids_are_stable_across_scans(books_dir: Path) -> None:
    """A bookmarked download link keeps working after a restart."""
    first = {entry.title: entry.id for entry in scan_catalog(books_dir)}
    second = {entry.title: entry.id for entry in scan_catalog(books_dir)}

    assert first == second
    assert len(set(first.values())) == len(first)


def test_scan_rejects_a_missing_directory(tmp_path: Path) -> None:
    """Scanning a path that is not a directory is a loud error."""
    with pytest.raises(NotADirectoryError):
        scan_catalog(tmp_path / "nope")


def test_download_name_is_filesystem_safe(tmp_path: Path, epub_builder) -> None:
    """Path separators and colons never reach the download filename."""
    library = tmp_path / "unsafe"
    library.mkdir()
    source = epub_builder(items=[CHAPTER], title='A/B: Notes "draft"', language="sl")
    (library / "x.epub").write_bytes(source.read_bytes())

    entry = scan_catalog(library)[0]

    assert entry.download_name == "AB Notes draft (sl).epub"
    assert "/" not in entry.download_name


@pytest.mark.parametrize(
    ("title", "language", "expected"),
    [
        ("Knjiga (sl)", "sl", "Knjiga (sl).epub"),
        ("[EN-US] The New Rules of War", "en-US", "[EN-US] The New Rules of War.epub"),
        ("Maščevanje geografije", "sl", "Maščevanje geografije (sl).epub"),
        # "Islands" contains "sl" but does not carry it as a tag.
        ("Islands", "sl", "Islands (sl).epub"),
    ],
)
def test_language_suffix_is_never_doubled(
    tmp_path: Path, epub_builder, title: str, language: str, expected: str
) -> None:
    """A title that already carries its language tag does not get a second one."""
    library = tmp_path / f"suffixed-{abs(hash(title))}"
    library.mkdir()
    source = epub_builder(items=[CHAPTER], title=title, language=language)
    (library / "x.epub").write_bytes(source.read_bytes())

    assert scan_catalog(library)[0].download_name == expected


# --- page ----------------------------------------------------------------


def test_page_lists_every_book_and_escapes_html(books_dir: Path) -> None:
    """Titles render on the page, and markup in a title cannot inject HTML."""
    entries = scan_catalog(books_dir)

    html = render_catalog_page(entries, TOKEN)

    assert "Maščevanje geografije" in html
    assert html.count('class="download"') == len(entries)
    assert TOKEN in html
    assert "<script" not in html


def test_page_escapes_markup_in_metadata() -> None:
    """A title carrying markup is rendered as text, never as HTML."""
    entry = CatalogEntry(
        id="abc123",
        path=Path("/tmp/x.epub"),
        title='<script>alert("x")</script>',
        authors=['" onload="evil()'],
        language="sl",
        size_bytes=1000,
        download_name="x.epub",
    )

    html = render_catalog_page([entry], TOKEN)

    assert "<script>" not in html
    assert "&lt;script&gt;" in html
    assert 'onload="evil()' not in html


def test_page_has_no_external_references(books_dir: Path) -> None:
    """The tablet may have no internet: the page must be self-contained."""
    html = render_catalog_page(scan_catalog(books_dir), TOKEN)

    assert "http://" not in html.replace("http://127.0.0.1", "")
    assert "https://" not in html


def test_empty_directory_renders_a_message(tmp_path: Path) -> None:
    """An empty library says so instead of rendering a blank page."""
    html = render_catalog_page([], TOKEN)

    assert "ni nobene knjige" in html


@pytest.mark.parametrize(
    ("size", "expected"),
    [(2_400_000, "2,4 MB"), (1_000_000, "1,0 MB"), (12_000, "12 kB"), (10, "1 kB")],
)
def test_size_formatting(size: int, expected: str) -> None:
    """Sizes read naturally, with a Slovenian decimal comma."""
    assert format_size(size) == expected


# --- HTTP ----------------------------------------------------------------


def test_catalog_page_requires_the_token(server: BookServer) -> None:
    """No token and a wrong token are both indistinguishable 404s."""
    assert get(server, "/", token=None)[0] == 404
    assert get(server, "/", token="f" * 32)[0] == 404


def test_catalog_page_served_with_the_token(server: BookServer) -> None:
    """With the token, the page lists the library."""
    status, body, headers = get(server, "/")

    assert status == 200
    assert headers["Content-Type"] == "text/html; charset=utf-8"
    assert "Maščevanje geografije" in body.decode("utf-8")


def test_book_downloads_byte_identical(server: BookServer, books_dir: Path) -> None:
    """The served bytes are exactly the file on disk, typed as an EPUB."""
    entry = next(e for e in scan_catalog(books_dir) if e.title == "The Revenge of Geography")

    status, body, headers = get(server, f"/book/{entry.id}")

    assert status == 200
    assert headers["Content-Type"] == EPUB_MEDIA_TYPE
    assert body == entry.path.read_bytes()
    assert zipfile.is_zipfile(entry.path)


def test_download_header_carries_non_ascii_titles(server: BookServer, books_dir: Path) -> None:
    """Šumniki survive into the filename the browser saves."""
    entry = next(e for e in scan_catalog(books_dir) if e.title == "Maščevanje geografije")

    _, _, headers = get(server, f"/book/{entry.id}")

    disposition = headers["Content-Disposition"]
    assert disposition.startswith("attachment;")
    assert "filename*=UTF-8''" in disposition
    assert urllib.parse.quote("Maščevanje geografije (sl).epub", safe="") in disposition


def test_book_download_requires_the_token(server: BookServer, books_dir: Path) -> None:
    """A valid book id without the token still yields nothing."""
    entry = scan_catalog(books_dir)[0]

    assert get(server, f"/book/{entry.id}", token=None)[0] == 404


def test_unknown_book_id_is_not_found(server: BookServer) -> None:
    """An id that is not in the catalog is a 404."""
    assert get(server, "/book/deadbeefdeadbeef")[0] == 404


@pytest.mark.parametrize(
    "path",
    [
        "/book/../../../etc/passwd",
        "/book/%2e%2e%2f%2e%2e%2fetc%2fpasswd",
        "/etc/passwd",
        "/../conftest.py",
        "/book/",
    ],
)
def test_traversal_and_stray_paths_are_not_found(server: BookServer, path: str) -> None:
    """No request path ever becomes a filesystem path."""
    status, body, _ = get(server, path)

    assert status == 404
    assert b"root:" not in body


def test_a_book_added_while_running_appears(server: BookServer, books_dir: Path, epub_builder):
    """The catalog is rescanned per request, so no restart is needed."""
    before = len(scan_catalog(books_dir))
    source = epub_builder(items=[CHAPTER], title="Pozna knjiga", language="sl")
    (books_dir / "late.epub").write_bytes(source.read_bytes())

    _, body, _ = get(server, "/")

    assert "Pozna knjiga" in body.decode("utf-8")
    assert len(scan_catalog(books_dir)) == before + 1


# --- address detection ---------------------------------------------------

# Real `ip -4 -oneline addr show` output from the box this was built on: a
# wired NIC alongside a WireGuard tunnel, a Tailscale interface and several
# Docker bridges. Only the wired address is reachable from a tablet.
_IP_ADDR_OUTPUT = """\
1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever
3: enp4s0    inet 192.168.100.3/24 brd 192.168.100.255 scope global dynamic enp4s0
4: tailscale0    inet 100.97.71.111/32 scope global tailscale0
6: br-ca72cb677f63    inet 172.29.0.1/16 brd 172.29.255.255 scope global br-ca
7: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0
9: wg-ursiv    inet 192.168.120.8/32 scope global wg-ursiv
12: virbr0    inet 192.168.122.1/24 brd 192.168.122.255 scope global virbr0
"""


def test_only_physical_interfaces_are_offered() -> None:
    """VPN, container and loopback addresses are never suggested to the tablet."""
    assert parse_ip_addresses(_IP_ADDR_OUTPUT) == ["192.168.100.3"]


def test_address_parsing_survives_junk() -> None:
    """Unparseable output yields no hint rather than raising."""
    assert parse_ip_addresses("") == []
    assert parse_ip_addresses("garbage\n1: lo\n") == []


def test_route_probe_targets_a_public_address() -> None:
    """A private probe address would resolve to whichever VPN claims that range.

    A WireGuard peer routing 10/8, 172.16/12 and 192.168/16 made the old
    10.255.255.255 probe report a tunnel address no LAN device could reach.
    """
    probe_address = ipaddress.ip_address(_ROUTE_PROBE_ADDRESS[0])

    assert probe_address.is_global


def test_generated_tokens_differ() -> None:
    """Each run gets its own token."""
    assert generate_token() != generate_token()
    assert len(generate_token()) == 32


def test_url_embeds_port_and_token(server: BookServer) -> None:
    """The printed URL is directly usable."""
    url = server.url("127.0.0.1")

    assert url == f"http://127.0.0.1:{server.port}/?t={TOKEN}"


# --- CLI -----------------------------------------------------------------


def test_serve_rejects_a_missing_directory(tmp_path: Path) -> None:
    """A bad --dir exits nonzero with a clean message, not a traceback."""
    result = CliRunner().invoke(cli, ["serve", "--dir", str(tmp_path / "nope")])

    assert result.exit_code != 0
    assert "Not a directory" in result.output


def test_serve_is_listed_in_help() -> None:
    """``berilo --help`` advertises the command."""
    result = CliRunner().invoke(cli, ["--help"])

    assert result.exit_code == 0
    assert "serve" in result.output
