# On-device translation + translator hardening

**Date:** 2026-07-26 · **Status:** draft, pre-critic
**Assignment:** translate a book inside the Android app; fold the 2026-07-26 code
review into the Python translator while doing it.

---

## 1. Goal

Today a book becomes readable in Slovenian only if Niko runs `berilo translate`
on the workstation and hands the result to the tablet (S1.15's LAN server). The
tablet cannot translate anything it imports.

**Target state:** the app imports a *source-language* EPUB, shows a cost
estimate, translates it on-device against the user's own API key, and the
translated EPUB lands in the library alongside CLI-produced ones —
indistinguishable, because both are produced by the same algorithm.

**Non-goal:** PDF and MOBI on-device. `normalize/pdf.py` is 1436 LOC bound to
PyMuPDF and `mobi.py` shells out to Calibre; neither has an Android equivalent
worth building. **EPUB-only on device**, which is consistent with CLAUDE.md §2
("EPUB is the canonical interchange format"). PDF stays a workstation job whose
output reaches the tablet over S1.15.

---

## 2. What the code review changes about this port

The review (`docs/reviews/2026-07-26-translator.md`) found 20 defects. Four of
them are *port-blocking* — porting the current Python faithfully would copy a
known defect into a second language, and in three cases the app makes the defect
**more** reachable than the CLI does.

| # | Review finding | Why it blocks the port |
|---|---|---|
| **4** | Default style hardcodes Slovenian regardless of `--to` | The CLI masks this because Niko only targets `sl`. The app exposes **target language as a free-text field** (`settings/LlmSettings.kt:29-36`, `SettingsScreen.kt:116-117`). The defect is one keystroke away from every user. **The port must bind style to target language, and the Python fix must land first so both share one rule.** |
| **3** | Glossary injected into every prompt but absent from the cache key | This is a **recurrence of CLAUDE.md §9's canonical cache-key rule**, in the same subsystem that produced it. A fresh Kotlin cache must include the glossary identity in its key on day one, not inherit the defect and get a migration later. |
| **6 + 7** | `cost_eur` raises on an unknown model *after* the API is billed; content-less responses return `""` billed | Both already exist in Kotlin: `Pricing.kt:41-42` throws for unknown models, and `LlmClient` has no empty-text guard. A dictionary lookup makes 1 call, so the blast radius is a toast. **A book makes ~324 calls over hours** — a crash-after-billing at call 200 loses the run's foreground progress and bills for nothing. Must be fixed in Kotlin *before* the translate feature, not after. |
| **2** | A malformed XHTML spine document is dropped whole, with only a warning | Android's `XmlPullParser`/`DocumentBuilder` are exactly as strict as `ET.fromstring`. Porting the structure faithfully reproduces "chapter 3 silently missing". The Kotlin reader must be lenient **by construction**, and the loss must be loud. |

Two more are pure algorithm and would be copied verbatim if not fixed first:
**14** (marker regex unanchored → needless strict retries, extra cost) and
**10** (`context_pairs=0` never trims → whole book as rolling context).

**Sequencing consequence:** the Python fixes for findings 4, 3, 14, 10 are
**upstream** of the corresponding Kotlin stories. The port copies a corrected
algorithm, once.

---

## 3. Design

### 3.1 What actually gets ported

Per the engine map, the EPUB→EPUB path is ~3900 LOC of the translator's 18214.
Excluded: PDF (1436), MOBI (112), CLI (955), eval (1567), experiment (1239),
serve (729) — all workstation-only.

| Layer | Python | Kotlin target | Character |
|---|---|---|---|
| Segment model, `make_segment_id`, `book_hash`, `segment_hash` | `models.py`, `cache.py:85-111` | `translate/model/` | pure algorithm — **must be byte-identical or the CLI cache is worthless** |
| Prompt registry | `prompts.py` | `translate/prompts/` | string constants + version identity |
| Cache | `cache.py` (SQLite, 5-col composite PK) | Room v6 | schema translation |
| Glossary | `glossary.py` | `translate/glossary/` | sampling + JSON salvage |
| Translate engine | `translate.py` | `translate/engine/` | batching, rolling context, marker parse, retry ladder, revise pass |
| EPUB read | `normalize/epub.py` (824) | `translate/epub/Reader` | XML + zip, **lenient** |
| EPUB write | `assemble.py` (558) | `translate/epub/Writer` | zip with pinned timestamps, UUID5 |

### 3.2 The cross-language identity invariant

This is the load-bearing design decision. `book_hash` is sha1 over ordered
segment ids; `make_segment_id` is `sha1("{chapter_index}:{position}:{text.strip()}")`.
If the Kotlin reader assigns a different `chapter_index` or `position` to the
same EPUB, every id differs, `book_hash` differs, and **the tablet shares no
cache row with the workstation** — a book already paid for on the laptop would
be re-billed in full on the tablet.

**Therefore:** Kotlin `normalize` must reproduce Python `normalize_epub`'s
chapter-index and position assignment exactly, including the chapter-title
resolution ladder (`epub.py:734-758`), the segment-less-document rule
(`epub.py:730-732`), and `_is_heading_like` retyping (`epub.py:436-449`).

**Verified by golden fixtures, not by reading:** the Python side emits
`Book.to_json()` for a set of real EPUBs; the Kotlin test suite reads the same
EPUBs and asserts an identical `book_hash` and identical per-segment ids. Any
divergence is a test failure, in CI, in both languages. This is the story that
must land before any other Kotlin translate story.

*(Note: this makes cache sharing between devices **possible**, not automatic —
the caches are separate stores. Sharing them is out of scope here; the
invariant's immediate value is that the two implementations are provably the
same algorithm.)*

### 3.3 Language-bound styles (review finding 4)

Replace "one default style" with a **(target language → style) resolution**:

- `revise_v1` declares `target_langs = ["sl"]`.
- A generic `revise_generic_v1` carries the two-pass structure with no
  language-specific contract, for any other target.
- Resolution is explicit and logged in the run summary; the resolved style's
  version still flows into the cache key, unchanged.
- A style whose declared language does not match `--to` is a **loud refusal**,
  not a silent mismatch.

This is the one place the app's UI and the CLI must not diverge: the app's
free-text target-language field resolves through the same table.

### 3.4 Cost gating on device (CLAUDE.md §4)

A full book is ~€1.45 and ~324 calls at `revise_v1` (ledger 2026-07-25). On a
tablet this is a multi-hour job on battery. Required by §4 ("costs are visible
and gated"):

- **Dry-run first, always.** The estimate screen shows chapters, segments,
  estimated €, and the resolved model+style before any spend is possible.
- **Explicit confirmation** is the only path to a paid run. No implicit start.
- **Resumable by construction** — the cache commits per batch
  (`translate.py:826-842`), so process death costs at most one batch. The job
  runs under WorkManager, mirroring `SyncWorker`'s pattern.
- **Actual € reported** on completion, and it must include fallback spend
  (review finding 5, which the CLI gets wrong today).

### 3.5 Lenient EPUB parsing (review finding 2)

The Kotlin reader parses XHTML in tolerant mode and **never drops a document
silently**. A document that cannot be parsed at all is surfaced as a
user-visible error naming the chapter, not a log line. Candidate: Jsoup
(tolerant HTML parser, ~400 KB) vs. hand-rolled recovery on `XmlPullParser`.
**[OPEN-A]** — adding a dependency vs. building recovery; decided at B2.

---

## 4. Stories

### Track A — translator hardening (Python), from the review

| id | Story | pt | tier | footprint |
|---|---|---|---|---|
| **A1** | Extraction data loss: numeric titles (finding 1), malformed XHTML (2), plus 15/16/17 | 3 | opus | `normalize/pdf.py`, `normalize/epub.py` |
| **A2** | Glossary in the cache key (3), crash-atomic migration (9), T7 model filter (11) | 3 | opus | `cache.py`, `glossary.py`, `eval/runner.py`, translate.py cache call sites |
| **A3** | Language-bound styles (4); marker anchoring (14); `context_pairs=0` (10) | 3 | opus | `prompts.py`, `translate.py`, `cli.py` |
| **A4** | Provider + cost correctness: fallback spend (5), pricing pre-flight (6), empty/truncated response (7), Anthropic ContentPolicyError (18), `"o"` prefix (19), empty memo (20) | 2 | sonnet | `providers/**`, `cli.py` summary |
| **A5** | Experiment/eval edges: memo in per-word rate (8), lead-in hash scope (12), screen default (13) | 2 | sonnet | `experiment.py`, `screen.py` |

### Track B — on-device translation (Kotlin)

| id | Story | pt | tier | footprint |
|---|---|---|---|---|
| **B1** | Core port + **cross-language golden fixtures**: segment model, the three hashes, prompt registry, pricing. Gates every later B story | 3 | opus | `translate/model/`, `translate/prompts/`, golden fixtures both sides |
| **B2** | EPUB reader, lenient (finding 2), identity-exact per §3.2 | 5 | opus | `translate/epub/Reader` |
| **B3** | EPUB writer: pinned zip timestamps, UUID5-by-hand, deterministic OPF/nav | 5 | opus | `translate/epub/Writer` |
| **B4** | Room v5→v6 translation cache + glossary, glossary in the key from day one | 3 | sonnet | `store/db/**`, `Migrations.kt` |
| **B5** | Translate engine: batching, rolling context, marker parse, retry ladder, revise pass, per-batch commit | 5 | opus | `translate/engine/` |
| **B6** | Provider hardening: pricing pre-flight (6), empty/truncated guard (7), exponential backoff + `Retry-After`, Anthropic policy mapping (18) | 2 | sonnet | `llm/**` |
| **B7** | UI: import source EPUB → dry-run estimate → confirmation gate → WorkManager job → progress → library | 3 | sonnet | `ui/translate/`, `settings/`, `MainActivity` |

**Total: 39 points ≈ 10–13 focused sessions.** This is a milestone, not a
session. Proposed as **m4**.

### Dependency order

```
A3 ──────────────► B1 ──► B2 ──► B5 ──► B7
A2 ──────────────►  │      B3 ──►  │
A1 (independent)    └──► B4 ──────►┘
A4 ──────────────► B6
A5 (independent)
```

A3 and A2 gate B1 because B1 freezes the prompt registry and cache identity the
whole Kotlin side is built on. Porting them before the fix means porting twice.

### Wave 1 (disjoint footprints, 3 agents)

**A1** (`normalize/**`) · **A2** (`cache.py`/`glossary.py`) · **B6** (`android/llm/**`).
Disjoint by construction: two separate Python subsystems and a third language.
A3 is held to wave 2 because A2 edits translate.py's cache call sites.

---

## 5. Gates

- Every A story: `cd translator && make test && make lint` green, **plus a test
  that fails without the fix** (mutation-proven, per the S2.12 precedent).
- **A2 specifically:** the migration must be proven crash-atomic by killing the
  process between DROP and RENAME against a copy of the real 10,936-row cache —
  the S1.10 precedent. And the glossary-key change must be proven to *not*
  invalidate existing translation rows (§9: verify the hash before assuming a
  paid re-run).
- **B1:** golden-fixture equality on ≥3 real EPUBs, asserted in both suites.
- Rubric T must not regress: re-score one book after A1+A2+A3 land. Expected
  €0 if `book_hash` is unchanged — **and whether it is unchanged is itself a
  gate**, because A1 changes extraction and therefore may change segment ids.
  **[OPEN-B]** — if A1 shifts `book_hash`, a re-score costs a paid re-translation
  (~€1.45/book). Niko's call before A1 lands.
- No story is checked off without its Verify line executed in its closing
  session (CLAUDE.md §6).

---

## 6. Open decisions

- **[OPEN-A]** Jsoup dependency vs. hand-rolled `XmlPullParser` recovery for
  lenient XHTML. Decide at B2.
- **[OPEN-B]** If A1's extraction fixes change `book_hash` on the example books,
  a rubric re-score means a paid re-translation. Accept the cost, or gate A1's
  extraction changes behind a flag so existing books keep their hash?
- **[OPEN-C]** On-device model default. `gpt-5-mini` at `revise_v1` is ~€1.45 and
  ~324 calls per book. On a tablet, is the two-pass revise style the right
  default, or should on-device default to single-pass and let the user opt into
  revise? Affects B7's UI and the cost story.
- **[OPEN-D]** Does the tablet-produced EPUB need to be byte-identical to the
  workstation's for the same book+model+style? §3.2 makes the *segments*
  identical; byte-identity additionally requires the writer to match
  `assemble.py` exactly. Worth it, or is segment-identity enough?
