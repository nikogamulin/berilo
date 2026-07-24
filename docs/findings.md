# Findings register (Tier 2)

- [2026-07-24] Android build env: box has JRE-only OpenJDK (no javac, no
  sudo) — JDK 21.0.2 bootstrapped at `~/.local/share/jdk-bootstrap/jdk-21.0.2`;
  every Android gradle invocation needs
  `JAVA_HOME=$HOME/.local/share/jdk-bootstrap/jdk-21.0.2` +
  `ANDROID_HOME=$HOME/Android/Sdk`. (adoptium mirrors 504'd;
  download.java.net worked.)
- [2026-07-24] Android dependency ceiling with platform-35 only: AGP 8.8.2 +
  Gradle 8.10.2 (AGP 8.8.2 needs ≥8.10.2) + Kotlin 2.1.0 + Room 2.8.4 +
  Readium 3.1.2 (needs core-library desugaring on minSdk 26) + Coil 3.2.0 +
  coreKtx 1.15/lifecycle 2.8.7/activityCompose 1.9.3. Later androidx/Readium
  need compileSdk 36+/AGP 9.1+ — hold this set or install platforms;android-36
  and rebase. Verified via each AAR's aar-metadata `minCompileSdk`.
- [2026-07-24] Room `exportSchema=true` + schemaLocation crashes
  kspReleaseKotlin (AbstractMethodError, kotlinx-serialization clash on this
  dep set) — left `exportSchema=false` until a migration needs it.
- [2026-07-24] Android testability pattern: inject the IO dispatcher
  (constructor param defaulting to Dispatchers.IO) — hardcoded
  `withContext(Dispatchers.IO)` escapes `runTest` virtual time and flakes.
- [2026-07-24] Root `.gitignore` had unanchored `data/` which silently
  unstaged ANY directory named `data` (bit the Android package layout —
  package renamed `store/`); pattern now anchored to `/data/`.
- [2026-07-24] Android SDK installed headlessly at `~/Android/Sdk`
  (cmdline-tools latest, platform-tools/adb 37, platforms;android-35,
  build-tools;35.0.0; licenses accepted). No system gradle — projects use
  the wrapper. Set `ANDROID_HOME=~/Android/Sdk`; NEVER commit
  `local.properties` (contains a local path → §7 scan trips).

> Durable, session-discovered knowledge: gotchas, working commands,
> conventions, stuck-patterns. **Scan before debugging or running non-obvious
> commands.** Promote to CLAUDE.md §9 once a finding recurs or is endorsed.
> Format: `- [YYYY-MM-DD] finding — evidence/command`.

- [2026-07-24] Example set is **2 PDF + 1 EPUB, no MOBI** despite "pdf or mobi"
  in the brief — MOBI support goes through `ebook-convert` with a converted
  fixture for tests.
- [2026-07-24] Both example PDFs have text layers (no OCR pass needed), but
  *This Is How They Tell Me the World Ends* is OCR-sourced: running headers and
  roman page numbers appear inline in extracted text (`"PROLOGUE \nXix \n"`).
  Normalizer must strip header/footer lines by repeating-pattern detection, not
  just regex.
- [2026-07-24] Environment (TensorForgeX): Python 3.10.12, Node v22.13.1,
  PyMuPDF 1.27.2 installed system-wide, `pdftotext` and Calibre
  `ebook-convert` on PATH.
- [2026-07-24] `~/.local/bin/gh` is a stray PyPI package (`gh` 0.0.4) shadowing
  the real GitHub CLI — always call `/usr/bin/gh`. Installed gh is 2.4.0
  (Ubuntu): no `gh label` command, `gh issue close` broken (git exit 128) —
  use `gh api repos/nikogamulin/berilo/...` for labels, milestones, issue
  state changes.
- [2026-07-24] The `pytest` shim on PATH (`~/.local/bin/pytest`) is shebang'd
  to python3.12 while `pip install --user` targets python3 (3.10.12) — bare
  `pytest` can't see the installed package. Always run `python3 -m pytest`
  and `python3 -m black` (encoded in `translator/Makefile`); `ruff` is a
  native binary and unaffected.
- [2026-07-24] `epubcheck` 5.3.0 installed to `~/.local/bin/epubcheck`
  (wrapper over jar in `~/.local/share/epubcheck-5.3.0/`, Java 21 present);
  `ebook-convert` and `pdftotext` are on PATH.
- [2026-07-24] **gpt-5-mini bills hidden reasoning tokens as output**: the
  doctor one-sentence smoke used 479 output tokens for a ~15-token visible
  translation (≈15–30× inflation on short outputs). S1.5 `--dry-run` cost
  estimates must include a reasoning-token multiplier or they will
  underestimate badly. claude-haiku-4-5 returned 25 output tokens for the
  same sentence.
- [2026-07-24] Synthetic API keys in tests must NOT use real vendor key
  prefixes ("sk-" + proj/ant forms) or the §7 secret scan false-positives — use
  `test-openai-key-...` style. Real vendor exception types for retry tests
  are constructed with a fake `httpx.Response(429, request=...)`; OpenAI and
  Anthropic SDK clients construct offline with dummy keys (no network at
  init).
- [2026-07-24] `data/` exists only in the main checkout, not in agent
  worktrees (gitignored ⇒ not shared): `data/`-gated integration tests must
  skip gracefully; run real-book Verify numbers from the main checkout.
- [2026-07-24] EPUB3 `<nav>` documents (TOC/landmarks) must be excluded from
  segment extraction or their `<li>/<a>` structure injects a duplicate TOC
  as junk segments at chapter 0 (handled in `normalize/epub.py`).
- [2026-07-24] *The New Rules of War* back matter is huge: Notes+Index+
  Bibliography ≈ 1086 of 2309 segments (47%; Index alone 757). Translating
  back matter by default would roughly double cost for near-zero reader
  value — S1.5 should expose `--skip-back-matter` (or similar) and dry-run
  estimates should show per-chapter breakdown.
- [2026-07-24] Deterministic EPUB output: `zipfile.writestr(name, data)`
  stamps wall-clock time — build explicit `ZipInfo` with fixed
  `date_time=(1980,1,1,...)` per entry, fixed entry order, fixed
  `dcterms:modified` (done in `assemble.py`; the S1.5 byte-identical cache
  Verify depends on it).
- [2026-07-24] Don't validate the inline-emphasis subset by XML round-trip
  (`ET.fromstring`): literal `&`/`<` in prose is legit content, not
  malformed markup. Use a regex tokenizer + open/close tag stack
  (`assemble._render_inline`); attribute-bearing tags fall back to full
  escaping (XSS/injection safe, reviewer-probed).
- [2026-07-24] `normalize/epub.py` only emits known block tags: prose
  sitting directly in a `<div>`/`<td>` with no block wrapper is skipped —
  known limitation, 0 occurrences in the example books.
- [2026-07-24] PDF reflow: a single global modal-x0 indent threshold breaks
  on alternating recto/verso margins (World Ends verso x≈16 / recto x≈28) —
  every minority-margin line reads as an indent and capitalized wraps split
  into orphan fragments (raw p.371: 1 paragraph → 3 segments). Threshold
  must be per-page/per-parity. Active Measures (equal margins x=77) is
  unaffected — the bug is input-conditional.
- [2026-07-24] Garbled OCR page numbers in the header band ("40OI1" = p.401)
  evade bare-number/roman regexes AND ≥3-page recurrence, then reflow's
  continuation-override merges them into the next body paragraph — strip
  in-band single tokens matching `^\S*\d\S*$` BEFORE reflow, not post-merge.
- [2026-07-24] Prose-quality sampling must be PARAGRAPH-only with every
  non-prose class explicitly typed: front matter folded to a "Front Matter"
  chapter (mirroring the "Notes" back-matter fold, in BOTH TOC and heuristic
  paths), captions → CAPTION, scene-setter datelines and body scan residue →
  OTHER (retyped, NEVER dropped — `translate_book` translates all non-empty
  segments so 1:1 integrity holds). `_is_ocr_gibberish` (real-word-ratio)
  keeps real short sentences. Front/back matter must be fixed TOGETHER —
  excluding one shifts the fixed-seed sample into the other's noise (screen
  went 27→22/30 after a genuine fix purely from the pool redraw).
- [2026-07-24] Screen prompt bumped to v2 (`screen.SCREEN_PROMPT_VERSION`),
  scoped to EXTRACTION artifacts per Rubric T6's definition — source-OCR
  character garble ("s500" for "$500", "T think" for "I think") screens YES
  when extraction is faithful. Final S1.2 gate: AM 29/30, WE 29/30 (96.7%,
  seed 42, gpt-5-mini).
- [2026-07-24] Screen-gate design caveat: World Ends has a source-OCR garble
  floor (~1/30 in the seed-42 sample: "s500" for "$500", "/eet" for "leet" —
  verbatim in the PDF text layer). A perfect extractor may still hover near
  95% on OCR books; if the gate fails purely on source-OCR flags, that is a
  gate-design decision (exempt OCR sources / optional correction pass), not
  a normalize defect. Screen-fix iterations must target artifact CLASSES —
  each pool change redraws the seed-42 sample, so instance-chasing never
  converges.
- [2026-07-24] Rubric T alignment (`berilo/eval/rubric_t.py`): difflib
  SequenceMatcher over (chapter, type, heading_level) fingerprints —
  structural scramble raises AlignmentError (exit 2), truncation flows into
  T1<100% which caps the total at 40. Same-type in-chapter swaps pair
  positionally by design (a real swap shows up as low T2). `berilo eval`
  auto-discovers the source from `<stem>.<lang>.epub`.
- [2026-07-24] T7's "≤1.5× dry-run estimate" clause is unevaluable — the
  cache `calls` table stores only actual cost. T7 currently scores the
  €1.50/100k-words clause only; persisting the estimate into the cache is a
  small follow-up if the ratio clause should count.
- [2026-07-24] S1.5 cost model: reasoning surcharge is a FIXED per-call
  additive (~464 output tokens/call, from the 479−15 doctor evidence), not a
  multiplier — a flat multiplier wildly overestimates long batches. Dry-run
  estimate includes the glossary call; the run summary now reports the true
  total via a cost-tracking client proxy (`cli._CostTrackingClient`).
- [2026-07-24] Translation cache keys on sha1(source text) not segment.id:
  free dedup of identical paragraphs, position-independent resume.
- [2026-07-24] CLI commands use `find_dotenv(usecwd=True)` → they WILL read
  the repo `.env`; CLI tests must run inside
  `CliRunner.isolated_filesystem()` to stay hermetic.
- [2026-07-24] Calibre's default `.mobi` output is legacy MOBI 6 (no heading
  semantics, `<blockquote>` indent hack): EPUB→MOBI6→EPUB round-trip drifts
  2.56% in segment count (over S1.3's 2% bar); `--mobi-file-type both`
  (embeds KF8, read back preferentially) gives 1.30%, identical to AZW3.
  Fixture builder uses `both`; `normalize_mobi` is input-variant-agnostic.
  S1.3 residual: legacy MOBI6-only user files may exceed 2% — upstream
  format lossiness, not a segment drop.
- [2026-07-24] `data/` is gitignored and holds copyrighted books — never
  commit, never upload contents anywhere except segment batches to the
  configured LLM API during translation.
