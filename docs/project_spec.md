# Berilo — Product Specification

> Read books in your own language. Translation that keeps the meaning, a reader
> built for people who actually read.

---

## 1. Problem

Most books never get translated into small-market languages. A Slovenian reader
who wants Thomas Rid or Nicole Perlroth reads them in English or not at all.
Machine translation of whole books is now cheap (~€0.50/book with mini-class
LLMs) but no tool does it well end-to-end: format handling is lossy, meaning
drifts, terminology wanders, and there is no reading experience built around
the result.

**Berilo** translates PDF/EPUB/MOBI books into the user's language with
meaning-preserving LLM translation, and provides an Android reading app with an
LLM dictionary, paragraph interpretation, and notes — later synced to a cloud
review service.

## 2. Users

- **Primary persona:** the avid non-fiction reader in a small language market
  (first user: Niko, English → Slovenian, reading on a Boox e-ink tablet).
- Users bring **their own API key**. Defaults are the cheapest models that do
  the job; every model is user-overridable in configuration.

## 3. Product phases

| Phase | Deliverable | Distribution |
|-------|------------|--------------|
| **1 — Translator CLI** | Local Python script: PDF/EPUB/MOBI in → translated EPUB out | Open source (MIT) |
| **2 — Reader app** | Android app (offline): reads translated books, LLM dictionary, paragraph interpretation, highlights & notes | Open source (MIT) |
| **3 — Cloud service** | Next.js on Vercel + Supabase: account, sync of notes/highlights/progress, web review of notes | **Closed source, separate private repo** |

Development is gradual: each phase ships a verifiable artifact before the next
starts. Progress is measured against the rubrics in [`rubric.md`](rubric.md).

---

## 4. Phase 1 — Translator CLI (`translator/`)

### 4.1 Pipeline

```
input (pdf | epub | mobi)
   │
   ▼
NORMALIZE ── canonical intermediate: segment list (JSON) ── chapters → paragraphs
   │            epub: parse directly (ebooklib/lxml)
   │            mobi/azw3: ebook-convert → epub → parse
   │            pdf: pymupdf extraction + reflow heuristics (headers/footers/
   │                 page numbers stripped, hyphenation joined, OCR noise cleaned)
   ▼
TRANSLATE ── paragraph batches with rolling context + book glossary
   │            resumable: SQLite cache keyed (book_hash, segment_hash, model, lang)
   ▼
VERIFY ───── completeness (no dropped/empty segments — hard fail),
   │            LLM-judge sample scoring, glossary consistency
   ▼
ASSEMBLE ─── EPUB out (reader-ready), optional bilingual EPUB (source + target)
```

### 4.2 Requirements

- **Meaning preservation is the top objective.** The system prompt instructs
  translation of *meaning*, not words: keep register, idioms rendered natively,
  terminology consistent via a per-book glossary built on first pass
  (names, technical terms → fixed Slovenian renderings). Proper šumniki.
- **Segment integrity:** 1:1 source↔target segment mapping. A segment is never
  silently dropped; empty/failed segments fail the run loudly and are retryable.
- **Context:** each batch includes the previous translated paragraph(s) and the
  glossary so style and terms stay coherent across chunk boundaries.
- **Idempotent & resumable:** re-running a partially translated book continues
  from the cache; nothing is re-billed.
- **Cost transparency:** dry-run mode prints token estimate and cost before
  spending; final report prints actual cost.
- **Provider abstraction:** OpenAI and Anthropic behind one interface. Defaults:
  `gpt-5-mini` (translation and judge). User overrides via `.env` / `--model`.
  Expected cost, 100k-word book: ≈ €0.40–0.80 with mini-class models.
- **CLI:** `berilo translate <file> --to sl [--model ...] [--bilingual] [--dry-run]`
  plus `berilo inspect <file>` (segment stats, extraction quality preview).
- Python 3.10+, type hints, logging (no print), Black, pytest. External
  binaries: `ebook-convert` (Calibre) for MOBI only.

### 4.3 Known input realities (from `data/examples/`)

- `Active Measures` (PDF, 522 p): clean text layer.
- `This Is How They Tell Me the World Ends` (PDF, 532 p): OCR'd — running
  headers and page numbers appear inline (`"PROLOGUE \nXix \n"`); pipeline must
  strip them.
- `The New Rules of War` (EPUB): structured, the easy path.
- No MOBI example present — MOBI path is covered by `ebook-convert → EPUB` and
  tested with a converted fixture.

## 5. Phase 2 — Reader app (`android/`)

### 5.1 Stack decision

**Kotlin + Jetpack Compose + Readium Kotlin toolkit** (EPUB rendering).

Tradeoffs considered: React Native fits the existing TypeScript stack but adds
a JS bridge and is weak on e-ink refresh control and long-document rendering;
Flutter has no mature EPUB engine. Readium is the standard open-source reader
engine, native performance matters on e-ink, and the rendering surface is where
this app lives. **Recommendation: native Kotlin.** Target: Android 8.0+
(minSdk 26); primary test device: Boox e-ink tablet.

### 5.2 Features (offline-first, no server dependency)

- **Library:** import EPUBs (produced by Phase 1 or any EPUB), covers, reading
  progress.
- **Reader:** paginated EPUB rendering tuned for e-ink (no animations mode,
  full-refresh page turns, high-contrast theme), font size/margins, chapters.
- **LLM dictionary:** tap a word → definition/translation in context (the
  surrounding sentence is sent, so "bank" resolves correctly). Cheap model by
  default, user-selectable. Responses cached locally (Room) — a word is billed
  once per book.
- **Paragraph interpretation:** long-press a paragraph → "explain this"
  (dense/allusive passages, historical references). Cached like dictionary.
- **Notes & highlights:** select text → highlight (4 colors) or note; browsable
  notebook per book; export to Markdown.
- **Settings:** API key entry (stored in Android Keystore/EncryptedSharedPreferences,
  never in plaintext or logs), model selection, target language.
- **Bilingual mode:** if the EPUB is bilingual (Phase 1 `--bilingual`), tap a
  translated paragraph to peek at the original.

### 5.3 Value features for avid readers (differentiators, post-MVP backlog)

- Reading stats that respect readers: pace, streaks, time-to-finish estimate.
- "Ask the book": question answered from the content read so far — no spoilers.
- Vocabulary notebook: every dictionary lookup becomes a reviewable word list
  (spaced-repetition export).
- Quote cards: typographically clean image export of a highlighted passage.

## 6. Phase 3 — Cloud service (`berilo-cloud`, separate private repo)

- **Stack:** Next.js (App Router) on Vercel at **berilo.app**; Supabase —
  Postgres with RLS, 1000-row pagination on every query; **Clerk** for auth
  (integrated with Supabase RLS via Clerk third-party-auth JWTs).
- **Sync:** app ⇄ cloud sync of notes, highlights, vocabulary, reading progress.
  Last-write-wins per record with `updated_at` (UTC); device is source of truth
  for reading position. Sync is optional — the app remains fully functional
  offline; a free tier account is only needed for sync.
- **Web app:** review/search notes and highlights across books, vocabulary
  review, export. Book files stay on the device unless the user opts into the
  personal vault (`docs/sync_api.md` §8, encrypted client-side, per-user
  namespace, off by default) — otherwise only user-created data
  (notes, highlights, progress, lookups) syncs.
- **Boundary:** the open-source app talks to the service through a small,
  documented REST API; the service implementation stays private.

### 6.1 Reading page — the social layer (Slovenian-first)

A public web page on berilo.app where readers share what they read. Built to
the bar in [`design_guidelines.md`](design_guidelines.md): the text is the
hero, one accent color, no engagement mechanics — no infinite-scroll bait,
no like-count theater, no algorithmic feed. A well-set page, not a network.

- **Profiles & shelves:** what a reader is reading / has read — book
  *metadata only* (title, author, cover, language pair). Book files never
  touch the service.
- **Shared passages:** a highlight becomes a typographically clean quote
  (Literata), **capped at 500 characters** with title + author attribution —
  citation-length excerpts only; no piracy surface.
- **Ratings:** 1–5 stars with an optional short review; a book page
  aggregates ratings and shared passages across readers.
- **Slovenian-first:** UI copy in Slovenian with proper šumniki; English
  fallback. The wedge is the small-language reading community the big
  platforms ignore.
- **Privacy default:** everything is private until explicitly shared,
  per item. Deleting a share deletes it everywhere.

## 7. Non-goals (all phases)

- No book piracy features: no downloading, sharing, or DRM stripping. The user
  supplies files they own.
- No server-side translation in phases 1–2 (keys and books stay on-device).
- ~~No iOS until Android + cloud are done.~~ **Lifted 2026-07-27** (Niko).
  Android reached feature completeness through m4 (on-device translation) and
  the cloud service is live, so the gate this non-goal described has been
  passed. iOS is now milestone `m5`, in the separate **private** repo
  [`berilo-ios`](https://github.com/nikogamulin/berilo-ios) — the same
  open/closed split already used for `berilo-cloud`. The plan lives in that
  repo's `docs/project_plan.md`; only the sync contract crosses between them.

  Recording the reversal rather than deleting the line: this non-goal was a
  sequencing decision, not a product one, and the sequence it was protecting
  is now satisfied.

## 8. Design direction

See [`design_guidelines.md`](design_guidelines.md). Summary: the text is the
hero; chrome recedes. Apple-level restraint, e-ink-first palette (true
black/white, no low-contrast grays), one accent color, no decorative
animation. The app should feel like a well-set book, not an app.
