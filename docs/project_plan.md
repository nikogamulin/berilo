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
- [ ] Translate all 3 example books EN→SL at default models; record costs; fix top defects found by eval; iterate until gate
- **Verify:** Rubric **T ≥ 85 (CI lower bound ≥ 80) on all 3 books**; total cost ≤ €5; scores in `rubric_scores.jsonl`; 2-page manual spot-read by Niko signed off (recorded in issue).

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

---

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
- [ ] Auth (Clerk JWTs against Supabase RLS), background sync of notes/highlights/vocabulary/progress, last-write-wins with `updated_at` UTC, offline queue
- **Verify:** Rubric S1 scenario matrix 100%; S3 chaos test 0 data loss; DST-boundary test green.

### S3.3 — Web review app (5 pt)
- [ ] Next.js on Vercel: Clerk login, notes/highlights browser (search, filter by book/color/date), vocabulary review, Markdown export
- **Verify:** S4 checklist 10/10; S5 LCP ≤ 2.5 s on Vercel analytics; e2e Playwright suite green in CI.

### S3.4 — Reading page — social layer (5 pt) *(spec §6.1)*
- [ ] Public reading page on berilo.app, Slovenian-first: profiles & shelves (book metadata only), shared passages (≤ 500-char excerpt, title+author attribution), 1–5 ratings + short reviews, per-item opt-in sharing (private by default), design per `design_guidelines.md`
- **Verify:** Playwright e2e: sign up → shelve book → share passage → rate → anonymous visitor sees only opted-in content; passage > 500 chars rejected server-side; UI strings render šumniki correctly (č/š/ž spot-check on live page); Lighthouse accessibility ≥ 95 on the reading page.

### S3.5 — Launch gate (2 pt)
- [ ] Privacy policy (only user-created data syncs, books never leave device), free-tier limits, error monitoring
- **Verify:** Rubric **S ≥ 85**; S2 and S3 gates at max; policy page live.

---

## Backlog (post-m3, unscheduled)
Reading stats · "Ask the book" (spoiler-safe) · vocabulary spaced repetition
export · quote cards · additional source languages (DE, FR) · iOS.
