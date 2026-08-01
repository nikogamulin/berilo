"""Render the catalog page.

One self-contained HTML document: inline CSS, no scripts, no external fonts.
The tablet is on the LAN and may have no route to the internet at all, so
anything it cannot fetch from this server must not be needed to read the page.

Follows ``contracts/design_guidelines.md``: serif body, one accent (deep amber),
white background, high contrast, no animation, Slovenian UI strings.
"""

from __future__ import annotations

from html import escape

from berilo.serve.catalog import CatalogEntry

_ACCENT = "#B45309"

_STYLE = f"""
:root {{ color-scheme: light; }}
* {{ box-sizing: border-box; }}
body {{
  margin: 0;
  padding: 1.5rem 1.25rem 3rem;
  background: #ffffff;
  color: #1c1917;
  font-family: Literata, Charter, Georgia, "Times New Roman", serif;
  font-size: 18px;
  line-height: 1.5;
  -webkit-text-size-adjust: 100%;
}}
main {{ max-width: 40rem; margin: 0 auto; }}
h1 {{
  font-size: 1.5rem;
  letter-spacing: 0.01em;
  margin: 0 0 0.25rem;
}}
.count {{
  font-family: Inter, "Helvetica Neue", Arial, sans-serif;
  font-size: 0.85rem;
  color: #57534e;
  margin: 0 0 2rem;
}}
article {{
  border-top: 1px solid #d6d3d1;
  padding: 1.5rem 0;
}}
h2 {{
  font-size: 1.15rem;
  font-weight: 600;
  margin: 0 0 0.25rem;
}}
.authors {{ margin: 0 0 0.5rem; color: #44403c; }}
.meta {{
  font-family: Inter, "Helvetica Neue", Arial, sans-serif;
  font-size: 0.8rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #57534e;
  margin: 0 0 1rem;
}}
a.download {{
  display: block;
  padding: 0.9rem 1rem;
  border: 2px solid {_ACCENT};
  border-radius: 2px;
  color: {_ACCENT};
  font-family: Inter, "Helvetica Neue", Arial, sans-serif;
  font-size: 1rem;
  font-weight: 600;
  text-align: center;
  text-decoration: none;
}}
.empty {{ color: #57534e; }}
footer {{
  border-top: 1px solid #d6d3d1;
  margin-top: 2rem;
  padding-top: 1rem;
  font-family: Inter, "Helvetica Neue", Arial, sans-serif;
  font-size: 0.8rem;
  color: #78716c;
}}
"""

_EMPTY_MESSAGE = "V tej mapi ni nobene knjige EPUB."

_FOOTER = "Prenesi knjigo, nato jo v Berilu uvozi prek izbirnika datotek."


def format_size(size_bytes: int) -> str:
    """Format a byte count for display (e.g. ``2,4 MB``), Slovenian decimal comma."""
    megabytes = size_bytes / 1_000_000
    if megabytes >= 1:
        return f"{megabytes:.1f} MB".replace(".", ",")
    return f"{max(1, round(size_bytes / 1000))} kB"


def _render_entry(entry: CatalogEntry, token: str) -> str:
    """Render one book as an ``<article>`` block."""
    meta = [format_size(entry.size_bytes)]
    if entry.language:
        meta.append(escape(entry.language))
    if entry.distinguisher:
        meta.append(escape(entry.distinguisher))
    authors = f'<p class="authors">{escape(entry.author_line)}</p>' if entry.author_line else ""
    return (
        "<article>"
        f"<h2>{escape(entry.title)}</h2>"
        f"{authors}"
        f'<p class="meta">{" · ".join(meta)}</p>'
        f'<a class="download" href="/book/{entry.id}?t={escape(token)}"'
        f' download="{escape(entry.download_name, quote=True)}">Prenesi</a>'
        "</article>"
    )


def render_catalog_page(entries: list[CatalogEntry], token: str) -> str:
    """Render the full catalog page.

    Args:
        entries: Books to list, in display order.
        token: Per-run access token, carried into every download link.

    Returns:
        A complete, self-contained HTML document.
    """
    if entries:
        count = f"{len(entries)} {_book_word(len(entries))}"
        body = "".join(_render_entry(entry, token) for entry in entries)
    else:
        count = ""
        body = f'<p class="empty">{_EMPTY_MESSAGE}</p>'
    return (
        "<!DOCTYPE html>"
        '<html lang="sl"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        "<title>Berilo — knjižnica</title>"
        f"<style>{_STYLE}</style></head>"
        "<body><main>"
        "<h1>Berilo</h1>"
        f'<p class="count">{escape(count)}</p>'
        f"{body}"
        f"<footer>{_FOOTER}</footer>"
        "</main></body></html>"
    )


def _book_word(count: int) -> str:
    """Return the Slovenian plural form of "knjiga" for *count* (dual included)."""
    remainder = count % 100
    if remainder == 1:
        return "knjiga"
    if remainder == 2:
        return "knjigi"
    if remainder in (3, 4):
        return "knjige"
    return "knjig"
