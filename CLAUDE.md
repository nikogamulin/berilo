# CLAUDE.md — Berilo

Translate books (PDF/EPUB/MOBI) into the reader's language with
meaning-preserving LLM translation; read them in a purpose-built Android app
with an LLM dictionary, paragraph interpretation, and notes. First user: Niko,
English → Slovenian, Boox e-ink tablet.

| Phase | Where | Boundary |
|-------|-------|----------|
| 1 — Translator CLI | `translator/` (Python) — file in, translated EPUB out | open source |
| 2 — Reader app | `android/` (Kotlin/Compose/Readium) — offline reader + LLM features | open source |
| 3 — Cloud sync | separate **private** repo `berilo-cloud` (Next.js/Vercel + Supabase) | only the API contract (`docs/sync_api.md`) may live here |

**Scan [`docs/findings.md`](docs/findings.md) before debugging or running a
non-obvious command** — it is the register of what already went wrong here.

## 2. Architectural invariants

- **BYO API key.** `.env` (CLI) or EncryptedSharedPreferences (app). Never in code, logs, or git.
- **Cheapest model that does the job** is the default (`gpt-5-mini`); every model is user-overridable through one provider interface. No provider lock-in.
- **Meaning preservation over literalism** — the translate-and-verify prompt design *is* the product.
- **Segment integrity:** source↔target mapping is 1:1. Nothing is silently dropped; failures are loud and resumable.
- **Books never leave the machine** except as segment batches to the configured LLM API. Phase 3 syncs user-created data only.
- **EPUB is the canonical interchange format** — translator output = app input.

## 3. Design

[`docs/design_guidelines.md`](docs/design_guidelines.md): the text is the hero,
chrome recedes. E-ink first (Boox), Literata + Inter, one accent color, no
engagement mechanics, WCAG AA.

## 4. Constraints that bite

- **Secrets:** run the scan in §7 before every commit. `.env` is gitignored; so is `android/local.properties` (holds a local path).
- **`data/` is gitignored** — copyrighted books. Never commit, never upload, never copy into a worktree.
- **Costs are visible and gated:** `--dry-run` before any paid run; report actual € after. Full-book runs and anything above a ~€0.20 smoke need Niko's explicit go-ahead. Subagents never spend API budget.
- **No piracy features** — the user supplies files they own. No downloading, sharing, or DRM stripping.
- **Sampled metrics carry bootstrap CIs** (rubric scores, cost estimates) — never a bare point estimate.
- **Open/closed boundary:** nothing service-private enters this repo; nothing but the API contract comes back from `berilo-cloud`.
- **Supabase (Phase 3):** RLS on every table; paginate; test time logic across DST.

## 5. Git

Remote `git@github.com:nikogamulin/berilo.git`, default `main`, feature
branches via PR, `Refs #<issue>`. MIT, author: Niko Gamulin, PhD.

## 6. How work runs

Rubric-driven: one hypothesis per iteration, scored against
[`docs/rubric.md`](docs/rubric.md) (T translation, R reader, S sync, D
process), kept only if the score didn't regress and tests are green.
[`docs/project_plan.md`](docs/project_plan.md) is the task list; **a story is
checked off only in the session where its Verify line ran and passed.**

- Iteration mechanics, ledger/score bookkeeping, knowledge-tier promotion → **`/build-loop`**
- Assignment spanning more than one story → **`/orchestrate`** (Supervisor + agent pipeline)
- Before claiming any story done → **`/verify-implementation`**

## 7. Commands and environment traps

```bash
# Secret scan — before EVERY commit (exclusions = docs that describe the scan)
git grep --cached -iE 'sk-(proj|ant)|api03|/home/niko' -- ':!CLAUDE.md' ':!docs/rubric.md' ':!.claude/' && echo LEAK || echo clean

# Translator. PATH shims lie here: bare `pytest`/`black` resolve to the wrong
# interpreter — the Makefile's `python3 -m` form is the working one.
cd translator && make test && make lint
berilo doctor                                # provider smoke, ~€0
berilo serve --dir data/examples             # LAN catalog + QR for the tablet, ~€0
berilo inspect data/examples/<file>          # extraction preview, no API cost
berilo translate <file> --to sl --dry-run    # cost estimate — ALWAYS before a full run
berilo eval <translated.epub> --sample 40 --seed 42   # Rubric T + CI

# In an agent worktree, imports and data/ still resolve to the main checkout:
PYTHONPATH=<worktree>/translator python3 -m pytest

# Android. Default JDK on this box is JRE-only; gradle needs the bootstrapped one.
cd android && JAVA_HOME=$HOME/.local/share/jdk-bootstrap/jdk-21.0.2 \
  ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug test
adb install -r app/build/outputs/apk/debug/app-debug.apk   # Boox over USB

# GitHub CLI: ~/.local/bin/gh is a stray PyPI package, and the real gh (2.4.0)
# has no `gh label` and a broken `gh issue close` — go through the API.
/usr/bin/gh api repos/nikogamulin/berilo/issues/<n> -X PATCH -f state=closed
```

## 8. Map

| Document | Contents |
|----------|----------|
| [`docs/project_spec.md`](docs/project_spec.md) | Problem, users, all 3 phases, pipeline design, stack decisions, non-goals |
| [`docs/project_plan.md`](docs/project_plan.md) | Stories m0–m3 with points and **Verify** lines |
| [`docs/rubric.md`](docs/rubric.md) | T/R/S scoring procedures, weights, gates; process checklist D |
| [`docs/findings.md`](docs/findings.md) | Tier-2 register: session-discovered gotchas and working commands |
| [`docs/design_guidelines.md`](docs/design_guidelines.md) | Typography, e-ink rules, components, anti-patterns |
| [`docs/sync_api.md`](docs/sync_api.md) | Phase-3 API contract — the only `berilo-cloud` surface in this repo |
| [`loops/build/`](loops/build/) | `ledger.jsonl` (one row per iteration), `rubric_scores.jsonl` |
| [`.claude/`](.claude/) | Skills (`build-loop`, `orchestrate`, `verify-implementation`), agents, session-reflection hook |

## 9. Learned rules — canonical (add as mistakes happen)

<!-- When a mistake happens, don't just fix it — add the rule.
     Format: what went wrong → what the rule is now.
     Promotion target for recurring/endorsed findings.md entries. -->

- Example brief said "pdf or mobi" but the examples are PDF+EPUB → **verify
  input assumptions against `data/examples/` before building format support;
  EPUB is first-class, MOBI goes through `ebook-convert`.**
- Agent worktrees resolve installed packages and `data/` to the main
  checkout (recurred 3×) → **run worktree tests with
  `PYTHONPATH=<worktree>/translator`; keep `data/`-gated tests skippable;
  never copy books into worktrees** (details in `docs/findings.md`).
- A cache key that omits an experimental factor silently turns every
  experiment on that factor into a no-op that looks like a null result (the
  translation cache keyed `(book, segment, model, lang)` but not the prompt;
  recurred 2026-07-26 with the glossary, injected into every prompt but absent
  from both keys) → **before trusting any null result, check that the cache key
  contains the thing you changed**, and **derive cache identity from the
  rendered artefact rather than declaring it** — a hand-declared version drifts
  from what the model saw, while a hash of the rendered prompt block cannot.
  Derivation is only safe if the rendering has a **total order** (an
  insertion-ordered render is reproducible in-process and unstable across
  re-derivation). The same rule governs in-process guards, not just caches: a
  forbidden-hash set scoped to one run failed the moment two unrelated items
  shared a `segment_hash`. Corollary: `book_hash`/`segment_hash` cover segment
  IDs and text only, so normalize fixes that change types or titles rebuild
  from cache for €0 — verify the hash before assuming a paid re-run.
- A code review ranks findings by what the code *can* do, not by what your data
  actually triggers; scheduling by its severity order put the two
  zero-occurrence fixes first and the actively-firing one second →
  **probe each finding's blast radius against the real corpus at €0 before
  scheduling it.** A latent defect is worth fixing — not worth fixing *first*.
- Making a silent failure loud removed the recovery that was handling it: a
  truncated batch used to degrade to per-segment retry (a far smaller prompt,
  i.e. the actual remedy), and raising instead turned it into an unrecoverable
  mid-book stall → **before promoting a silent degradation to an exception,
  find what was catching it and confirm the new type lands in the same
  handler.**
- A4 was bounced for the defect above; the write-up landed in `findings.md`
  before B5 was scheduled, and B5 then got the same degrade-vs-abort decision
  right across four call sites on its first attempt → **write the bounce up
  before scheduling the story that will repeat it.** A recorded failure costs
  one paragraph and saves a second bounce.
- The §7 secret scan was made to pass by appending an exclusion rather than
  fixing the content, so every later commit reported clean while the path sat
  in the repo → **never widen a secret-scan exclusion; the list in §7 is the
  specification, not a starting point. If the scan is red, fix the content.**
- Measure the judge before tuning the thing it judges: intra-sample σ plus the
  verdict distribution costs ~€0.15 and settles "is this a real ceiling or a
  measurement artifact". **A judge that already awards top marks to some of
  your output is not what is capping you.** Related: fixing a measurement
  artifact can move a score DOWN (untranslated headings scored meaning 5/5),
  so judge such fixes by pool cleanliness, not score direction.
- Screen-gate fixes chased flagged instances for 4 rounds while the
  fixed-seed sample kept redrawing (recurred 3×) → **fix quality gates by
  artifact CLASS (type/fold/exclude whole categories), never by instance;
  any pool change redraws a seeded sample, so per-instance fixes are
  non-monotonic** (evidence in `docs/findings.md`).
- S2.6 shipped highlights, notes, the notebook and the decoration renderer with
  no reachable path to any of them: the actions lived in chrome revealed by a
  tap, and that tap destroyed the text selection they read, so no tap order
  worked. Two months of green tests and a passing build said nothing → **an
  action that consumes transient UI state must be hosted ON that state, never
  in chrome whose own reveal destroys it; and a feature is not done until one
  end-to-end path from user gesture to stored result has been walked.**
  Corollary, same story: **an unset optional config is not an error anywhere** —
  Readium's `selectionActionModeCallback` defaults to `null` and silently
  yields the platform popup, so absence-of-configuration defects need an
  explicit assertion (`beriloNavigatorConfiguration`), not a compiler.
