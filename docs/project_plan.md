# Berilo — Project Plan

> Task list with **objective completion criteria**. A task is `[x]` only when
> its **Verify** line has been executed and passed in the closing session.
> Until a GitHub remote exists this file is the source of progress truth;
> after S0.3 each story is mirrored to a GitHub issue (label `story`,
> milestone per phase) per CLAUDE.md §6.
>
> Estimates are story points (1 pt ≈ half a focused session). Max 3–4 stories
> per session.

---

## Phase 0 — Foundation (milestone `m0`)

### S0.1 — Repo scaffold ✅ (2 pt)
- [x] CLAUDE.md, specs, rubrics, plan, findings register, .gitignore, .env(.example)
- [x] git initialized, initial commit
- **Verify:** `git log --oneline | wc -l` ≥ 1; `git check-ignore .env data/` lists both; `git grep -iE 'sk-(proj|ant)' $(git rev-parse HEAD)` empty.

### S0.2 — Translator package skeleton ✅ (1 pt)
- [x] `translator/` installable package: `pyproject.toml`, `berilo/` with CLI entry point, pytest wired, Black + Ruff configured, Makefile (`make test`, `make lint`)
- **Verify:** `cd translator && pip install -e . && berilo --help` exits 0 and lists `translate|inspect|eval`; `make test` passes (≥1 placeholder test); `make lint` clean.

### S0.3 — GitHub repo wiring ✅ (1 pt)
- [x] Remote `git@github.com:nikogamulin/berilo.git` pushed (`main`) — done 2026-07-24
- [x] MIT `LICENSE` file (author: Niko Gamulin, PhD), labels (`plan`,`story`,`bug`,`m0`–`m3`), milestones m0–m3, this plan mirrored to issues #1–#24
- **Verify:** `gh repo view nikogamulin/berilo --json licenseInfo` shows MIT; `gh issue list --label story | wc -l` equals story count; secret-scan of full history empty.

---

## Phase 1 — Translator CLI (milestone `m1`) — target: Rubric T ≥ 85 on all 3 example books

### S1.1 — Normalize: EPUB → segments ✅ (3 pt)
- [x] Parse EPUB into ordered segment list (JSON): chapters, headings, paragraphs, inline emphasis retained; stable segment IDs (content hash)
- **Verify:** `berilo inspect "data/examples/The New Rules of War.epub" --json` reports ≥ 500 segments, ≥ 8 chapters, 0 empty segments; round-trip test in `make test` asserts segment order = document order.

### S1.2 — Normalize: PDF → segments ✅ (5 pt)
- [x] pymupdf extraction with reflow: join hyphenated line breaks, merge hard-wrapped lines into paragraphs, strip running headers/footers/page numbers (incl. OCR artifacts like inline `PROLOGUE Xix`), detect chapter headings
- **Verify:** `berilo inspect` on both example PDFs: ≥ 95% of 30 randomly sampled segments (seed 42) are clean prose (scripted LLM screen, `--screen` flag); 0 segments matching `^\d+$` or known header regexes; chapter count within ±2 of the printed TOC.

### S1.3 — Normalize: MOBI → segments ✅ (1 pt)
- [x] MOBI/AZW3 via `ebook-convert` → EPUB → S1.1 path; clear error if Calibre missing — round-trip delta 1.30% (2339 vs 2309); legacy MOBI6-only inputs may exceed 2% (format-inherent, see findings)
- **Verify:** `make test` includes fixture test: `ebook-convert` the example EPUB → MOBI, then `berilo inspect` on it yields segment count within 2% of the EPUB's.

### S1.4 — Provider layer + config ✅ (2 pt)
- [x] One `LLMClient` interface; OpenAI + Anthropic implementations; models/keys from `.env`/flags; retry with backoff; token+cost accounting per call
- **Verify:** `make test` unit tests pass with mocked HTTP (no live calls in CI); live smoke `berilo doctor` translates one hardcoded sentence via each configured provider and prints cost > €0.

### S1.5 — Translate engine ✅ (5 pt)
- [x] Paid Verify passed live 2026-07-24 on *The New Rules of War*: run killed at 183/2309 → resumed with 0 re-billed segments; 2nd full run 0 API calls €0.0000 and byte-identical sha256; T1 = 100% (eval 20/20)
- [x] Batched paragraph translation with rolling context; glossary pass (extract names/terms → fixed renderings, injected into every batch); SQLite cache keyed `(book_hash, segment_hash, model, lang)`; strict 1:1 mapping with loud failure + retry for bad segments; `--dry-run` cost estimate
- **Verify:** translate the EPUB example to `sl` twice — second run makes 0 API calls (cache log) and is byte-identical; kill the first run at ~50% and resume — completes with no re-billed segments (call count in log); T1 completeness = 100%.

### S1.6 — Assemble EPUB (3 pt)
- [x] Build valid EPUB 3 from translated segments: chapters, TOC, metadata (`[SL] <title>`), emphasis retained; `--bilingual` variant with collapsible/adjacent source paragraphs — landed 2026-07-24; epubcheck exit 0 on both variants verified on main
- [x] T5 residual met 2026-07-24: eval scored structural fidelity 10/10 on the translated example
- [ ] Verify residual: Calibre-viewer open (manual, Niko)
- **Verify:** `epubcheck` exits 0 on both variants; output opens in Calibre viewer; T5 structural-fidelity script ≥ 9/10.

### S1.7 — Eval harness (Rubric T) ✅ (3 pt)
- [x] Live Verify passed 2026-07-24: `berilo eval "The New Rules of War.sl.epub" --sample 40 --seed 42` printed T=89.7 [87.9, 91.6] and wrote the score row; seed determinism test-proven
- [x] `berilo eval` implements Rubric T end-to-end: sampled LLM-judge scoring (seed, bootstrap CI), completeness, terminology, structure checks; appends to `loops/build/rubric_scores.jsonl`
- **Verify:** `berilo eval <translated epub> --sample 40 --seed 42` prints score + 95% CI and writes a ledger row; running twice with same seed gives identical sample selection.

### S1.8 — Full-book milestone run (2 pt)
- [x] All 3 books translated EN→SL 2026-07-24 (gpt-5-mini, reasoning=low; content-policy batches via claude-haiku fallback); T v1.1: 88.5 [86.0,90.8] / 85.0 [82.6,87.5] / 86.7 [84.3,89.0] — all ≥85 with CI lower ≥80; epubcheck 0 errors ×3; total cost ≈ €3.4 ≤ €5; defects fixed en route (reasoning cost, moderation fallback, CAPTION/OTHER round-trip, rubric v1.1)
- [ ] Residual: 2-page manual spot-read sign-off by Niko (record in issue #11)
- **Verify:** Rubric **T ≥ 85 (CI lower bound ≥ 80) on all 3 books**; total cost ≤ €5; scores in `rubric_scores.jsonl`; 2-page manual spot-read by Niko signed off (recorded in issue).

### S1.9 — Eval instrumentation: per-sample dump + judge-repeat (2 pt) ✅
*Plan: [`docs/plans/2026-07-25-fluency-uplift.md`](plans/2026-07-25-fluency-uplift.md) §3 F1. Rationale: T3 is flat at 12.4–13.5/20 on all 5 books and we have no per-sample evidence of why.*
- [x] `berilo eval --dump <file.jsonl>` writes one row per judged sample: `segment_id`, `chapter_index`, `chapter_title`, `source`, `target`, `meaning`, `fluency` (and `screen` rows where applicable)
- [x] `berilo eval --judge-repeats N` (default 1) judges each sample N times; the dump records every repeat and the report prints mean intra-sample standard deviation for T2 and T3
- [x] Dump path never written unless requested; `--dry-run` still makes zero judge calls; existing score-row schema unchanged
- [x] Offline Verify run 2026-07-25 on merged `main`: 190 passed, lint clean. Supervisor-audited: repeats collapse to a per-sample mean (`rubric_t.py:778-779`) **before** the bootstrap arrays, so the CI is not shrunk by repeat count
- **Verify:** offline — `python3 -m pytest tests/ -k "dump or repeat"` green with a mocked judge, asserting a 40-row dump with all fields and that `--judge-repeats 3` produces 3× the judge calls and a σ column. Live (Supervisor, ~€0.07): `berilo eval "data/examples/The Revenge of Geography.sl.epub" --sample 40 --seed 42 --dump /tmp/kaplan.jsonl --no-write` yields 40 well-formed rows.

### S1.10 — Prompt registry + Slovenian style contract + prompt-keyed cache (3 pt) ✅
*Plan: §3 F2 and §3.1. Opus-tier story: translate path, silent-degradation failure mode.*
- [x] `berilo/prompts.py`: named, versioned registry of translation styles; `baseline_v1` reproduces today's `_TRANSLATE_SYSTEM`, `_TRANSLATE_SYSTEM_STRICT` and `_SINGLE_SEGMENT_SYSTEM` **byte-identically** (test asserts exact string equality)
- [x] Variants `sl_style_v1`, `book_context_v1`, `revise_v1` per plan §4; each applies to the batch prompt **and** the `_translate_single` fallback
- [x] `translate_book(..., style=BASELINE)` — default behavior unchanged; the style's version string flows into the cache
- [x] **Cache-key fix (§3.1):** `prompt_version` joins the `translations` primary key; migration defaults existing rows to `baseline_v1`
- [x] Offline Verify run 2026-07-25 on merged `main`: 190 passed, lint clean. Supervisor-audited independently: byte-identity confirmed by diffing the registry against `main:translate.py` pre-refactor constants; migration run against a **copy of the real 10,936-row cache** — all rows preserved, text byte-identical, all tagged `baseline_v1`, idempotent across repeated opens, `glossaries`/`calls` intact; estimator honest (`revise_v1` = 2.09× baseline, style-only variants 1.00×); revise pass degrades to un-revised text on a mapping mismatch (segment integrity holds) and is counted in `TranslationStats.revision_failures`; cache commit happens after both passes
- **Verify:** `cd translator && make test && make lint` green, including new tests proving (a) `baseline_v1` strings are byte-identical to the pre-refactor constants, (b) the same segment under two prompt versions stores two distinct cache rows and each reads back correctly, (c) a pre-migration cache DB opens and its rows read as `baseline_v1`, (d) a `baseline_v1` run against an existing cache makes zero API calls.

### S1.11 — A/B prompt-variant harness (3 pt) ✅
*Plan: §3 F3. Depends on S1.9 + S1.10. Opus-tier: measurement correctness is the whole point.*
- [ ] `berilo/experiment.py` + `berilo ab <translated.epub> --variant <name>`: seeded selection of K *contiguous* body-prose runs, re-translated through the real `translate_book` path (production batch size, rolling context, the book's cached glossary) against a scratch cache
- [x] Judges control vs variant on T2 + T3, reports **paired** deltas with bootstrap CIs, plus actual € spent and €/1k words per variant
- [x] Resampling unit is the contiguous run (cluster), not the segment, so within-run correlation does not shrink the CI
- [x] `--dry-run` prints the plan, judge-call count and estimated cost without spending
- [x] Verify run 2026-07-25 on merged `main`: 216 passed, lint clean; Supervisor-audited that `cluster_bootstrap_ci` resamples whole runs and that a test asserts production batch shapes (not just the function name). Used live for the E2 bake-off at €0.20–0.26 per hypothesis
- **Verify:** offline — `python3 -m pytest tests/ -k experiment` green with mocked translate+judge, asserting the harness calls `translate_book` (not a bespoke path), that control and variant do not collide in the cache, and that the reported CI is cluster-bootstrapped. Live (Supervisor, ≤ €0.10): `berilo ab "data/examples/The Revenge of Geography.sl.epub" --variant sl_style_v1 --dry-run` then one real run inside budget.

### S1.13 — Fix chapter-title fallback when TOC/nav fails to parse (2 pt) ✅
*Defect found by the E1 calibration run, 2026-07-25. Gates a clean E3 measurement because Kaplan is the chosen book.*
- [x] When both `toc.ncx` and the nav document fail to resolve (manifest names absent from the archive), fall back to per-spine-item headings rather than to the book title. Today 94.9% of Kaplan's segments inherit the book title, which makes rubric v1.1's front/back-matter fold inert and lets untranslated TOC headings into the body-prose pool
- [x] Heading-like segments that are byte-identical to their source after translation must be typed out of the PARAGRAPH prose pool (they are headings, not prose) — fix by CLASS per §9, never per instance
- [x] Emit a loud warning (not the current cosmetic one) when the title-fallback share exceeds a threshold — a silent 95% fallback is how this hid
- [x] Verify run 2026-07-25: title-fallback share 32.8 / 17.3 / **38.6** / 40.4 / 32.3 % — all < 50%; the seed-42 dump contains **0** byte-identical source/target samples (was 1–2); 239 passed on merged `main`, lint clean; no segment/chapter-count regression on any book; `book_hash` unchanged so both arms rebuilt from cache at €0
- **Verify:** title-fallback share for all five example books printed and **< 50% each** (Kaplan is 94.9% today); `berilo eval "…Revenge of Geography.sl.epub" --sample 30 --seed 42 --dump` contains **zero** samples whose target is byte-identical to its English source; `make test && make lint` green.

### S1.12 — Promote the winning prompt (2 pt)
- [x] `revise_v1` is the default style (`prompts.DEFAULT`); `translate --style <name>` selects any registry style; the choice flows through the dry-run estimate, confirmation prompt, cache key and run summary, and `revision_failures` is surfaced loudly
- [x] Verify run 2026-07-25: 227 passed, lint clean. E2 deltas recorded in `loops/build/ledger.jsonl` and `docs/findings.md`
- [x] Full book re-translated and re-scored 2026-07-25 (Niko approved): **Kaplan 83.9 [81.7,86.1] → 88.0 [86.2,89.9]**, +4.1 pts with essentially disjoint CIs, both arms measured under the SAME fixed normalization. T2 24.6→26.7, T3 12.0→13.9, T4 7.6→7.7, T1 100%, T5 10/10, epubcheck 0 errors. Actual €1.4494 vs €1.2173 estimate (1.19×, inside T7's 1.5× clause); zero revision failures
- [ ] Residual: T3 lands at 13.9/20, short of the plan's G3 target of 16 — the revision pass is a real, replicated gain but does not close the fluency gap alone. Next hypotheses in `docs/plans/2026-07-25-fluency-uplift.md`
- **Verify:** Rubric T on the re-translated book **≥ 89** with T3 ≥ 16/20, T2 not regressed beyond −0.5 pts, T1 = 100%, T7 = 5; score row in `rubric_scores.jsonl`.

### S1.14 — Carry images through the pipeline (3 pt) ✅
*Defect reported by Niko 2026-07-25: source books have images, every translated
EPUB has none. Verified universal, not book-specific — source vs output image
files: New Rules of War 4→**0**, Sandworm 3→**0**, Revenge of Geography 17→**0**,
Active Measures (PDF) →**0**. Opus-tier: touches the segment model, both
normalizers and the assembler.*
- [x] **Images are book-level resources, never segments.** `book_hash` is sha1
      over ordered segment IDs (`cache.py:95`) and `make_segment_id` includes
      document position (`models.py:30`), so inserting IMAGE segments would
      change every ID, miss every cache row and force a **paid re-translation of
      all five books (~€7 at the `revise_v1` default)**. Adding a `Book.images`
      field leaves `book_hash` byte-identical ⇒ rebuild from cache at **€0**.
      It also keeps `rubric_t.align`'s `(chapter, type, heading_level)`
      fingerprints unchanged, avoiding the AlignmentError class in findings
- [x] `models.py`: `ImageResource` (id, media_type, bytes, source_href, alt) +
      `Book.images`, anchored to the segment it follows; `to_json`/`from_json`
      round-trip
- [x] `normalize/epub.py`: collect manifest image items and `<img>` anchor
      positions (today `_iter_blocks`/`_read_manifest` discard both)
- [x] `normalize/pdf.py`: pymupdf image extraction with page anchors — required,
      since the reported book (*Active Measures*) is PDF-sourced. Existing
      CAPTION segments are already translated, so today captions survive while
      their image is dropped; captions must re-attach to their image
- [x] `assemble.py`: image entries into the zip with fixed `ZipInfo`
      `date_time=(1980,1,1,...)` (byte-identical-output invariant), manifest
      entries in `_content_opf`, `<img>` emitted in `_render_chapter_body`
- **Verify:** offline — `cd translator && make test && make lint` green, with a
  test asserting `book_hash` is unchanged by adding images to a `Book`, and a
  round-trip test that a source EPUB's image count survives normalize→assemble.
  Measured — rebuild all five books from cache and assert **0 API calls / €0.00**
  in the run log, then re-run the image census: each output's image-file count
  equals its source's (4/3/17 for the EPUB books, > 0 for Active Measures) and
  `epubcheck` still exits 0 on every output.
- **Verify run 2026-07-25 on merged `main`** (commit `4d3cc84`): offline
  `make test && make lint` → **270 passed**, black + ruff clean. Measured, at
  **€0 proven by construction** — rebuilt through the real `translate_book`
  path with a client whose every attribute raises on call, so a single API
  request would have aborted the run; it never fired, i.e. 100% cache hits.
  Image census on the rebuilt outputs: New Rules of War **4/4**, Sandworm
  **3/3**, Revenge of Geography **17/17**, Active Measures **83/83** figures
  (`<img>` refs match file counts on all four). `epubcheck` **exit 0, 0 errors,
  0 warnings** on every output. `book_hash` byte-identical to pre-change on two
  real books (`2db2bd1c…`/2309 segs, `f30cd8f3…`/1294 segs).
- **Known limits:** *This Is How They Tell Me the World Ends* is OCR-sourced —
  532/532 pages carry a page-sized raster, so image extraction is correctly
  skipped wholesale rather than embedding a photographic copy of the English
  source into the translation. Revenge of Geography's source has 17 image files
  behind 45 `<img>` references; the rebuild emits 17 files / 17 references, so
  repeated references collapse to one — acceptable, but not byte-parity.

### S1.15 — LAN book server: hand books to the tablet (2 pt)
*Requested by Niko 2026-07-26: getting a translated EPUB onto the Samsung
tablet meant USB or a cloud round-trip, and the latter is exactly what
CLAUDE.md §2 forbids for book files. Design:
[`docs/plans/2026-07-26-lan-book-server.md`](plans/2026-07-26-lan-book-server.md).*
- [x] `berilo serve [--dir] [--port] [--host] [--no-qr]`: scans a directory of
      EPUBs, prints a tokenized LAN URL plus a terminal QR code, serves until
      Ctrl-C; falls back to a free port when the default one is busy
- [x] `berilo/serve/{catalog,page,server}.py` — catalog labelled from OPF
      metadata (not filenames), self-contained HTML page per
      `design_guidelines.md`, stdlib `ThreadingHTTPServer`
- [x] Per-run random token on both routes, compared with `compare_digest`;
      wrong/missing token is a 404, never a 401; traversal structurally
      impossible (ids are catalog lookups, never path components)
- [x] `normalize/epub.py`: public `read_epub_metadata()` so labelling a file
      does not require the full segment walk
- [x] Real-data fixes: books sharing a title are disambiguated by filename
      stem; the language suffix is not appended when the title already carries
      the tag
- [ ] **Residual (Niko, on the tablet):** scan the QR, download a book, import
      it in Berilo, confirm it opens
- **Verify:** offline — `cd translator && make test && make lint` green, with
  tests covering the token gate on both routes, a byte-identical download, the
  RFC 5987 filename header, and traversal attempts. Live — from the Samsung
  tablet on the same Wi-Fi: scan the QR, the catalog page renders, one book
  downloads and imports into Berilo and opens.
- **Verify run 2026-07-26:** offline — **306 passed**, black + ruff clean.
  Measured on this box at €0 (no API path touched): server bound
  `192.168.120.8:8577`, catalog listed 12 EPUBs from `data/examples`, page
  returned 200 with the token and **404 with none and with a wrong one**,
  `The New Rules of War.sl.epub` downloaded md5-identical to the file on disk
  (`b76dfbbf…`, 248663 B) and `zipfile.is_zipfile` true. Tablet leg not run.

---

## Phase 2 — Android reader (milestone `m2`) — target: Rubric R ≥ 85 on Boox

### S2.1 — App skeleton + library (3 pt)
- [ ] Kotlin/Compose project (minSdk 26), Readium integration; import EPUB via SAF; library grid with covers + progress; Room DB
- **Verify:** `./gradlew assembleDebug test` green; APK installs on Boox; all 3 translated books import and show covers.

### S2.2 — Reader core (5 pt)
- [x] Code landed 2026-07-24 (reviewer LAND-WITH-FIXES: open-path hang + publication leak, folded; 44 JVM tests)
- [ ] Device residual: R1 walkthrough 25/25 + R2 latencies on Boox
- [ ] Paginated Readium rendering; e-ink mode (no animations, full-refresh turns, pure B/W theme); font/margin settings; position persistence; chapter nav
- **Verify:** Rubric R1 walkthrough = 25/25 on Boox; R2 measurements meet all 3 thresholds (debug overlay logs attached to issue).

### S2.3 — API key settings (2 pt)
- [x] Code landed 2026-07-24 (reviewer LAND; 45 JVM tests incl. no-key-in-exception audits; allowBackup=false)
- [ ] Device residual: R7 logcat/backup audit on Boox
- [ ] Key entry + validation (`doctor`-style test call), EncryptedSharedPreferences storage, model picker (cheap default, user-selectable), target language
- **Verify:** R7 audit passes: `adb logcat` during a lookup session contains no key substring; backup extract contains no plaintext key; invalid key shows actionable error.

### S2.4 — LLM dictionary (3 pt)
- [x] Code landed 2026-07-24 (reviewer LAND-WITH-FIXES, folded; 107 JVM tests; capture = select + Define action)
- [ ] Device residual: R3 latency/disambiguation thresholds on Boox
- [ ] Tap word → bottom-sheet definition in context (sentence sent along); Room cache; offline + error states
- **Verify:** R3 thresholds met (p50 ≤ 4 s over 10 logged lookups, cached ≤ 300 ms, 5/5 ambiguous words disambiguated — word list fixed in test doc).

### S2.5 — Paragraph interpretation (2 pt)
- [x] Code landed 2026-07-24 (reviewer LAND, no fixes; 138 JVM tests incl. proven dismiss-cancellation)
- [ ] Device residual: R4 thresholds on 5 dense *Active Measures* paragraphs on Boox
- [ ] Long-press paragraph → interpretation sheet; cached; streaming render if provider supports
- **Verify:** R4 thresholds met on 5 pre-selected dense paragraphs from *Active Measures*.

### S2.6 — Highlights & notes (3 pt)
- [x] Code landed 2026-07-24 (reviewer LAND-WITH-FIXES: e-ink luma-separated fills, folded; 173 JVM tests)
- [ ] Device residual: R5 10-action sequence + Obsidian export render on Boox
- [ ] Text selection → highlight (4 colors)/note; per-book notebook screen; Markdown export via share sheet
- **Verify:** R5 scripted 10-action sequence 15/15; export file renders correctly in Obsidian.

### S2.7 — Design pass + walkthrough checklist (3 pt)
- [x] Design pass + `docs/checklists/reader_walkthrough.md` landed 2026-07-24 (reviewer LAND; WCAG contrast verified computationally; 176 tests)
- [ ] Device residual: full Rubric R scoring run on Boox (checklist ready)
- [ ] Apply `design_guidelines.md`; create `docs/checklists/reader_walkthrough.md`; full rubric R scoring run
- **Verify:** Rubric **R ≥ 85** recorded in `rubric_scores.jsonl`; R6 checklist ≥ 10/12; screenshots archived in issue.

### S2.8 — Release v0.1 (1 pt)
- [x] Build wiring + README landed 2026-07-24 (reviewer LAND-WITH-FIXES, folded; minified release APK 4.6 MiB builds; 352 tests)
- [ ] Gated: real keystore (Niko), GitHub release publish after the Boox R pass, fresh-install R1 on device
- [ ] Signed APK, GitHub release, README install instructions
- **Verify:** `gh release view` shows APK asset; fresh install from the release artifact passes R1 on Boox.

### S2.9 — Curated book icons + library polish (2 pt)
- [x] Generate cohesive, text-free 2:3 cover icons for the five translated example books and use them as title-matched fallbacks when an imported EPUB has no usable embedded cover
- [x] Library polish landed `429017f` (`LibraryScreen.kt` +187) — audited on `main` 2026-07-25: `CardShape`/border `:60-63,197-199`, `RoundedCornerShape(12.dp)`+`clip` cover treatment, author hierarchy one weight down on the pinned `onSurfaceVariant` role `:226,235-237`, distinct LOADING/EMPTY states `:94-95,141,330`, `GridCells.Adaptive(minSize=140.dp)` `:172`; `IconButton` supplies the ≥48 dp target
- [x] Cross-screen consistency landed `c04b573` — annotation editor, notebook, dictionary, interpretation and settings all touched, with ~591 lines of Compose tests
- [ ] Verify residual: screenshots at both widths now exist via S2.10 and surfaced **3 defects → S2.11** (baseline-purple FAB, purple progress track, reader-chrome title collapse at phone width); TalkBack labels and the R6 checklist remain device-gated
- **Verify:** `./gradlew test assembleDebug` green; all five translated books show either their embedded cover or the correct curated fallback; screenshot comparison at phone and Boox widths has no clipping, uneven grid rhythm, or low-contrast controls; TalkBack labels and R6 checklist remain passing.

### S2.10 — JVM screenshot harness at phone + Boox widths (2 pt) ✅
*Enabling work for S2.9's Verify line, which demands "screenshot comparison at
phone and Boox widths" — impossible today: no Boox attached and no emulator
installed on this box (`~/Android/Sdk/emulator/` and `~/.android/avd/` absent).
Compose UI tests already run JVM-side under Robolectric
(`gradle/libs.versions.toml:17,35-36`; no `androidTest` source set), so
screenshots are reachable offline.*
- [x] Renders 6 surfaces (library, reader chrome ×2, notebook, dictionary
      sheet, interpretation sheet, settings) to 26 PNGs at two qualifiers —
      phone 411×914dp@420dpi → 1078×2399px, Boox `w990dp-h1319dp…227dpi` →
      1404×1871px (1871 not 1872 is dp round-tripping, verified) — light + dark
- [x] Screenshots written to gitignored `app/build/outputs/roborazzi/s2.10/`;
      no binaries committed (diff is 9 files, all source)
- [x] **Deterministic — verified over 5 consecutive runs, all 10 pairwise
      comparisons identical across all 26 PNGs.** First cut was NOT: Coil's
      `AsyncImage` decodes on `Dispatchers.IO`, outside Robolectric's looper, so
      `waitForIdle()` could return before covers painted and `library_boox_dark`
      flapped between two states (23,008 px, `(0,0,0)` raw canvas vs `#121212`
      `PaperDark`). Fixed by a JVM-scoped Coil `ImageLoader` on
      `Dispatchers.Unconfined` — deterministic by construction, not a poll
- [x] Capture cannot silently no-op: tests report **SKIPPED** (JUnit `Assume`)
      when `roborazzi.test.record` is unset and assert the PNG exists and is
      non-empty when it is set. "Passed" can no longer mean "wrote nothing"
- [x] **Scope honesty:** this does NOT substitute for Rubric R6. R6 is a
      12-item human visual checklist scored on real e-ink hardware (contrast,
      ghosting, full-refresh behaviour) — `docs/rubric.md:58`. This harness
      closes S2.9's screenshot clause only
- **Verify:** `cd android && JAVA_HOME=… ANDROID_HOME=… ./gradlew <task>` emits
  a PNG per surface per width with zero test failures; each PNG opens and is
  visually reviewed for clipping, grid rhythm and control contrast; existing
  JVM test count does not regress (390 green today).
- **Verify run 2026-07-25 on merged `main`:** `./gradlew screenshots test` →
  `screenshots` 26/26 pass, `testDebugUnitTest` 232 (26 skipped, 0 failures),
  `testReleaseUnitTest` 184 — 416 unique vs the 390 baseline, no regression.
  26 PNGs: 13 at 1078×2399, 13 at 1404×1871. All 26 programmatically confirmed
  non-blank (484–28,897 distinct colours, 2.9–65.2% ink); library, reader chrome
  and settings reviewed visually in depth. Found 3 real defects → **S2.11**.

### S2.11 — Fix defects caught by the screenshot harness (2 pt) ✅
*Found by S2.10 on its first run, 2026-07-25 — measured from the PNGs, not
eyeballed. Both classes would cost R6 points on the device (`docs/rubric.md:58`
scores contrast on e-ink and OLED).*
- [x] **Baseline-purple leak via component defaults.** `Theme.kt` pins only
      `background, error, onBackground, onError, onPrimary, onSurface,
      onSurfaceVariant, outline, primary, surface, surfaceVariant`. Every role
      referenced explicitly through `colorScheme.` in app code is pinned — the
      leak is entirely from **Material3 component defaults** referencing
      unpinned roles: `FloatingActionButton` → `primaryContainer`/
      `onPrimaryContainer` (`#EADDFF` on `#210050`, the library's primary
      action), `LinearProgressIndicator` track → `secondaryContainer`
      (`#E8DEF8`, reader chrome), `HorizontalDivider` → `outlineVariant`
      (`#CAC4D0`, settings + reader settings panel). Fix by CLASS: pin every
      role of both schemes, not just the ones currently referenced
- [x] **Reader top bar collapses at phone width.** In `reader/ReaderChrome.kt`
      the chapter-title `Text` at `weight(1f)` competes with a
      `horizontalScroll` Row of 6 action buttons; the scrollable sibling
      measures at its unconstrained preferred width and starves the title,
      which **disappears entirely rather than ellipsising** despite
      `overflow = TextOverflow.Ellipsis`. The action row is also clipped
      mid-word. Renders correctly at Boox width (990 dp) — phone-width only
- [x] Regression cover: assert no unpinned-role colour reaches a rendered
      surface, so this class cannot silently return
- **Verify:** regenerate via S2.10's `./gradlew screenshots test` and re-run the
  violet-pixel scan over all 26 PNGs — **zero** pixels matching the M3 baseline
  violet family (`#EADDFF`, `#210050`, `#E8DEF8`, `#CAC4D0`, `#4F378B`,
  `#4A4458`); `reader_chrome_phone_light.png` shows the chapter title present
  and the action row not clipped; JVM test count does not regress (416 with
  S2.10 landed).

---
- **Verify run 2026-07-25 on merged `main`** (merge `299014b`): `./gradlew
  screenshots test` → `screenshots` 26/26, `testDebugUnitTest` 235 (26 skipped),
  `testReleaseUnitTest` 187 = **422 unique**, 0 failures (390 at session start,
  416 after S2.10). Violet scan over all 26 PNGs: **0 exact M3-baseline pixels**,
  down from hits on 5 surfaces. The 364 residual "violet-band" pixels are
  bundled cover artwork, not palette — they appear at identical counts in light
  AND dark (34/34 boox, 148/148 phone), which theme colours never would, and the
  agent independently traced them to the `cover_*.webp` drawables. Determinism
  re-verified: 5 runs, all 10 pairwise comparisons identical.
  `ThemeContrastTest` now enumerates all 48 roles as a permanent guard and was
  proven to fail without the fix (catching `#EADDFF` and `#4F378B` by name).

### S2.12 — Make the selection → highlight/note path reachable (3 pt) — code landed, device residual
*Reported from the device 2026-07-26: "when I select a passage I can't save the
highlight or create a note — I only get the actions inherited from the system;
share offers Zotero, Quick Share…". S2.6 landed the storage, the editor and the
decorations; nothing ever connected a live text selection to them.*

Two independent defects, either of which alone makes the feature unreachable:

- [x] **The selection popup is the platform's, not Berilo's.**
      `ReaderActivity.openBookAndAttachNavigator()` calls
      `createFragmentFactory(initialLocator, null, preferences)` with no
      `EpubNavigatorFragment.Configuration`, so `selectionActionModeCallback`
      stays `null` and `R2BasicWebView.startActionMode` falls through to the
      platform menu (Copy / Share / Web search / every `PROCESS_TEXT` app
      installed — hence Zotero). Fix: supply a `Configuration` whose
      `selectionActionModeCallback` builds Berilo's own action set, and read the
      selection *before* finishing the mode (finishing clears it).
- [x] **Highlight/Note/Define lived only in chrome that cannot coexist with a
      selection.** Those four `TextButton`s sit in the reader top bar, which is
      hidden while reading; revealing it costs a tap on the WebView, and that tap
      drops the selection — so `navigator.currentSelection()` was always `null`
      by the time `captureHighlightTarget()` ran and every action short-circuited
      to the "select something first" toast. There was no ordering of taps that
      worked. Fix by CLASS: selection-dependent actions belong on the selection,
      not in chrome; the top bar keeps only what works without one.
- [x] **Third-order consequence:** the chrome `ComposeView` — which hosts the
      annotation editor, dictionary and interpretation sheets — is `GONE`
      whenever `chromeVisible` is false. An action fired from a selection with
      chrome hidden would therefore have persisted state into an invisible view.
      Its visibility must be driven by "chrome shown **or** any sheet open".
- **Verify:** `./gradlew test assembleDebug` green with no regression on the 422
  baseline; on the Boox — long-press a passage → Berilo's own bar appears with
  Highlight / Note / Define / Interpret / Copy and no system entries; Highlight →
  a colour → the passage stays tinted after a page turn and appears in the
  notebook; Note → text → Save → same, with the note body; Define/Interpret open
  their sheets with chrome hidden; Copy puts the passage on the clipboard.
- **Verify run 2026-07-26 (offline half, on `main` + this change):** `./gradlew
  test assembleDebug lintDebug` green — **536 JVM tests, 0 failures** (292 debug
  incl. 26 skipped screenshot tests + 244 release) against a **492** baseline
  measured on the stashed tree the same session; +44 is the 22 new tests × both
  variants, so nothing regressed. `screenshots` 26/26; `reader_chrome_*` shows
  the top bar down to Chapters | title | Interpret / Notebook / Text settings,
  with the S2.11 title-ellipsis behaviour intact at 411 dp and all three actions
  fitting at Boox's 990 dp. The two guards were **proven to fail without the
  fix** by mutation: deleting `selectionActionModeCallback` from
  `beriloNavigatorConfiguration` and `menu.clear()` from the callback failed
  exactly the two tests written for them.
- **Device residual (needs the Boox — no tablet attached, no emulator on this
  box, `docs/findings.md` 2026-07-25):** the whole Verify line above, plus R5's
  scripted 10-action sequence, which S2.6 never had a reachable path to run.

## Phase 3 — Cloud sync & web review (milestone `m3`) — target: Rubric S ≥ 85
> Lives in **private repo `berilo-cloud`**; this repo only gains the app-side
> sync client + documented API contract. Detailed stories are drafted at m2
> close; headline stories now:

### S3.1 — API contract + Supabase schema (3 pt)
- [x] Contract half landed 2026-07-24: `docs/sync_api.md` v1.1 (plan-critic pass: 6 MAJOR + 7 MINOR fixed/elevated; DDL + delete-wins trigger functionally validated on throwaway Postgres; [OPEN-1..5] await Niko)
- [ ] Infra half gated: live Supabase RLS audit + 2500-row pagination test (berilo-cloud)
- [ ] OpenAPI spec for sync endpoints; Supabase schema (users, books-metadata, highlights, notes, vocabulary, progress, shelves, ratings, shared-passages) with RLS; every query paginated
- **Verify:** contract committed in **this** repo (`docs/sync_api.md`); RLS audit script: user A gets 0 rows of user B across all tables; S6 synthetic 2500-row test returns complete data.

### S3.2 — App sync client (5 pt)
- [x] Offline half landed 2026-07-25: Clerk auth (email-code primary, password fallback) behind an `AuthGateway` seam; Room schema v5 (`updatedAt`/`deletedAt` tombstones, `vocabulary.sentence`, `sync_state`) with a real `MIGRATION_4_5`; pull/push client against `sync_api.md` v1.3 (keyset drain, LWW, delete-wins, conflict adoption); WorkManager background sync + sync-on-launch; account screen. 270 JVM tests green (was 236), debug **and** release APKs build.
- [ ] Auth (Clerk JWTs against Supabase RLS), background sync of notes/highlights/vocabulary/progress, last-write-wins with `updated_at` UTC, offline queue
- **Verify:** Rubric S1 scenario matrix 100%; S3 chaos test 0 data loss; DST-boundary test green.
- **Verify status (2026-07-25):** DST-boundary test **green** (`SyncTimeTest`, Europe/Ljubljana March gap + the repeated October hour). Chaos/data-loss **green at unit level** (`SyncEngineTest`: a rejected push never advances the watermark, offline mid-round keeps the queue, 401 touches nothing). S1 scenario matrix **not run** — it needs the live Supabase/Vercel deployment and two real devices, so **this story stays open**; nothing here has been exercised against the deployed API or on the Boox.
- **Open before close:** the build is wired to a Clerk **`pk_test_`** (development instance); production needs the `pk_live_` key and `berilo.app` added to the Clerk production instance. `[OPEN-5]` (client-clock LWW) is still unresolved — the client sends its own UTC clock as the contract specifies.

### S3.3 — Web review app (5 pt)
- [ ] Next.js on Vercel: Clerk login, notes/highlights browser (search, filter by book/color/date), vocabulary review, Markdown export
- **Verify:** S4 checklist 10/10; S5 LCP ≤ 2.5 s on Vercel analytics; e2e Playwright suite green in CI.

### S3.4 — Reading page — social layer (5 pt) *(spec §6.1)*
- [ ] Public reading page on berilo.app, Slovenian-first: profiles & shelves (book metadata only), shared passages (≤ 500-char excerpt, title+author attribution), 1–5 ratings + short reviews, per-item opt-in sharing (private by default), design per `design_guidelines.md`
- **Verify:** Playwright e2e: sign up → shelve book → share passage → rate → anonymous visitor sees only opted-in content; passage > 500 chars rejected server-side; UI strings render šumniki correctly (č/š/ž spot-check on live page); Lighthouse accessibility ≥ 95 on the reading page.

### S3.5 — Launch gate (2 pt)
- [ ] Privacy policy (only user-created data syncs, books never leave device), free-tier limits, error monitoring
- **Verify:** Rubric **S ≥ 85**; S2 and S3 gates at max; policy page live.

### S3.6 — Growth surface (16 pt) *(implemented as Phase C6 in `berilo-cloud`; evidence in its `docs/research/2026-07-25-virality/`)*
- [ ] The social object is a passage, not a number: passage permalinks with typographic OG cards and a bilingual variant (source sentence above, translation below — needs `source_excerpt`, contract v1.2); book/author pages as Slovenian SEO surface with sitemap and thin-content `noindex`; four-slot favourites block on public profiles; send-to-one-person recommendation with private gift pages; qualitative year in review; Goodreads/StoryGraph import landing all-private
- [ ] Binding rules (violations are defects): no reading pace or reading time on any public surface · aggregate rating hidden below n ≥ 3 · a rating counts only with reading evidence · LLM output may describe a text, never the reader · no referral rewards · no numeric counters on public pages
- **Verify:** each C6.x Verify line in `berilo-cloud/docs/project_plan.md` executed and green in its closing session; the [OPEN-4] edition→translation-revision decision recorded in `sync_api.md` §2 before anything social ships (interim: `content_hash` keying).

---

## Phase 4 — On-device translation (milestone `m4`)

> Spec: [`docs/plans/2026-07-26-ondevice-translation.md`](plans/2026-07-26-ondevice-translation.md).
> Source review: [`docs/reviews/2026-07-26-translator.md`](reviews/2026-07-26-translator.md) (20 findings).
> **Sequencing decided by Niko 2026-07-26: Track A closes in full before Track B
> starts.** Four review findings are port-blocking — fixing them first means the
> Kotlin side ports a corrected algorithm once instead of inheriting known
> defects and being fixed twice.
>
> Measured at €0 before scheduling: review findings 1 and 2 are **latent on this
> corpus** (0 spine-document parse failures across 191 documents; 0 droppable
> heading lines in either PDF). Finding 3 is therefore the real #1 — it is
> *actively* poisoning experiments as a €0 no-op, and is a recurrence of
> CLAUDE.md §9's own cache-key rule.

### Track A — translator hardening

#### A2 — Glossary in the cache key (3 pt) ✅
*Review findings 3 (HIGH), 9, 11. Promoted ahead of A1 on the €0 measurement above.*
- [x] The glossary's identity joins the translation cache key; `prompt_version` joins the glossary key; `_GLOSSARY_SYSTEM` and the sampling constants become versioned. **Both identities are derived, never declared** — `glossary_hash` hashes the rendered prompt block, `glossary_prompt_version` hashes the system prompt plus the sampling params actually used, so neither can drift from what the model saw
- [x] Migration is crash-atomic — both migrations collapsed into one `BEGIN IMMEDIATE`
- [x] T7's actual-cost query filters on `model` — **partial, see A7**
- [x] **Existing cache is not invalidated** — proven, and independently re-proven by the Supervisor
- [x] Ordering defect caught in review and fixed before landing: `to_prompt_block()` iterated dict insertion order, so two extractions of a semantically identical glossary keyed differently
- **Verify:** `cd translator && make test && make lint` green, plus: the same segment under two glossaries stores two distinct rows; a glossary-prompt change causes re-translation rather than a €0 no-op (the defect's exact scenario, proven fixed); migration survives a simulated kill between DROP and RENAME; non-invalidation proven on a real-cache copy with row counts reported; every guard mutation-proven to fail without its fix.
- **Verify run 2026-07-26 on merged `main`** (merge `90fa95a`): `python3 -m pytest tests/ -q` → **328 passed, 2 skipped** (307 baseline at `65e90f7`); **363 passed** on the merged tree with A1; black (48 files) + ruff clean. Non-invalidation re-run **by the Supervisor** against a fresh copy of the real cache: counts unchanged across all four tables (`translations` 13426, `glossaries` 6, `calls` 1551, `book_contexts` 0), **13426/13426 byte-identical**, **13426/13426 resolving under the runtime key**, idempotent over two opens. Order-independence confirmed: identical terms in two insertion orders now yield one identity. Mutation: reverting the sort turns exactly the two new tests red, the other 19 guards stay green. **€0.**
- **Why the ordering fix had to land in this commit, measured:** all six glossaries in the real cache are stored out of sorted order, so landing unsorted would have written six `glossary_hash` values the sorted renderer could never reproduce — all 13,426 rows stranded behind a second migration on the only copy of ~€7 of paid translation. Free now; six-for-six breakage one commit later.

#### A4 — Provider and cost correctness (2 pt) ✅ *(bounced once)*
*Review findings 5, 6, 7, 18, 19, 20.*
- [x] Content-policy fallback spend appears in the CLI's reported total (§4 invariant: costs are visible)
- [x] An unknown model fails **before** the API is billed. Supervisor-verified that `create_client` is the *only* construction route (both direct constructions live inside it) and nothing mutates `.model` after `__init__` — so no path reaches `cost_eur` post-billing
- [x] Content-less and truncated responses fail loudly instead of returning `""` billed — **but degrade, not abort**; see below
- [x] `"o"` prefix no longer swallows typos like `"opus-4"`; empty memo is cached so a resume does not re-bill
- [x] **Finding 18 corrects the review.** Anthropic signals policy refusals *both* as an HTTP `BadRequestError` **and** in-band via `stop_reason="refusal"` on an otherwise-200 response (SDK-typed in `anthropic==0.109.1`). A wrapper around `BadRequestError` alone — which is what the review prescribes — misses the in-band case entirely
- **Verify:** `make test && make lint` green, one test per finding, each mutation-proven; the fallback-spend test asserts the printed € equals `stats.cost_eur`; the unknown-model test asserts the provider was never invoked.
- **Verify run 2026-07-26 on merged `main`** (merge `e52930a`): **379 passed** on the fully merged tree (A1 + A2 + A4), black + ruff clean. Hash gate re-run: **all six books unchanged**. Non-invalidation re-run: **13426/13426** byte-identical and resolving. **€0.**
- **Bounced once, and the bounce is the story.** The first implementation made providers raise instead of returning `""` — and thereby broke the retry ladder, which catches only `ValueError` (`:476`, `:487`) and `ContentPolicyError` (`:414`, `:566`). The raises happen inside `client.complete()`, **outside every handler**. That turned a truncation from "degrade to per-segment fallback" into "abort mid-book" — strictly worse, because per-segment retry is precisely the right remedy for a truncation (one segment per call is a far smaller prompt), and since the cache commits per batch, a resume rebuilt the same oversized prompt and aborted again. An automatic recovery became an unrecoverable stall. Found by Supervisor inspection, not by any reviewer agent.
- **The landed version degrades at every call site that has a smaller unit** — batch rung (`_complete_batch_rung`), revise pass, book-context memo — and stays **loud** at `_translate_single`, which has none. Two of those four sites were beyond the literal ask and landed together deliberately: `_revise_batch` is the default style's second pass carrying the identical gap, and `build_book_context` was a regression the first A4 commit introduced. Splitting them would have left a known landmine in the file just bounced on — §9 is fix by class, not instance.
- **Truncated calls carry their already-billed `CompletionResult`** so real cost still reaches `stats.cost_eur`. An abort bug is not fixed by introducing an under-reporting one.
- **Observation, not fixed:** Anthropic's `stop_reason="refusal"` path is a full 200 response with real usage, so `ContentPolicyError` loses that billed cost the same way. Left alone — it is control flow for a class outside this story. Candidate for A10.

#### A1 — Extraction robustness (3 pt) ✅ *(finding 17 split out to A8)*
*Review findings 1, 2, 15, 16, 17. Hardening against future inputs — all three drop paths measured latent on the current corpus.*
- [x] Tolerant XHTML parsing; an unrecoverable document raises `EpubParseError` **naming the document**. **Strict parse first**, so every well-formed document takes the identical old path — this is what holds `book_hash` still, and it is asserted by making the repair function *unreachable*, not by comparing output. TOC documents keep warn-and-degrade, asymmetry documented — they carry no content
- [x] The digit rule is now **role-aware** rather than looser: a lone digit-bearing token in reflowed prose really is an artifact; the same token as a heading or a band line is not. Needed **three** sites, not the two the review names — the third, `_is_front_matter_title`, is a genuinely unreviewed instance (`_normalize_head_key` strips digits, so a chapter titled `1984` normalizes to the empty key and was classified untitled)
- [x] Finding 15 — a cross-chapter anchor emits at chapter end with a warning rather than vanishing (a misplaced image is a defect; a vanished one is data loss). Finding 16 — reference-count-aware dedup, a no-op on the corpus (no book references any path exactly twice)
- [x] The leniency rule written normatively above `_repair_xhtml` for B2 to mirror: strict parse → R0 decode fallback → R1 undeclared HTML5 entity → numeric ref → R2 bare `&` → `&amp;` → R3 unclosed void element → self-closing (quote-aware) → raise. Nothing else is repaired
- [ ] **Finding 17 not landed — split to A8.** Collapsing continuation documents changes `chapter_index`, hence every segment id. Kaplan has 21 such documents and would go 47 chapters → ~26: a paid re-translation of at least two books
- **Verify:** `make test && make lint` green; **the four-book `book_hash` table in the spec §5 is byte-for-byte unchanged**; a synthetic EPUB with a bare `&nbsp;`, unclosed `<br>` and stray `&` keeps its content; an unrecoverable document raises a named error; `"1984"` survives both drop paths; every guard mutation-proven.
- **Verify run 2026-07-26 on merged `main`** (merge `fea323c`): `python3 -m pytest tests/ -q` → **340 passed, 2 skipped** (307 baseline); **363 passed** on the merged tree with A2; black + ruff clean. **HASH GATE PASS byte-for-byte, re-run independently by the Supervisor on the merged tree, on all six books** — the four EPUBs plus **two PDFs the agent baselined beyond its brief** (`8cf7c161…`/2450/41/83, `62ce823d…`/3229/34/0), because both have already-assembled `.sl.epub` artifacts that a source-side change would de-align. 11 mutations, 11 caught. **€0**, no `data/` copied into the worktree.
- **The `_strip_page_furniture` probe** (the drop path my pre-flight did not cover): *Active Measures* has **0 band lines at all**; *World Ends* has 1483 band lines, 935 dropped, and **exactly 8 killed solely by the digit rule** — six OCR folios, one garbled roman numeral (`XXV1`), one archive.org provenance stamp. **0 genuine content lines lost.** Latent, like the other two paths.
- **M3 initially passed, and that mattered:** the repairs are near-idempotent on valid markup, so an output-comparison test could never catch a leniency pass that always ran. Probing for a discriminator surfaced a real R3 defect — a literal `>` inside an attribute value ended the tag early and self-closed mid-attribute.

#### A3 — Language-bound styles (3 pt) ✅
*Review findings 4 (HIGH), 14, 10. The last port-blocking Python story.*
- [x] Styles declare `target_langs`; `revise_v1` is `sl`-only; `revise_generic_v1` covers other targets; a mismatch is a **loud, actionable refusal** naming the style, its languages, the request, and the style that would have run
- [x] Resolution is a table keyed `(tier, language)`. **Execution context sets the default tier only** — workstation → two-pass, device → single-pass per Niko 2026-07-26. B7's "higher quality, ~2× cost" toggle overrides the *tier*; it never falsifies the context
- [x] `[[n]]` markers anchored to line starts, **with the historical unanchored scan kept as a second attempt** so the change is cost-monotone: it can only remove retries, never add one for a model that replies on one line
- [x] `context_pairs<=0` disables rolling context instead of feeding every prior pair into each batch
- **Verify:** `make test && make lint` green; `--to de` does not run a Slovenian editor pass; a translation containing a literal `[[2]]` does not trigger a strict retry; `context_pairs=0` produces no context block; every guard mutation-proven.
- **Verify run 2026-07-26 on merged `main`:** **428 passed** on the merged tree (377 in-worktree baseline), black (50 files) + ruff clean. CLI evidence: `--style revise_v1 --to de` refuses with an actionable message and prints **no estimate**; `--to de` resolves `revise_generic_v1`; `--to sl` still resolves `revise_v1`. **8 mutations, 8 caught.** Hash gate re-run: all six books unchanged. **€0.**
- **Non-invalidation proven at both base and HEAD** against a copy of the real cache: 13426/13426 byte-identical and resolving, `baseline_v1` and `revise_v1` prompt digests unmoved. The *mechanism*, not just the number: `target_langs` never reaches the model, so it sits outside both `version` and `prompt_digest` — and mutation M8, which folds it into the digest, turns the pinning test red. That mutation is what stops the exclusion eroding later.
- **The ECONOMY/sl cell is `baseline_v1`, not `sl_style_v1`,** on the E2 bake-off evidence (ledger 2026-07-25): the Slovenian contract without the editor pass measured null on fluency (+0.05 [−0.10, +0.22]), so a single-pass run gains nothing by paying for its extra prompt tokens. A judgement call — one table row to reverse.
- **Reported, not fixed:** `prompts.DEFAULT`/`DEFAULT_STYLE_NAME` still point at `revise_v1` and are now a language-blind shortcut past the table (guarded by `ensure_supports`, but worth deleting once B1b is written against `resolve_style`). `estimate_cost`'s `TARGET_EXPANSION = 1.1` is documented as a Slovenian assumption but applies to every target — now reachable via `--to de`. → A5/A7.

#### A5 — Experiment and eval edges (2 pt)
*Review findings 8, 12, 13.*
- [ ] `eur_per_1k_words` excludes the fixed memo cost its own docstring says it excludes (`experiment.py:1020`, `:552-568`)
- [ ] Lead-in forbidden-hash guard is global, not per-run (`experiment.py:300`)
- [ ] The extraction screen does not silently default an unparseable reply to "dirty" (`screen.py:206-208`)
- **Verify:** `make test && make lint` green; a book-context variant's reported €/1k words excludes the memo; an identical paragraph across two runs is not served the control translation at €0; an unparseable screen reply raises rather than scoring; every guard mutation-proven.

#### A6 — Re-score Rubric T after Track A (1 pt)
- [ ] Re-score one book and confirm no regression against the 88.0 [86.2, 89.9] baseline (`rubric_scores.jsonl`, commit `9697a90`)
- **Verify:** `berilo eval "data/examples/The Revenge of Geography.sl.epub" --sample 40 --seed 42` — T not regressed beyond the CI. **Expected €0** if `book_hash` held per A1's gate; if it did not, this needs Niko's go-ahead first.

#### A7 — `calls.prompt_version` so T7 charges one arm (1 pt)
*Found by A2, out of its scope. Finding 11's fix is one column short.*
- [ ] `calls` has no `prompt_version` column, so T7's cost sums every prompt version for a book/model/lang. **Confirmed in the real cache:** Kaplan (`f30cd8f3…`) holds both `baseline_v1` (1267 rows) and `revise_v1` (1267 rows), so evaluating either arm today charges it with both runs' spend
- **Verify:** `make test && make lint` green; a book with two prompt versions in `calls` yields a T7 figure charging only the evaluated arm, mutation-proven.

#### A8 — Continuation documents over-count chapters (2 pt)
*Review finding 17, split out of A1 because it moves `book_hash`. **Needs Niko's go-ahead — it implies a paid re-translation.***
- [ ] A chapter split across N XHTML files yields N distinct `chapter_index` values sharing one title (`epub.py:697-701` vs `:755`), so `chapter_count` over-counts and `chapter_index` is no longer 1:1 with `chapter_title`
- [ ] **Measured continuation-document counts:** Kaplan **21**, Ember Spark **10**, New Rules 1, Sandworm 1. Kaplan's `chapter_count` over-counts by ~45% (47 → ~26)
- [ ] Fixing changes `chapter_index` → `make_segment_id` → every hash → **paid re-translation of at least two books (~€1.45 each)**
- **Verify:** hash delta reported at €0 first; then, only with explicit go-ahead, re-translate and re-score affected books with no Rubric T regression.

#### A9 — `<br/>` joins words in EPUB extraction (1 pt)
*Found by A1, out of scope, pre-existing — not introduced by the leniency pass.*
- [ ] `First line<br/>second line.` normalizes to `First linesecond line.` — `_serialize_inline` unwraps `<br/>` with no separating space. A real word-joining defect in the canonical input format
- **Verify:** `make test && make lint` green; a `<br/>`-separated pair round-trips with a space, mutation-proven; **`book_hash` reported before/after** — this changes segment text, so it may move hashes and needs the A8 treatment if it does.

#### A10 — Housekeeping from the review (1 pt)
- [ ] `berilo doctor` smoke-tests the provider *default* models, not `config.translation_model`/`judge_model` (`doctor.py:44-47`, and the review's own scope note) — it passes while the configured model is unpriced. No longer a billing bug after A4's pre-flight, but false confidence
- [ ] `assemble.py` packages a twice-referenced figure's bytes twice (`_image_hrefs` keys href by position). Correct placement at a small size cost; no example book triggers it
- **Verify:** `make test && make lint` green; `doctor` exercises the configured models; a twice-referenced figure yields one packaged payload with two references, both mutation-proven.

### Track B — on-device translation (Kotlin)

> **Sequencing correction, 2026-07-26:** Track B was initially deferred whole,
> but B1 splits — its identity half is gated by **A1** (landed) and only its
> prompt-registry half by A3. **B6** is gated by **A4** (landed). Both started
> as soon as their gates cleared rather than waiting for all of Track A.

#### B6 — Kotlin provider hardening (2 pt) ✅
*Review findings 6, 7, 18, ported from the **corrected** Python — the reason Track A went first.*
- [x] `requireKnownModel` pre-flights inside `createLlmClient`, **before** either client is constructed (`Pricing.kt:41-42` previously threw *after* the response was billed)
- [x] Content-less and truncated responses are representable failures (`EMPTY_COMPLETION`/`TRUNCATED_COMPLETION`) **carrying the already-billed `LlmResult`** — deliberately not a design B5 must undo. The Python version of this fix was bounced for making the failure loud in a way that removed the recovery handling it; here the failure carries its cost so B5's ladder can degrade rather than abort
- [x] Content-policy signal added (Kotlin had none): **both** the HTTP 400 body and Anthropic's in-band `stop_reason="refusal"` on an otherwise-200 response — the case A4 found that the review's prescribed `BadRequestError` wrapper misses
- [x] Backoff: one fixed 1000 ms retry → the Python ladder (up to 5 retries, `base*2^(n-1)+jitter`, honouring `Retry-After`). One retry is thin for a ~162-call on-device book
- **Verify:** `./gradlew test assembleDebug` green with no regression on 536, plus: an unknown model fails before any HTTP request (**zero** MockWebServer requests); empty and truncated responses surface distinctly carrying billed tokens and cost; both Anthropic refusal paths raise content-policy; `Retry-After` honoured on virtual time; no error message contains a body or key substring; every guard mutation-proven.
- **Verify run 2026-07-26 on merged `main`:** **580 JVM tests** (314 debug incl. 26 skipped screenshot + 266 release), **0 failures**, up from 536. Android Lint clean on touched files. Secret scan clean; Supervisor-confirmed that `LlmHttp.kt:145-148` interpolates only a provider label and HTTP code — never a body. **€0.**
- **Known gap, reported not fixed — filed to B7.** `createLlmClient` is **not** the sole construction route the way Python's `create_client` is: `SettingsViewModel.kt:60-61` builds `OpenAiClient`/`AnthropicClient` directly for the key-test feature. Supervisor-verified. Harmless today because those models are hardcoded constants always in the pricing table, but any future direct site with a user-supplied model reopens finding 6 in Kotlin specifically. **B7 must also fix `SettingsViewModel.persistCurrentState()` (`:132-142`), which drops `dictionaryModel`/`interpretationModel` on every save and would inherit `translationModel`.**

#### B1a — Kotlin identity + cross-language golden fixtures (3 pt) ✅
*Makes the §3.2 invariant provable rather than asserted. Gated by A1 (landed); fixtures generated from post-A1 Python.*
- [x] Kotlin segment model, `makeSegmentId`/`bookHash`/`segmentHash` byte-exact against Python, `Book` JSON round-trip. No new dependency (`MessageDigest`, `Base64`)
- [x] Golden fixtures per example book, **containing no book text**
- [x] Fixture format designed so B2 tightens to a full round trip without changing it
- **Verify:** both suites green; the four fixtures regenerate byte-identically and reproduce the §5 table; `makeSegmentId` matches Python on non-ASCII (č/š/ž), whitespace and empty cases; `bookHash` reproduces each fixture; mutation-proven; **no committed fixture contains book text**, asserted programmatically.
- **Verify run 2026-07-26 on merged `main`:** Android **628 JVM tests** (338 debug incl. 26 skipped screenshot + 290 release), 0 failures, from a 536 baseline; Python **428 passed**; lint clean both sides. Fixtures regenerate byte-identically and reproduce all four §5 rows. **3 mutations, 3 caught** (id separator, strip, book-hash join). **€0.**
- **Byte-exactness defect found and fixed — it would have been silent.** Python's `str.strip()` removes **29** code points; the JVM's `isWhitespace|isSpaceChar` covers **28**. The sole divergence is **U+0085 NEXT LINE** — category `Cc`, so neither predicate matches, and it is above U+0020 so `trim()` leaves it. Any segment padded with it would hash differently in the two languages and break the invariant with no error. `Identity.kt` carries its own `pythonStrip()` with the set hard-coded, which also immunizes the hash against a JDK Unicode-version bump. **Supervisor-verified in both runtimes** (Python set = 29, contains U+0085; JVM set = 28, `trim()` does not remove it).
- **Copyright discipline, Supervisor-audited.** Across all four real-book fixtures the *complete* set of non-digest, non-image-id strings is `{blockquote, heading, list_item, paragraph, epub, image/jpeg, four slugs, en|eng|en-US|EN-GB}` — nothing with more than two words. Filenames are recorded as slugs, never the on-disk names, which carry download provenance.
- **The gate recomputes rather than compares:** `assertMatchesFixture` takes a `Book` and derives every id from `(chapterIndex, position, text)`, so ids copied from elsewhere cannot pass. `segment_hash` is in the fixture deliberately — it is the actual translation-cache key, so B2/B4 can prove cache-row agreement on real text without a second fixture; `data_sha1` does the same for B3.
- **Reported, not fixed:** `Book.language` is raw OPF soup across the corpus (`en-US`, `en`, `eng`, `EN-GB`). A3's `normalize_lang` handles this on the Python side; **B7 must use the same rule** or Ember Spark's `EN-GB` and Sandworm's `eng` will refuse against a `--to`-derived tag. Also: `android/` has **no ktlint or spotless** — "lint clean" for Kotlin here means the compiler plus Android Lint only.

#### B4 — Room v6 translation cache (3 pt) ✅
*Ports A2's **corrected** design — the glossary is in the key from day one, not migrated in later.*
- [x] Translations PK is the same six columns as `cache.py`: `(bookHash, segmentHash, model, lang, promptVersion, glossaryHash)`; glossaries and calls tables alongside
- [x] `glossaryIdentity` derived from the rendered block, terms **sorted by source term** — the exact defect A2 shipped and had to fix, so B4 carries a mutation that unsorts the renderer
- [x] `storeBatch` is `@Transaction` — the resumability guarantee, mirroring `translate.py`'s per-batch commit so process death costs at most one batch
- **Verify:** `./gradlew test assembleDebug` green with no regression on 628; a v5 DB opens as v6 with every pre-existing row intact; the same segment under two glossaries stores two rows; `glossaryIdentity` is insertion-order independent and `null`/empty share an identity; cross-language digest vectors match Python; batch store atomic; every guard mutation-proven.
- **Verify run 2026-07-26 on merged `main`** (merge `0a267bc`): **664 JVM tests** (356 debug + 308 release), 0 failures, from a 628 baseline the agent confirmed by stashing. Lint clean. **€0.**
- **Cross-language agreement Supervisor-verified** by recomputing all four vectors in Python: ASCII `8d8a2f16…`, non-ASCII šumniki `6f83e5a0…`, single-term `daf59ea9…`, empty/null `da39a3ee…` (sha1 of `""`). Both languages sort by code point, so `Ljubljana < Čatež < Šiška < Žale` agrees across the boundary.
- **The migration is purely additive** — three `CREATE TABLE`, no `ALTER`, no backfill, because no on-device translation ever existed. Asserted rather than assumed: the test hand-builds the v5 schema per the `MIGRATION_4_5` precedent, since `exportSchema=false` leaves no schema JSON to diff. Destructive migration deliberately **not** extended to v6 — a cache holding paid work must never be dropped.

#### B1b — Kotlin prompt registry and style resolution (2 pt) ✅
*Mirrors A3's contract. The reason review finding 4 was ranked port-blocking: the CLI masks it because Niko only targets `sl`, but the app exposes target language as a **free-text field**.*
- [x] `normalizeLang`, the `(tier × language)` table, `ensureSupports`, `resolveStyle` reproduce A3's rule exactly. The app is always `DEVICE` context; B7's quality toggle overrides the **tier** and never falsifies the context
- [x] Prompt strings byte-identical to Python's, with the vector **generated from the live `berilo.prompts` module** rather than hand-transcribed — removing the transcription step instead of relying on a test to catch a typo afterwards
- **Verify:** `./gradlew test assembleDebug` green, no regression on 628; every style's prompts and `promptDigest` byte-identical to Python; `normalizeLang` matches A3 on six cases; the five named resolution scenarios; an explicit mismatch throws naming style/languages/target/suggestion; `EN-GB`/`eng` do not spuriously refuse; every guard mutation-proven.
- **Verify run 2026-07-26 on merged `main`:** **712 JVM tests** (380 debug incl. 26 skipped screenshot + 332 release), 0 failures, from a 628 baseline. Lint clean. **8 mutations, 8 caught** — including (g), folding `targetLangs` into the digest payload, which mirrors A3's own M8 and confirms `targetLangs` stays outside cache identity on this side too. **€0.**
- **Byte-identity Supervisor-verified** against the live Python module: all 5 styles, all 6 prompt fields each, all `promptDigest`s and `STRICT_MARKER_CLAUSE` — every one identical. `baseline_v1` = `b6199d788ddac339` and `revise_v1` = `66f0704615450652` agree with the digests A3 independently reported from its cache non-invalidation proof, cross-checking both stories.

#### B2 — Lenient Kotlin EPUB reader (5 pt) ✅
*The hardest story of m4. Makes the §3.2 invariant true on **real books**, not just synthetic ones.*
- [x] `normalize_epub` reimplemented in Kotlin: container→OPF, namespace-insensitive matching, chapter-title ladder, the segment-less-document rule, `_is_heading_like` retyping, inline subset, book-global `position`, image anchoring and dedup
- [x] **[OPEN-A] resolved against Jsoup** — identity-exactness requires mirroring A1's exact R0–R3 sequence; a different tolerant parser recovers *differently*, producing different segments and a different `book_hash`. Hand-rolled, no new dependency
- [x] Strict parse first, **asserted by mechanism**: `XhtmlParser` takes the repair pass as a constructor parameter, and a whole well-formed book is read through a parser whose repair *throws on invocation*. Output comparison cannot work here — the repairs are near-idempotent
- [x] Python behaviours reproduced **deliberately, not fixed**: `<br/>` unwraps with no separating space (A9) and continuation documents increment `chapter_index` (A8). Mutation M7 — adding the A9 "fix" — is caught by the identity gate, which is exactly why those are separate stories
- **Verify:** `./gradlew test assembleDebug` green, no regression on 628; all four books reproduce the §5 table with ids recomputed; a synthetic EPUB with `&nbsp;`, unclosed `<br>` and stray `&` recovers intact; an unrecoverable document raises naming itself; a well-formed document never reaches repair; real-book tests skip cleanly without `data/`; identity-critical paths mutation-proven.
- **Verify run 2026-07-26 on merged `main`:** **692 JVM tests** (628 baseline), 0 failures; Python unchanged at 428. **Supervisor-verified in both directions** — with `data/examples` present the identity gate runs **2 tests, 0 skipped, 0 failures**, so the reader genuinely reproduced all four hashes; without the books it **skips 2, 0 failures**, so CI stays green. **14 mutations, 13 caught, 1 proven *equivalent*** rather than papered over. **€0.**
- **A defect that would have silently broken the whole port.** The JVM XML parser **drops an undeclared entity with no error at all** when a DOCTYPE is present — expat raises either way, Xerces only *without* one. With a DOCTYPE naming an external subset the parser won't fetch, the well-formedness constraint becomes "not checkable" and the entity vanishes: no `fatalError`, no warning, no callback. `Bare&nbsp;entity` → `Bareentity`. **Every real EPUB has a DOCTYPE**, so a faithful-looking port loses a character per entity and hashes every book differently. Fixed with `expandEntityReferences=false` plus hand splicing.
- **Device residual:** verified against the JDK's Xerces; the Boox runs Android's XML parser, so the entity-reference behaviour needs one on-device re-confirmation.

#### B5 — On-device translate engine (5 pt) ✅
***The story m4 exists for: a book can now be translated inside the Android app.***
- [x] `translateBook` ports `translate_book` faithfully on the verified base — dynamic batching capped at 10 (broken by chapter boundary, empty segment or cache hit), rolling context, anchored-with-fallback marker parsing, the batch → strict → per-segment ladder, the revise pass, per-batch cache commit, glossary pass, 1:1 integrity
- [x] `estimateCost` for B7's spending gate (CLAUDE.md §4)
- [x] **The truncation-degrade design, which is what A4 was bounced for getting wrong.** `EMPTY_COMPLETION`/`TRUNCATED_COMPLETION` degrade at every rung with a smaller unit — batch, revise, book-context memo — appending B6's already-billed result to the accounting, and stay **loud** only at `translateSingle`, which has none. Two mutations pin both halves
- [x] `DEVICE` → ECONOMY by default; a QUALITY override resolves the two-pass style; the context is never falsified
- **Verify:** `./gradlew test assembleDebug` green, no regression; a killed run resumes re-billing zero cached segments; a second full run makes zero API calls; batch shapes asserted concretely; truncation degrades and completes; billed cost still folded in; revise failure keeps the draft; 1:1 integrity matches Python on duplicate/missing/stray markers; mutation-proven.
- **Verify run 2026-07-27 on merged `main`:** **924 JVM tests** (486 debug incl. 28 skipped + 438 release), **0 failures**, from a **776** baseline the agent measured with `git stash -u` rather than trusting the packet's 712 (which was from merged `main`). **12/12 mutations caught.** Lint 0 errors. **€0.** Supervisor-verified: all **31** `TranslateEngineTest` cases pass with **0 skipped**, including the resumability and full degrade-ladder cases by name.
- **Resumability evidence:** a 25-segment book, client dies on call 3 → 20 segments committed; resume with a fresh client makes **one** call carrying exactly the 5 uncached segments; a third run makes **zero**. Removing the per-batch commit fails that test at `expected:<20> but was:<0>`. Batch shapes asserted as `[10, 2, 1, 3]` from a book tripping the cap, a chapter boundary, an empty segment and a cache hit — not merely "batches happened".
- **Deliberate divergence:** `TranslationStats` is immutable, unlike Python's mutable dataclass — B7 renders it in Compose, and a mutated object handed to the UI would compare equal to itself and never recompose.
- **Reported, not fixed:** B4 shipped no `book_contexts` table, so the per-book style memo cannot persist on device. Inert today (no `DEVICE`-resolvable style declares a `bookContextSystem`) and asserted as such rather than left to be discovered. Required before any book-context style reaches the device. Also: `TARGET_EXPANSION = 1.1` is a Slovenian assumption applied to every language, ported as-is and pinned so it cannot drift from the CLI.

#### B3 — Deterministic Kotlin EPUB writer (5 pt) ✅
*Ports `assemble.py`. [OPEN-D] resolved toward byte-identity — see the correction below.*
- [x] Chapter grouping, body rendering, the inline subset with whole-segment escape fallback, image placement, OPF/nav/chapter/CSS, bilingual mode with 1:1 alignment validation
- [x] Hand-written ZIP headers, hand-rolled RFC 4122 UUID v5
- [x] `CAPTION`/`OTHER` class tags and `<div class="figure">` preserved deliberately — the agent confirmed why: `EpubReader` recovers those types from `p.caption`/`p.other`, and a real `<figure>` would mint a **new** CAPTION segment on rebuild, shifting every later `position` and id and the whole `bookHash`, so a paid book would stop resolving against its own cache
- **Verify:** `./gradlew test assembleDebug` green; byte-identity against Python's `build_epub` on real books with both sha256 reported; round-trip type stability; UUID5 matches `uuid.uuid5`; STORED entries carry correct CRC-32 and size, `mimetype` first and uncompressed; inline escape rules; orphan-anchor image emitted not dropped; real-book tests skip without `data/`; mutation-proven.
- **Verify run 2026-07-27 on merged `main`:** **982 JVM tests** (515 debug incl. 30 skipped + 467 release), 0 failures, from a **776** in-worktree baseline confirmed with `git stash -u`. **20 mutations, 20 caught.** **€0.** Byte-identity **Supervisor-verified**: the claimed digest for *The New Rules of War* (`85e89f18…`, 2 652 144 B) reproduces exactly from Python's `build_epub` given the same absolute source path.
- **The real porting trap was not the CRC.** `java.util.zip.ZipOutputStream` cannot byte-match Python's `zipfile` at all — three header fields differ on every archive (extract-version 10 vs 20 on STORED, create-system 0 vs 3, external attributes 0 vs `0o600 << 16`), plus flag-3 data descriptors on DEFLATED entries of undeclared size. Hence hand-written headers. The deflate payload agreed out of the box.
- **Two mutations initially survived and were closed rather than declared equivalent** — whole-segment escaping coincides with per-tag escaping unless a *valid* pair precedes the bad tag, and first-appearance chapter order coincides with sorted order on anything `normalize_epub` emits. Each was one synthetic case away from being caught.

#### B8 — The EPUB identifier is path-dependent (2 pt) — *needs Niko's decision*
*Found while Supervisor-verifying B3's byte-identity claim. It invalidates part of the [OPEN-D] resolution.*
- [ ] `assemble.py:374-377` seeds the `dc:identifier` UUID5 on `berilo:{source_path}:{title}:{language}`. Measured on one book, same `Book`, only the path varying: absolute → `85e89f18…`/2 652 144 B · repo-relative → `3ff13117…`/2 652 144 B · `../`-relative → `16f34b3e…`/2 652 142 B · a device path → `5b320784…`/2 652 145 B. The length moves too, because `content.opf` is DEFLATED
- [ ] **Consequence:** the device stores books at `filesDir/books/<sha256>.epub`, the workstation at `data/examples/<name>` — so the same book translated on both gets a different `dc:identifier` **and** a different file sha256, and `BookImporter` dedupes on exactly that sha256 (`BookImporter.kt:73`). It imports as **two separate books**, which also affects Phase-3 sync
- [ ] **B3's gate stays correct** as a *writer-fidelity* test (same `Book` in → same bytes out, across languages). It is not, and cannot be, a cross-device guarantee
- [ ] **Decision required:** reseed the identifier on something path-free such as `book_hash` — which changes `dc:identifier` for every EPUB already produced — or accept per-device identifiers and dedupe on `book_hash` instead of file bytes
- **Verify:** the same `Book` written from two different `source_path` values produces byte-identical output, mutation-proven; existing translated EPUBs' identifiers reported before/after so the blast radius is known before anything ships.

#### Remaining (1 story, 3 pt)
**B7** import → estimate → confirm → WorkManager → progress UI (3).

Full text in the spec §4. Two decisions stay open: **[OPEN-A]** Jsoup vs.
hand-rolled tolerant parsing (decide at B2) and **[OPEN-D]** whether tablet
output must be byte-identical to workstation output (decide at B3).

---

## Backlog (post-m3, unscheduled)
Reading stats · "Ask the book" (spoiler-safe) · vocabulary spaced repetition
export · quote cards · additional source languages (DE, FR) · iOS.
