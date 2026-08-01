"""LAN book server: hand translated EPUBs to a tablet over the local network.

The reader app imports books through the Android file picker, so a book has
to reach the device before it can be read. :mod:`berilo.serve` closes that
gap without a cloud round-trip: :func:`~berilo.serve.catalog.scan_catalog`
labels the EPUBs in a directory from their own metadata, and
:func:`~berilo.serve.server.serve_forever` publishes them on the LAN behind a
per-run token.

Books stay on the machine (CLAUDE.md §2): the server binds a local-network
interface and never uploads anything.
"""

from berilo.serve.catalog import CatalogEntry, scan_catalog
from berilo.serve.server import BookServer, serve_forever

__all__ = ["BookServer", "CatalogEntry", "scan_catalog", "serve_forever"]
