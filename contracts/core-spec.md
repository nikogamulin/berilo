# The translation core, normatively

**Status:** in force. Surface 1 has vectors; the other six are specified here
and not yet mechanically gated — see [`conformance.md`](conformance.md) for
which is which.

---

## 1. What "the core" is

The core is the part of Berilo that turns a book into segments, prices and
translates them, and writes the result back out — everything whose disagreement
between two devices costs the user money or corrupts their data.

`translator/berilo/` in this repository is the **reference implementation**.
Not "a" reference: the definition. Where this document and the Python differ,
the Python is right and this document is a bug.

Three consumers stand in different relations to it:

| Consumer | Relation |
|---|---|
| `berilo-cloud` | **imports it** as a package (`api/pyproject.toml` installs `berilo-translator` from this repo's `translator/`). Conformant by construction; the only thing to check is which commit it pinned. |
| `berilo-android` | a **hand-written Kotlin port**. Conformance is a claim that must be tested. |
| `berilo-ios` | a **hand-written Swift port** (BeriloKit). Same, with one declared exception (§3). |

This is why the spec exists at all. A port cannot be conformant by inspection:
16k lines of Kotlin agreeing with 8k lines of Python is not something a reader
can verify, and the failure is silent — nothing crashes, the user is simply
billed twice.

## 2. The seven surfaces

In scope is what costs money or corrupts data when two platforms disagree. Out
of scope is everything that merely looks different — layout, animation,
navigation, copy. Those are `design_guidelines.md`'s business, and a divergence
there is a design decision, not a defect.

### Surface 1 — Identity

**Reference:** `translator/berilo/cache.py` (`book_hash`, `segment_hash`),
`translator/berilo/models.py` (`make_segment_id`).

- `make_segment_id(text, chapter_index, position)` is sha1 over
  `"{chapter_index}:{position}:{text.strip()}"`.
- `segment_hash(text)` is sha1 over the text.
- `book_hash(book)` is sha1 over the ordered segment ids.
- `strip()` and `\s+` collapse follow **Python's** `str.isspace()`, which is not
  Java's or Swift's. The full code-point set is pinned in
  `translator/berilo/identity_fixture.py` as `PYTHON_WHITESPACE_CODEPOINTS`.
  U+0085 NEXT-LINE is the single divergence from Java's whitespace predicate,
  and it is exactly why a port may not call `String.trim()`.

**Cost of divergence:** every id differs, so `book_hash` differs, so the phone
and the workstation share no translation-cache row. A book already paid for is
re-billed in full on the second device.

**Vectors:** `vectors/v1/identity/`.

### Surface 2 — Prompts

**Reference:** `translator/berilo/prompts.py`.

The style contract, the prompt registry, and the rendered prompt text for each
(style, source language, target language). Styles are **bound to target
languages**: a style resolved for the wrong language is a different prompt.

**Cost of divergence:** translation quality changes silently. Nothing fails; the
prose is just worse on one platform than the other, and no test says so.

### Surface 3 — Markers and batching

**Reference:** `translator/berilo/translate.py`.

The marker format that delimits segments inside one LLM request, the parser that
reads them back, and the recovery path when a response is truncated, empty, or
malformed. Degrade — never abort, never silently drop.

**Cost of divergence:** segments are dropped or misaligned, so the translated
book carries the wrong text under the wrong heading. This is the failure that
survives review, because the output is fluent.

### Surface 4 — Models and pricing

**Reference:** `translator/berilo/providers/pricing.py`,
`translator/berilo/providers/__init__.py`.

Catalog ids, per-token prices, and the cost-estimation arithmetic built on them.

**Cost of divergence:** the cost gate lies. A user who confirmed €1.20 is
charged something else, which is the one bug that spends the user's money
without asking.

### Surface 5 — Normalization

**Reference:** `translator/berilo/normalize/`.

Language-code handling, PDF line joining, XHTML repair, HTML5 entity expansion.

**Cost of divergence:** segment boundaries differ, which cascades straight into
surface 1 — so this is surface 1's failure with a longer fuse.

### Surface 6 — EPUB writer determinism

**Reference:** `translator/berilo/assemble.py`.

The same book must produce a byte-identical archive: entry order, timestamps,
compression, and generated UUIDs all fixed.

**Cost of divergence:** `book_hash` of the *output* differs, so a book
translated on the phone and the same book translated on the workstation are two
different books to the library and the sync service.

### Surface 7 — Cache key composition

**Reference:** `translator/berilo/cache.py`, `translator/berilo/glossary.py`.

What the translation-cache key is composed of — book, segment, model, language
pair, **prompt**, and **glossary**.

**Cost of divergence:** more subtle than the rest, and recorded here because it
already happened once. A key that omits a factor turns every experiment on that
factor into a no-op that reads as a null result: change the prompt, get the old
text served from cache at €0, conclude the prompt does not matter. Before
trusting any null result, check that the key contains the thing you changed.

## 3. Declared exceptions

An exception is a divergence that is **decided, bounded and written down**. It
is not a bug backlog item, and an implementer may not add one — only this
document may.

### PDF identity on iOS

`berilo-ios` extracts PDFs with PDFKit, not PyMuPDF. Reproducing Python's
`normalize/pdf.py` inputs requires linking MuPDF, which is AGPL or a paid
Artifex licence. A PDF's identity on iOS is therefore **self-consistent but
different** from the reference.

Bounded because: this repo always assembles to EPUB regardless of input format,
so every book the pipeline emits reaches a phone as an EPUB and shares cache
rows exactly as before. Only a PDF translated *directly* on an iPhone and again
on the workstation bills twice.

Decided 2026-07-27. Revisit only if the MuPDF licence is bought. Until then, do
not "fix" the iOS PDF hash to match Python: it cannot be done with PDFKit, and
an attempt that looked close would be worse than a difference that is declared.

## 4. Changing this document

Changing the core means changing the Python first, regenerating the vectors, and
then updating each port. The reverse — a port changes and the spec is backfilled
— is how the reference stops being the reference.

A change that alters any vector is a **vectors version bump**: see
[`conformance.md`](conformance.md) §3.
