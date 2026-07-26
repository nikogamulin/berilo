# Findings register (Tier 2)

- [2026-07-25] **Rubric R cannot be scored on this box at all**: no Boox
  attached (`~/Android/Sdk/platform-tools/adb devices` lists none) and no
  emulator installed (`~/Android/Sdk/emulator/` and `~/.android/avd/` do not
  exist — the headless SDK install brought only cmdline-tools, platform-tools,
  platforms;android-35 and build-tools). All 7 R dimensions are device-measured
  (`docs/rubric.md:44-59`), so every Phase 2 story's residual is gated on
  physically connecting the tablet. The offline ceiling is
  `./gradlew screenshots test` = 416 JVM tests + 26 screenshots.
- [2026-07-25] **The Material3 baseline-purple leak recurred, and the recorded
  advice was why.** The 2026-07-24 bullet said to "grep `colorScheme.` before
  using a new role" — but every role referenced that way IS pinned. The leak
  comes from Material3 **component defaults** reaching for roles nobody pinned,
  which no grep of app code can see: `FloatingActionButton` →
  `primaryContainer`/`onPrimaryContainer` (`#EADDFF` on `#210050`, the
  library's primary action), `LinearProgressIndicator` track →
  `secondaryContainer` (`#E8DEF8`), `HorizontalDivider` → `outlineVariant`
  (`#CAC4D0`). `Theme.kt` pins 11 roles; M3 defines ~30. **Pin every role in
  both schemes.** Detector, €0 and repo-wide: convert each pixel of every
  screenshot to HSV and flag hue 0.63–0.85 with s>0.05, v>0.15 — caught the
  leak on 5 surfaces at once (fix by CLASS, per §9). Filed as S2.11.
- [2026-07-25] **Two samples cannot distinguish "stable" from "alternating".**
  A screenshot compared across 2 runs looked deterministic; across 5 runs it
  proved bistable. `library_boox_dark.png` alternated between two
  pixel-distinct states (23,008 px differing, 0.88%, max channel delta 18) —
  regions painting raw `(0,0,0)` canvas in one run and `#121212` `PaperDark`
  in the next. Comparing run N to run N+1 passes half the time by construction.
  **To claim determinism, run ≥5 and compare all pairs.**
- [2026-07-25] **Compose screenshot capture is not deterministic by default:
  Coil is the culprit.** `AsyncImage` dispatches fetch/decode onto
  `Dispatchers.IO`, a real thread Robolectric's main looper does not control,
  so `composeRule.waitForIdle()` can return before the decode and its
  recomposition have painted. Only the Boox-width dark library flapped (more
  covers on screen ⇒ more decodes in flight) and only on the first Coil decode
  in a JVM (later tests hit the warm memory cache). Fix that is deterministic
  *by construction* rather than a poll: install a JVM-scoped
  `SingletonImageLoader.setUnsafe(ImageLoader.Builder(ctx)
  .coroutineContext(Dispatchers.Unconfined).build())` — `Unconfined` does not
  hop threads for work with no real suspension point, and decoding a local
  drawable is exactly that, so fetch+decode complete inline before capture.
- [2026-07-25] Roborazzi ≥1.50.0 is unusable here: its kotlin_module metadata
  (2.3.0) exceeds this repo's pinned Kotlin 2.1.0 and `kspDebugKotlin` fails
  with "Module was compiled with an incompatible version of Kotlin". **1.45.1**
  works, library-only, no Gradle plugin (so no compileSdk pressure on the
  platform-35 ceiling). Generalization: the dependency ceiling already recorded
  for AGP/compileSdk **also binds via Kotlin metadata version** — pin new
  libraries to Kotlin-2.1.0-era releases, never "latest".
- [2026-07-25] Roborazzi's `captureRoboImage` is a **silent no-op that still
  passes** unless `roborazzi.test.record=true` is set (normally set by its
  Gradle plugin, which we deliberately do not apply). A missing system property
  looks identical to "nothing changed" rather than "nothing ran". The harness
  therefore reports **SKIPPED** (JUnit `Assume`) when the property is absent
  and asserts the PNG exists and is non-empty when it is present — "passed"
  must never mean "wrote nothing". **Grepping a new test suite for assertions
  is a cheap way to detect a gate that cannot fail.**
- [2026-07-25] Reader top bar collapses at phone width: `ReaderChrome.kt`'s
  chapter-title `Text` at `weight(1f)` loses all space to a sibling
  `horizontalScroll` Row of 6 action buttons, which measures at its
  unconstrained preferred width. The title **disappears rather than
  ellipsising** despite `TextOverflow.Ellipsis`, and the action row is clipped
  mid-word. Correct at Boox width (990 dp). The S2.9 Compose tests could never
  catch it — they assert node existence, never layout. Filed as S2.11.
- [2026-07-25] Never run a verification inside a worktree an agent still owns.
  A determinism check showed 1 of 26 PNGs differing and read as
  non-determinism; the worktree was in fact dirty (`git -C <wt> status
  --short` listed all 7 files) because the implementer was mid-edit. Check
  `git status` in the worktree, or copy the tree, before measuring anything an
  agent produced. Same family as the "measure the artifact FIRST" rule below.
- [2026-07-25] **The translation cache was not keyed on the prompt** — PK was
  `(book_hash, segment_hash, model, lang)`. Any prompt change would therefore
  serve the OLD translation at €0 on a re-run: a prompt A/B or a full re-run
  would report "no change" while never calling the model at all — a null result
  indistinguishable from a real one. Fixed in S1.10: `prompt_version` joins the
  PK, migration defaults existing rows to `baseline_v1`. **Generalization: any
  cache whose key omits an experimental factor silently converts that
  experiment into a no-op — check the key before trusting a null result.**
- [2026-07-25] Cache migrations must be verified against a COPY of the real
  cache, not just synthetic fixtures: `~/.cache/berilo/translations.db` holds
  10,936 rows / 4.0 MB of text across 5 books ≈ €4 of paid work. Verification
  recipe that caught nothing but would have caught everything: snapshot
  `{(book,segment,model,lang): text}` before, open the cache twice (proves
  idempotency), then assert row count, per-row text equality, correct default
  `prompt_version`, and that `glossaries`/`calls` survive.
- [2026-07-25] Judge-repeat aggregation must collapse repeats to a **per-sample
  mean before** the bootstrap arrays (`rubric_t.py`). Flattening N repeats of M
  samples into one N×M array would fake an N-fold larger sample and shrink the
  CI without adding information — the tempting-but-wrong implementation.
- [2026-07-25] **A 60-segment A/B predicted the 1294-segment outcome almost
  exactly**: the harness measured T3 **+1.93/20** for `revise_v1` from 6
  contiguous runs at €0.26; the full-book run delivered **+1.9** (12.0→13.9).
  Paired, cluster-bootstrapped sampling under production batching conditions is
  therefore a trustworthy stand-in for a full run — hypotheses can be screened
  at ~1/6 the cost and ~1/20 the wall-clock before committing to a book.
- [2026-07-25] **Fixing a measurement artifact can LOWER the score, and that is
  correct.** After S1.13 cleaned the body-prose pool, Kaplan went 89.0 → 88.0:
  the removed segments were untranslated English headings that the meaning
  judge had been scoring **5/5** (a title "translates" perfectly) while the
  fluency judge scored 1/5. They were inflating T2 more than they depressed T3.
  Expect artifact removal to move a score either way; judge the fix by pool
  cleanliness (0 byte-identical source/target pairs), not by score direction.
- [2026-07-25] **The fluency win comes from a SECOND PASS, not from a better
  prompt.** E2 bake-off (Kaplan, 6 contiguous runs × 10 segments, cluster
  bootstrap, seed 42): `sl_style_v1` — an explicit Slovenian style contract in
  the system prompt (no calques, verbal over nominal, drop redundant pronouns,
  dual, šumniki) — scored T3 **+0.05 [−0.10, +0.22]**, i.e. nothing, and T2
  **−0.23 [−0.53, +0.08]**, a hint of meaning regression. `revise_v1`, the same
  contract plus a native-editor revision pass over each batch, won BOTH: T3
  **+0.48 [+0.17, +0.87]**, T2 **+0.23 [+0.07, +0.45]**. Generalizable: telling
  a model to write better in the instruction is far weaker than giving it a
  separate turn to edit what it just wrote — and the edit pass improved
  *fidelity* too, so the expected style-vs-meaning tradeoff did not appear.
- [2026-07-25] Retyping heading-like paragraphs and repairing chapter-title
  resolution does **not** change `book_hash` or any `segment_hash` — both are
  derived from segment IDs and stripped text, not from types or titles
  (verified on Kaplan: same hash, same 1267 distinct segment hashes before and
  after S1.13). So a normalize fix of this class costs **€0** to adopt: existing
  translated EPUBs can be rebuilt from cache with no re-billing. Check
  `book_hash` before assuming a normalize change forces a paid re-run.
- [2026-07-25] Changing normalization on the SOURCE side alone breaks
  `rubric_t.align` against already-assembled translated EPUBs (they were built
  under the old segmentation). Symptom: `AlignmentError` even though segment
  counts still match, because alignment fingerprints on
  `(chapter, type, heading_level)`. Sequence such work as: measure the current
  artifact FIRST, then land the normalize change, then rebuild from cache and
  re-measure — otherwise a prompt effect and a normalization effect land in the
  same number and neither is attributable.
- [2026-07-25] **E1 calibration verdict (€0.145, Kaplan, sample 30, repeats 3):
  the fluency ceiling is REAL, not a judge artifact.** Judge intra-sample
  σ=0.18 (T3) / 0.22 (T2) — highly reproducible — and the verdict distribution
  is a full spread (1:2, 2:5, 3:10, 4:8, **5:5**). The judge awards 5/5
  ("indistinguishable from professional native prose") to 5 of 30 samples, so it
  is not floored at 3 by prompt design. **The score distribution is a cheaper
  discriminator than a human-prose control** — if a judge already gives top
  marks to some of your own output, its ceiling is not what is limiting you.
- [2026-07-25] **Kaplan (Revenge of Geography) has a book-specific normalize
  defect: its `toc.ncx` AND nav document both fail to parse (names in the
  manifest do not match the archive), so 94.9% of segments (1228/1294) fall back
  to the BOOK TITLE as `chapter_title`.** Rubric v1.1's front/back-matter fold
  keys on chapter titles, so on this book the fold is inert — it excludes 6 of
  47 chapters while the real front matter and TOC heading lines sit inside the
  1228-segment fallback bucket and pollute the "body prose" pool. Visible
  symptom: 2/30 sampled "body prose" segments were untranslated English headings
  ("THE REVENGE OF GEOGRAPHY", "Chapter III: Herodotus and His Successors")
  scored fluency 1.0. Removing just those two moves mean fluency 3.21→3.37
  (T3 12.8→~13.5). Also the likely cause of this book's T4 outlier (7.57 vs
  9.08–9.87 elsewhere). **Book-specific, verified by scanning all five:** title
  fallback share is 32.8% / 17.3% / **94.9%** / 40.4% / 32.3%. A "could not parse
  nav/ncx" warning is therefore NOT always cosmetic — check the title-fallback
  share before dismissing it.
- [2026-07-25] **T3 fluency is flat at 12.4–13.5/20 across all 5 books**, every
  source format and genre (mean judge ≈ 3.1–3.4/5), while T2 sits at 4.2/5 and
  T1/T5 are perfect. A dimension invariant across 5 different books is systemic
  — in the translate stage or in the measurement — never a property of a book.
  Note `fluency_v1` judges the target in ISOLATION (no source, no surrounding
  text, same model family that wrote it), so the ceiling may be partly the
  judge's; a human-prose control is the discriminator. Do not tune the
  translate prompt against an unmeasured judge ceiling (cf. §9 screen-gate).

- [2026-07-24] Book-1 milestone numbers (The New Rules of War, EN→SL,
  gpt-5-mini @ reasoning_effort=low): translation €0.56 actual (dry-run
  estimated €0.53 at default effort — the low-effort surcharge constant now
  overestimates; recalibrate `translate.py`'s reasoning additive), eval
  €0.04. T=89.7 [87.9,91.6]: weak dims T3 fluency 15.3/20, T4 terminology
  6.1/10 — first iteration targets if a re-run is wanted. Eval prints a
  benign "Could not parse nav document at nav.xhtml" warning on our own
  EPUBs (assembler names/paths differ from the eval parser's expectation) —
  cosmetic, T5 still scores; worth a 1-line fix in eval's nav lookup.
- [2026-07-24] Readium hosting recipe (S2.2): `fragment-compose` 1.8.9 must
  be a DIRECT app dependency (navigator keeps androidx.fragment at runtime
  scope); host with classic FragmentActivity + supportFragmentManager.
  fragmentFactory + FragmentContainerView (AndroidFragment can't inject
  Readium's FragmentFactory); activity theme must descend from
  Theme.AppCompat (framework Theme.Material crashes the navigator; AppCompat
  resources reach the merge from runtime-scope AARs); call
  `EpubNavigatorFragment.createDummyFactory()` before super.onCreate() for
  restored-fragment safety; Robolectric required for JVM tests touching
  Locator/Link (android.net.Uri); `buildFeatures { buildConfig = true }`
  needed for BuildConfig.DEBUG gating.
- [2026-07-24] Book-2 milestone numbers (Active Measures, PDF): translation
  €0.94, eval €0.064 (+€0.008 wasted on the pre-fix aligned-failure run).
  T=78.6 [75.9,81.3] < 85 gate — investigation in flight; T1/T5/T7 perfect.
  Root-cause candidate: T2/T3/T6 sampling pools the translated Notes back
  matter (989 citation fragments).
- [2026-07-24] PDF-sourced books need CAPTION/OTHER types to round-trip
  through assembled EPUBs (class-tagged `<p>`, mapped back in
  normalize_epub) or eval alignment fails on rebuilt books — fixed; rebuilds
  from cache cost €0.
- [2026-07-24] Throwaway `postgres:15-alpine` + stubbed `auth.jwt()` +
  `SET LOCAL` session vars can functionally exercise Supabase RLS/trigger
  logic offline for €0 (proved the delete-wins trigger + shelf-ownership
  check before any deployment exists) — reuse for schema stories.
- [2026-07-24] Material3 `lightColorScheme()`/`darkColorScheme()` silently
  fill unset roles (onSurfaceVariant/outline/error/…) with baseline PURPLE —
  a one-accent design must pin every role it references; grep
  `colorScheme\.` before using a new role (AA values pinned in
  `ui/theme/Color.kt`, computationally verified). Downloadable Google Fonts
  need GMS at runtime — wrong for offline-first e-ink; system serif until a
  font-bundling story.
- [2026-07-24] `stateIn(scope, WhileSubscribed, seed)` JVM ViewModel tests:
  assert via `.value` after a no-op `backgroundScope` collector +
  `advanceUntilIdle()`; a list-collector reliably captures only the seed
  (coroutines 1.10.2 / lifecycle 2.8.7 ordering quirk, isolated repro).
- [2026-07-24] Android coroutine-test gotchas: a `TestDispatcher` injected as
  a client's ioDispatcher must be constructed with the enclosing
  `TestScope.testScheduler` (else `delay()` throws "different schedulers");
  JVM ViewModel tests need `Dispatchers.setMain`/`resetMain` or
  `viewModelScope.launch` failures are silently swallowed. Prefer
  `Icons.AutoMirrored.*` (app is RTL-enabled). Occasional gradle JVM SIGSEGV
  in R8/C2 on this box — `./gradlew --stop` + retry, not a code bug.
- [2026-07-24] S2.3 latent traps for S2.4/S2.5: SettingsViewModel.
  persistCurrentState() drops dictionaryModel/interpretationModel (writes
  null on any edit) — fix when those fields gain UI; key text fields lack
  KeyboardOptions(Password) (IME dictionary risk, keys usually pasted).
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
- [2026-07-25] Clerk Android SDK version ceiling (S3.2). `com.clerk:clerk-android-api`
  **1.0.x is unusable** on this repo's pinned set: it needs kotlin-stdlib 2.4.0
  metadata and drags `androidx.browser` 1.10 + `okhttp-android` 5.4, whose
  aar-metadata demands compileSdk 36 + AGP 8.9.1. **0.1.31 is the last
  Kotlin-2.1.x-era release** (kotlin-stdlib 2.1.20, okhttp 4.12.0 — exactly
  this project's versions). Its API differs from 1.0's: no `Clerk.auth` builder
  facade, use `SignIn.create(SignIn.CreateParams.Strategy.EmailCode(...))` +
  `signIn.attemptFirstFactor(...)`, `Clerk.signOut()`, and
  `Clerk.session?.fetchToken()` for the bearer JWT. Even on 0.1.31,
  `androidx.browser` still resolves to 1.9.0 transitively and must be held with
  `version { strictly("1.8.0") }` — safe because androidx.browser is Custom Tabs,
  used only by Clerk's OAuth/SSO redirect paths, which this app never invokes.
  Confirms the existing generalization: **pin new libraries to Kotlin-2.1.0-era
  releases and verify each AAR's `aar-metadata.properties` minCompileSdk first.**
- [2026-07-25] Excluding Clerk's Play-Services deps (`com.google.android.gms`,
  `com.google.android.play`, `googleid`, `credentials-play-services-auth` — Boox
  tablets often lack Play Services) compiles fine but **fails `minifyReleaseWithR8`**
  with ~15 "Missing class" errors from Clerk's One-Tap/Integrity code. Fix is
  `-dontwarn` for those three package roots in `proguard-rules.pro`; no keep rules
  needed, since the Clerk AAR ships consumer rules covering its own serializers.
  `assembleDebug` passing is NOT evidence the release build works — R8 runs only
  on release.
- [2026-07-25] **Never call `WorkManager.getInstance()` from
  `Application.onCreate`**: Robolectric constructs the real Application for every
  test, WorkManager has no initialized instance there, and this turned 1 change
  into **81 unrelated test failures** (`IllegalStateException at
  WorkManagerImpl.java:179`) across DAO/ViewModel suites that never touch sync.
  Schedule from the launcher Activity instead — `ExistingPeriodicWorkPolicy.KEEP`
  makes re-registration a no-op, so nothing is lost.
- [2026-07-25] Kotlin block comments **nest**, so a KDoc containing a path like
  `/api/v1/sync/*` opens a nested comment and the file dies with "Unclosed
  comment" pointing at the LAST line of the file, not the offending line.
- [2026-07-25] Verifying an Android build: `./gradlew` reports `BUILD SUCCESSFUL`
  when every task is `UP-TO-DATE`, which looks identical to "compiled and passed".
  Check `--console=plain` task lines, and read
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` for real
  tests/failures/errors counts — same "passed must never mean nothing ran" rule
  already recorded for Roborazzi.
- [2026-07-26] **Readium selection actions are opt-in, and omitting them fails
  silently as the platform's popup.** `EpubNavigatorFragment.Configuration
  .selectionActionModeCallback` defaults to `null`, and
  `R2BasicWebView.startActionMode` then falls through to `WebView
  .startActionMode` — so a long-press shows Android's own bar (Copy, Share,
  Web search, and every installed `PROCESS_TEXT` handler: Zotero, Quick Share)
  and **nothing anywhere errors, warns, or fails a test**. S2.6 shipped
  highlights, notes, the editor, the notebook and the decoration renderer with
  no way to reach any of them for two months. Pass a `Configuration` to
  `createFragmentFactory(initialLocator =, initialPreferences =,
  configuration =)`; `decorationTemplates` still defaults to
  `HtmlDecorationTemplates.defaultTemplates()` (verified in the 3.1.2 bytecode),
  so supplying a `Configuration` does not cost the highlight rendering.
  Readium wraps the callback in `Callback2Wrapper`, so `onGetContentRect`
  positioning still works. **`ActionMode.finish()` clears the WebView selection
  and `currentSelection()` is a suspending JS round trip — read the selection
  first, finish the bar second.**
- [2026-07-26] Verifying a UI affordance that "does nothing": before debugging
  the handler, check the affordance is *reachable in the state it requires*.
  Berilo's Highlight/Note/Define buttons were correct code that could never run
  — they lived in chrome revealed by a tap on the WebView, and that tap destroys
  the text selection they read. There is no tap order that works, so the toast
  ("select something first") was the only reachable outcome and looked like a
  capture bug. Generalization in `CLAUDE.md` §9.
- [2026-07-26] **A code review's severity ranking describes the code, not your
  corpus.** The 2026-07-26 translator review ranked finding 1 first — *"real
  books lose chapters today (numeric titles)"*. Probed against the real data at
  €0 before scheduling anything: **0** occurrences on all three drop paths.
  Finding 2 (malformed XHTML drops a spine document): 0 across all four source
  EPUBs — 191 spine documents, all parse clean. Finding 1 heading admission: 0
  lines simultaneously accepted by `_looks_like_heading` and killed by
  `_is_droppable` in either PDF. Finding 1 band strip (`_strip_page_furniture`):
  *Active Measures* has 0 band lines at all; *World Ends* has 1483, of which 935
  are dropped and exactly **8** die solely to the digit rule — six OCR folios,
  one garbled roman numeral (`XXV1`), one archive.org provenance stamp. **0
  genuine content lines lost.** All correct robustness defects; none active.
  Meanwhile finding 3 (glossary absent from the cache key), ranked second, was
  firing continuously. Probes: `ET.fromstring` over every spine member resolved
  through `_read_manifest`/`_read_spine` (note `_read_spine(opf_root, manifest)`
  takes two args); `_looks_like_heading(s) and _is_droppable(s)` over raw lines.
  Generalization in `CLAUDE.md` §9.
- [2026-07-26] **A cache key derived from rendered text inherits the renderer's
  ordering.** `Glossary.to_prompt_block()` iterated dict insertion order, and
  the terms come from a model with no ordering contract — so the identity was
  stable *within* a process (dicts are insertion-ordered; `PYTHONHASHSEED` is
  irrelevant) yet unstable across *re-extractions*. Two semantically identical
  glossaries would key differently and re-translate a whole book for nothing
  (~€1.45 at the `revise_v1` default). The test is not "is the hash reproducible
  here" but **"is it reproducible from a fresh derivation of the same content"**.
  Sequencing corollary, and it was load-bearing: a fix that changes a derived key
  is **free while writer and reader still move together**, and needs a second
  migration the moment stored values exist. All six glossaries in the real cache
  are stored out of sorted order, so landing unsorted would have stranded all
  13,426 rows. Ordering defects in a key-deriving renderer must land in the same
  commit as the key.
- [2026-07-26] **The migration-verification recipe (generalizes S1.10).**
  snapshot → migrate → open twice (idempotence) → assert (a) row count, (b)
  per-row byte equality, **(c) resolution under the key the runtime computes**.
  Clause (c) is the one that actually proves non-invalidation — row count alone
  passes under a mutation that breaks resolution. Never open the real cache for
  writing; copy first. Real cache as of 2026-07-26: **13,426 translations /
  15.5 MB / 6 books**, 6 glossaries, 1551 calls, 0 book_contexts (earlier notes
  saying 10,936 / 4.0 MB are stale). `sqlite3` CLI is **not installed** on this
  box — use Python's `sqlite3` module.
- [2026-07-26] **A near-idempotent transform cannot be mutation-tested by
  comparing output.** Forcing every document through A1's XHTML recovery pass
  passed every test, because R0–R3 leave valid markup alone — the very property
  that makes recovery safe makes it invisible. The discriminating assertion is on
  the **mechanism** (the repair function is unreachable for a well-formed
  document), not the output. Reaching for one exposed a real defect: R3's
  `[^<>]*` attribute scan ended the tag at a legal literal `>` inside an
  attribute value and self-closed mid-attribute. When a guard's correct behaviour
  is "changes nothing", assert that it did not **run**.
- [2026-07-26] **`_normalize_head_key` strips digits, so a purely numeric title
  normalizes to the empty key** — and every predicate keyed on "empty key ⇒ not
  a real title" then silently classifies it as untitled. This is a third,
  unreviewed instance of review finding 1, living in `_is_front_matter_title`;
  the review named only two sites. When a normalization function discards a
  character class, audit every predicate that reads its empty output as
  "absent".
- [2026-07-26] **Continuation-document counts, for review finding 17 (A8):**
  Kaplan **21**, Ember Spark **10**, New Rules 1, Sandworm 1. Kaplan's
  `chapter_count` over-counts by ~45% (47 → ~26). Fixing changes `chapter_index`
  → `make_segment_id` → every hash, so it forces a paid re-translation of at
  least two books. Do not fold it into an extraction story.
- [2026-07-26] **Give an implementer its base SHA explicitly; "main" is often
  wrong.** A2 rebased onto `main` (`b5527fe`) while the live work was on
  `feat/lan-book-server` (`65e90f7`), so its branch lacked S1.15 and its "268
  baseline" test count was not comparable to A4's 307. A4 independently found its
  own worktree 2 commits behind and rebased. Extends the existing worktree rule:
  the question is not "is the branch behind" but **"did any commit I am missing
  touch a file in my footprint"** — check `git log --oneline -1 <file>` on both
  branches for every file in scope. Also: the Bash tool's working directory
  persists across calls, so a `cd translator` poisons later relative paths.
- [2026-07-26] **Anthropic signals content-policy refusals two ways.** An HTTP
  `BadRequestError` (analogous to OpenAI's `invalid_prompt`) **and** in-band via
  `stop_reason="refusal"` on an otherwise-200 response — the latter is SDK-typed
  in `anthropic==0.109.1`'s `Message.stop_reason`. A fallback that only wraps
  `BadRequestError`, as review finding 18 prescribes, misses the in-band case
  entirely. Anthropic's wording for the pre-generation BadRequest path is not
  publicly documented the way OpenAI's code is, so any substring heuristic there
  is unverified until a live refusal is observed.
- [2026-07-26] **Making a silent failure loud can remove the recovery that was
  handling it.** A4's fix for review finding 7 made providers raise
  `EmptyCompletionError`/`TruncatedCompletionError` instead of returning `""`.
  But `translate.py`'s batch ladder catches only `ValueError` (`:476`, `:487`)
  and `ContentPolicyError` (`:414`, `:566`), and the raises happen inside
  `client.complete()` at `:472`/`:483` — outside every handler. Previously `""`
  → `parse_numbered_response` raises `ValueError` → strict retry → **per-segment
  fallback**, which is precisely the right remedy for a truncation because one
  segment per call is a far smaller prompt. After the fix that remedy is
  unreachable and the run aborts mid-book; since the cache commits per batch, a
  resume rebuilds the same oversized prompt and aborts again. **Before promoting
  a silent degradation to an exception, find what was catching it and confirm
  the new type lands in the same handler.**

- [2026-07-26] **The JVM XML parser drops an undeclared entity *silently* when a
  DOCTYPE is present — and every real EPUB has one.** `&nbsp;` in content:
  expat (Python) raises `undefined entity` with or without a DOCTYPE; Xerces
  raises **only without** one. With a DOCTYPE naming an external subset the
  parser is configured not to fetch, the well-formedness constraint becomes "not
  checkable" and the entity simply vanishes — **no `fatalError`, no `error`, no
  warning, no callback.** `Bare&nbsp;entity` parses to `Bareentity`. A
  faithful-looking Kotlin port therefore loses one character per entity and
  hashes every book differently from Python. Fix: `expandEntityReferences =
  false`, splice references by hand, and treat a childless
  `ENTITY_REFERENCE_NODE` as the parse failure Python reports. Generalization:
  **when you disable a parser's ability to check something, find out whether it
  then reports "cannot check" or says nothing at all.** Measured in both
  runtimes (B2).
- [2026-07-26] **Kotlin's `trim()` is not Java's `trim()`.**
  `CharSequence.trim()` uses `Char.isWhitespace()` (Unicode, 28 code points);
  `java.lang.String.trim()` cuts every char <= U+0020, including control
  characters that are not whitespace. The U+0085 gap recorded above is correct
  for **Kotlin** `trim()` and wrong for any Java call site — check which one you
  are actually calling before reasoning about a hash.
- [2026-07-26] **A mutation harness that strips the environment reports false
  positives.** Running Gradle with `env={JAVA_HOME, ANDROID_HOME, PATH, HOME}`
  and no `LANG` gives the test JVM an ASCII default charset, and
  `java.nio.file.Path` then cannot be constructed for the Sandworm filename
  (U+2019 in "Kremlin's") — `InvalidPathException`, which read as "the identity
  gate is red" under six unrelated mutations. Cost ~40 min and three wrong
  hypotheses. **Inherit `os.environ` in any subprocess harness whose verdict you
  intend to report, and hand-check at least one CAUGHT verdict before trusting
  the table.** Corollary: `data/` filenames carry non-ASCII, so anything reading
  them needs a UTF-8 locale.
- [2026-07-26] **The golden-fixture gate is necessary but not sufficient.**
  `<sub>`, twice-referenced figures and astral-character heading-like paragraphs
  do **not** occur in the four example books, so mutations of those rules are
  invisible to the real-book gate; the synthetic structure tests are what cover
  them. A corpus-derived gate proves agreement on what the corpus contains and
  nothing more — pair it with synthetic cases for every rule the corpus does not
  exercise.
- [2026-07-26] **Leniency is latent from the Kotlin side too:** 0 recovered
  documents across all four books, matching A1's Python measurement exactly.
- [2026-07-26] **Generate ported string constants from the live source module;
  do not transcribe and rely on a test to catch typos.** B1b produced
  `python_prompts.json` by evaluating `berilo.prompts` and writing the bytes
  out, rather than hand-copying 5 styles x 6 prompt fields into Kotlin. That
  removes the transcription step instead of catching its errors afterwards —
  verified byte-identical on all 5 styles, all fields, all digests and
  `STRICT_MARKER_CLAUSE`. Reuse for any Python-to-Kotlin string-literal port.
- [2026-07-26] **A NUL escape in a Kotlin/Java string literal can land as a raw
  NUL byte, and Read/grep render it invisibly.** B1b caught this in its own
  draft: the `promptDigest` separator (newline, NUL, newline) was written as a
  literal NUL instead of the six-character `backslash-u-0-0-0-0` escape. A visual
  diff will not show it — only `xxd` will. Verify control-character escapes with
  a hex dump. (This bullet itself failed to commit on the first attempt for the
  same reason: the tool rejected literal control characters in the command.)
- [2026-07-26] **`git stash -u` / `git stash pop` gives a true pre-change test
  baseline inside an agent worktree.** When a packet states an expected count,
  stashing and re-running proves the baseline is the environment's rather than
  the packet's — B4 confirmed 628 exactly (338 debug + 290 release) this way
  before claiming 664. Cheaper and more honest than quoting a number measured on
  another branch, which is how two agents reported non-comparable counts earlier
  the same session.
- [2026-07-26] **Worktree staleness recurred four times in one session** —
  A4 two commits behind; A2 rebased onto `main` while the live work was on
  `feat/lan-book-server`; B4 **~20 commits** behind, missing the very
  `glossary_identity` and `Identity.kt` its spec depended on; B1b 24 commits
  behind. All four were caught by the agent, none by the harness. Naming the
  exact base SHA in the packet is necessary but not sufficient — the worktree is
  created from wherever the branch happened to be. **The question is not "is the
  branch behind" but "did any commit I am missing touch a file in my
  footprint"**: check `git log --oneline -1 <file>` on both branches for every
  file in scope.
- [2026-07-26] **A blocked story may be only half-blocked.** The m4 spec drew
  `A3 -> B1` and Track B was deferred whole; but B1's identity half was gated by
  **A1** and only its prompt-registry half by A3, while B6 was gated only by
  **A4**. Once A1 and A4 landed, two Kotlin stories were runnable while still
  being treated as deferred. **When a dependency edge is drawn story-to-story,
  check whether it is really edge-to-half; a story whose deliverables have
  different gates should be split in the plan, not deferred whole.**
- [2026-07-26] **The reviewer-agent lane failed completely — six dispatches,
  zero verdicts.** `plan-critic` (x2, including an explicit re-request in a fixed
  output format), `review-A4` (x3), `review-A2` (x1) each returned
  `idle_notification` with `idleReason: "available"` and no findings, leaving
  `/orchestrate`'s Phase-1 critic gate and Phase-4 pre-merge review unserved for
  the whole milestone. **Every defect found this session came from the
  Supervisor checking claims directly** — A4's finding-7 fix breaking the retry
  ladder, and A2's order-sensitive glossary identity. Of four agent claims
  verified independently, **two were wrong, and neither appeared in the agent's
  own self-report.** Substitutes that worked, ~1 tool call each: re-run the
  implementer's mutation proofs rather than reading them; re-execute the headline
  measurement against real data (the EUR0 cache-copy proof, the six-book hash
  gate); answer empirical questions with a probe, not an argument. **If a critic
  or reviewer idles twice, stop polling and do the attack yourself — budget one
  Supervisor tool call per load-bearing claim.**

- [2026-07-27] **Java's `MULTILINE` `^` matches after CR, U+0085, U+2028 and
  U+2029; Python's matches only after `\n`.** Any regex ported from
  `re.MULTILINE` needs `RegexOption.UNIX_LINES` or it parses the same model reply
  differently. This is not academic: A3 anchored the `[[n]]` marker parser to
  line starts precisely to stop needless retries, so a Kotlin port without
  `UNIX_LINES` would anchor at *more* positions than Python and diverge on
  exactly the billed path the anchoring was meant to protect (B5).
- [2026-07-27] **`pythonStrip()` on the translate path governs which segments get
  billed, not just how they hash.** Kotlin's `isBlank()`/`trim()` miss U+0085
  where Python's `strip()` removes it, so a segment that the CLI treats as empty
  and skips would be sent to the API by the tablet. The U+0085 divergence is
  already recorded for hashes above; this is its second, costlier consequence.
- [2026-07-27] **`runTest` cannot nest, and JUnit's `assertThrows` cannot host a
  suspending call.** Every suspending failure path needs a
  `suspend inline fun <reified T> assertSuspendThrows` helper. Four B5 tests
  would otherwise have been silently unwritable — the failure mode is "the test
  cannot be expressed", which reads as "that path is untestable" rather than as
  a missing helper.
- [2026-07-27] **The invisible-control-character trap recurred in a new form.**
  Writing a KDoc comment that *names* U+0085/U+2028/U+2029 lands them as raw
  bytes in the file; the Edit tool then cannot match the block, and Bash refuses
  the heredoc that would fix it. Escape control characters in prose as well as in
  string literals, and scan with `LC_ALL=C grep -P` before committing. (Third
  occurrence this session, after B1b's NUL separator and the findings entry that
  itself failed to commit for the same reason.)
- [2026-07-27] **B4 shipped no `book_contexts` table**, so the per-book style
  memo cannot be persisted on device. Inert today — no style `resolveStyle`
  returns for `DEVICE` (`baseline_v1`, `revise_v1`, `revise_generic_v1`) declares
  a `bookContextSystem` — and `RoomTranslationCacheTest` asserts exactly that
  rather than leaving it to be discovered later. Adding the table is a B4-shaped
  change (entity + migration) and is required before any book-context style
  reaches the device.

- [2026-07-27] **EPUB byte-identity is `source_path`-dependent, so "the tablet's
  file equals the workstation's" is unachievable by construction.**
  `assemble.py:374-377` seeds the `dc:identifier` UUID5 on
  `berilo:{source_path}:{title}:{language}`. Measured on *The New Rules of War*,
  same `Book`, only the path varying:
  `/home/niko/workspace/berilo/data/examples/…` -> `85e89f18…` / 2 652 144 B ·
  `data/examples/…` -> `3ff13117…` / 2 652 144 B ·
  `../data/examples/…` -> `16f34b3e…` / 2 652 142 B ·
  `/data/user/0/app.berilo.reader/files/books/….epub` -> `5b320784…` / 2 652 145 B.
  The length moves too, because `content.opf` is DEFLATED and a different UUID
  compresses differently. **Consequence:** the device stores books at
  `filesDir/books/<sha256>.epub` and the workstation at `data/examples/<name>`,
  so the same book translated on both yields a different `dc:identifier` *and* a
  different file sha256 — and `BookImporter` dedupes on exactly that sha256
  (`BookImporter.kt:73`), so it imports as two separate books. B3's gate remains
  correct and valuable as a **writer-fidelity** test (same `Book` in, same bytes
  out, across languages); it is not, and cannot be, a cross-device guarantee.
  Fixing it means reseeding the identifier on something path-free such as
  `book_hash`, which changes `dc:identifier` for every EPUB already produced.
  Filed as B8.
- [2026-07-27] **A surviving mutation is a claim about your tests, not your
  code.** Both of B3's survivors were rules the corpus happened not to
  discriminate: whole-segment escaping coincides with per-tag escaping unless a
  *valid* pair precedes the bad tag, and first-appearance chapter ordering
  coincides with sorted ordering on anything `normalize_epub` emits. Each was one
  synthetic case away from being caught. **Reach for "equivalent mutation" only
  after constructing the input that would distinguish the two behaviours and
  finding it impossible** — B5 correctly claimed one equivalent mutant by that
  standard; B3 correctly rejected two.
- [2026-07-27] **`java.util.zip.ZipOutputStream` cannot byte-match Python's
  `zipfile`.** Three header fields differ on every archive — extract-version 10
  vs 20 on STORED entries, create-system 0 (MS-DOS) vs 3 (Unix), external
  attributes 0 vs `0o600 << 16` — and it sets general-purpose bit 3 with a
  trailing data descriptor on any DEFLATED entry whose size was not pre-declared,
  which Python never does on a seekable file. Hand-write the headers. The
  *compressed payload* is the easy part: `Deflater(DEFAULT_COMPRESSION,
  nowrap=true)` is byte-identical to `zlib.compressobj(-1, DEFLATED, -15)` (both
  sides zlib 1.2.11). Generalization: **when porting a stdlib zip writer for
  byte-identity, compression is rarely what breaks — it is the metadata each
  stdlib considers its own to fill in.**
