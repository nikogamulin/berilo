"""HTTP layer of the LAN book server.

Two routes, both token-gated:

======================  ====================================================
``GET /``               The catalog page.
``GET /book/<id>``      The EPUB bytes, as a download.
======================  ====================================================

Everything else — a wrong token, an unknown id, any other path — returns an
identical 404. A missing token is deliberately *not* a 401: a scanner on the
same Wi-Fi should learn nothing about what is running here.

Path traversal is structurally impossible rather than filtered: ``<id>`` is
looked up in the catalog scanned from disk, and a miss is a 404, so no part
of a request ever becomes a path component.
"""

from __future__ import annotations

import logging
import secrets
import socket
import subprocess
import threading
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

from berilo.serve.catalog import CatalogEntry, scan_catalog
from berilo.serve.page import render_catalog_page

logger = logging.getLogger(__name__)

EPUB_MEDIA_TYPE = "application/epub+zip"

_TOKEN_PARAM = "t"

# 16 bytes of entropy: not guessable on a LAN, and short enough that the QR
# code stays coarse (fewer modules scan faster on a tablet camera).
_TOKEN_BYTES = 16

_BOOK_PREFIX = "/book/"

_NOT_FOUND_BODY = b"Not found\n"

# Chunk size for streaming a book to the client. Books run to a few MB; this
# keeps the whole file out of memory without making syscalls dominate.
_CHUNK_BYTES = 64 * 1024

# Address used only to ask the kernel which local interface carries the
# default route. No packet is sent to it — a UDP connect() just selects a
# route. It must be a PUBLIC address: probing an RFC 1918 range picks
# whichever VPN claims that range (a WireGuard peer here routes all of
# 10/8, 172.16/12 and 192.168/16), yielding a tunnel address no device on
# the local network can reach.
_ROUTE_PROBE_ADDRESS = ("1.1.1.1", 1)

# Interface name prefixes that never carry a LAN address a tablet can reach:
# container and VM bridges, virtual pairs, and VPN tunnels.
_VIRTUAL_INTERFACE_PREFIXES = (
    "br-",
    "docker",
    "veth",
    "virbr",
    "wg",
    "tailscale",
    "tun",
    "tap",
    "zt",
    "lo",
)


def generate_token() -> str:
    """Return a fresh URL-safe access token for one server run."""
    return secrets.token_hex(_TOKEN_BYTES)


def detect_lan_address() -> str:
    """Return this machine's LAN IP address, or ``127.0.0.1`` if it has none.

    Asks the kernel which source address it would use to reach the public
    internet, i.e. the address on the interface holding the default route.
    Nothing is transmitted; ``connect()`` on a UDP socket only selects a route.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(_ROUTE_PROBE_ADDRESS)
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def lan_address_candidates() -> list[str]:
    """Return every local IPv4 address a device on the network might reach.

    Detection can only ever be a guess — a machine with a VPN, Docker bridges
    and a wired NIC has a dozen addresses and the kernel's routing table does
    not know which one the tablet shares a network with. Printing the
    alternatives turns a wrong guess into a visible second option instead of a
    connection that silently times out.

    Best-effort and Linux-specific: an empty list simply means no hint is
    shown.
    """
    try:
        output = subprocess.run(  # noqa: S603, S607 - fixed argv, no shell
            ["ip", "-4", "-oneline", "addr", "show"],
            capture_output=True,
            text=True,
            timeout=5,
            check=True,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    return parse_ip_addresses(output)


def parse_ip_addresses(output: str) -> list[str]:
    """Extract physical-interface IPv4 addresses from ``ip -4 -oneline addr show``.

    Args:
        output: Raw command output, one interface address per line, e.g.
            ``3: enp4s0    inet 192.168.100.3/24 brd … scope global enp4s0``.

    Returns:
        Addresses on interfaces that are not loopback, container/VM bridges,
        virtual pairs, or VPN tunnels, in the order they appear, deduplicated.
    """
    addresses: list[str] = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 4 or fields[2] != "inet":
            continue
        interface, address = fields[1], fields[3].split("/")[0]
        if interface.startswith(_VIRTUAL_INTERFACE_PREFIXES):
            continue
        if address not in addresses:
            addresses.append(address)
    return addresses


def _content_disposition(filename: str) -> str:
    """Build a ``Content-Disposition`` value that survives non-ASCII filenames.

    Emits both a stripped ASCII ``filename`` for old clients and the RFC 5987
    ``filename*`` form that carries šumniki intact.
    """
    ascii_name = filename.encode("ascii", "ignore").decode("ascii").replace('"', "")
    ascii_name = ascii_name.strip() or "book.epub"
    encoded = quote(filename, safe="")
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{encoded}"


class _Handler(BaseHTTPRequestHandler):
    """Request handler bound to a :class:`BookServer` through its server instance."""

    server_version = "Berilo"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    @property
    def _book_server(self) -> BookServer:
        return self.server.book_server  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        """Route a GET request to the catalog page or a book download."""
        parsed = urlparse(self.path)
        token = parse_qs(parsed.query).get(_TOKEN_PARAM, [""])[0]
        if not self._book_server.check_token(token):
            self._send_not_found()
            return
        if parsed.path == "/":
            self._send_catalog()
        elif parsed.path.startswith(_BOOK_PREFIX):
            self._send_book(parsed.path[len(_BOOK_PREFIX) :])
        else:
            self._send_not_found()

    def do_HEAD(self) -> None:  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        """Answer HEAD like GET without a body, as some download managers probe first."""
        self._suppress_body = True
        try:
            self.do_GET()
        finally:
            self._suppress_body = False

    def _send_catalog(self) -> None:
        """Render and send the catalog page."""
        entries = self._book_server.catalog()
        body = render_catalog_page(entries, self._book_server.token).encode("utf-8")
        self._send_bytes(HTTPStatus.OK, body, "text/html; charset=utf-8")

    def _send_book(self, book_id: str) -> None:
        """Stream the EPUB identified by *book_id*, or 404 if it is unknown."""
        entry = self._book_server.find(book_id)
        if entry is None:
            self._send_not_found()
            return
        try:
            size = entry.path.stat().st_size
            handle = entry.path.open("rb")
        except OSError as error:
            # The file vanished between the scan and this read.
            logger.warning("Could not open %s: %s", entry.path.name, error)
            self._send_not_found()
            return

        with handle:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", EPUB_MEDIA_TYPE)
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", _content_disposition(entry.download_name))
            self.send_header("Accept-Ranges", "none")
            self.end_headers()
            if getattr(self, "_suppress_body", False):
                return
            remaining = size
            while remaining > 0:
                chunk = handle.read(min(_CHUNK_BYTES, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def _send_not_found(self) -> None:
        """Send the single, uniform 404 used for every rejected request."""
        self._send_bytes(HTTPStatus.NOT_FOUND, _NOT_FOUND_BODY, "text/plain; charset=utf-8")

    def _send_bytes(self, status: HTTPStatus, body: bytes, content_type: str) -> None:
        """Send a complete small response."""
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not getattr(self, "_suppress_body", False):
            self.wfile.write(body)

    def log_message(self, format: str, *args) -> None:  # noqa: A002 - signature is fixed
        """Log one line per request through the module logger, not stderr.

        Local time, not UTC: this line exists to be read live in a terminal.
        """
        timestamp = datetime.now().strftime("%H:%M:%S")  # noqa: DTZ005
        logger.info("%s %s %s", timestamp, self.client_address[0], format % args)


class _ThreadingServer(ThreadingHTTPServer):
    """``ThreadingHTTPServer`` carrying a back-reference to its :class:`BookServer`."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], book_server: BookServer) -> None:
        self.book_server = book_server
        super().__init__(address, _Handler)


class BookServer:
    """A token-gated HTTP server publishing the EPUBs in one directory.

    The catalog is rescanned on every request rather than cached: the
    directory holds a handful of files, and a rescan means a book dropped in
    while the server runs shows up on the next refresh with no restart.

    Attributes:
        directory: Directory scanned for books.
        token: Per-run access token required by every request.
    """

    def __init__(
        self,
        directory: Path,
        *,
        host: str = "0.0.0.0",  # noqa: S104 - must be reachable from the tablet
        port: int = 8080,
        token: str | None = None,
    ) -> None:
        """Create the server and bind its socket.

        Args:
            directory: Directory to serve EPUBs from.
            host: Interface to bind. The default accepts LAN connections;
                pass ``127.0.0.1`` to keep it on this machine.
            port: TCP port; ``0`` binds an arbitrary free port.
            token: Access token; a fresh one is generated when omitted.

        Raises:
            NotADirectoryError: If *directory* is not a directory.
            OSError: If the address is already in use.
        """
        if not directory.is_dir():
            raise NotADirectoryError(f"Not a directory: {directory}")
        self.directory = directory
        self.token = token or generate_token()
        self._server = _ThreadingServer((host, port), self)
        self._thread: threading.Thread | None = None

    @property
    def port(self) -> int:
        """The bound port (resolved, when the server was created with port 0)."""
        return self._server.server_address[1]

    def url(self, host: str | None = None) -> str:
        """Return the tokenized catalog URL, defaulting to the detected LAN address."""
        return f"http://{host or detect_lan_address()}:{self.port}/?t={self.token}"

    def check_token(self, candidate: str) -> bool:
        """Constant-time comparison of *candidate* against this run's token."""
        return secrets.compare_digest(candidate, self.token)

    def catalog(self) -> list[CatalogEntry]:
        """Scan the served directory and return its entries."""
        try:
            return scan_catalog(self.directory)
        except OSError as error:
            logger.warning("Could not scan %s: %s", self.directory, error)
            return []

    def find(self, book_id: str) -> CatalogEntry | None:
        """Return the catalog entry with *book_id*, or ``None`` if there is none."""
        return next((entry for entry in self.catalog() if entry.id == book_id), None)

    def start(self) -> None:
        """Serve requests on a background thread."""
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stop serving and release the socket."""
        self._server.shutdown()
        self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None

    def serve_forever(self) -> None:
        """Serve on the calling thread until interrupted."""
        try:
            self._server.serve_forever()
        finally:
            self._server.server_close()

    def __enter__(self) -> BookServer:
        self.start()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.stop()


def serve_forever(
    directory: Path, *, host: str = "0.0.0.0", port: int = 8080
) -> None:  # noqa: S104
    """Bind a :class:`BookServer` on *directory* and serve until interrupted."""
    BookServer(directory, host=host, port=port).serve_forever()
