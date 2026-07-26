# LAN book server — design (S1.15)

> Status: implemented 2026-07-26. Offline Verify green (306 tests, lint clean);
> live LAN verification against the Samsung tablet is the open residual.

## 1. Problem

The reader app imports books through the Android file picker, so a translated
EPUB has to already be on the device before it can be opened. Today that means
USB, `adb push`, or a cloud round-trip — the last of which is exactly what
CLAUDE.md §2 forbids for book files.

`berilo serve` closes the gap: the workstation publishes its translated EPUBs
on the local network, the tablet downloads one over Wi-Fi, and the file never
leaves the LAN.

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Receiving end | Tablet browser → Downloads → existing SAF import | Zero Android changes; the import path already works |
| Catalog source | Scan a directory (`--dir`, default `data/examples`) | No manifest to maintain; drop a file in and refresh |
| Discovery | Terminal QR code of a tokenized URL | No typing an IP on a tablet keyboard |
| Access control | Random per-run token in the URL | Free given the QR; a port scan on the same Wi-Fi gets 404s |
| HTTP stack | stdlib `ThreadingHTTPServer` | FastAPI + uvicorn is two heavy deps to serve a dozen files |

Rejected: OPDS (needs client support in Berilo to be one-tap); an in-app
"Nearby" screen (best UX, but Android work this does not need); mDNS
(`berilo.local` is unreliable on Android Chrome); idle auto-shutdown (the
token dies with the process, which covers the same risk).

## 3. Modules

- **`berilo/serve/catalog.py`** — directory → `list[CatalogEntry]`. Pure
  filesystem work, no HTTP knowledge. Labels each book from its own OPF
  metadata rather than its filename, because books arrive named
  `Title (Author) (z-library.sk, …).epub`. Assigns an opaque, stable id
  (truncated sha256 of the resolved path) and a clean download filename.
- **`berilo/serve/page.py`** — `list[CatalogEntry]` → one self-contained HTML
  document. Inline CSS, no scripts, no external fonts: the tablet may have no
  route to the internet while on this Wi-Fi. Follows `design_guidelines.md`
  (serif body, deep amber `#B45309`, white, no animation), Slovenian strings.
- **`berilo/serve/server.py`** — `BookServer` over `ThreadingHTTPServer`:
  routing, token check, headers, streaming.
- **`berilo/cli.py`** — the `serve` command: LAN-IP detection, QR render,
  lifecycle.

`normalize/epub.py` gained a public `read_epub_metadata(path)` (and an
`EpubMetadata` dataclass) wrapping the existing private OPF reader; the full
`normalize_epub` walk is far too expensive just to label a file.

## 4. HTTP surface

| Route | Behavior |
|---|---|
| `GET /?t=<token>` | Catalog page |
| `GET /book/<id>?t=<token>` | EPUB bytes, `application/epub+zip`, `Content-Disposition: attachment` with RFC 5987 `filename*` so šumniki survive |
| anything else | 404, identical body |

- A missing or wrong token returns **404, not 401** — a scanner learns nothing.
  Compared with `secrets.compare_digest`.
- **Path traversal is structurally impossible, not filtered**: `<id>` is looked
  up in the catalog scanned from disk and a miss is a 404, so no part of a
  request ever becomes a path component.
- Only `*.epub` directly in the directory is listed or served — no recursion.
- Binds `0.0.0.0` by default because it must be reachable; `--host 127.0.0.1`
  keeps it local.
- The catalog is rescanned per request: a book dropped in while the server
  runs appears on the next refresh, no restart.

## 5. Behavior learned from real data

Two things showed up only against `data/examples/`, not the synthetic fixtures:

1. **Duplicate titles.** `Kaplan.baseline.sl.epub`, `Kaplan.revise.sl.epub` and
   `The Revenge of Geography.sl.epub` carry identical metadata, so the page
   showed the same title three times and all three would have downloaded under
   one filename. Entries sharing a title+authors now carry a `distinguisher`
   (the filename stem), shown in the meta line and folded into the download
   name.
2. **Doubled language tags.** The assembler stamps the title (`[EN-US] The New
   Rules of War`) and the catalog appended the language again, giving
   `[EN-US] The New Rules of War (en-US).epub`. The suffix is now skipped when
   the title already carries the tag as a word-boundary token ("Islands" is not
   read as carrying `sl`).

## 6. Known defect surfaced, not fixed here

Translated output is tagged with the **source** language, not the target:
`The New Rules of War.sl.epub` has `dc:title = "[EN-US] The New Rules of War"`
and `dc:language = en-US`, where S1.6 specifies `[SL] <title>`. All five
`.sl.epub` outputs are affected. The server renders metadata faithfully, so
this is visible on the catalog page. Out of scope for S1.15 — needs its own
story against `assemble.py`, and a rebuild is €0 from cache (`book_hash`
covers segment ids and text only).

## 7. Testing

`translator/tests/test_serve.py`, 36 tests, all offline, no API cost, loopback
only: catalog labelling and fallbacks, id stability, filename safety and the
language-suffix rule, disambiguation, HTML escaping, self-containment, the
token gate on both routes, byte-identical download, the RFC 5987 header,
traversal attempts, live rescan, and the CLI's error paths.

## 8. Residual

Live verification on the Samsung tablet: scan the QR, download a book, import
it in Berilo, confirm it opens. Not exercisable from this machine.
